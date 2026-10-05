"""Finite read-only proof that this job's Android emulator exited before iOS."""
import hashlib
import json
import os
import platform
import re
import shlex
import signal
import socket
import stat
import sys
import time
import uuid
from pathlib import Path

from android_boot_diagnostics import binding, capture, SERIAL, AVD

CAP = 8192
PORTS = (5554, 5555)
SDK_EXECUTABLES = ("emulator", "qemu/darwin-aarch64/qemu-system-aarch64",
                   "qemu/darwin-aarch64/qemu-system-aarch64-headless")


def read(path, limit=CAP):
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise RuntimeError("Oversized handoff identity")
    return json.loads(data)


def write(path, value):
    data = (json.dumps(value) + "\n").encode()
    if len(data) > CAP or path.exists():
        raise RuntimeError("Duplicate or oversized handoff publication")
    with path.open("xb") as stream:
        stream.write(data)


def context():
    return {"binding": binding(), "sourceSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "observerSourceSha256": hashlib.sha256(Path(__file__).with_name("android_boot_diagnostics.py").read_bytes()).hexdigest(),
            "serial": SERIAL, "avd": AVD}


def eligible(outcomes, cancelled):
    return cancelled is False and outcomes == {"inputs": "success", "sdk": "success", "tools": "success",
        "prepare": "success", "observer": "success", "android": outcomes.get("android")} and outcomes.get("android") in ("success", "failure")


def checked_result(result, deadline):
    if result["outcome"] != "completed" or not result["joined"] or result["truncated"] or any(result["droppedBytes"].values()):
        raise RuntimeError("Unknown process/socket inspection")
    if time.monotonic() >= deadline:
        raise RuntimeError("Handoff deadline expired")
    return result



def checked(argv, deadline):
    return checked_result(capture(argv, deadline=deadline, limit=CAP), deadline)


def listener_observation(directory, seed, pid, result):
    fields, unknown, recognized = [], [], 0
    rows = result["stdout"].splitlines()
    for row in rows:
        numeric = re.fullmatch(r"([pf])([0-9]{1,10})", row)
        endpoint = re.fullmatch(r"n(\*|127\.0\.0\.1|\[::\]|\[::1\]):([0-9]{1,5})", row)
        if numeric:
            value = {"field": numeric[1], "value": int(numeric[2])}
        elif endpoint:
            value = {"field": "n", "address": endpoint[1], "port": int(endpoint[2])}
        else:
            unknown.append(row)
            continue
        recognized += 1
        if len(fields) < 64:
            fields.append(value)
    stderr = result["stderr"].encode()
    unknown_data = "\n".join(unknown).encode()
    dropped = recognized - len(fields)
    write(directory / "listener-observation.json", {"binding": seed["binding"], "sourceSha256": seed["sourceSha256"],
        "nonce": seed["nonce"], "serial": SERIAL, "avd": AVD, "pid": pid, "provisional": True,
        "status": {key: result[key] for key in ("outcome", "exit", "seconds", "joined", "truncated", "droppedBytes")},
        "rowCount": len(rows), "fields": fields, "droppedStructuredRows": dropped,
        "unknownRowCount": len(unknown), "unknownBytes": len(unknown_data),
        "unknownSha256": hashlib.sha256(unknown_data).hexdigest(),
        "stderrRowCount": len(stderr.splitlines()), "stderrBytes": len(stderr), "stderrSha256": hashlib.sha256(stderr).hexdigest()})
    if dropped:
        raise RuntimeError("Truncated listener observation")


