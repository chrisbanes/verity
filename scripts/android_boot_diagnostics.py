"""Read-only, job-owned Android bootstrap observations; never qualifies a device."""

import argparse
import hashlib
import json
import os
import platform
import selectors
import signal
import subprocess
import sys
import time
import uuid
from pathlib import Path

MAX_REPORT = 262144
REPORT_CAP = MAX_REPORT - 8192
MAX_STREAM = 2048
SERIAL = "emulator-5554"
AVD = "test"
KEYS = ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB", "GITHUB_SHA", "VERITY_BOOT_CANDIDATE_SHA")
PROPERTIES = ("ro.boot.bootreason", "init.svc.zygote", "init.svc.bootanim", "init.svc.surfaceflinger", "sys.boot_completed",
              "dalvik.vm.heapsize", "dalvik.vm.heapgrowthlimit")


CRASH_LOG_SCRIPT = r"logcat -b crash -d -t 128 -v brief | grep -E 'dev\.mobile\.maestro(\.test)?([: /]|$)|dev\.mobile\.maestro\.MaestroDriverService([: /]|$)|^E/AndroidRuntime(\([ ]*[0-9]+\))?[ ]*: (FATAL EXCEPTION IN SYSTEM PROCESS|Process: system_server([, ]|$))|^W/Watchdog(\([ ]*[0-9]+\))?[ ]*: \*\*\* WATCHDOG KILLING SYSTEM PROCESS: |^F/DEBUG[ ]*(\([ ]*[0-9]+\))?[ ]*: pid: [0-9]+, tid: [0-9]+, name: .*  >>> system_server <<<$'"


def binding():
    values = {key: os.environ[key] for key in KEYS}
    if any(not value or len(value) > 256 for value in values.values()):
        raise RuntimeError("Invalid job binding")
    return values


def write_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False) + "\n")
    temporary.replace(path)


def stopped(directory, token):
    path = directory / "stop.json"
    if not path.exists():
        return False
    if json.loads(path.read_text()) != {"token": token}:
        raise RuntimeError("Observer stop token mismatch")
    return True


def capture(argv, cancel=lambda: False, deadline=None, limit=MAX_STREAM):
    """Drain capped pipes and reserve cleanup within the total five-second budget."""
    started = time.monotonic()
    end = min(started + 5, deadline if deadline is not None else started + 5)
    if cancel() or end - started <= .75:
        return {"outcome": "stopped" if cancel() else "deadline", "exit": None, "stdout": "", "stderr": "",
                "droppedBytes": {"stdout": 0, "stderr": 0}, "truncated": False, "joined": True, "seconds": 0}
    retained = {"stdout": bytearray(), "stderr": bytearray()}
    dropped = {"stdout": 0, "stderr": 0}
    outcome = "completed"
    process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        with selectors.DefaultSelector() as selector:
            for name, stream in (("stdout", process.stdout), ("stderr", process.stderr)):
                os.set_blocking(stream.fileno(), False)
                selector.register(stream, selectors.EVENT_READ, name)
            terminating = None
            while selector.get_map() or process.poll() is None:
                now = time.monotonic()
                if terminating is None and (cancel() or now >= end - .75):
                    outcome = "stopped" if cancel() else "timeout"
                    process.terminate()
                    terminating = now
                if terminating is not None and now >= min(terminating + .25, end - .25) and process.poll() is None:
                    process.kill()
                if now >= end - .05:
                    break
                for key, _ in selector.select(min(.02, max(0, end - .05 - now))):
                    chunk = os.read(key.fileobj.fileno(), 32768)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    room = max(0, limit - len(retained[key.data]))
                    retained[key.data].extend(chunk[:room])
                    dropped[key.data] += max(0, len(chunk) - room)
        process.wait(timeout=max(0, end - time.monotonic()))
    finally:
        try:
            if process.poll() is None:
                process.kill()
                try:
                    process.wait(timeout=max(0, end - time.monotonic()))
                except subprocess.TimeoutExpired as failure:
                    raise RuntimeError("Owned diagnostic command failed to join within its total deadline") from failure
        finally:
            process.stdout.close()
            process.stderr.close()
    return {"outcome": outcome, "exit": process.returncode, "seconds": round(time.monotonic() - started, 3),
            **{key: value.decode("utf-8", "replace") for key, value in retained.items()},
            "droppedBytes": dropped, "truncated": any(dropped.values()), "joined": True}


