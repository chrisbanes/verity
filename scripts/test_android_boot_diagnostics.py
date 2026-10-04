"""Offline fixtures only: Python children and fake SDK commands, never a device."""

import ast
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("diagnostics", Path(__file__).with_name("android_boot_diagnostics.py"))
D = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D)
ENV = {key: "fixture-" + key for key in D.KEYS}


class DiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.env = patch.dict(os.environ, ENV)
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def control(self):
        return {"token": "offline-token", "binding": D.binding(), "scriptSha256": hashlib.sha256(Path(D.__file__).read_bytes()).hexdigest(),
                "serial": D.SERIAL, "avd": D.AVD}

    def startup_fixture(self):
        control = {**self.control(), "pid": 1234}
        identity = {"token": control["token"], "pid": control["pid"]}
        records = {"control": control, "ready": identity, "stop": {"token": control["token"]},
                   "done": {**identity, "status": "deadline"}, "joined": {**identity, "observerExited": True},
                   "report": {"binding": control, "target": {"serial": D.SERIAL, "avd": D.AVD}}}
        for name, value in records.items():
            D.write_json(self.directory / (name + ".json"), value)
        return records

    def test_startup_encoded_cap_and_aggregate_accounting(self):
        records = self.startup_fixture()
        total = sum(p.stat().st_size for p in self.directory.glob("*.json"))
        records["report"]["padding"] = "x" * (D.MAX_REPORT - 5000 - total - 15)
        D.write_json(self.directory / "report.json", records["report"])
        result = {"exit": 0, "outcome": "completed", "stdout": '\"\\' * 4000, "stderr": "字" * 4000,
                  "droppedBytes": {"stdout": 0, "stderr": 0}, "joined": True, "truncated": False}
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}), patch.object(D, "capture", side_effect=lambda *_args, **_kwargs: json.loads(json.dumps(result))):
            self.assertEqual(D.startup(self.directory, 17, commands=lambda: [(str(i), ["offline"]) for i in range(8)]), 0)
        path = self.directory / "startup.json"
        report = json.loads(path.read_text())
        self.assertLessEqual(path.stat().st_size, 6000)
        self.assertLessEqual(sum(p.stat().st_size for p in self.directory.glob("*.json")), D.MAX_REPORT)
        self.assertEqual(report["gradleExit"], 17)
        self.assertFalse(report["qualification"])
        self.assertGreater(report["droppedRetainedBytes"], 0)
        trimmed = [r for r in report["commands"].values() if r["truncated"]]
        self.assertTrue(trimmed)
        self.assertTrue(all(r["evidenceStatus"] == "unknown" for r in trimmed))
        self.assertTrue(all(r["droppedBytes"] == {"stdout": 0, "stderr": 0} for r in trimmed))

    def test_startup_actual_capture_truncation_is_unknown_while_complete_output_observed(self):
        self.startup_fixture()
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}):
            self.assertEqual(D.startup(self.directory, 17, commands=lambda: [
                ("huge", [sys.executable, "-c", "import os; os.write(1,b'x'*300000); os.write(2,b'y'*300000)"]),
                ("complete", [sys.executable, "-c", "print('complete')"])]), 0)
        report = json.loads((self.directory / "startup.json").read_text())
        huge = report["commands"]["huge"]
        self.assertEqual(huge["exit"], 0)
        self.assertTrue(huge["joined"])
        self.assertTrue(huge["truncated"])
        self.assertEqual(huge["evidenceStatus"], "unknown")
        self.assertEqual(huge["droppedBytes"], {"stdout": 300000 - 256, "stderr": 300000 - 256})
        self.assertEqual(huge["stdout"], "x" * 256)
        self.assertEqual(report["commands"]["complete"]["evidenceStatus"], "observed")
        self.assertEqual(report["gradleExit"], 17)
        self.assertFalse(report["qualification"])

    def test_startup_duplicate_is_refused_without_overwriting_prior_evidence(self):
        self.startup_fixture()
        path = self.directory / "startup.json"
        path.write_text('{"prior": true}\n')
        previous = path.read_bytes()
        with patch.object(D, "capture", side_effect=AssertionError("duplicate probe")):
            with self.assertRaisesRegex(RuntimeError, "prior evidence, not a new invocation"):
                D.startup(self.directory, 17)
        self.assertEqual(path.read_bytes(), previous)

    def test_startup_stale_identity_source_and_success_refused_before_probe(self):
        for name, key, value in (("control", "scriptSha256", "bad"), ("control", "serial", "ambient"),
                                 ("ready", "token", "stale"), ("done", "pid", 999),
                                 ("joined", "observerExited", False), ("report", "binding", {})):
            with self.subTest(name=name, key=key):
                records = self.startup_fixture()
                records[name][key] = value
                D.write_json(self.directory / (name + ".json"), records[name])
                with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}), patch.object(D, "capture", side_effect=AssertionError("invalid probe")):
                    with self.assertRaises(RuntimeError):
                        D.startup(self.directory, 1)
        self.startup_fixture()
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}), patch.object(D, "capture", side_effect=AssertionError("successful suite probe")):
            with self.assertRaises(RuntimeError):
                D.startup(self.directory, 0)

    def test_startup_deadline_cancels_and_joins_command_without_later_probe(self):
        self.startup_fixture()
        started = time.monotonic()
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}):
            self.assertEqual(D.startup(self.directory, 1, duration=1.2,
                                     commands=lambda: [("slow", [sys.executable, "-c", "import os,time; print(os.getpid(),flush=True); time.sleep(30)"]),
                                                       ("later", [sys.executable, "-c", "print('offline')"])]), 0)
        report = json.loads((self.directory / "startup.json").read_text())
        self.assertLess(time.monotonic() - started, 1.2)
        self.assertTrue(report["commands"]["slow"]["joined"])
        self.assertEqual(report["commands"]["slow"]["outcome"], "timeout")
        with self.assertRaises(ProcessLookupError):
            os.kill(int(report["commands"]["slow"]["stdout"].strip()), 0)
        self.assertNotEqual(report["commands"].get("later", {}).get("outcome"), "completed")

    def test_startup_signal_stops_after_owned_command_and_restores_handler(self):
        self.startup_fixture()
        calls = []
        def fake_capture(argv, cancel, deadline, limit):
            calls.append(argv)
            os.kill(os.getpid(), D.signal.SIGTERM)
            self.assertTrue(cancel())
            return {"exit": -15, "outcome": "stopped", "stdout": "", "stderr": "", "joined": True,
                    "truncated": False, "droppedBytes": {"stdout": 0, "stderr": 0}}
        previous = D.signal.getsignal(D.signal.SIGTERM)
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}), patch.object(D, "capture", side_effect=fake_capture):
            self.assertEqual(D.startup(self.directory, 1, commands=lambda: [("first", ["offline"]), ("later", ["forbidden"])]), 0)
        self.assertEqual(calls, [["offline"]])
        self.assertEqual(D.signal.getsignal(D.signal.SIGTERM), previous)
        self.assertEqual(json.loads((self.directory / "startup.json").read_text())["status"], "stopped")

    def test_startup_command_scope_and_guest_filters(self):
        with patch.dict(os.environ, {"ANDROID_HOME": "/offline/sdk"}):
            commands = D.startup_commands()
        for name, argv in commands:
            self.assertEqual(argv[:4], ["/offline/sdk/platform-tools/adb", "-s", "emulator-5554", "shell"])
            self.assertNotIn("install", argv[-1])
            self.assertNotIn("am instrument", argv[-1])
            self.assertNotIn("forward", argv[-1])
        self.assertEqual([name for name, _ in commands[:3]], ["properties", "packageService", "systemServer"])
        scripts = dict(commands)
        fixture = self.directory / "bin"
        fixture.mkdir()
        outputs = {"getprop": "[sys.boot_completed]: [1]\n[ro.build.version.sdk]: [34]\n[sys.system_server.start_count]: [2]\n[sys.system_server.start_elapsed]: [123]\n[sys.system_server.start_uptime]: [120]\n[sys.system_server.start_count.secret]: [private]\n[sys.system_serverXstart_count]: [private]\n[sys.boot_completed.secret]: [private]\n[unrelated]: [private]\n",
                   "pm": "instrumentation:dev.mobile.maestro.test/androidx.test.runner.AndroidJUnitRunner (target=dev.mobile.maestro)\ninstrumentation:other/private (target=private)\n",
                   "logcat": "E/AndroidRuntime: private other crash\nE/AndroidRuntime: dev.mobile.maestro.private unrelated\nE/AndroidRuntime: dev.mobile.maestro startup failure\nI/TestRunner: dev.mobile.maestro.MaestroDriverService ready\nE/AndroidRuntime: FATAL EXCEPTION IN SYSTEM PROCESS: main\nE/AndroidRuntime: Process: system_server, PID: 569\nW/Watchdog( 524): *** WATCHDOG KILLING SYSTEM PROCESS: blocked\nE/AndroidRuntime: Process: system_server.private\nE/Other: FATAL EXCEPTION IN SYSTEM PROCESS private\nE/Other: WATCHDOG KILLING SYSTEM PROCESS private\nW/WatchdogPrivate: *** WATCHDOG KILLING SYSTEM PROCESS: private\nF/DEBUG   ( 600): pid: 524, tid: 525, name: Binder:524_1  >>> system_server <<<\nF/DEBUG   ( 600): pid: 524, tid: 526, name: Binder Pool  >>> system_server <<<\nF/DEBUG   ( 600): pid: 524, tid: 525, name: Binder:524_1  >>> system_server.private <<<\nF/DEBUGPrivate: pid: 524, tid: 525, name: Binder:524_1  >>> system_server <<< private\n",
                   "service": "Service package: not found\n", "pidof": "569\n"}
        for name, output in outputs.items():
            path = fixture / name
            path.write_text("#!" + sys.executable + "\nprint(" + repr(output) + ", end='')\n")
            path.chmod(0o755)
        with patch.dict(os.environ, {"PATH": str(fixture) + os.pathsep + os.environ["PATH"]}):
            for name in ("properties", "instrumentation", "crash", "startupLog"):
                result = subprocess.run(["/bin/sh", "-c", scripts[name][-1]], capture_output=True, text=True, check=True)
                self.assertNotIn("private", result.stdout)
                self.assertTrue(result.stdout)
                if name == "properties":
                    self.assertEqual(result.stdout.splitlines(), ["[sys.boot_completed]: [1]", "[ro.build.version.sdk]: [34]",
                                     "[sys.system_server.start_count]: [2]", "[sys.system_server.start_elapsed]: [123]",
                                     "[sys.system_server.start_uptime]: [120]"])
                if name in ("crash", "startupLog"):
                    self.assertIn("FATAL EXCEPTION IN SYSTEM PROCESS", result.stdout)
                    self.assertIn("W/Watchdog( 524): *** WATCHDOG KILLING SYSTEM PROCESS: blocked", result.stdout)
                    if name == "crash":
                        self.assertIn("F/DEBUG   ( 600): pid: 524, tid: 525, name: Binder:524_1  >>> system_server <<<", result.stdout)
                        self.assertIn("name: Binder Pool  >>> system_server <<<", result.stdout)
                        self.assertIn("-t 128", scripts[name][-1])
                    else:
                        self.assertNotIn("F/DEBUG", result.stdout)
            self.assertEqual(subprocess.run(["/bin/sh", "-c", scripts["packageService"][-1]], capture_output=True, text=True, check=True).stdout,
                             "Service package: not found\n")
            self.assertEqual(subprocess.run(["/bin/sh", "-c", scripts["systemServer"][-1]], capture_output=True, text=True, check=True).stdout, "569\n")
            (fixture / "pidof").write_text("#!/bin/sh\nexit 1\n")
            self.assertEqual(subprocess.run(["/bin/sh", "-c", scripts["systemServer"][-1]], capture_output=True, text=True, check=True).stdout, "\n")

    def test_actual_mac_gradle_argv_forces_only_complete_selected_test_task(self):
        workflow = (Path(__file__).parents[1] / ".github/workflows/ci.yml").read_text()
        script = workflow.split("pre-emulator-launch-script:", 1)[1].split("python3 -c '", 1)[1].split("'", 1)[0]
        parsed = ast.parse(script.replace("\n", " "))
        tail = ast.Module(body=parsed.body[-3:], type_ignores=[])
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            with self.assertRaises(SystemExit):
                exec(compile(tail, "actual-task-argv", "exec"), {"subprocess": subprocess})
        self.assertEqual(run.call_args.args[0], ["./gradlew", ":verity:smoke-tests:packagedAndroidTest", "--rerun",
                                              "-PpackagedVariant=macos-aarch64,universal", "--no-scan",
                                              "-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g"])

    def test_failed_gradle_code_preserved_even_when_diagnostics_fail(self):
        workflow = (Path(__file__).parents[1] / ".github/workflows/ci.yml").read_text()
        script = workflow.split("pre-emulator-launch-script:", 1)[1].split("python3 -c '", 1)[1].split("'", 1)[0]
        parsed = ast.parse(script.replace("\n", " "))
        tail = ast.Module(body=parsed.body[-3:], type_ignores=[])
        for failed in (0, 19):
            with patch.object(subprocess, "run", side_effect=[subprocess.CompletedProcess([], failed), subprocess.CompletedProcess([], 99)]) as run:
                with self.assertRaises(SystemExit) as exit_result:
                    exec(compile(tail, "failure-tail", "exec"), {"subprocess": subprocess})
                self.assertEqual(exit_result.exception.code, failed)
                self.assertEqual(run.call_count, 1 if failed == 0 else 2)
                if failed:
                    self.assertEqual(run.call_args.args[0][-1], "19")

    def test_huge_output_drains_both_pipes_and_reaps(self):
        result = D.capture([sys.executable, "-c", "import os; os.write(1,b'x'*300000); os.write(2,b'y'*300000)"])
        self.assertEqual(result["exit"], 0)
        self.assertEqual(result["stdout"], "x" * D.MAX_STREAM)
        self.assertEqual(result["stderr"], "y" * D.MAX_STREAM)
        self.assertEqual(result["droppedBytes"], {"stdout": 300000 - D.MAX_STREAM, "stderr": 300000 - D.MAX_STREAM})
        self.assertTrue(result["joined"])

    def test_deadline_terminates_and_joins_exact_owned_child(self):
        result = D.capture([sys.executable, "-c", "import os,time; print(os.getpid(),flush=True); time.sleep(30)"], deadline=time.monotonic() + 1)
        self.assertEqual(result["outcome"], "timeout")
        self.assertLess(result["seconds"], 2)
        with self.assertRaises(ProcessLookupError):
            os.kill(int(result["stdout"].strip()), 0)

    def test_no_spawn_without_remaining_cleanup_budget(self):
        with patch.object(D.subprocess, "Popen", side_effect=AssertionError("spawned without cleanup allowance")):
            result = D.capture(["never"], deadline=time.monotonic() + .5)
        self.assertEqual(result["outcome"], "deadline")

    def test_cancellation_joins_child_that_ignores_term(self):
        start = time.monotonic()
        result = D.capture([sys.executable, "-c", "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); print('ready',flush=True); time.sleep(30)"],
                           cancel=lambda: time.monotonic() - start > .15)
        self.assertEqual(result["outcome"], "stopped")
        self.assertLess(result["seconds"], 2)
        self.assertTrue(result["joined"])

    def test_no_spawn_after_stop(self):
        with patch.object(D.subprocess, "Popen", side_effect=AssertionError("spawned after stop")):
            result = D.capture(["never"], cancel=lambda: True)
        self.assertEqual(result["outcome"], "stopped")
        D.write_json(self.directory / "stop.json", {"token": "offline-token"})
        with patch.object(D, "capture", side_effect=AssertionError("probe after stop")):
            self.assertEqual(D.observe(self.directory, self.control(), inventory=lambda *_: self.fail("input after stop"), commands=lambda: self.fail("commands after stop")), 0)
        self.assertEqual(json.loads((self.directory / "report.json").read_text())["snapshots"], [])

    def test_immutable_binding_and_target_rejected_before_probes(self):
        for key, replacement in (("serial", "emulator-5556"), ("avd", "user-device"), ("scriptSha256", "bad"), ("binding", {})):
            with self.subTest(key=key):
                control = self.control()
                control[key] = replacement
                self.assertEqual(D.observe(self.directory, control, inventory=lambda *_: self.fail("probe with invalid binding")), 1)
                self.assertEqual(json.loads((self.directory / "done.json").read_text())["status"], "failed")

    def test_report_cap_preserves_early_and_late_and_accounts_drops(self):
        report = {"snapshots": [{"index": i, "data": "\\\"" * 12000} for i in range(30)], "droppedSnapshots": 0, "droppedSnapshotBytes": 0, "truncated": False}
        D.retain_report(self.directory / "report.json", report)
        self.assertLessEqual((self.directory / "report.json").stat().st_size, D.REPORT_CAP)
        self.assertEqual(report["snapshots"][0]["index"], 0)
        self.assertEqual(report["snapshots"][-1]["index"], 29)
        self.assertGreater(report["droppedSnapshotBytes"], 0)

    def test_observe_deadline_and_later_host_pressure(self):
        counts = []
        self.assertEqual(D.observe(self.directory, self.control(), inventory=lambda *_: {"offline": True}, commands=lambda: [], duration=.2, cadence=.03,
                                   pressure=lambda *_: counts.append("pressure") or {"offline": True}), 0)
        report = json.loads((self.directory / "report.json").read_text())
        self.assertEqual(report["status"], "deadline")
        self.assertEqual(counts, ["pressure"])
        self.assertFalse(report["qualification"])

    def test_input_error_records_failure_and_done(self):
        def fail(*_):
            raise OSError("offline inventory fixture error")
        self.assertEqual(D.observe(self.directory, self.control(), inventory=fail), 1)
        self.assertEqual(json.loads((self.directory / "done.json").read_text())["status"], "failed")
        self.assertIn("fixture error", json.loads((self.directory / "report.json").read_text())["error"])

    def test_stop_during_first_command_prevents_later_probe(self):
        calls = []
        def fake_capture(argv, cancel, deadline):
            calls.append(argv)
            D.write_json(self.directory / "stop.json", {"token": "offline-token"})
            return {"exit": 0, "outcome": "completed", "truncated": False}
        with patch.object(D, "capture", side_effect=fake_capture):
            self.assertEqual(D.observe(self.directory, self.control(), inventory=lambda *_: {}, commands=lambda: [("first", ["fixture"]), ("second", ["forbidden"])]), 0)
        self.assertEqual(calls, [["fixture"]])

    def fake_sdk(self):
        sdk = self.directory / "sdk"
        for name in ("emulator", "platform-tools", "bin", "avd/test.avd"):
            (sdk / name).mkdir(parents=True)
        (sdk / "emulator/source.properties").write_text("Pkg.Revision=offline\n")
        (sdk / "emulator/emulator").write_text("not executable; hash fixture only")
        (sdk / "avd/test.avd/config.ini").write_text("hw.cpu.ncore=2\nhw.ramSize=2048\n")
        for path in (sdk / "platform-tools/adb", sdk / "bin/sysctl", sdk / "bin/vm_stat"):
            path.write_text("#!" + sys.executable + "\nprint('offline fixture')\n")
            path.chmod(0o755)
        return {"ANDROID_HOME": str(sdk), "ANDROID_AVD_HOME": str(sdk / "avd"), "PATH": str(sdk / "bin") + os.pathsep + os.environ["PATH"]}

    def test_real_start_stop_join_handshake_offline_commands_only(self):
        directory = self.directory / "observer"
        with patch.dict(os.environ, self.fake_sdk()):
            process = D.start(directory)
            try:
                end = time.monotonic() + 3
                while not (directory / "report.json").exists() and time.monotonic() < end:
                    time.sleep(.02)
            finally:
                try:
                    D.stop(directory)
                finally:
                    process.wait(timeout=3)
            D.stop(directory)
        joined = json.loads((directory / "joined.json").read_text())
        self.assertTrue(joined["observerExited"])
        self.assertLessEqual(sum(p.stat().st_size for p in directory.glob("*.json")), D.MAX_REPORT)
        self.assertEqual(json.loads((directory / "control.json").read_text())["serial"], "emulator-5554")

    def test_post_boot_script_stop_precedes_functional_work_and_propagates_failure(self):
        workflow = (Path(__file__).parents[1] / ".github/workflows/ci.yml").read_text()
        script = workflow.split("pre-emulator-launch-script:", 1)[1].split("python3 -c '", 1)[1].split("'", 1)[0]
        parsed = ast.parse(script.replace("\n", " "))
        prefix = ast.Module(body=parsed.body[:3], type_ignores=[])
        expected = ["python3", "scripts/android_boot_diagnostics.py", "stop",
                    "verity/smoke-tests/build/reports/packaged-targets/android-bootstrap"]
        with patch.object(subprocess, "run") as run:
            exec(compile(prefix, "post-boot-fixture", "exec"), {})
            run.assert_called_once_with(expected, check=True)
        with patch.object(subprocess, "run", side_effect=subprocess.CalledProcessError(1, expected)) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                exec(compile(parsed, "post-boot-fixture", "exec"), {})
            run.assert_called_once_with(expected, check=True)

    def test_stop_binding_mismatch_never_requests_stop(self):
        control = self.control()
        control["binding"] = {}
        D.write_json(self.directory / "control.json", control)
        with self.assertRaisesRegex(RuntimeError, "binding mismatch"):
            D.stop(self.directory)
        self.assertFalse((self.directory / "stop.json").exists())

    def test_post_spawn_control_write_failure_joins_owned_child(self):
        original_write = D.write_json
        original_popen = D.subprocess.Popen
        owned = []
        def write(path, value):
            if path.name == "control.json" and "pid" in value:
                raise OSError("offline post-spawn write fixture")
            original_write(path, value)
        def spawn(*args, **kwargs):
            process = original_popen(*args, **kwargs)
            owned.append(process)
            return process
        with patch.dict(os.environ, self.fake_sdk()), patch.object(D, "write_json", side_effect=write), patch.object(D.subprocess, "Popen", side_effect=spawn):
            with self.assertRaisesRegex(OSError, "post-spawn"):
                D.start(self.directory / "observer")
        self.assertEqual(len(owned), 1)
        self.assertIsNotNone(owned[0].poll())

    def test_guest_commands_are_read_only_and_serial_bound(self):
        with patch.dict(os.environ, {"ANDROID_HOME": "/offline/sdk", "ANDROID_SERIAL": "ambient-device"}):
            commands = D.guest_commands()
        self.assertEqual([name for name, _ in commands], ["properties", "systemServer", "onlineCpu", "memTotal", "crash", "bootEvents"])
        for _, argv in commands:
            self.assertEqual(argv[:3], ["/offline/sdk/platform-tools/adb", "-s", "emulator-5554"])
            self.assertNotIn("devices", argv)
            self.assertNotIn("clear", argv)
        self.assertIn("pidof system_server", commands[1][1][-1])
        self.assertIn("-d", commands[4][1])
        self.assertIn("-t", commands[5][1])

    def test_single_property_list_filters_exact_names_before_retention(self):
        sdk = self.directory / "snapshot-sdk"
        sdk.mkdir()
        calls = sdk / "calls"
        allowed = ["[" + name + "]: [allowed-" + name + "]" for name in D.PROPERTIES]
        deceptive = ["[sysXboot_completed]: [secret-dot]", "[sys.boot_completed.extra]: [secret-suffix]",
                     "[dalvik.vm.heapsize.extra]: [secret-heap]", "[unrelated.private]: [secret-private]",
                     "prefix [sys.boot_completed]: [secret-prefix]"]
        for name, output in (("pidof", "579\n"), ("getprop", "\n".join(allowed + deceptive) + "\n")):
            executable = sdk / name
            executable.write_text("#!" + sys.executable + "\nfrom pathlib import Path\np = Path(" + repr(str(calls)) + ")\n"
                                  + "with p.open('a') as f: f.write(" + repr(name + "\n") + ")\n"
                                  + "print(" + repr(output) + ", end='')\n")
            executable.chmod(0o755)
        cpu, memory = sdk / "cpu", sdk / "meminfo"
        cpu.write_text("0-1\n")
        memory.write_text("MemFree: 500 kB\nMemTotal: 2621440 kB\nPrivateData: 123 kB\n")
        with patch.dict(os.environ, {"ANDROID_HOME": str(sdk)}):
            commands = D.guest_commands()
        self.assertEqual(commands[0][0], "properties")
        script = commands[0][1][-1]
        self.assertEqual(script.count("getprop"), 1)
        self.assertNotIn("pidof", script)
        self.assertNotIn("/proc/", script)
        outputs = []
        with patch.dict(os.environ, {"PATH": str(sdk) + os.pathsep + os.environ["PATH"]}):
            for _, argv in commands[:4]:
                script = argv[-1].replace("/sys/devices/system/cpu/online", str(cpu)).replace("/proc/meminfo", str(memory))
                result = subprocess.run(["/bin/sh", "-c", script], check=True, capture_output=True, text=True, timeout=5)
                outputs.extend(result.stdout.splitlines())
        self.assertEqual(calls.read_text().splitlines(), ["getprop", "pidof"])
        self.assertEqual(outputs, allowed + ["system_server=579", "online_cpu=0-1", "MemTotal=2621440 kB"])
        self.assertFalse(any("secret" in line or "PrivateData" in line for line in outputs))
        (sdk / "pidof").write_text("#!" + sys.executable + "\nimport sys; sys.exit(1)\n")
        with patch.dict(os.environ, {"PATH": str(sdk) + os.pathsep + os.environ["PATH"]}):
            missing = subprocess.run(["/bin/sh", "-c", commands[1][1][-1]], check=True, capture_output=True, text=True, timeout=5)
        self.assertEqual(missing.stdout.splitlines(), ["system_server="])

    def test_slow_memory_callback_preserves_prior_properties_and_later_callbacks(self):
        sdk = self.directory / "slow-sdk/platform-tools"
        sdk.mkdir(parents=True)
        adb = sdk / "adb"
        adb.write_text("#!" + sys.executable + "\nimport sys,time\n"
                       "assert sys.argv[1:3] == ['-s','emulator-5554']\n"
                       "command = sys.argv[-1]\n"
                       "if 'MemTotal' in command: time.sleep(30)\n"
                       "elif 'getprop' in command: print('[sys.boot_completed]: []')\n"
                       "elif 'pidof' in command: print('system_server=590')\n"
                       "elif 'online_cpu' in command: print('online_cpu=0-1')\n")
        adb.chmod(0o755)
        capture = D.capture
        def owned_capture(argv, cancel, deadline):
            result = capture(argv, cancel, deadline)
            if "boot_progress_start:I" in argv:
                D.write_json(self.directory / "stop.json", {"token": "offline-token"})
            return result
        with patch.dict(os.environ, {"ANDROID_HOME": str(sdk.parent)}), patch.object(D, "capture", side_effect=owned_capture):
            self.assertEqual(D.observe(self.directory, self.control(), inventory=lambda *_: {}, duration=20), 0)
        commands = json.loads((self.directory / "report.json").read_text())["snapshots"][0]["commands"]
        self.assertEqual(list(commands), ["properties", "systemServer", "onlineCpu", "memTotal", "crash", "bootEvents"])
        self.assertEqual(commands["properties"]["stdout"], "[sys.boot_completed]: []\n")
        self.assertEqual(commands["properties"]["outcome"], "completed")
        self.assertEqual(commands["memTotal"]["outcome"], "timeout")
        self.assertTrue(commands["memTotal"]["joined"])
        self.assertLess(commands["memTotal"]["seconds"], 5)
        self.assertEqual(commands["bootEvents"]["outcome"], "completed")

    def test_late_pressure_once_with_no_probes_after_stop(self):
        clock = [0.0]
        calls = []
        def pressure(cancel, deadline):
            self.assertFalse(cancel())
            calls.append(clock[0])
            if clock[0] >= 300:
                D.write_json(self.directory / "stop.json", {"token": "offline-token"})
            return {"fixture": True}
        def inventory(cancel, deadline):
            return {"pressure": pressure(cancel, deadline)}
        with patch.object(D.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(D.time, "sleep", side_effect=lambda seconds: clock.__setitem__(0, clock[0] + 50)):
            self.assertEqual(D.observe(self.directory, self.control(), inventory=inventory, commands=lambda: [], cadence=100, pressure=pressure), 0)
        self.assertEqual(calls, [0, 100, 300])
        report = json.loads((self.directory / "report.json").read_text())
        self.assertEqual(report["status"], "stopped")
        self.assertEqual([s["elapsedSeconds"] for s in report["snapshots"] if "hostPressure" in s], [100, 300])
        self.assertEqual(report["snapshots"][-1]["elapsedSeconds"], 300)

    def test_late_pressure_is_not_repeated_on_later_snapshots(self):
        clock = [0.0]
        calls = []
        def pressure(*_):
            calls.append(clock[0])
            return {}
        with patch.object(D.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(D.time, "sleep", side_effect=lambda seconds: clock.__setitem__(0, clock[0] + min(50, 548 - clock[0]))):
            self.assertEqual(D.observe(self.directory, self.control(), inventory=lambda *_: {}, commands=lambda: [],
                                       duration=550, cadence=100, pressure=pressure), 0)
        self.assertEqual(calls, [100, 300])
        report = json.loads((self.directory / "report.json").read_text())
        self.assertEqual(report["snapshots"][-1]["elapsedSeconds"], 500)

    def test_host_command_failures_are_reported(self):
        self.assertEqual(D.failures({"memory": {"exit": 1, "outcome": "completed"}, "swap": {"exit": -15, "outcome": "timeout"}}), 2)

    def test_completion_token_mismatch_rejects_join(self):
        control = self.control()
        control["pid"] = os.getpid()
        D.write_json(self.directory / "control.json", control)
        D.write_json(self.directory / "done.json", {"token": "wrong-owner", "pid": os.getpid(), "status": "stopped"})
        with self.assertRaisesRegex(RuntimeError, "completion binding mismatch"):
            D.stop(self.directory)
        self.assertFalse((self.directory / "joined.json").exists())

    def test_missing_done_is_failure_not_join_success(self):
        control = self.control()
        control["pid"] = os.getpid()
        D.write_json(self.directory / "control.json", control)
        times = iter([0, 20])
        with patch.object(D.time, "monotonic", side_effect=lambda: next(times)), self.assertRaisesRegex(RuntimeError, "did not acknowledge"):
            D.stop(self.directory)
        self.assertFalse((self.directory / "joined.json").exists())


if __name__ == "__main__":
    unittest.main()