def listener_ports(stdout, pid):
    rows = stdout.splitlines()
    if not rows or rows[0] != "p" + str(pid) or len(rows) < 3 or (len(rows) - 1) % 2:
        raise RuntimeError("Missing or foreign owned listener process/file set")
    descriptors, ports = set(), set()
    for index in range(1, len(rows), 2):
        descriptor = re.fullmatch(r"f([0-9]{1,10})", rows[index])
        endpoint = re.fullmatch(r"n(\*|127\.0\.0\.1|\[::\]|\[::1\]):([0-9]{1,5})", rows[index + 1])
        if not descriptor or not endpoint:
            raise RuntimeError("Unknown or orphan listener descriptor/endpoint")
        fd = int(descriptor[1])
        port = int(endpoint[2])
        if not 0 <= fd <= 2147483647 or fd in descriptors or not 1 <= port <= 65535:
            raise RuntimeError("Duplicate or invalid listener descriptor/port")
        descriptors.add(fd)
        ports.add(port)
    if not set(PORTS).issubset(ports):
        raise RuntimeError("Declared emulator ports not owned by exact process")
    return sorted(ports)


def free_ports(ports):
    for port in ports:
        if type(port) is not int or not 1 <= port <= 65535:
            raise RuntimeError("Invalid owned listener port")
        for family, address in ((socket.AF_INET, "0.0.0.0"), (socket.AF_INET6, "::")):
            with socket.socket(family, socket.SOCK_STREAM) as probe:
                if family == socket.AF_INET6:
                    probe.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
                probe.bind((address, port))



def closed_ports(ports, deadline):
    # An exited connection may remain in TIME_WAIT; require no LISTEN socket and
    # refused loopback connections, rather than treating non-reuse bind failure as live.
    for port in ports:
        if type(port) is not int or not 1 <= port <= 65535:
            raise RuntimeError("Invalid owned listener port")
        listeners = checked(["lsof", "-nP", "-t", "-iTCP:" + str(port), "-sTCP:LISTEN"], deadline)
        if listeners["exit"] != 1 or listeners["stdout"].strip() or listeners["stderr"].strip():
            raise RuntimeError("Owned port has a live or unknown listener")
        for family, address in ((socket.AF_INET, "127.0.0.1"), (socket.AF_INET6, "::1")):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("Handoff deadline expired")
            with socket.socket(family, socket.SOCK_STREAM) as probe:
                probe.settimeout(min(.2, remaining))
                try:
                    probe.connect((address, port))
                except ConnectionRefusedError:
                    continue
                except OSError as failure:
                    raise RuntimeError("Unknown port reachability") from failure
                raise RuntimeError("Owned port is still reachable")

def avd_paths(preparing=False):
    home = Path(os.environ["HOME"]) / ".android" / "avd" if preparing else Path(os.environ["ANDROID_AVD_HOME"])
    if not home.is_absolute() or ".." in home.parts or any(p.is_symlink() for p in (home, *home.parents)) or home.resolve() != home:
        raise RuntimeError("Ambiguous configured AVD parent")
    return home, home / "test.avd", home / "test.ini"


def config_path():
    return avd_paths()[1] / "config.ini"


def directory_identity(path):
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or path.resolve() != path:
        raise RuntimeError("Ambiguous AVD directory")
    return {"path": str(path), "device": info.st_dev, "inode": info.st_ino,
            "birthUnix": getattr(info, "st_birthtime", None)}