def retain_report(path, report):
    while len((json.dumps(report, ensure_ascii=False) + "\n").encode()) > REPORT_CAP:
        if len(report["snapshots"]) <= 1:
            raise RuntimeError("Observer metadata exceeds report cap")
        # Preserve the first and latest observations; remove older middle data.
        removed = report["snapshots"].pop(1 if len(report["snapshots"]) > 2 else 0)
        report["droppedSnapshotBytes"] += len(json.dumps(removed, ensure_ascii=False).encode())
        report["droppedSnapshots"] += 1
        report["truncated"] = True
    write_json(path, report)


def static_inventory(cancel, deadline):
    inventory = {"os": platform.system(), "arch": platform.machine(), "logicalCpu": os.cpu_count()}
    inventory["memoryBytes"] = capture(["sysctl", "-n", "hw.memsize"], cancel, deadline)
    inventory["pressure"] = host_pressure(cancel, deadline)
    avd_home = Path(os.environ.get("ANDROID_AVD_HOME", str(Path.home() / ".android/avd")))
    config = avd_home / (AVD + ".avd/config.ini")
    values = {}
    with config.open() as stream:
        config_text = stream.read(16384)
    for row in config_text.splitlines():
        key, sep, value = row.partition("=")
        if sep and key.strip() in ("hw.cpu.ncore", "hw.ramSize", "vm.heapSize"):
            values[key.strip()] = value.strip()
    inventory["declaredAvd"] = values
    sdk = Path(os.environ["ANDROID_HOME"]) / "emulator"
    with (sdk / "source.properties").open() as stream:
        properties = stream.read(8192)
    inventory["emulatorVersion"] = [row for row in properties.splitlines()
                                    if row.startswith(("Pkg.Revision", "Pkg.BuildId"))]
    binary = sdk / "emulator"
    if binary.stat().st_size <= 134217728:
        digest = hashlib.sha256()
        hash_deadline = min(deadline, time.monotonic() + 5)
        with binary.open("rb") as stream:
            while True:
                if cancel() or time.monotonic() >= hash_deadline:
                    inventory["emulatorSha256Unavailable"] = "stopped-or-deadline"
                    break
                chunk = stream.read(1048576)
                if not chunk:
                    inventory["emulatorSha256"] = digest.hexdigest()
                    break
                digest.update(chunk)
    else:
        inventory["emulatorSha256Unavailable"] = "binary-exceeds-cheap-hash-size"
    return inventory


def host_pressure(cancel, deadline):
    return {"vmStat": capture(["vm_stat"], cancel, deadline),
            "swapUsage": capture(["sysctl", "vm.swapusage"], cancel, deadline)}


def guest_commands():
    properties = "|".join(prop.replace(".", r"\.") for prop in PROPERTIES)
    script = r"getprop | grep -E '^\[(" + properties + ")\]: \['"
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
    prefix = [adb, "-s", SERIAL]
    return [("properties", prefix + ["shell", script]),
            ("systemServer", prefix + ["shell", "printf 'system_server='; pidof system_server || printf '\n'"]),
            ("onlineCpu", prefix + ["shell", "IFS= read -r cpu < /sys/devices/system/cpu/online; printf 'online_cpu=%s\n' \"$cpu\""]),
            ("memTotal", prefix + ["shell", "while read -r key value unit; do case \"$key\" in MemTotal:) printf 'MemTotal=%s %s\n' \"$value\" \"$unit\"; break;; esac; done < /proc/meminfo"]),
            ("crash", prefix + ["shell", CRASH_LOG_SCRIPT.replace("logcat -b crash ", "logcat -b crash -b main -b system ", 1)]),
            ("bootEvents", prefix + ["logcat", "-b", "events", "-d", "-t", "40", "-v", "brief", "-s",
                                     "boot_progress_start:I", "boot_progress_preload_start:I", "boot_progress_preload_end:I",
                                     "boot_progress_system_run:I", "boot_progress_pms_start:I", "boot_progress_pms_ready:I",
                                     "boot_progress_ams_ready:I", "boot_progress_enable_screen:I"])]


