"""Offline process/socket fixtures; never an emulator, adb, or simulator."""
import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import android_ios_handoff as H

OUTCOMES = {"inputs": "success", "sdk": "success", "tools": "success", "prepare": "success", "observer": "success", "android": "failure"}


def result(out="", code=0, **changes):
    return {"outcome": "completed", "exit": code, "seconds": .001, "stdout": out, "stderr": "",
            "droppedBytes": {"stdout": 0, "stderr": 0}, "truncated": False, "joined": True, **changes}


class HandoffTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.directory = self.root / "ownership"
        self.observer = self.root / "observer"
        self.observer.mkdir()
        env = {key: "fixture-" + key for key in H.binding.__globals__["KEYS"]}
        env.update(ANDROID_AVD_HOME=str(self.root / "avds"), ANDROID_HOME=str(self.root / "sdk"))
        self.env = patch.dict(os.environ, env)
        self.env.start()
        self.ports = patch.object(H, "free_ports")
        self.ports.start()
        self.closed = patch.object(H, "closed_ports")
        self.closed.start()
        with patch.object(H.platform, "system", return_value="Darwin"), patch.object(H.platform, "machine", return_value="arm64"):
            H.prepare(self.directory)
        config = H.config_path()
        config.parent.mkdir(parents=True)
        config.write_text("AvdId=test\n")
        H.prelaunch(self.directory)
        pre = H.read(self.directory / "prelaunch.json")
        pre["beforeLaunchUnix"] = time.time() - 2
        (self.directory / "prelaunch.json").write_text(json.dumps(pre))
        seed = H.read(self.directory / "prepared.json")
        self.control = {"binding": seed["binding"], "scriptSha256": seed["observerSourceSha256"], "serial": H.SERIAL, "avd": H.AVD, "token": "owned-observer-token", "pid": 891}
        self.save(self.observer / "control.json", self.control)
        expected = {"token": self.control["token"], "pid": self.control["pid"]}
        for name, data in {"ready": expected, "stop": {"token": self.control["token"]}, "done": {**expected, "status": "stopped"}, "joined": {**expected, "observerExited": True}, "report": {"binding": self.control, "target": {"serial": H.SERIAL, "avd": H.AVD}, "truncated": False, "droppedSnapshots": 0, "droppedSnapshotBytes": 0}}.items():
            self.save(self.observer / (name + ".json"), data)
        self.ps = time.strftime("%a %b %d %H:%M:%S %Y") + " " + str(self.root / "sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64") + " -avd test -port 5554 -qemu -accel tcg\n"

    def tearDown(self):
        self.ports.stop()
        self.closed.stop()
        self.env.stop()
        self.temp.cleanup()

    def save(self, path, data):
        path.write_text(json.dumps(data))

    def active(self, replies=None):
        with patch.object(H, "capture", side_effect=replies or [result("431\n"), result(self.ps), result("p431\nn*:5554\nn127.0.0.1:5555\nn[::1]:8554\n")]):
            H.active(self.directory, self.observer, time.monotonic() + 28)

    def guard(self, reply=None, **kwargs):
        with patch.object(H, "capture", return_value=reply or result(code=1)) as capture:
            H.guard(self.directory, self.observer, kwargs.get("outcomes", OUTCOMES), kwargs.get("cancelled", False), kwargs.get("deadline", time.monotonic() + 28))
            return capture

    def test_failed_android_still_allows_only_proven_exit_and_all_owned_ports(self):
        self.active()
        self.guard()
        row = H.read(self.directory / "handoff.json")
        self.assertTrue(row["iosMayStart"])
        self.assertEqual(row["androidOutcome"], "failure")
        self.assertEqual(row["portsClosed"], [5554, 5555, 8554])
        self.assertEqual(H.closed_ports.call_args.args[0], [5554, 5555, 8554])

    def test_success_and_failure_allowed_but_setup_skip_failure_extra_key_or_cancel_refused(self):
        self.assertTrue(H.eligible(OUTCOMES, False))
        self.assertTrue(H.eligible({**OUTCOMES, "android": "success"}, False))
        for key in OUTCOMES:
            self.assertFalse(H.eligible({**OUTCOMES, key: "skipped"}, False))
            if key != "android":
                self.assertFalse(H.eligible({**OUTCOMES, key: "failure"}, False))
        self.assertFalse(H.eligible(OUTCOMES, True))
        self.assertFalse(H.eligible({**OUTCOMES, "ambient": "success"}, False))

    def test_missing_active_receipt_refuses(self):
        with self.assertRaises(FileNotFoundError):
            self.guard()
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_stale_source_job_and_launch_receipt_refuse(self):
        self.active()
        for field in ("binding", "sourceSha256", "serial"):
            row = H.read(self.directory / "prepared.json")
            original = dict(row)
            row[field] = "stale"
            self.save(self.directory / "prepared.json", row)
            with self.assertRaises(RuntimeError):
                self.guard()
            self.save(self.directory / "prepared.json", original)

    def test_occupied_port_and_stale_avd_refuse_prelaunch(self):
        (self.directory / "prelaunch.json").unlink()
        with patch.object(H, "free_ports", side_effect=OSError("occupied")), self.assertRaises(OSError):
            H.prelaunch(self.directory)
        os.utime(H.config_path(), (1, 1))
        with self.assertRaisesRegex(RuntimeError, "freshly"):
            H.prelaunch(self.directory)

    def test_console_collision_foreign_argv_and_incomplete_listener_inventory_refuse(self):
        variants = [[result("431\n432\n")], [result("431\n"), result(self.ps.replace("-avd test", "-avd ambient"))], [result("431\n"), result(self.ps), result("p431\nn*:5554\n")]]
        for replies in variants:
            with self.assertRaises(RuntimeError):
                self.active(replies)
        self.assertFalse((self.directory / "active.json").exists())

    def test_reused_and_live_exact_pid_refuse_no_termination(self):
        self.active()
        with self.assertRaisesRegex(RuntimeError, "reused"):
            self.guard(result(self.ps.replace("-avd test", "-avd different")))
        with self.assertRaisesRegex(RuntimeError, "still live"):
            self.guard(result(self.ps), deadline=time.monotonic() + .1)
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_exit_with_reoccupied_port_refuses(self):
        self.active()
        with patch.object(H, "closed_ports", side_effect=OSError("port reused")), self.assertRaises(OSError):
            self.guard()

    def test_observer_not_joined_mismatched_or_truncated_refuses(self):
        self.active()
        for name, change in [("joined", {"observerExited": False}), ("done", {"token": "wrong"}), ("report", {"truncated": True}), ("stop", {"token": "wrong"})]:
            path = self.observer / (name + ".json")
            original = H.read(path)
            self.save(path, {**original, **change})
            with self.assertRaises(RuntimeError):
                self.guard()
            self.save(path, original)

    def test_cancelled_timeout_truncated_and_unjoined_inspection_refuse(self):
        self.active()
        with self.assertRaises(RuntimeError):
            self.guard(cancelled=True)
        for change in [{"outcome": "timeout"}, {"truncated": True}, {"joined": False}]:
            with self.assertRaises(RuntimeError):
                self.guard(result(code=1, **change))
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_actual_oversized_python_child_is_drained_joined_and_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Unknown"):
            H.checked([sys.executable, "-c", "import sys;sys.stdout.write('x'*200000);sys.stderr.write('y'*200000)"], time.monotonic() + 5)

    def test_time_wait_is_not_a_listener_and_refused_connections_prove_closed(self):
        self.closed.stop()
        try:
            with patch.object(H, "capture", return_value=result(code=1)), patch.object(H.socket, "socket") as sock:
                sock.return_value.__enter__.return_value.connect.side_effect = ConnectionRefusedError()
                H.closed_ports([5554], time.monotonic() + 5)
                self.assertEqual(sock.return_value.__enter__.return_value.connect.call_count, 2)
                sock.return_value.__enter__.return_value.bind.assert_not_called()
        finally:
            self.closed.start()

    def test_live_listener_reachable_socket_and_unknown_connection_refuse(self):
        self.closed.stop()
        try:
            with patch.object(H, "capture", return_value=result("431\n")), self.assertRaisesRegex(RuntimeError, "listener"):
                H.closed_ports([5554], time.monotonic() + 5)
            for error in (None, TimeoutError("unknown")):
                with patch.object(H, "capture", return_value=result(code=1)), patch.object(H.socket, "socket") as sock:
                    sock.return_value.__enter__.return_value.connect.side_effect = error
                    with self.assertRaises(RuntimeError):
                        H.closed_ports([5554], time.monotonic() + 5)
        finally:
            self.closed.start()

    def test_no_remaining_deadline_and_invalid_pid_refuse_without_probe(self):
        with patch.object(H, "capture") as capture:
            for pid in (0, -1, "431", 2147483648):
                with self.assertRaises(RuntimeError):
                    H.process_identity(pid, time.monotonic() + 5)
            capture.assert_not_called()
        with patch.object(H, "capture", return_value=result(code=1)), self.assertRaises(RuntimeError):
            H.checked(["offline"], time.monotonic() - 1)

    def test_workflow_preserves_failure_and_sequential_guard(self):
        source = Path(__file__).parents[1] / ".github/workflows/ci.yml"
        text = source.read_text()
        mac = text[text.index("  smoke-ios:"):]
        self.assertNotIn("continue-on-error", mac)
        self.assertLess(mac.index("name: macOS packaged Android"), mac.index("name: Verify Android exit"))
        self.assertLess(mac.index("name: Verify Android exit"), mac.index("name: Create job-owned iOS"))
        self.assertIn("steps.android_ios_guard.outputs.ios_ready == 'true'", mac)
        self.assertIn("steps.ios_setup.outcome == 'success'", mac)
        self.assertIn("raise SystemExit(result)", mac)
        self.assertIn("if: always() && !cancelled()", mac)


if __name__ == "__main__":
    unittest.main()