def descriptor_directory(home, target, descriptor):
    if not stat.S_ISREG(descriptor.lstat().st_mode):
        raise RuntimeError("Ambiguous AVD descriptor")
    with descriptor.open("rb") as stream:
        data = stream.read(CAP + 1)
    if len(data) > CAP:
        raise RuntimeError("Oversized AVD descriptor")
    values = {"path": [], "path.rel": []}
    for line in data.decode("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() in values:
            values[key.strip()].append(value.strip())
    if len(values["path"]) != 1 or len(values["path.rel"]) > 1:
        raise RuntimeError("Missing or duplicate AVD descriptor path")
    absolute = Path(values["path"][0])
    if not absolute.is_absolute() or absolute != target or absolute.resolve() != target:
        raise RuntimeError("AVD descriptor points outside owned directory")
    if values["path.rel"]:
        relative = Path(values["path.rel"][0])
        candidate = home.parent / relative
        if not values["path.rel"][0] or relative.is_absolute() or ".." in relative.parts or any(p.is_symlink() for p in (candidate, *candidate.parents)) or candidate.resolve() != target:
            raise RuntimeError("Relative AVD descriptor points outside owned directory")


def owned_directory(seed):
    home, target, descriptor = avd_paths()
    if directory_identity(home) != seed["avdHome"] or str(target) != seed["avdDirectoryPath"] or str(descriptor) != seed["avdDescriptorPath"] or seed.get("avdTargetAbsent") is not True:
        raise RuntimeError("Configured AVD parent/namespace identity changed")
    identity = directory_identity(target)
    descriptor_directory(home, target, descriptor)
    return identity


def sdk_registration():
    configured = Path(os.environ["ANDROID_HOME"]) / "emulator"
    if not configured.is_absolute():
        raise RuntimeError("Configured SDK path is not absolute")
    sdk = configured.resolve()
    directory = directory_identity(sdk)
    files, absent = [], []
    for relative in SDK_EXECUTABLES:
        path = sdk / relative
        if not path.exists() and not path.is_symlink():
            absent.append(str(path))
            continue
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or path.resolve() != path or sdk not in path.parents or not os.access(path, os.X_OK):
            raise RuntimeError("Ambiguous configured SDK executable")
        files.append({"path": str(path), "device": info.st_dev, "inode": info.st_ino,
                      "size": info.st_size, "mtimeNs": info.st_mtime_ns,
                      "birthUnix": getattr(info, "st_birthtime", None)})
    if str(sdk / "emulator") in absent or len(files) < 2:
        raise RuntimeError("Required configured SDK frontend/ARM64 backend is missing")
    return {"configuredPath": str(configured), "directory": directory, "files": files, "absent": absent}


def config_identity(path):
    if path.is_symlink() or path.parent.is_symlink() or not path.is_file():
        raise RuntimeError("Ambiguous AVD configuration")
    with path.open("rb") as stream:
        data = stream.read(CAP + 1)
    if len(data) > CAP:
        raise RuntimeError("Oversized AVD configuration")
    stat = path.stat()
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(data).hexdigest(),
            "inode": stat.st_ino, "mtimeNs": stat.st_mtime_ns}


def load(directory, name):
    seed = read(directory / "prepared.json")
    if any(seed.get(k) != v for k, v in context().items()):
        raise RuntimeError("Stale source/job/target ownership")
    if not re.fullmatch(r"[a-f0-9]{32}", seed.get("nonce", "")):
        raise RuntimeError("Invalid ownership nonce")
    record = read(directory / name)
    if record.get("prepared") != seed:
        raise RuntimeError("Ownership receipt mismatch")
    return seed, record


def prepare(directory):
    if platform.system() != "Darwin" or platform.machine() not in ("arm64", "aarch64"):
        raise RuntimeError("Matching Mac ARM host required")
    home, target, descriptor = avd_paths(preparing=True)
    home.mkdir(parents=True, exist_ok=True)
    parent = directory_identity(home)
    if target.exists() or target.is_symlink() or descriptor.exists() or descriptor.is_symlink():
        raise RuntimeError("AVD directory or descriptor already exists before preparation")
    directory.mkdir(parents=True, exist_ok=False)
    free_ports(PORTS)
    write(directory / "prepared.json", {**context(), "nonce": uuid.uuid4().hex,
                                       "startedUnix": time.time(), "declaredPorts": list(PORTS),
                                       "avdHome": parent, "avdDirectoryPath": str(target),
                                       "avdDescriptorPath": str(descriptor), "avdTargetAbsent": True})


def prelaunch(directory):
    seed = read(directory / "prepared.json")
    if any(seed.get(k) != v for k, v in context().items()):
        raise RuntimeError("Prelaunch binding mismatch")
    free_ports(PORTS)
    identity = owned_directory(seed)
    config = config_identity(config_path())
    write(directory / "prelaunch.json", {"prepared": seed, "avdDirectory": identity,
                                        "config": config, "sdkExecutables": sdk_registration(), "beforeLaunchUnix": time.time()})