def failures(value):
    if isinstance(value, dict):
        if "outcome" in value:
            return int(value["exit"] != 0 or value["outcome"] != "completed")
        return sum(failures(item) for item in value.values())
    return 0


def observe(directory, control, inventory=static_inventory, commands=guest_commands, duration=600, cadence=30, pressure=host_pressure):
    token = control["token"]
    terminate = [False]
    previous = {}
    def stop_signal(*_):
        terminate[0] = True
    for sig in (signal.SIGTERM, signal.SIGINT):
        previous[sig] = signal.signal(sig, stop_signal)
    cancel = lambda: terminate[0] or stopped(directory, token)
    started = time.monotonic()
    wall_deadline = started + duration
    deadline = wall_deadline - min(2, duration / 4)
    report = {"binding": control, "target": {"serial": SERIAL, "avd": AVD}, "qualification": False,
              "snapshots": [], "truncated": False, "droppedSnapshots": 0, "droppedSnapshotBytes": 0,
              "diagnosticFailures": 0, "status": "running"}
    try:
        if control["binding"] != binding() or control["scriptSha256"] != hashlib.sha256(Path(__file__).read_bytes()).hexdigest():
            raise RuntimeError("Observer immutable binding mismatch")
        if control["serial"] != SERIAL or control["avd"] != AVD:
            raise RuntimeError("Observer target mismatch")
        control["pid"] = os.getpid()
        write_json(directory / "ready.json", {"token": token, "pid": os.getpid()})
        if not cancel():
            report["host"] = inventory(cancel, deadline)
            report["diagnosticFailures"] += failures(report["host"])
        late_pressure_sampled = False
        while not cancel() and time.monotonic() < deadline:
            elapsed = time.monotonic() - started
            snapshot = {"elapsedSeconds": round(elapsed, 3), "commands": {}}
            for name, argv in commands():
                if cancel() or time.monotonic() >= deadline:
                    break
                result = capture(argv, cancel, deadline)
                snapshot["commands"][name] = result
                report["diagnosticFailures"] += int(result["exit"] != 0 or result["outcome"] != "completed")
                report["truncated"] |= result["truncated"]
            late_pressure = elapsed >= 300 and not late_pressure_sampled
            if (len(report["snapshots"]) == 1 or late_pressure) and not cancel() and time.monotonic() < deadline:
                snapshot["hostPressure"] = pressure(cancel, deadline)
                late_pressure_sampled |= late_pressure
                report["diagnosticFailures"] += failures(snapshot["hostPressure"])
            report["snapshots"].append(snapshot)
            retain_report(directory / "report.json", report)
            next_snapshot = min(deadline, time.monotonic() + cadence)
            while not cancel() and time.monotonic() < next_snapshot:
                time.sleep(.05)
        report["status"] = "stopped" if cancel() else "deadline"
    except Exception as failure:
        report["status"] = "failed"
        report["error"] = str(failure)[:1024]
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        if time.monotonic() >= wall_deadline:
            report["status"] = "failed"
            report["error"] = "Observer exceeded wall deadline during bounded cleanup/publication"
        retain_report(directory / "report.json", report)
        write_json(directory / "done.json", {"token": token, "pid": os.getpid(), "status": report["status"]})
    return 1 if report["status"] == "failed" else 0


def start(directory):
    directory.mkdir(parents=True, exist_ok=False)
    control = {"token": uuid.uuid4().hex, "binding": binding(), "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "startedUnix": time.time(), "serial": SERIAL, "avd": AVD}
    write_json(directory / "control.json", control)
    process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "observe", str(directory.resolve()), control["token"]],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        control["pid"] = process.pid
        write_json(directory / "control.json", control)
        end = time.monotonic() + 5
        while time.monotonic() < end and process.poll() is None:
            if (directory / "ready.json").exists():
                if json.loads((directory / "ready.json").read_text()) != {"token": control["token"], "pid": process.pid}:
                    break
                return process
            time.sleep(.05)
        raise RuntimeError("Observer failed start handshake")
    except BaseException:
        # Covers receipt/handshake failures after the exact child was created.
        process.terminate()
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=1)
        raise


