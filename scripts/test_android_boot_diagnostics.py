"""Offline fixtures only: Python children and fake SDK commands, never a device."""

import ast
import hashlib
import importlib.util
import json
import os
import signal
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
        self.doCleanups()
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

    def history_fixture(self, pid=71):
        records = self.startup_fixture()
        report = records["report"]
        report.update(status="stopped", elapsedSeconds=500, snapshots=[self.history_row(pid, 200)])
        D.write_json(self.directory / "report.json", report)
        return records

    def history_row(self, pid, elapsed):
        return {"elapsedSeconds": elapsed, "commands": {"systemServer": {
            "outcome": "completed", "exit": 0, "joined": True, "truncated": False,
            "droppedBytes": {"stdout": 0, "stderr": 0}, "stdout": "system_server=" + str(pid) + "\n"}}}

    def test_historical_pid_uses_newest_eligible_not_later_failed_read(self):
        records = self.history_fixture()
        report = records["report"]
        failed = self.history_row(99, 450)
        failed["commands"]["systemServer"].update(outcome="timeout", exit=-15)
        truncated = self.history_row(98, 440)
        truncated["commands"]["systemServer"]["truncated"] = True
        report["snapshots"] += [self.history_row(72, 420), failed, truncated]
        selected = D.historical_system_server(report, records["control"])
        self.assertEqual(selected["pid"], 72)
        self.assertEqual(selected["sourceSnapshotElapsedSeconds"], 420)
        self.assertTrue(selected["historical"])
        self.assertEqual(selected["evidenceStatus"], "historical")
        self.assertIn("stale PID", selected["limitations"])

    def test_historical_pid_exact_bounds_row_status_and_elapsed_are_required(self):
        records = self.history_fixture()
        for pid in (1, 2147483647):
            records["report"]["snapshots"] = [self.history_row(pid, 200)]
            self.assertEqual(D.historical_system_server(records["report"], records["control"])["pid"], pid)
        for pid in (0, -1, 2147483648, "01", "+7", "7 8", "7;echo private", "7\n8", " 7", "7.0"):
            with self.subTest(pid=pid):
                records["report"]["snapshots"] = [self.history_row(pid, 200)]
                self.assertNotIn("pid", D.historical_system_server(records["report"], records["control"]))
        for key, value in (("exit", False), ("exit", 1), ("joined", False), ("truncated", True),
                           ("droppedBytes", {"stdout": 0, "stderr": 1}), ("droppedBytes", {"stdout": False, "stderr": 0}),
                           ("stdout", "system_server=7\n\n"), ("outcome", "timeout")):
            row = self.history_row(7, 200)
            row["commands"]["systemServer"][key] = value
            records["report"]["snapshots"] = [row]
            self.assertNotIn("pid", D.historical_system_server(records["report"], records["control"]))
        for elapsed in (-1, 501, float("nan"), float("inf"), True, "200"):
            records["report"]["snapshots"] = [self.history_row(7, elapsed)]
            self.assertNotIn("pid", D.historical_system_server(records["report"], records["control"]))
        for elapsed in (-1, 601, float("nan"), float("inf"), True, "500"):
            records["report"].update(elapsedSeconds=elapsed, snapshots=[self.history_row(7, 200)])
            self.assertNotIn("pid", D.historical_system_server(records["report"], records["control"]))
        for pid in (0, True, "7", "7;echo private", 2147483648):
            with self.assertRaises(ValueError):
                D.historical_system_server_command(pid)

    def test_historical_report_refusal_gates_and_absent_history_unknown_preserve_scope(self):
        for mode in ("missing", "malformed", "binding", "target"):
            records = self.history_fixture()
            path = self.directory / "report.json"
            if mode == "missing": path.unlink()
            elif mode == "malformed": path.write_text("{bad")
            else:
                records["report"][mode] = {}
                D.write_json(path, records["report"])
            with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}), patch.object(D, "capture", side_effect=AssertionError("unbound probe")):
                with self.assertRaises((RuntimeError, FileNotFoundError, ValueError)):
                    D.startup(self.directory, 17)
        self.startup_fixture()  # Valid bound report, but no eligible history.
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}), patch.object(D, "capture", return_value={
                "outcome": "completed", "exit": 0, "joined": True, "truncated": False,
                "stdout": "valid", "stderr": "", "droppedBytes": {"stdout": 0, "stderr": 0}}) as capture:
            self.assertEqual(D.startup(self.directory, 17, commands=lambda: [("existing", ["offline"])]), 0)
            capture.assert_called_once()
        report = json.loads((self.directory / "startup.json").read_text())
        self.assertEqual(report["gradleExit"], 17)
        self.assertEqual(report["commands"]["historicalSystemServerLogs"]["evidenceStatus"], "unknown")
        self.assertEqual(report["commands"]["existing"]["evidenceStatus"], "observed")

    def test_historical_callback_is_early_exact_serial_and_other_commands_stay_ordered(self):
        self.history_fixture(81)
        seen = []
        def fake_capture(argv, *_args, **_kwargs):
            seen.append(argv)
            return {"outcome": "completed", "exit": 0, "joined": True, "truncated": False,
                    "stdout": "marker", "stderr": "", "droppedBytes": {"stdout": 0, "stderr": 0}}
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000", "ANDROID_HOME": "/offline/sdk", "ANDROID_SERIAL": "ambient"}), patch.object(D, "capture", side_effect=fake_capture):
            original = D.startup_commands()
            self.assertEqual(D.startup(self.directory, 17), 0)
        index = next(i for i, row in enumerate(original) if row[0] == "crash")
        self.assertEqual(seen[:index], [argv for _, argv in original[:index]])
        self.assertEqual(original[index-1][0], "firstFrameworkMarker")
        self.assertEqual(seen[index][:4], ["/offline/sdk/platform-tools/adb", "-s", "emulator-5554", "shell"])
        self.assertIn("logcat --pid=81 ", seen[index][-1])
        self.assertNotIn("DEBUG", seen[index][-1])
        self.assertEqual(seen[index+1:], [argv for _, argv in original[index:]])

    def test_historical_guest_filter_drains_huge_output_and_rejects_private_markers(self):
        self.history_fixture(91)
        sdk = self.directory / "sdk"
        (sdk / "platform-tools").mkdir(parents=True)
        adb = sdk / "platform-tools/adb"
        adb.write_text("#!" + sys.executable + "\nimport os,sys\nassert sys.argv[1:4]==['-s','emulator-5554','shell']\nos.execv('/bin/sh',['sh','-c',sys.argv[4]])\n")
        adb.chmod(0o755)
        logcat = sdk / "logcat"
        huge = sdk / "huge"
        logcat.write_text("#!" + sys.executable + "\nimport sys\nfrom pathlib import Path\n"
                          "assert sys.argv[1:]==['--pid=91','-b','system','-b','main','-b','crash','-d','-v','brief']\n"
                          "records=['W/Watchdog( 91): *** WATCHDOG KILLING SYSTEM PROCESS: blocked',"
                          "'E/AndroidRuntime( 91): FATAL EXCEPTION IN SYSTEM PROCESS: main',"
                          "'E/AndroidRuntime( 91): Process: system_server, PID: 91',"
                          "'E/AndroidRuntime: Process: system_server.private',"
                          "'W/WatchdogPrivate: *** WATCHDOG KILLING SYSTEM PROCESS: private',"
                          "'E/Private: FATAL EXCEPTION IN SYSTEM PROCESS private',"
                          "'F/DEBUG   ( 92): pid: 91, tid: 91, name: main  >>> system_server <<<',"
                          "'E/AndroidRuntime: private application crash']\n"
                          "if Path(" + repr(str(huge)) + ").exists(): records=[records[0]]*10000\n"
                          "print('\\n'.join(records))\n")
        logcat.chmod(0o755)
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000", "ANDROID_HOME": str(sdk),
                                     "PATH": str(sdk) + os.pathsep + os.environ["PATH"]}):
            argv = D.historical_system_server_command(91)
            filtered = D.capture(argv)
            self.assertEqual(len(filtered["stdout"].splitlines()), 3)
            self.assertNotIn("private", filtered["stdout"])
            self.assertNotIn("DEBUG", filtered["stdout"])
            huge.touch()
            self.assertEqual(D.startup(self.directory, 17, commands=lambda: []), 0)
        report = json.loads((self.directory / "startup.json").read_text())
        result = report["commands"]["historicalSystemServerLogs"]
        self.assertTrue(result["joined"])
        self.assertTrue(result["truncated"])
        self.assertGreater(result["droppedBytes"]["stdout"], 500000)
        self.assertEqual(len(result["stdout"].encode()), 256)
        self.assertEqual(result["evidenceStatus"], "unknown")
        self.assertLessEqual((self.directory / "startup.json").stat().st_size, 6000)
        self.assertLessEqual(sum(p.stat().st_size for p in self.directory.glob("*.json")), D.MAX_REPORT)

    def test_historical_callback_timeout_joins_and_no_cleanup_budget_never_spawns(self):
        self.history_fixture()
        original = D.capture
        def slow_capture(argv, cancel, deadline, limit):
            self.assertIn("--pid=71", argv[-1])
            return original([sys.executable, "-c", "import os,time; print(os.getpid(),flush=True); time.sleep(30)"], cancel, deadline, limit)
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000", "ANDROID_HOME": "/offline/sdk"}), patch.object(D, "capture", side_effect=slow_capture):
            self.assertEqual(D.startup(self.directory, 17, duration=1.2, commands=lambda: []), 0)
        report = json.loads((self.directory / "startup.json").read_text())
        result = report["commands"]["historicalSystemServerLogs"]
        self.assertEqual(result["outcome"], "timeout")
        self.assertTrue(result["joined"])
        self.assertEqual(result["evidenceStatus"], "unknown")
        with self.assertRaises(ProcessLookupError): os.kill(int(result["stdout"].strip()), 0)
        (self.directory / "startup.json").unlink()
        with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000", "ANDROID_HOME": "/offline/sdk"}), patch.object(D.subprocess, "Popen", side_effect=AssertionError("no cleanup budget")):
            self.assertEqual(D.startup(self.directory, 17, duration=.5, commands=lambda: []), 0)
        report = json.loads((self.directory / "startup.json").read_text())
        self.assertEqual(report["commands"]["historicalSystemServerLogs"]["evidenceStatus"], "unknown")

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

    def test_failure_dump_retains_old_marker_and_drains_filtered_oversize_as_unknown(self):
        self.startup_fixture()
        fixture = self.directory / "retention-bin"
        fixture.mkdir()
        mode = fixture / "huge"
        logcat = fixture / "logcat"
        logcat.write_text("#!" + sys.executable + "\nimport sys\nfrom pathlib import Path\n"
                          "args=sys.argv[1:]\n"
                          "expected=['-b','crash','-b','main','-b','system','-d','-v','brief']\n"
                          "assert args == expected or args == expected[:7]+['-t','128']+expected[7:]\n"
                          "marker='W/Watchdog( 524): *** WATCHDOG KILLING SYSTEM PROCESS: blocked'\n"
                          "records=[marker]+['E/PrivateApp: private record']*200\n"
                          "if Path(" + repr(str(mode)) + ").exists(): records=[marker]*10000\n"
                          "if '-t' in args: records=records[-128:]\n"
                          "print('\\n'.join(records))\n")
        logcat.chmod(0o755)
        with patch.dict(os.environ, {"ANDROID_HOME": "/offline/sdk", "PATH": str(fixture) + os.pathsep + os.environ["PATH"],
                                     "MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}):
            script = dict(D.startup_commands())["crash"][-1]
            baseline = script.replace(" -d -v", " -d -t 128 -v", 1)
            old = D.capture(["/bin/sh", "-c", baseline], limit=256)
            self.assertEqual(old["exit"], 1)
            self.assertFalse(old["stdout"])
            current = D.capture(["/bin/sh", "-c", script], limit=256)
            self.assertEqual(current["exit"], 0)
            self.assertIn("*** WATCHDOG", current["stdout"])
            self.assertNotIn("private", current["stdout"])
            mode.touch()
            self.assertEqual(D.startup(self.directory, 17, commands=lambda: [("crash", ["/bin/sh", "-c", script])]), 0)
        report = json.loads((self.directory / "startup.json").read_text())
        result = report["commands"]["crash"]
        self.assertEqual(result["exit"], 0)
        self.assertTrue(result["joined"])
        self.assertTrue(result["truncated"])
        self.assertEqual(len(result["stdout"].encode()), 256)
        self.assertGreater(result["droppedBytes"]["stdout"], 500000)
        self.assertEqual(result["evidenceStatus"], "unknown")
        self.assertEqual(report["gradleExit"], 17)
        self.assertFalse(report["qualification"])

    def test_health_workflow_is_mac_only_with_path_import_before_bindings_and_original_exit(self):
        import re
        import shlex
        text = (Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml").read_text()
        linux, mac = text.split("  smoke-ios:\n", 1)
        self.assertNotIn("VERITY_PACKAGED_ANDROID_HEALTH", linux)
        hook = next(line for line in mac.splitlines() if "pre-emulator-launch-script:" in line)
        self.assertLess(hook.index("verify-emulator"), hook.index(" prelaunch "))
        self.assertLess(hook.index(" prelaunch "), hook.index(" start "))
        block = re.search(r"script: >-\n(.*?)(?:\n\n)", mac, re.S)[1]
        command = shlex.split(" ".join(line.strip() for line in block.splitlines()))
        self.assertEqual(command[:2], ["python3", "-c"])
        tree = ast.parse(command[2])
        imported = next(i for i, n in enumerate(tree.body) if isinstance(n, ast.ImportFrom) and n.module == "pathlib")
        selected = [i for i,n in enumerate(tree.body) if isinstance(n,ast.Assign) and "VERITY_PACKAGED_ANDROID_HEALTH" in ast.dump(n)]
        self.assertEqual(len(selected),2)
        self.assertTrue(all(i > imported for i in selected))
        native = next(i for i,n in enumerate(tree.body) if isinstance(n,ast.Assign) and "./gradlew" in ast.dump(n))
        self.assertTrue(all(i < native for i in selected))
        self.assertIn("SystemExit(result)", ast.unparse(tree.body[-1]))
        self.assertIn("value='stop'", ast.dump(tree.body[2]))

    def health_fixture(self):
        if self.directory.name != "bootstrap":
            self.directory = self.directory / "bootstrap"
            self.directory.mkdir()
        self.startup_fixture()
        sdk = self.directory / "health-sdk"
        (sdk / "emulator").mkdir(parents=True)
        (sdk / "emulator/source.properties").write_text("Pkg.Revision=37.2.12\nPkg.BuildId=16428233\n")
        with patch.dict(os.environ, {"ANDROID_HOME": str(sdk)}):
            D.verify_emulator(self.directory.parent / "android-emulator-identity.json")
        return sdk

    def test_emulator_properties_drift_and_ambiguity_refuse_before_launch(self):
        sdk = self.directory / "identity-sdk"
        (sdk / "emulator").mkdir(parents=True)
        for text in ("Pkg.Revision=37.1.11\nPkg.BuildId=16428233\n", "Pkg.Revision=37.2.12\nPkg.BuildId=other\n",
                     "Pkg.Revision=37.2.12\nPkg.Revision=37.2.12\nPkg.BuildId=16428233\n", "x"*4097):
            (sdk / "emulator/source.properties").write_text(text)
            with self.subTest(text=text[:40]), patch.dict(os.environ, {"ANDROID_HOME": str(sdk)}), patch.object(D, "capture", side_effect=AssertionError("executable probe")):
                with self.assertRaises(RuntimeError):
                    D.verify_emulator(self.directory / "identity.json")
            self.assertFalse((self.directory / "identity.json").exists())

    def test_health_host_filter_discards_private_and_incomplete_or_denied_is_unknown(self):
        state = "system_server=123\nlogd=124\n[init.svc.zygote]: [running]\n[init.svc.logd]: [running]\n[sys.boot_completed]: [1]\n[sys.system_server.start_count]: [1]\n[sys.system_server.start_elapsed]: [120]\n[sys.system_server.start_uptime]: [120]\n"
        base = {"outcome": "completed", "exit": 0, "joined": True, "truncated": False,
                "droppedBytes": {"stdout": 0, "stderr": 0}, "stderr": "", "stdout": state}
        observed = D.safe_health_result("state", {**base, "stdout": state+"[init.svc.logd.private]: [private]\nprivate application\n"})
        self.assertEqual(observed["stdout"], state.rstrip())
        self.assertEqual(observed["evidenceStatus"], "selected-fragment")
        self.assertNotIn("private", json.dumps(observed))
        for change in ({"stdout": "Service package: found\nsystem_server=\n"}, {"stderr": "private permission denied"},
                       {"outcome": "timeout"}, {"exit": 1}, {"joined": False}, {"truncated": True},
                       {"droppedBytes": {"stdout": 1, "stderr": 0}}):
            result = D.safe_health_result("state", {**base, **change})
            self.assertEqual(result["evidenceStatus"], "unknown")
            self.assertNotIn("private", json.dumps(result))
        kernel = D.safe_health_result("kernel", {**base, "stdout": "[ 123.456] Killed process 123 (system_server) private surrounding text\nKilled process 124 (private_app)\nprivate Killed process 123 (system_server)\nKilled process 123 (system_server_private)\n"})
        self.assertEqual(kernel["stdout"], "Killed process 123 (system_server)")
        self.assertNotIn("private", json.dumps(kernel))
        huge = D.safe_health_result("kernel", {**base, "stdout": "Killed process 123 (system_server)\n"*1000})
        self.assertEqual(huge["evidenceStatus"], "unknown")
        self.assertFalse(huge["stdout"])

    def test_health_guest_filters_and_kernel_reader_failure_are_not_masked(self):
        fixture = self.directory / "health-bin"
        fixture.mkdir()
        outputs = {"service": "Service package: found\n", "pidof": "123\n", "getprop": "[init.svc.logd]: [running]\n[init.svc.logd.private]: [private]\n",
                   "dmesg": "[ 1.234] Killed process 123 (system_server) total-vm:123kB\nKilled process 123 (private_app)\n"}
        for name, output in outputs.items():
            path = fixture / name
            path.write_text("#!"+sys.executable+"\nimport os,sys\nprint("+repr(output)+",end='')\nif os.environ.get('DENIED') and sys.argv[0].endswith('dmesg'):\n print('private denied path',file=sys.stderr);sys.exit(1)\n")
            path.chmod(0o755)
        with patch.dict(os.environ, {"PATH": str(fixture)+os.pathsep+os.environ["PATH"], "ANDROID_HOME": "/offline/sdk"}):
            self.assertEqual([n for n,_ in D.health_commands("before-factory")], ["state", "package"])
            commands = dict(D.health_commands("after-failure"))
            state = D.capture(["/bin/sh", "-c", commands["state"][-1]])
            self.assertNotIn("private", state["stdout"])
            self.assertIn("logd=123", state["stdout"])
            kernel = D.capture(["/bin/sh", "-c", commands["kernel"][-1]])
            self.assertEqual(kernel["exit"], 0)
            self.assertNotIn("private_app", kernel["stdout"])
            with patch.dict(os.environ, {"DENIED": "1"}):
                denied = D.capture(["/bin/sh", "-c", commands["kernel"][-1]])
            self.assertNotEqual(denied["exit"], 0)
            retained = D.safe_health_result("kernel", denied)
            self.assertEqual(retained["evidenceStatus"], "unknown")
            self.assertFalse(retained["stdout"])
            self.assertNotIn("private", json.dumps(retained))

    def test_health_binding_refusal_and_both_variant_isolated_phase_receipts(self):
        sdk = self.health_fixture()
        result = {"outcome": "completed", "exit": 0, "joined": True, "truncated": False,
                  "droppedBytes": {"stdout": 0, "stderr": 0}, "stdout": "Service package: not found\n", "stderr": ""}
        with patch.dict(os.environ, {"ANDROID_HOME": str(sdk)}), patch.object(D, "capture", return_value=result):
            for variant in ("macos-aarch64", "universal"):
                report = D.health(self.directory, variant, "before-factory")
                self.assertEqual(list(report["commands"]), ["state", "package"])
                self.assertEqual(report["commands"]["state"]["evidenceStatus"], "unknown")
                self.assertFalse(report["qualification"])
                self.assertLessEqual(sum(len(r["stdout"].encode()) for r in report["commands"].values()), 1024)
                self.assertLessEqual((self.directory.parent/"android-health"/("health-"+variant+"-before-factory.json")).stat().st_size,4096)
        control = json.loads((self.directory/"control.json").read_text())
        control["binding"]["GITHUB_SHA"] = "stale"
        D.write_json(self.directory/"control.json", control)
        with patch.dict(os.environ, {"ANDROID_HOME": str(sdk)}), patch.object(D, "capture", side_effect=AssertionError("unbound probe")):
            with self.assertRaisesRegex(RuntimeError,"binding mismatch"):
                D.health(self.directory,"universal","after-failure")

    def test_health_deadline_and_signal_join_owned_callback_and_skip_kernel(self):
        for cancel in (False, True):
            self.health_fixture()
            seen = []
            original = D.capture
            def slow(argv, stop, deadline, limit):
                seen.append(argv)
                if cancel: os.kill(os.getpid(), signal.SIGTERM)
                result = original([sys.executable,"-c","import time;time.sleep(30)"],stop,deadline,limit)
                if not cancel: time.sleep(max(0,deadline-time.monotonic()))
                return result
            with patch.dict(os.environ,{"ANDROID_HOME":"/offline/sdk"}),patch.object(D,"capture",side_effect=slow):
                report = D.health(self.directory,"universal","after-failure",duration=1.2)
            self.assertEqual(len(seen),1)
            self.assertTrue(report["commands"]["state"]["joined"])
            self.assertEqual(report["commands"]["state"]["evidenceStatus"],"unknown")
            self.assertLess(report["elapsedSeconds"],1.2)
            (self.directory.parent/"android-emulator-identity.json").unlink()
            (self.directory.parent/"android-health/health-universal-after-failure.json").unlink()
            import shutil
            shutil.rmtree(self.directory/"health-sdk")


    def test_watchdog_event_guest_and_host_projection_reader_status_and_private_decoys(self):
        fixture = self.directory / "watchdog-bin"
        fixture.mkdir()
        logcat = fixture / "logcat"
        logcat.write_text("#!"+sys.executable+"\nimport os,sys\nfrom pathlib import Path\nPath(os.environ['ARGS']).write_text(' '.join(sys.argv[1:]))\nprint(os.environ['EVENTS'],end='')\nif os.environ.get('DENIED'):\n print('private permission path',file=sys.stderr);sys.exit(1)\n")
        logcat.chmod(0o755)
        events = "I/watchdog( 547): private subject and stack\nI/watchdog_private( 9): secret\nW/watchdog( 9): secret\nI/other( 9): secret\nI/watchdog(0): bad\n"
        with patch.dict(os.environ, {"PATH": str(fixture)+os.pathsep+os.environ["PATH"], "ANDROID_HOME": "/offline/sdk", "EVENTS": events, "ARGS": str(fixture/"args")}):
            script = dict(D.health_commands("after-failure"))["watchdog"][-1]
            result = D.capture(["/bin/sh", "-c", script])
            self.assertEqual(result["stdout"], "watchdog_pid=547\n")
            self.assertEqual((fixture/"args").read_text(), "-b events -d -v brief -s watchdog:I -m 1")
            safe = D.safe_health_result("watchdog", result)
            self.assertEqual(safe["evidenceStatus"], "selected-fragment")
            self.assertNotIn("private", json.dumps(safe))
            self.assertIn("historical", safe["limitations"])
            with patch.dict(os.environ, {"DENIED": "1"}):
                denied = D.capture(["/bin/sh", "-c", script])
            self.assertNotEqual(denied["exit"], 0)
            self.assertEqual(D.safe_health_result("watchdog", denied)["stdout"], "")
        base = {**result, "stdout": "watchdog_pid=547\n"}
        for change in ({"stdout":""}, {"stdout":"watchdog_pid=547 private\n"}, {"stdout":"watchdog_pid=2147483648\n"},
                       {"stdout":"watchdog_pid=547\nwatchdog_pid=548\n"}, {"stderr":"unknown option/private"},
                       {"outcome":"timeout"}, {"outcome":"deadline"}, {"outcome":"stopped"}, {"joined":False},
                       {"truncated":True}, {"droppedBytes":{"stdout":1,"stderr":0}}, {"exit":1}):
            with self.subTest(change=change):
                safe = D.safe_health_result("watchdog", {**base, **change})
                self.assertEqual(safe["evidenceStatus"], "unknown")
                self.assertEqual(safe["stdout"], "")
                self.assertNotIn("private", json.dumps(safe))

    def test_health_state_survives_independent_stalled_package_owned_capture(self):
        sdk = self.health_fixture()
        fixture = self.directory / "state-bin"
        fixture.mkdir()
        state = "system_server=123\nlogd=124\n[init.svc.zygote]: [running]\n[init.svc.logd]: [running]\n[sys.boot_completed]: [1]\n[sys.system_server.start_count]: [2]\n[sys.system_server.start_elapsed]: [120]\n[sys.system_server.start_uptime]: [120]\n"
        for name, body in {"pidof":"print('123' if sys.argv[1]=='system_server' else '124')",
                           "getprop":"print("+repr(state.split('logd=124\n')[1])+",end='')",
                           "service":"import time;time.sleep(30)"}.items():
            path=fixture/name;path.write_text("#!"+sys.executable+"\nimport sys\n"+body+"\n");path.chmod(0o755)
        capture = D.capture
        def offline(argv, cancel, deadline, limit):
            self.assertEqual(argv[1:4], ["-s",D.SERIAL,"shell"])
            return capture(["/bin/sh","-c", "exec service check package" if argv[-1]=="service check package" else argv[-1]],cancel,deadline,limit)
        with patch.dict(os.environ, {"ANDROID_HOME":str(sdk),"PATH":str(fixture)+os.pathsep+os.environ["PATH"]}), patch.object(D,"capture",side_effect=offline):
            report=D.health(self.directory,"universal","before-factory",duration=2.2)
        self.assertEqual(report["commands"]["state"]["stdout"],state.rstrip())
        self.assertEqual(report["commands"]["state"]["evidenceStatus"],"selected-fragment")
        self.assertEqual(report["commands"]["package"]["evidenceStatus"],"unknown")
        self.assertTrue(report["commands"]["package"]["joined"])
        self.assertLess(report["elapsedSeconds"],2.2)
        self.assertLessEqual(sum(len(v["stdout"].encode()) for v in report["commands"].values()),1024)

    def test_health_per_command_deadlines_order_and_metadata_fit(self):
        sdk=self.health_fixture()
        calls=[]
        def capture(argv,cancel,deadline,limit):
            calls.append((argv,deadline-time.monotonic(),limit))
            return {"outcome":"completed","exit":0,"joined":True,"truncated":False,"droppedBytes":{"stdout":0,"stderr":0},"stdout":"","stderr":""}
        with patch.dict(os.environ,{"ANDROID_HOME":str(sdk)}),patch.object(D,"capture",side_effect=capture):
            report=D.health(self.directory,"macos-aarch64","after-failure")
        self.assertEqual(list(report["commands"]),["state","package","watchdog","kernel"])
        for (_argv,budget,limit),expected in zip(calls,[4,2,3,2]):
            self.assertGreater(budget,expected-.1);self.assertLessEqual(budget,expected);self.assertEqual(limit,1024)
        self.assertLessEqual((self.directory.parent/"android-health/health-macos-aarch64-after-failure.json").stat().st_size,4096)
        self.assertNotIn('service check package',calls[0][0][-1])
        self.assertFalse(report['qualification'])

    def first_marker_fixture(self):
        fixture = self.directory / "first-marker-bin"
        fixture.mkdir(exist_ok=True)
        logcat = fixture / "logcat"
        logcat.write_text("#!" + sys.executable + "\n" + r'''
import json, os, re, subprocess, sys, time
from pathlib import Path
Path(os.environ['OFFLINE_LOG_PID']).write_text(json.dumps({'pid':os.getpid(),'start':subprocess.check_output(['/bin/ps','-p',str(os.getpid()),'-o','lstart='],text=True).strip()}))
parent = os.getppid()
args = sys.argv[1:]
rows = json.loads(os.environ['OFFLINE_LOG_ROWS'])
if os.environ.get('OFFLINE_UNSUPPORTED'):
    print('unsupported option -m', file=sys.stderr)
    print('W/Watchdog( 7): *** WATCHDOG KILLING SYSTEM PROCESS: blocked')
    raise SystemExit(2)
regex = re.compile(args[args.index('-e')+1]) if '-e' in args else None
threshold = {'AndroidRuntime': 'E', 'Watchdog': 'W', 'DEBUG': 'F'}
count = 0
for priority, tag, message in rows:
    if regex and (tag not in threshold or 'VDIWEF'.index(priority) < 'VDIWEF'.index(threshold[tag]) or not regex.search(message)):
        continue
    print(priority+'/'+tag+'( 7): '+message, flush=True)
    count += 1
    if '-m' in args and count >= int(args[args.index('-m')+1]):
        raise SystemExit(0)
if os.environ.get('OFFLINE_REMAINDER'):
    end = time.monotonic()+30
    while os.getppid() == parent and time.monotonic() < end:
        time.sleep(.01)
''')
        logcat.chmod(0o755)
        grep = fixture / "grep"
        grep.write_text("#!" + sys.executable + "\nimport json,os,subprocess,sys\nfrom pathlib import Path\nPath(os.environ['OFFLINE_GREP_PID']).write_text(json.dumps({'pid':os.getpid(),'start':subprocess.check_output(['/bin/ps','-p',str(os.getpid()),'-o','lstart='],text=True).strip()}))\nos.execv('/usr/bin/grep',['grep']+sys.argv[1:])\n")
        grep.chmod(0o755)
        env = {"OFFLINE_LOG_PID": str(fixture / "log.pid"), "OFFLINE_GREP_PID": str(fixture / "grep.pid"), "PATH": str(fixture) + os.pathsep + os.environ["PATH"],
                "MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000", "ANDROID_HOME": "/offline/sdk"}
        self.addCleanup(self.assert_fixture_readers_exited, env)
        return env

    def assert_fixture_readers_exited(self, env):
        # Exact fixture-written PID/start identities; no name-based selection.
        for key in ("OFFLINE_LOG_PID", "OFFLINE_GREP_PID"):
            path = Path(env[key])
            if not path.exists():
                continue
            identity = json.loads(path.read_text())
            pid = identity["pid"]
            start = subprocess.run(["/bin/ps", "-p", str(pid), "-o", "lstart="], capture_output=True, text=True, timeout=.5)
            if not start.stdout.strip():
                continue
            self.assertEqual(start.stdout.strip(), identity["start"], "Owned fixture PID was reused")
            os.kill(pid, signal.SIGTERM)
            end = time.monotonic() + 1
            while True:
                state = subprocess.run(["/bin/ps", "-p", str(pid), "-o", "stat="], capture_output=True, text=True, timeout=.5)
                if not state.stdout.strip() or state.stdout.strip().startswith("Z"):
                    break
                if time.monotonic() >= end:
                    current = subprocess.run(["/bin/ps", "-p", str(pid), "-o", "lstart="], capture_output=True, text=True, timeout=.5)
                    self.assertEqual(current.stdout.strip(), identity["start"])
                    os.kill(pid, signal.SIGKILL)
                    state = subprocess.run(["/bin/ps", "-p", str(pid), "-o", "stat="], capture_output=True, text=True, timeout=.5)
                    self.assertTrue(not state.stdout.strip() or state.stdout.strip().startswith("Z"), "Owned fixture child did not exit")
                    break
                time.sleep(.01)
            path.unlink()

    def test_first_marker_raw_message_stops_before_blocked_remainder_and_filters_privacy(self):
        env = self.first_marker_fixture()
        rows = [["E", "Private", "FATAL EXCEPTION IN SYSTEM PROCESS private"],
                ["W", "WatchdogPrivate", "*** WATCHDOG KILLING SYSTEM PROCESS: private"],
                ["I", "Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: private"],
                ["F", "DEBUG", "pid: 7, tid: 8, name: Binder Pool  >>> system_server.private <<<"],
                ["W", "Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: blocked"],
                ["E", "AndroidRuntime", "Process: system_server, PID: 7"]]
        with patch.dict(os.environ, {**env, "OFFLINE_LOG_ROWS": json.dumps(rows), "OFFLINE_REMAINDER": "1"}):
            scripts = dict(D.startup_commands())
            old = D.capture(["/bin/sh", "-c", scripts["crash"][-1]], deadline=time.monotonic()+1.2, limit=256)
            self.assertEqual(old["outcome"], "timeout")
            self.assertTrue(old["joined"])
            self.assert_fixture_readers_exited(env)
            selected = D.capture(["/bin/sh", "-c", scripts["firstFrameworkMarker"][-1]], limit=256)
            self.assertEqual(selected["exit"], 0)
            self.assertTrue(selected["joined"])
            self.assertEqual(selected["stdout"].splitlines(), ["W/Watchdog( 7): *** WATCHDOG KILLING SYSTEM PROCESS: blocked"])
            wrong = scripts["firstFrameworkMarker"][-1].replace(D.FIRST_FRAMEWORK_RAW, "^W/Watchdog")
            raw = D.capture(["/bin/sh", "-c", wrong], deadline=time.monotonic()+1.2, limit=256)
            self.assertEqual(raw["outcome"], "timeout")
            self.assertFalse(raw["stdout"])
            self.assertTrue(raw["joined"])
            self.assert_fixture_readers_exited(env)
        for priority, tag, message in (("E", "AndroidRuntime", "FATAL EXCEPTION IN SYSTEM PROCESS: main"),
                                       ("E", "AndroidRuntime", "Process: system_server, PID: 7"),
                                       ("F", "DEBUG", "pid: 7, tid: 8, name: Binder Pool  >>> system_server <<<")):
            with self.subTest(tag=tag, message=message), patch.dict(os.environ, {**env, "OFFLINE_LOG_ROWS": json.dumps([[priority, tag, message]])}):
                result = D.capture(["/bin/sh", "-c", D.FIRST_FRAMEWORK_SCRIPT], limit=256)
                self.assertEqual(result["exit"], 0)
                self.assertIn(message, result["stdout"])

    def test_first_marker_unsupported_partial_output_absent_and_oversize_are_unknown(self):
        env = self.first_marker_fixture()
        for extra, rows in (({"OFFLINE_UNSUPPORTED": "1"}, []), ({}, []),
                            ({}, [["W", "Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: " + "x"*10000]])):
            with self.subTest(extra=extra, rows=len(rows)):
                self.startup_fixture()
                with patch.dict(os.environ, {**env, **extra, "OFFLINE_LOG_ROWS": json.dumps(rows)}):
                    D.startup(self.directory, 17, commands=lambda: [("firstFrameworkMarker", ["/bin/sh", "-c", D.FIRST_FRAMEWORK_SCRIPT])])
                report = json.loads((self.directory / "startup.json").read_text())
                result = report["commands"]["firstFrameworkMarker"]
                self.assertEqual(result["evidenceStatus"], "unknown")
                self.assertTrue(result["joined"])
                self.assertFalse(result["qualification"])
                self.assertLessEqual((self.directory / "startup.json").stat().st_size, 6000)
                (self.directory / "startup.json").unlink()

    def test_first_marker_clean_fragment_and_dirty_capture_statuses(self):
        base = {"exit": 0, "outcome": "completed", "stdout": "W/Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: blocked\n",
                "stderr": "", "joined": True, "truncated": False, "droppedBytes": {"stdout": 0, "stderr": 0}}
        for changes in ({}, {"stderr": "unsupported"}, {"joined": False}, {"truncated": True},
                        {"droppedBytes": {"stdout": 0, "stderr": 1}}, {"exit": 2},
                        {"outcome": "timeout"}, {"outcome": "stopped"}):
            with self.subTest(changes=changes):
                self.startup_fixture()
                with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}), patch.object(D, "capture", return_value={**base, **changes}):
                    D.startup(self.directory, 17, commands=lambda: [("firstFrameworkMarker", ["offline"])])
                result = json.loads((self.directory / "startup.json").read_text())["commands"]["firstFrameworkMarker"]
                self.assertEqual(result["evidenceStatus"], "unknown" if changes else "selected-fragment")
                self.assertFalse(result["qualification"])
                self.assertIn("not complete/current crash", result["limitations"])
                (self.directory / "startup.json").unlink()

    def test_first_marker_deadline_and_cancel_do_not_run_later_readers(self):
        for cancel in (False, True):
            self.startup_fixture()
            calls = []
            original = D.capture
            def capture(argv, stop, deadline, limit):
                calls.append(argv)
                if cancel:
                    os.kill(os.getpid(), D.signal.SIGTERM)
                result = original([sys.executable, "-c", "import time; time.sleep(30)"], stop, deadline, limit)
                if not cancel:
                    time.sleep(max(0, deadline-time.monotonic()))
                return result
            with patch.dict(os.environ, {"MAESTRO_DRIVER_STARTUP_TIMEOUT": "120000"}), patch.object(D, "capture", side_effect=capture):
                D.startup(self.directory, 17, duration=1.2, commands=lambda: [("firstFrameworkMarker", ["first"]), ("crash", ["later"])])
            report = json.loads((self.directory / "startup.json").read_text())
            self.assertEqual(calls, [["first"]])
            self.assertTrue(report["commands"]["firstFrameworkMarker"]["joined"])
            self.assertEqual(report["commands"]["firstFrameworkMarker"]["evidenceStatus"], "unknown")
            (self.directory / "startup.json").unlink()

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
                        self.assertNotIn(" -t ", scripts[name][-1])
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

    def test_explicit_early_ownership_runs_before_guest_callbacks_once_at_existing_cadence(self):
        import android_ios_handoff as H
        control = self.control()
        control["ownership"] = {"directory": str(self.directory), "nonce": "bound", "sourceSha256": "owned"}
        events = []
        def early(directory, observer, expected, state, deadline, cancel):
            events.append("early-proof")
            self.assertFalse(cancel())
            self.assertLessEqual(deadline - time.monotonic(), .2)
            state.update(attempted=True, status="complete")
        def command(*args, **kwargs):
            events.append("guest")
            return {"outcome": "completed", "exit": 0, "seconds": 0, "stdout": "", "stderr": "", "truncated": False,
                    "droppedBytes": {"stdout": 0, "stderr": 0}, "joined": True}
        with patch.object(H, "ownership_binding", return_value=control["ownership"]), patch.object(H, "early_ownership", side_effect=early) as proof, patch.object(D, "capture", side_effect=command):
            self.assertEqual(D.observe(self.directory, control, inventory=lambda *_: {}, commands=lambda: [("guest", ["fixture"])], duration=.2, cadence=.03, pressure=lambda *_: {}), 0)
        self.assertEqual(proof.call_count, 1)
        self.assertEqual(events[:2], ["early-proof", "guest"])
        self.assertEqual(json.loads((self.directory / "report.json").read_text())["ownership"], {"attempted": True, "status": "complete"})

    def test_early_stop_during_proof_prevents_guest_probes(self):
        import android_ios_handoff as H
        control = self.control()
        control["ownership"] = {"directory": str(self.directory), "nonce": "bound", "sourceSha256": "owned"}
        def early(directory, observer, expected, state, deadline, cancel):
            D.write_json(self.directory / "stop.json", {"token": control["token"]})
            self.assertTrue(cancel())
        with patch.object(H, "ownership_binding", return_value=control["ownership"]), patch.object(H, "early_ownership", side_effect=early), patch.object(D, "capture") as capture:
            self.assertEqual(D.observe(self.directory, control, inventory=lambda *_: {}, commands=lambda: [("forbidden", ["fixture"])]), 0)
        capture.assert_not_called()
        self.assertEqual(json.loads((self.directory / "report.json").read_text())["status"], "stopped")

    def test_early_signal_cancellation_restores_handler_and_never_probes_after_stop(self):
        import android_ios_handoff as H
        control = self.control()
        control["ownership"] = {"directory": str(self.directory), "nonce": "bound", "sourceSha256": "owned"}
        original = signal.getsignal(signal.SIGTERM)
        def early(directory, observer, expected, state, deadline, cancel):
            signal.raise_signal(signal.SIGTERM)
            H.checkpoint(deadline, cancel)
        with patch.object(H, "ownership_binding", return_value=control["ownership"]), patch.object(H, "early_ownership", side_effect=early), patch.object(D, "capture") as capture:
            self.assertEqual(D.observe(self.directory, control, inventory=lambda *_: {}, commands=lambda: [("forbidden", ["fixture"])]), 0)
        capture.assert_not_called()
        self.assertEqual(signal.getsignal(signal.SIGTERM), original)
        self.assertEqual(json.loads((self.directory / "report.json").read_text())["status"], "stopped")

    def test_early_binding_mismatch_refuses_before_guest_commands(self):
        import android_ios_handoff as H
        control = self.control()
        control["ownership"] = {"directory": str(self.directory), "nonce": "bound", "sourceSha256": "owned"}
        with patch.object(H, "ownership_binding", return_value={"stale": True}), patch.object(D, "capture") as capture:
            self.assertEqual(D.observe(self.directory, control, inventory=lambda *_: {}, commands=lambda: [("forbidden", ["fixture"])]), 1)
        capture.assert_not_called()

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
        self.assertIn("-d", commands[4][1][-1])
        self.assertIn("-t", commands[5][1])

    def test_observer_framework_callback_executes_exact_multi_buffer_filter(self):
        with patch.dict(os.environ, {"ANDROID_HOME": "/offline/sdk", "ANDROID_SERIAL": "ambient-private-device"}):
            commands = D.guest_commands()
            startup = dict(D.startup_commands())["crash"]
        self.assertEqual(len(commands), 6)
        crash = dict(commands)["crash"]
        self.assertEqual(crash[:4], ["/offline/sdk/platform-tools/adb", "-s", "emulator-5554", "shell"])
        self.assertEqual(crash[-1].replace(" -t 128", ""), startup[-1])
        fixture = self.directory / "prehook-bin"
        fixture.mkdir()
        logcat = fixture / "logcat"
        logcat.write_text("#!" + sys.executable + "\nimport sys\n"
                          "assert sys.argv[1:] == ['-b','crash','-b','main','-b','system','-d','-t','128','-v','brief']\n"
                          "print('F/DEBUG   ( 600): pid: 524, tid: 525, name: Binder Pool  >>> system_server <<<')\n"
                          "print('W/Watchdog( 524): *** WATCHDOG KILLING SYSTEM PROCESS: blocked')\n"
                          "print('E/AndroidRuntime( 524): FATAL EXCEPTION IN SYSTEM PROCESS: main')\n"
                          "print('I/TestRunner: dev.mobile.maestro.MaestroDriverService ready')\n"
                          "print('F/DEBUG   ( 600): pid: 524, tid: 525, name: Binder Pool  >>> system_server.private <<<')\n"
                          "print('W/WatchdogPrivate: *** WATCHDOG KILLING SYSTEM PROCESS: private')\n"
                          "print('E/PrivateApp: FATAL EXCEPTION IN SYSTEM PROCESS private')\n"
                          "print('E/AndroidRuntime: private application crash')\n")
        logcat.chmod(0o755)
        with patch.dict(os.environ, {"PATH": str(fixture) + os.pathsep + os.environ["PATH"]}):
            result = D.capture(["/bin/sh", "-c", crash[-1]])
        self.assertEqual(result["exit"], 0)
        self.assertTrue(result["joined"])
        self.assertFalse(result["truncated"])
        self.assertEqual(len(result["stdout"].splitlines()), 4)
        self.assertNotIn("private", result["stdout"])
        self.assertIn("*** WATCHDOG", result["stdout"])
        self.assertIn(">>> system_server <<<", result["stdout"])

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