def process_identity(pid, deadline):
    if type(pid) is not int or not 1 <= pid <= 2147483647:
        raise RuntimeError("Invalid recorded process PID")
    row = checked(["ps", "-p", str(pid), "-o", "lstart=", "-o", "command="], deadline)
    if row["exit"] == 1 and not row["stdout"].strip() and not row["stderr"].strip():
        return None
    if row["exit"] != 0 or row["stderr"].strip() or len(row["stdout"].splitlines()) != 1:
        raise RuntimeError("Unknown process identity")
    text = row["stdout"].strip()
    match = re.fullmatch(r"([A-Z][a-z]{2} [A-Z][a-z]{2} +[0-9]{1,2} [0-9:]{8} [0-9]{4}) +(.+)", text)
    if not match:
        raise RuntimeError("Malformed process start/argv")
    return {"pid": pid, "started": match[1], "argv": shlex.split(match[2])}


def active(directory, observer_directory, deadline):
    seed, pre = load(directory, "prelaunch.json")
    if owned_directory(seed) != pre["avdDirectory"]:
        raise RuntimeError("AVD directory identity changed before active collection")
    config = config_identity(config_path())  # Audit mutable SDK configuration; directory identity is ownership.
    control = read(observer_directory / "control.json")
    if control["binding"] != seed["binding"] or control["scriptSha256"] != seed["observerSourceSha256"] or control["serial"] != SERIAL or control["avd"] != AVD:
        raise RuntimeError("Observer binding mismatch")
    listener = checked(["lsof", "-nP", "-t", "-iTCP:5554", "-sTCP:LISTEN"], deadline)
    values = listener["stdout"].splitlines()
    if listener["exit"] != 0 or listener["stderr"].strip() or len(values) != 1 or not re.fullmatch(r"[1-9][0-9]{0,9}", values[0]):
        raise RuntimeError("Missing or ambiguous console listener")
    pid = int(values[0])
    if pid > 2147483647:
        raise RuntimeError("Invalid owned PID")
    identity = process_identity(pid, deadline)
    if identity is None:
        raise RuntimeError("Emulator exited before identity collection")
    argv = identity["argv"]
    configured_sdk = Path(os.environ["ANDROID_HOME"]) / "emulator"
    sdk = configured_sdk.resolve()
    raw_executable = Path(argv[0])
    executable = raw_executable.resolve()
    try:
        registered = sdk_registration()
    except (OSError, RuntimeError):
        registered = None
    checks = {"absolute": raw_executable.is_absolute(), "declaredSdkSpelling": str(raw_executable) in [str(root / relative) for root in (configured_sdk, sdk) for relative in SDK_EXECUTABLES],
              "configuredSdkContainment": sdk in executable.parents,
              "exactAllowedPath": str(executable) in [str(sdk / relative) for relative in SDK_EXECUTABLES],
              "registeredFile": str(executable) in [row["path"] for row in pre["sdkExecutables"]["files"]],
              "registrationUnchanged": registered == pre["sdkExecutables"]}
    write(directory / "process-observation.json", {"binding": seed["binding"], "sourceSha256": seed["sourceSha256"],
        "nonce": seed["nonce"], "serial": SERIAL, "avd": AVD, "provisional": True,
        "pid": pid, "started": identity["started"], "rawExecutablePath": str(raw_executable),
        "executablePath": str(executable), "expectedSdk": str(sdk),
        "checks": checks})
    if not all(checks.values()):
        raise RuntimeError("Console listener is not an unchanged registered SDK executable")
    if argv.count("-avd") != 1 or argv[argv.index("-avd") + 1] != AVD or argv.count("-port") != 1 or argv[argv.index("-port") + 1] != "5554":
        raise RuntimeError("Emulator argv does not identify the owned AVD/port")
    started = time.mktime(time.strptime(identity["started"], "%a %b %d %H:%M:%S %Y"))
    if started < int(pre["beforeLaunchUnix"]) or started > time.time():
        raise RuntimeError("Process predates owned launch")
    ports = capture(["lsof", "-nP", "-a", "-p", str(pid), "-iTCP", "-sTCP:LISTEN", "-Fn"], deadline=deadline, limit=CAP)
    listener_observation(directory, seed, pid, ports)
    checked_result(ports, deadline)
    if ports["exit"] != 0 or ports["stderr"].strip():
        raise RuntimeError("Unknown owned listener inventory status")
    owned = listener_ports(ports["stdout"], pid)
    if time.monotonic() >= deadline:
        raise RuntimeError("Identity collection deadline expired")
    write(directory / "active.json", {"prepared": seed, "prelaunch": pre, "observer": control,
                                      "process": identity, "listenerPorts": owned, "configAfter": config,
                                      "observedUnix": time.time()})