def stop(directory):
    control = json.loads((directory / "control.json").read_text())
    if control["binding"] != binding():
        raise RuntimeError("Observer stop binding mismatch")
    write_json(directory / "stop.json", {"token": control["token"]})
    end = time.monotonic() + 15
    while time.monotonic() < end:
        done = directory / "done.json"
        if done.exists():
            completion = json.loads(done.read_text())
            if completion["token"] != control["token"] or completion["pid"] != control["pid"]:
                raise RuntimeError("Observer completion binding mismatch")
            # A zombie has exited; only its adopting parent can reap it.
            status = capture(["ps", "-p", str(control["pid"]), "-o", "stat="], deadline=end)
            if status["exit"] == 1 or status["stdout"].strip().startswith("Z"):
                write_json(directory / "joined.json", {"token": control["token"], "pid": control["pid"], "observerExited": True})
                if completion["status"] == "failed":
                    raise RuntimeError("Observer diagnostic failure; inspect capped report")
                return
        time.sleep(.05)
    raise RuntimeError("Observer did not acknowledge stop and exit; iOS must not start")


def startup_commands():
    """Read only exact target/package/port data, filtered in the guest before retention."""
    prefix = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", SERIAL, "shell"]
    return [("properties", prefix + [r"getprop | grep -E '^\[(sys\.boot_completed|ro\.build\.version\.sdk|sys\.system_server\.(start_count|start_elapsed|start_uptime))\]: \['"]),
            ("packageService", prefix + ["service check package"]),
            ("systemServer", prefix + ["pidof system_server || printf '\n'"]),
            ("driverPackage", prefix + ["pm path dev.mobile.maestro"]),
            ("instrumentationPackage", prefix + ["pm path dev.mobile.maestro.test"]),
            ("instrumentation", prefix + [r"pm list instrumentation | grep -E '^instrumentation:dev\.mobile\.maestro\.test/androidx\.test\.runner\.AndroidJUnitRunner \(target=dev\.mobile\.maestro\)$'"]),
            ("driverPid", prefix + ["pidof dev.mobile.maestro || printf '\n'"]),
            ("driverPort", prefix + [r"grep -E '^[ ]*[0-9]+: [0-9A-F]+:1B59 ' /proc/net/tcp /proc/net/tcp6"]),
            ("crash", prefix + [CRASH_LOG_SCRIPT.replace("logcat -b crash ", "logcat -b crash -b main -b system ", 1).replace(" -t 128", "", 1)]),
            ("startupLog", prefix + [r"logcat -b main -b system -d -t 80 -v brief -s Maestro:V AndroidRuntime:V TestRunner:V AndroidJUnitRunner:V ActivityManager:I Watchdog:V SystemServer:E | grep -E 'dev\.mobile\.maestro(\.test)?([: /]|$)|dev\.mobile\.maestro\.MaestroDriverService([: /]|$)|^E/AndroidRuntime(\([ ]*[0-9]+\))?[ ]*: (FATAL EXCEPTION IN SYSTEM PROCESS|Process: system_server([, ]|$))|^W/Watchdog(\([ ]*[0-9]+\))?[ ]*: \*\*\* WATCHDOG KILLING SYSTEM PROCESS: '"])]