def guard(directory, observer_directory, outcomes, cancelled, deadline):
    if not eligible(outcomes, cancelled):
        raise RuntimeError("Cancelled, skipped or failed setup/verification/observer boundary")
    seed, active_record = load(directory, "active.json")
    _, pre = load(directory, "prelaunch.json")
    if active_record["prelaunch"] != pre:
        raise RuntimeError("Stale launch identity")
    if owned_directory(seed) != pre["avdDirectory"]:
        raise RuntimeError("AVD directory identity changed before handoff")
    if sdk_registration() != pre["sdkExecutables"]:
        raise RuntimeError("Configured SDK executable registration changed before handoff")
    control = read(observer_directory / "control.json")
    if active_record["observer"] != control:
        raise RuntimeError("Observer identity changed")
    expected = {"token": control["token"], "pid": control["pid"]}
    for name in ("done", "joined"):
        row = read(observer_directory / (name + ".json"))
        if any(row.get(k) != v for k, v in expected.items()):
            raise RuntimeError("Observer completion mismatch")
        if name == "done" and row.get("status") not in ("stopped", "deadline") or name == "joined" and row.get("observerExited") is not True:
            raise RuntimeError("Observer did not stop/join successfully")
    if read(observer_directory / "stop.json") != {"token": control["token"]} or read(observer_directory / "ready.json") != expected:
        raise RuntimeError("Observer stop/ready mismatch")
    report = read(observer_directory / "report.json", 262144)
    if report.get("binding") != control or report.get("target") != {"serial": SERIAL, "avd": AVD} or report.get("truncated") is not False or report.get("droppedSnapshots") != 0 or report.get("droppedSnapshotBytes") != 0:
        raise RuntimeError("Unknown or truncated observer ownership report")
    original = active_record["process"]
    while True:
        current = process_identity(original["pid"], deadline)
        if current is None:
            break
        if current != original:
            raise RuntimeError("Recorded PID reused or process identity changed")
        if time.monotonic() + .2 >= deadline:
            raise RuntimeError("Owned emulator still live at handoff deadline")
        time.sleep(.2)
    closed_ports(active_record["listenerPorts"], deadline)
    if time.monotonic() >= deadline:
        raise RuntimeError("Port inspection exceeded handoff deadline")
    write(directory / "handoff.json", {"prepared": seed, "processExited": True, "portsClosed": active_record["listenerPorts"],
                                      "observerJoined": expected, "androidOutcome": outcomes["android"], "iosMayStart": True})


def main():
    def cancel(*_):
        raise KeyboardInterrupt("Handoff cancelled; iOS refused")
    signal.signal(signal.SIGTERM, cancel)
    end = time.monotonic() + 28  # Reserve two seconds for publication within the 30s named envelope.
    mode, directory, *extra = sys.argv[1:]
    directory = Path(directory)
    if mode == "prepare":
        prepare(directory)
    elif mode == "prelaunch":
        prelaunch(directory)
    elif mode == "active":
        active(directory, Path(extra[0]), end)
    elif mode == "guard":
        guard(directory, Path(extra[0]), json.loads(os.environ["VERITY_HANDOFF_OUTCOMES"]), os.environ["VERITY_HANDOFF_CANCELLED"] != "false", end)
    else:
        raise RuntimeError("Unknown handoff operation")
    if time.monotonic() >= end + 2:
        raise RuntimeError("Handoff operation exceeded total deadline")


if __name__ == "__main__":
    main()