def startup(directory, gradle_exit, duration=45, commands=startup_commands):
    """One bounded post-failure observation after the original observer has joined."""
    started = time.monotonic()
    if (directory / "startup.json").exists():
        raise RuntimeError("Duplicate startup collection refused; existing startup.json is prior evidence, not a new invocation")
    deadline = started + duration - min(2, duration / 4)
    names = ("control", "ready", "report", "done", "stop", "joined")
    original = {}
    size = 0
    for name in names:
        path = directory / (name + ".json")
        with path.open("rb") as stream:
            data = stream.read(MAX_REPORT + 1)
        size += len(data)
        if size > MAX_REPORT - 1024:
            raise RuntimeError("Original diagnostic metadata exceeds aggregate allowance")
        original[name] = json.loads(data)
    control = original["control"]
    if control["binding"] != binding() or control["scriptSha256"] != hashlib.sha256(Path(__file__).read_bytes()).hexdigest():
        raise RuntimeError("Startup immutable source/job binding mismatch")
    if control["serial"] != SERIAL or control["avd"] != AVD:
        raise RuntimeError("Startup target mismatch")
    identity = {"token": control["token"], "pid": control["pid"]}
    if original["ready"] != identity or original["stop"] != {"token": control["token"]}:
        raise RuntimeError("Startup observer ownership mismatch")
    if any(original[name].get(key) != value for name in ("done", "joined") for key, value in identity.items()):
        raise RuntimeError("Startup completion identity mismatch")
    if original["joined"].get("observerExited") is not True or original["done"].get("status") not in ("deadline", "stopped"):
        raise RuntimeError("Startup requires successful observer stop/join")
    if original["report"]["binding"] != control or original["report"]["target"] != {"serial": SERIAL, "avd": AVD}:
        raise RuntimeError("Startup original report binding mismatch")
    if gradle_exit == 0 or os.environ.get("MAESTRO_DRIVER_STARTUP_TIMEOUT") != "120000":
        raise RuntimeError("Startup requires original failed suite and exact SDK allowance")
    report = {"binding": control, "phase": "post-failed-packaged-android-suite", "gradleExit": gradle_exit,
              "sdkStartupAllowanceMs": 120000, "qualification": False, "commands": {}, "diagnosticFailures": 0,
              "truncated": False, "droppedRetainedBytes": 0, "status": "running"}
    terminate = [False]
    previous = {}
    def stop_signal(*_):
        terminate[0] = True
    for sig in (signal.SIGTERM, signal.SIGINT):
        previous[sig] = signal.signal(sig, stop_signal)
    try:
        for name, argv in commands():
            if terminate[0] or time.monotonic() >= deadline:
                break
            result = capture(argv, lambda: terminate[0], deadline, limit=256)
            result["evidenceStatus"] = "observed" if result["outcome"] == "completed" and result["exit"] == 0 and result["stdout"].strip() and not result["truncated"] else "unknown"
            report["commands"][name] = result
            report["diagnosticFailures"] += failures(result)
            report["truncated"] |= result["truncated"]
        report["status"] = "stopped" if terminate[0] else "deadline" if time.monotonic() >= deadline else "completed"
    except Exception as failure:
        report["status"] = "failed"
        report["error"] = str(failure)[:256]
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        cap = min(6000, MAX_REPORT - size) - 256  # Reserve encoded final timing/error metadata.
        while len((json.dumps(report, ensure_ascii=False) + "\n").encode()) > cap:
            fields = [(result, key) for result in report["commands"].values() for key in ("stdout", "stderr") if result[key]]
            if not fields:
                raise RuntimeError("Startup metadata exceeds encoded report allowance")
            result, key = max(fields, key=lambda item: len(item[0][item[1]].encode()))
            value = result[key]
            result[key] = value[:len(value) // 2]
            report["droppedRetainedBytes"] += len(value.encode()) - len(result[key].encode())
            report["truncated"] = result["truncated"] = True
            result["evidenceStatus"] = "unknown"
        if time.monotonic() >= started + duration:
            report["status"] = "failed"
            report["error"] = "Startup diagnostics exceeded wall deadline"
            raise RuntimeError(report["error"])
        write_json(directory / "startup.json", report)
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        if time.monotonic() >= started + duration:
            report["status"] = "failed"
            report["error"] = "Startup diagnostics exceeded wall deadline during publication"
        write_json(directory / "startup.json", report)
    return 1 if report["status"] == "failed" else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("start", "observe", "stop", "startup"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("token", nargs="?")
    args = parser.parse_args()
    if args.mode == "start":
        start(args.directory)
    elif args.mode == "stop":
        stop(args.directory)
    elif args.mode == "startup":
        raise SystemExit(startup(args.directory, int(args.token)))
    else:
        control = json.loads((args.directory / "control.json").read_text())
        if control["token"] != args.token:
            raise RuntimeError("Observer launch token mismatch")
        # Parent publishes PID before ready; do not accept a different process.
        if control.get("pid", os.getpid()) != os.getpid():
            raise RuntimeError("Observer launch PID mismatch")
        raise SystemExit(observe(args.directory, control))


if __name__ == "__main__":
    main()
