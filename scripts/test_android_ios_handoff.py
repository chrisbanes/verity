"""Offline process/socket fixtures; never an emulator, adb, or simulator."""
import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from contextlib import ExitStack

import android_ios_handoff as H

OUTCOMES = {"inputs": "success", "sdk": "success", "tools": "success", "prepare": "success", "observer": "success", "android": "failure"}


def result(out="", code=0, **changes):
    return {"outcome": "completed", "exit": code, "seconds": .001, "stdout": out, "stderr": "",
            "droppedBytes": {"stdout": 0, "stderr": 0}, "truncated": False, "joined": True, **changes}


class HandoffTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.directory = self.root / "ownership"
        self.observer = self.root / "observer"
        self.observer.mkdir()
        env = {key: "fixture-" + key for key in H.binding.__globals__["KEYS"]}
        env.update(HOME=str(self.root), ANDROID_AVD_HOME=str(self.root / ".android/avd"), ANDROID_HOME=str(self.root / "sdk"))
        self.env = patch.dict(os.environ, env)
        self.env.start()
        self.ports = patch.object(H, "free_ports")
        self.ports.start()
        self.closed = patch.object(H, "closed_ports")
        self.closed.start()
        with patch.object(H.platform, "system", return_value="Darwin"), patch.object(H.platform, "machine", return_value="arm64"):
            H.prepare(self.directory)
        sdk = self.root / "sdk/emulator"
        for relative in H.SDK_EXECUTABLES:
            executable = sdk / relative
            executable.parent.mkdir(parents=True, exist_ok=True)
            executable.write_text("offline fixture, never executed")
            executable.chmod(0o755)
        config = H.config_path()
        config.parent.mkdir(parents=True)
        config.write_text("AvdId=test\n")
        (config.parent.parent / "test.ini").write_text("path=" + str(config.parent) + "\npath.rel=avd/test.avd\n")
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
        # Each fixture invocation is independent; production duplicate publication is tested separately.
        (self.directory / "process-observation.json").unlink(missing_ok=True)
        (self.directory / "listener-observation.json").unlink(missing_ok=True)
        with patch.object(H, "capture", side_effect=replies or [result("431\n"), result(self.ps), result("p431\nf3\nn*:5554\nf4\nn127.0.0.1:5555\nf5\nn[::1]:8554\n")]):
            H.active(self.directory, self.observer, time.monotonic() + 28)

    def guard(self, reply=None, **kwargs):
        with patch.object(H, "capture", return_value=reply or result(code=1)) as capture:
            H.guard(self.directory, self.observer, kwargs.get("outcomes", OUTCOMES), kwargs.get("cancelled", False), kwargs.get("deadline", time.monotonic() + 28))
            return capture

    def ownership(self):
        bound = H.ownership_binding(self.directory)
        self.control["ownership"] = bound
        self.save(self.observer / "control.json", self.control)
        report = H.read(self.observer / "report.json", 262144)
        report["binding"] = self.control
        self.save(self.observer / "report.json", report)
        return bound

    def early(self, state, replies=None, cancel=lambda: False, deadline=None):
        with patch.object(H, "capture", side_effect=replies or [result("431\n"), result("431\n"), result(self.ps), result("p431\nf3\nn*:5554\nf4\nn127.0.0.1:5555\n")]) as capture:
            H.early_ownership(self.directory, self.observer, self.ownership(), state,
                              deadline or time.monotonic() + 28, cancel)
            return capture

    def test_complete_early_identity_allows_preunlock_failure_exit_guard(self):
        state = {}
        self.early(state)
        self.assertEqual(state, {"attempted": True, "status": "complete"})
        self.assertEqual(H.read(self.directory / "active.json")["phase"], "early")
        self.assertTrue((self.directory / "early-listener-observation.json").exists())
        self.assertFalse((self.directory / "listener-observation.json").exists())
        self.guard()
        self.assertEqual(H.read(self.directory / "handoff.json")["portsClosed"], [5554, 5555])

    def test_early_pending_only_until_console_and_full_attempt_never_retried(self):
        state = {}
        self.early(state, [result(code=1)])
        self.assertEqual(state, {"status": "pending-console"})
        self.early(state, [result("431\n"), result("431\n"), result(self.ps), result("p431\nf3\nn*:5554\n")])
        self.assertEqual(state["status"], "failed")
        self.assertTrue(state["attempted"])
        self.assertFalse((self.directory / "active.json").exists())
        self.assertEqual(self.early(state, []).call_count, 0)
        self.active()  # Failed early diagnostics never poison the distinct original postboot path.
        self.guard()

    def test_unknown_early_console_refuses_without_full_identity_attempt(self):
        for reply in (result("431\n432\n"), result("431\n", stderr="unknown"), result("431\n", truncated=True), result("431\n", joined=False)):
            state = {}
            with self.assertRaises(RuntimeError):
                self.early(state, [reply])
            self.assertFalse(state.get("attempted", False))
            self.assertFalse((self.directory / "active.json").exists())

    def test_early_cancel_during_each_command_no_later_probe_or_active(self):
        for stop_at in (1, 2, 3, 4):
            for name in ("early-process-observation.json", "early-listener-observation.json"):
                (self.directory / name).unlink(missing_ok=True)
            stopped = [False]
            replies = [result("431\n"), result("431\n"), result(self.ps), result("p431\nf3\nn*:5554\nf4\nn*:5555\n")]
            def command(*args, **kwargs):
                self.assertIn("cancel", kwargs)
                answer = replies.pop(0)
                if 4 - len(replies) == stop_at:
                    stopped[0] = True
                return answer
            bound = self.ownership()
            with patch.object(H, "capture", side_effect=command) as capture, self.assertRaises(RuntimeError):
                H.early_ownership(self.directory, self.observer, bound, {}, time.monotonic() + 28, lambda: stopped[0])
            self.assertEqual(capture.call_count, stop_at)
            self.assertFalse((self.directory / "active.json").exists())

    def test_cancelled_exclusive_publication_removed_but_old_receipt_preserved(self):
        path = self.directory / "publication.json"
        with self.assertRaises(RuntimeError):
            H.write(path, {"proof": True}, time.monotonic() + 1, lambda: path.exists())
        self.assertFalse(path.exists())
        H.write(path, {"old": True})
        with self.assertRaises(RuntimeError):
            H.write(path, {"new": True})
        self.assertEqual(H.read(path), {"old": True})

    def test_early_source_binding_stop_and_deadline_before_probe(self):
        bound = self.ownership()
        for expected, cancel, deadline in (({**bound, "nonce": "stale"}, lambda: False, time.monotonic()+28),
                                            (bound, lambda: True, time.monotonic()+28),
                                            (bound, lambda: False, time.monotonic()-1)):
            with patch.object(H, "capture") as capture, self.assertRaises(RuntimeError):
                H.early_ownership(self.directory, self.observer, expected, {}, deadline, cancel)
            capture.assert_not_called()

    def test_postboot_full_revalidation_preserves_early_hash_and_union(self):
        self.early({})
        original = (self.directory / "active.json").read_bytes()
        self.active()
        self.assertEqual((self.directory / "active.json").read_bytes(), original)
        row = H.read(self.directory / "revalidated.json")
        self.assertEqual(row["activeSha256"], H.hashlib.sha256(original).hexdigest())
        self.assertEqual(row["listenerPorts"], [5554, 5555, 8554])
        self.guard()
        self.assertEqual(H.read(self.directory / "handoff.json")["portsClosed"], [5554, 5555, 8554])

    def test_postboot_exited_or_reused_pid_never_revalidated(self):
        self.early({})
        for reply in (result(code=1), result(self.ps.replace(" -qemu", " -changed -qemu"))):
            for name in ("revalidated-process-observation.json", "revalidated-listener-observation.json"):
                (self.directory / name).unlink(missing_ok=True)
            with self.assertRaises(RuntimeError):
                self.active([result("431\n"), reply, result("p431\nf3\nn*:5554\nf4\nn*:5555\n")])
            self.assertFalse((self.directory / "revalidated.json").exists())

    def test_early_composite_deadline_is_minimum_of_observer_and_28_seconds(self):
        bound = self.ownership()
        for remaining in (10, 600):
            started = time.monotonic()
            end = started + remaining
            with patch.object(H, "capture", return_value=result("431\n")), patch.object(H, "active") as active:
                H.early_ownership(self.directory, self.observer, bound, {}, end, lambda: False)
            supplied = active.call_args.args[2]
            self.assertLessEqual(supplied, end)
            self.assertLessEqual(supplied - started, 28.1)
            self.assertTrue(active.call_args.kwargs["early"])

    def test_mutated_early_receipt_during_revalidation_never_publishes_authority(self):
        self.early({})
        row = H.read(self.directory / "active.json")
        original_collect = H.collect_active
        def collect(*args, **kwargs):
            value = original_collect(*args, **kwargs)
            self.save(self.directory / "active.json", {**row, "observedUnix": row["observedUnix"] + 1})
            return value
        with patch.object(H, "collect_active", side_effect=collect), self.assertRaisesRegex(RuntimeError, "receipt changed"):
            self.active()
        self.assertFalse((self.directory / "revalidated.json").exists())

    def test_readiness_elapsed_consumes_same_absolute_28_second_operation_deadline(self):
        bound = self.ownership()
        for parent_end, elapsed in ((1000, 5), (120, 5)):
            now = [100.0]
            def ready(*args, **kwargs):
                self.assertEqual(kwargs["deadline"], min(parent_end, 128))
                now[0] += elapsed
                return result("431\n")
            with patch.object(H.time, "monotonic", side_effect=lambda: now[0]), patch.object(H, "capture", side_effect=ready), patch.object(H, "active") as active:
                H.early_ownership(self.directory, self.observer, bound, {}, parent_end, lambda: False)
            self.assertEqual(active.call_args.args[2], min(parent_end, 128))
            self.assertEqual(active.call_args.args[2] - now[0], min(parent_end, 128) - 105)
        now = [100.0]
        def expired(*args, **kwargs):
            now[0] = kwargs["deadline"]
            return result("431\n")
        with patch.object(H.time, "monotonic", side_effect=lambda: now[0]), patch.object(H, "capture", side_effect=expired) as capture, patch.object(H, "active") as active, self.assertRaises(RuntimeError):
            H.early_ownership(self.directory, self.observer, bound, {}, 1000, lambda: False)
        self.assertEqual(capture.call_count, 1)
        active.assert_not_called()
        self.assertFalse((self.directory / "active.json").exists())

    def test_failed_postboot_attempts_remain_durable_guard_refusals_after_cleanup(self):
        self.early({})
        original = (self.directory / "active.json").read_bytes()
        valid_replies = [result("431\n"), result(self.ps), result("p431\nf3\nn*:5554\nf4\nn*:5555\n")]
        for failure in ("exited", "reused", "listener", "sdk", "namespace", "hash", "cancel", "deadline", "publication"):
            with self.subTest(failure=failure):
                # Separate offline invocation fixtures; production never retries/removes durable intent.
                for name in ("postboot-revalidation-started.json", "revalidated.json", "revalidated-process-observation.json", "revalidated-listener-observation.json"):
                    (self.directory / name).unlink(missing_ok=True)
                (self.directory / "active.json").write_bytes(original)
                stopped = [False]
                deadline = time.monotonic() + 28
                with ExitStack() as stack:
                    if failure == "sdk":
                        stack.enter_context(patch.object(H, "sdk_registration", return_value={"changed": True}))
                    elif failure == "namespace":
                        stack.enter_context(patch.object(H, "owned_directory", return_value={"changed": True}))
                    elif failure == "hash":
                        original_hash = H.receipt_hash(self.directory / "active.json")
                        real_hash = H.receipt_hash
                        active_reads = [0]
                        def changed_hash(path):
                            if path.name == "active.json":
                                active_reads[0] += 1
                                return original_hash if active_reads[0] == 1 else "changed"
                            return real_hash(path)
                        stack.enter_context(patch.object(H, "receipt_hash", side_effect=changed_hash))
                    elif failure == "publication":
                        write = H.write
                        def broken(path, *args, **kwargs):
                            if path.name == "revalidated.json":
                                raise OSError("offline publication failure")
                            return write(path, *args, **kwargs)
                        stack.enter_context(patch.object(H, "write", side_effect=broken))
                    replies = list(valid_replies)
                    if failure == "exited":
                        replies[1] = result(code=1)
                    elif failure == "reused":
                        replies[1] = result(self.ps.replace(" -qemu", " -changed -qemu"))
                    elif failure == "listener":
                        replies[2] = result("p431\nf3\nn*:5554\n")
                    now = [100.0]
                    if failure == "deadline":
                        deadline = 128
                        stack.enter_context(patch.object(H.time, "monotonic", side_effect=lambda: now[0]))
                    def command(*args, **kwargs):
                        self.assertTrue((self.directory / "postboot-revalidation-started.json").exists())
                        if failure == "cancel":
                            stopped[0] = True
                        elif failure == "deadline":
                            now[0] = 128
                        return replies.pop(0)
                    capture = stack.enter_context(patch.object(H, "capture", side_effect=command))
                    with self.assertRaises((RuntimeError, OSError)):
                        H.active(self.directory, self.observer, deadline, lambda: stopped[0])
                    if failure in ("cancel", "deadline"):
                        self.assertEqual(capture.call_count, 1)
                    elif failure == "namespace":
                        self.assertEqual(capture.call_count, 0)
                self.assertTrue((self.directory / "postboot-revalidation-started.json").exists())
                self.assertFalse((self.directory / "revalidated.json").exists())
                # Original process has exited and old ports are closed, but fallback is now forbidden.
                with self.assertRaises((RuntimeError, FileNotFoundError)):
                    self.guard()
                self.assertFalse((self.directory / "handoff.json").exists())

    def test_completed_intent_then_cancel_retained_without_later_probe(self):
        self.early({})
        path = self.directory / "postboot-revalidation-started.json"
        with patch.object(H, "capture") as capture, self.assertRaises(RuntimeError):
            H.active(self.directory, self.observer, time.monotonic() + 28, lambda: path.exists())
        capture.assert_not_called()
        self.assertTrue(path.exists())
        with self.assertRaises(FileNotFoundError):
            self.guard()

    def test_partial_exclusively_created_intent_retained_and_refuses_guard_without_probes(self):
        self.early({})
        path = self.directory / "postboot-revalidation-started.json"
        original_open = Path.open
        class PartialWrite:
            def __init__(self, stream):
                self.stream = stream
            def __enter__(self):
                return self
            def __exit__(self, *_):
                self.stream.close()
            def write(self, data):
                self.stream.write(data[:10])
                raise OSError("offline partial intent publication")
        def opened(p, *args, **kwargs):
            stream = original_open(p, *args, **kwargs)
            return PartialWrite(stream) if p == path and args == ("xb",) else stream
        with patch.object(Path, "open", new=opened), patch.object(H, "capture") as capture, self.assertRaises(OSError):
            H.active(self.directory, self.observer, time.monotonic() + 28)
        capture.assert_not_called()
        self.assertEqual(path.stat().st_size, 10)
        with self.assertRaises(ValueError):
            self.guard()
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_precreate_intent_failure_has_no_new_collection_and_retains_original_guard(self):
        self.early({})
        path = self.directory / "postboot-revalidation-started.json"
        original_open = Path.open
        def opened(p, *args, **kwargs):
            if p == path and args == ("xb",):
                raise PermissionError("offline intent creation denied")
            return original_open(p, *args, **kwargs)
        with patch.object(Path, "open", new=opened), patch.object(H, "capture") as capture, self.assertRaises(PermissionError):
            H.active(self.directory, self.observer, time.monotonic() + 28)
        capture.assert_not_called()
        self.assertFalse(path.exists())
        self.guard()  # Explicit root disposition: no postboot collection attempt was made.
        self.assertEqual(H.read(self.directory / "handoff.json")["androidOutcome"], "failure")

    def test_intent_mutation_during_collection_never_publishes_success(self):
        self.early({})
        collect = H.collect_active
        def changed(*args, **kwargs):
            value = collect(*args, **kwargs)
            path = self.directory / "postboot-revalidation-started.json"
            row = H.read(path)
            self.save(path, {**row, "phase": "changed"})
            return value
        with patch.object(H, "collect_active", side_effect=changed), self.assertRaisesRegex(RuntimeError, "intent changed"):
            self.active()
        self.assertFalse((self.directory / "revalidated.json").exists())
        with self.assertRaises(RuntimeError):
            self.guard()

    def test_successful_revalidation_requires_exact_intent_and_hash_pair(self):
        self.early({})
        self.active()
        path = self.directory / "postboot-revalidation-started.json"
        intent = path.read_bytes()
        row = H.read(path)
        for field in ("activeSha256", "observer", "prelaunch", "phase", "prepared"):
            self.save(path, {**row, field: "stale"})
            with self.assertRaises(RuntimeError):
                self.guard()
        path.write_text('{"partial":')
        with self.assertRaises(ValueError):
            self.guard()
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            self.guard()  # Even a successful receipt alone cannot authorize.
        path.write_bytes(intent)
        revalidated = H.read(self.directory / "revalidated.json")
        self.save(self.directory / "revalidated.json", {**revalidated, "intentSha256": "stale"})
        with self.assertRaises(RuntimeError):
            self.guard()
        self.save(self.directory / "revalidated.json", revalidated)
        self.guard()
        self.assertEqual(H.read(self.directory / "handoff.json")["portsClosed"], [5554, 5555, 8554])

    def test_guard_revalidation_hash_and_identity_binding_refuse(self):
        self.early({})
        self.active()
        original = H.read(self.directory / "revalidated.json")
        for field in ("activeSha256", "process", "observer", "phase"):
            self.save(self.directory / "revalidated.json", {**original, field: "stale"})
            with self.assertRaises(RuntimeError):
                self.guard()
            self.assertFalse((self.directory / "handoff.json").exists())
        self.save(self.directory / "revalidated.json", original)

    def main_revalidate_and_guard(self, ownership, observer):
        replies = [result("431\n"), result(self.ps), result("p431\nf3\nn*:5554\nf4\nn*:5555\nf5\nn[::1]:8554\n")]
        with patch.object(H.os, "getcwd", return_value=str(self.root)), patch.object(H.signal, "signal"), patch.object(sys, "argv", ["handoff", "active", ownership, observer]), patch.object(H, "capture", side_effect=replies):
            H.main()
        with patch.object(H.os, "getcwd", return_value=str(self.root)), patch.object(H.signal, "signal"), patch.object(sys, "argv", ["handoff", "guard", ownership, observer]), patch.dict(os.environ, {"VERITY_HANDOFF_OUTCOMES": json.dumps(OUTCOMES), "VERITY_HANDOFF_CANCELLED": "false"}), patch.object(H, "capture", return_value=result(code=1)):
            H.main()

    def test_actual_main_relative_arguments_revalidate_exact_registered_early_identity_and_guard(self):
        self.early({})
        original = (self.directory / "active.json").read_bytes()
        self.main_revalidate_and_guard("ownership", "observer")
        self.assertEqual((self.directory / "active.json").read_bytes(), original)
        intent = H.read(self.directory / "postboot-revalidation-started.json")
        self.assertEqual(intent["observer"]["ownership"]["directory"], str(self.directory))
        self.assertEqual(H.read(self.directory / "revalidated.json")["activeSha256"], H.hashlib.sha256(original).hexdigest())
        self.assertEqual(H.read(self.directory / "handoff.json")["portsClosed"], [5554, 5555, 8554])
        self.assertEqual(H.read(self.directory / "handoff.json")["androidOutcome"], "failure")

    def test_actual_main_absolute_arguments_retain_same_complete_proof(self):
        self.early({})
        self.main_revalidate_and_guard(str(self.directory), str(self.observer))
        self.assertTrue(H.read(self.directory / "handoff.json")["iosMayStart"])

    def test_actual_main_lexical_parent_and_symlink_aliases_do_not_gain_authority(self):
        self.early({})
        (self.root / "subdir").mkdir()
        (self.root / "alias").symlink_to(self.directory, target_is_directory=True)
        for argument in ("subdir/../ownership", "alias"):
            for name in ("postboot-revalidation-started.json", "revalidated-process-observation.json", "revalidated-listener-observation.json"):
                (self.directory / name).unlink(missing_ok=True)
            with patch.object(H.os, "getcwd", return_value=str(self.root)), patch.object(H.signal, "signal"), patch.object(sys, "argv", ["handoff", "active", argument, "observer"]), patch.object(H, "capture") as capture, self.assertRaisesRegex(RuntimeError, "Ambiguous"):
                H.main()
            capture.assert_not_called()
            self.assertFalse((self.directory / "revalidated.json").exists())
            with self.assertRaises(FileNotFoundError):
                self.guard()
            self.assertFalse((self.directory / "handoff.json").exists())
        with self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            H.ownership_binding(Path("ownership"))  # Direct API stays strict; only explicit CLI is normalized.

    def test_actual_main_relative_path_does_not_mask_stale_observer_binding(self):
        self.early({})
        self.save(self.observer / "control.json", {**self.control, "token": "stale"})
        with patch.object(H.os, "getcwd", return_value=str(self.root)), patch.object(H.signal, "signal"), patch.object(sys, "argv", ["handoff", "active", "ownership", "observer"]), patch.object(H, "capture") as capture, self.assertRaisesRegex(RuntimeError, "binding"):
            H.main()
        capture.assert_not_called()
        self.assertFalse((self.directory / "revalidated.json").exists())
        with self.assertRaisesRegex(RuntimeError, "Observer identity"):
            self.guard()

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

    def test_occupied_port_and_missing_avd_refuse_prelaunch(self):
        (self.directory / "prelaunch.json").unlink()
        with patch.object(H, "free_ports", side_effect=OSError("occupied")), self.assertRaises(OSError):
            H.prelaunch(self.directory)
        H.config_path().parent.rename(self.root / "removed-avd")
        with self.assertRaises(FileNotFoundError):
            H.prelaunch(self.directory)

    def test_console_collision_foreign_argv_and_incomplete_listener_inventory_refuse(self):
        variants = [[result("431\n432\n")], [result("431\n"), result(self.ps.replace("-avd test", "-avd ambient"))], [result("431\n"), result(self.ps), result("p431\nf3\nn*:5554\n")]]
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

    def test_config_rewrite_touch_and_atomic_replacement_keep_directory_ownership(self):
        before = H.read(self.directory / "prelaunch.json")
        config = H.config_path()
        for mutation in ("rewrite", "touch", "replace"):
            with self.subTest(mutation=mutation):
                if mutation == "rewrite":
                    config.write_text("AvdId=test\nhw.ramSize=2048\n")
                elif mutation == "touch":
                    os.utime(config, (1, 1))
                else:
                    replacement = config.with_name("config-new.ini")
                    replacement.write_text("AvdId=test\nupdated=yes\n")
                    replacement.replace(config)
                self.active()
                active = H.read(self.directory / "active.json")
                self.assertEqual(active["prelaunch"]["avdDirectory"], before["avdDirectory"])
                self.assertEqual(active["prelaunch"]["config"], before["config"])
                self.assertNotEqual(active["configAfter"], before["config"])
                self.guard()
                self.assertTrue(H.read(self.directory / "handoff.json")["iosMayStart"])
                (self.directory / "active.json").unlink()
                (self.directory / "handoff.json").unlink()

    def test_preexisting_directory_descriptor_and_symlink_refuse_prepare(self):
        home = self.root / "other-home/.android/avd"
        home.mkdir(parents=True)
        with patch.dict(os.environ, HOME=str(self.root / "other-home"), ANDROID_AVD_HOME=str(home)), patch.object(H.platform, "system", return_value="Darwin"), patch.object(H.platform, "machine", return_value="arm64"):
            target = home / "test.avd"
            target.mkdir()
            with self.assertRaisesRegex(RuntimeError, "already exists"):
                H.prepare(self.root / "preexisting-directory")
            target.rmdir()
            descriptor = home / "test.ini"
            descriptor.write_text("owned elsewhere")
            with self.assertRaisesRegex(RuntimeError, "already exists"):
                H.prepare(self.root / "preexisting-descriptor")
            descriptor.unlink()
            target.symlink_to(home / "missing")
            with self.assertRaisesRegex(RuntimeError, "already exists"):
                H.prepare(self.root / "preexisting-symlink")
        self.assertFalse((self.root / "preexisting-directory").exists())
        self.assertFalse((self.root / "preexisting-descriptor").exists())
        self.assertFalse((self.root / "preexisting-symlink").exists())

    def test_pinned_home_prepare_without_export_then_export_must_match(self):
        fresh_home = self.root / "fresh-home"
        ownership = self.root / "fresh-ownership"
        with patch.dict(os.environ, HOME=str(fresh_home)), patch.object(H.platform, "system", return_value="Darwin"), patch.object(H.platform, "machine", return_value="arm64"):
            with patch.dict(os.environ) as env:
                env.pop("ANDROID_AVD_HOME", None)
                H.prepare(ownership)
            prepared = H.read(ownership / "prepared.json")
            home = fresh_home / ".android/avd"
            self.assertEqual(prepared["avdHome"], H.directory_identity(home))
            self.assertFalse((home / "test.avd").exists())
            self.assertFalse((home / "test.ini").exists())
            with patch.dict(os.environ, ANDROID_AVD_HOME=str(home)):
                (home / "test.avd").mkdir()
                (home / "test.avd/config.ini").write_text("AvdId=test\n")
                (home / "test.ini").write_text("path=" + str(home / "test.avd") + "\n")
                H.prelaunch(ownership)
            (ownership / "prelaunch.json").unlink()
            with patch.dict(os.environ, ANDROID_AVD_HOME=str(self.root / ".android/avd")), self.assertRaisesRegex(RuntimeError, "parent/namespace"):
                H.prelaunch(ownership)
            self.assertFalse((ownership / "prelaunch.json").exists())

    def test_directory_replacement_and_symlink_refuse_active_and_postaction(self):
        self.active()
        target = H.config_path().parent
        saved = self.root / "saved-avd"
        target.rename(saved)
        target.mkdir()
        (target / "config.ini").write_bytes((saved / "config.ini").read_bytes())
        with self.assertRaisesRegex(RuntimeError, "directory identity"):
            self.guard()
        with self.assertRaisesRegex(RuntimeError, "directory identity"):
            self.active()
        (target / "config.ini").unlink()
        target.rmdir()
        target.symlink_to(saved)
        with self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            self.guard()
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_parent_replacement_repoint_and_ambiguous_paths_refuse(self):
        home = H.config_path().parent.parent
        home.rename(self.root / "saved-home")
        home.mkdir()
        with self.assertRaisesRegex(RuntimeError, "parent/namespace"):
            self.active()
        for configured in (self.root / "saved-home", home / ".." / "saved-home", Path("relative")):
            with patch.dict(os.environ, ANDROID_AVD_HOME=str(configured)), self.assertRaises(RuntimeError):
                self.active()
        home.rmdir()
        home.symlink_to(self.root / "saved-home")
        with self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            self.active()

    def test_mutated_config_cannot_bypass_process_observer_or_cancel_refusal(self):
        H.config_path().write_text("mutable=yes\n")
        with self.assertRaises(RuntimeError):
            self.active([result("431\n"), result(self.ps.replace("-avd test", "-avd foreign"))])
        with self.assertRaises(RuntimeError):
            self.active([result("431\n432\n")])
        self.active()
        with self.assertRaises(RuntimeError):
            self.guard(result(self.ps), deadline=time.monotonic() + .1)
        with self.assertRaises(RuntimeError):
            self.guard(result(self.ps.replace("-port 5554", "-port 5556")))
        with self.assertRaises(RuntimeError):
            self.guard(cancelled=True)
        with patch.object(H, "closed_ports", side_effect=RuntimeError("live port")), self.assertRaises(RuntimeError):
            self.guard()
        self.save(self.observer / "joined.json", {"token": self.control["token"], "pid": self.control["pid"], "observerExited": False})
        with self.assertRaises(RuntimeError):
            self.guard()
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_descriptor_both_orderings_bind_same_target_and_mutable_fields_allowed(self):
        home, target, descriptor = H.avd_paths()
        for extra in ("", "path.rel=avd/test.avd\n", "target=android-30\npath.rel=avd/test.avd\n"):
            descriptor.write_text("path=" + str(target) + "\n" + extra)
            self.active()
            self.guard()
            (self.directory / "active.json").unlink()
            (self.directory / "handoff.json").unlink()

    def test_descriptor_duplicate_foreign_relative_missing_and_symlink_refuse(self):
        home, target, descriptor = H.avd_paths()
        original = descriptor.read_text()
        variants = ["path=" + str(target) + "\npath=" + str(target) + "\n",
                    "path=" + str(target) + "\npath.rel=avd/foreign.avd\n",
                    "path=" + str(target) + "\npath.rel=avd/test.avd\npath.rel=avd/test.avd\n",
                    "path=" + str(target) + "\npath.rel=../avd/test.avd\n",
                    "path=" + str(target) + "\npath.rel=" + str(target) + "\n",
                    "path=" + str(self.root) + "\n", "path.rel=avd/test.avd\n", "x" * (H.CAP + 1)]
        for text in variants:
            descriptor.write_text(text)
            with self.assertRaises(RuntimeError):
                self.active()
        descriptor.write_text(original)
        self.active()
        descriptor.write_text("path=" + str(target) + "\npath.rel=avd/foreign.avd\n")
        with self.assertRaises(RuntimeError):
            self.guard()
        descriptor.unlink()
        with self.assertRaises(FileNotFoundError):
            self.guard()
        saved = home / "saved.ini"
        saved.write_text(original)
        descriptor.symlink_to(saved)
        with self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            self.guard()
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_exact_registered_frontend_regular_and_headless_paths_pass_full_handoff(self):
        for relative in H.SDK_EXECUTABLES:
            executable = self.root / "sdk/emulator" / relative
            ps = self.ps.replace(str(self.root / "sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64"), str(executable))
            self.active([result("431\n"), result(ps), result("p431\nf3\nn*:5554\nf4\nn127.0.0.1:5555\n")])
            observed = H.read(self.directory / "process-observation.json")
            self.assertTrue(observed["provisional"])
            self.assertTrue(all(observed["checks"].values()))
            self.assertNotIn("argv", observed)
            self.assertEqual(observed["executablePath"], str(executable))
            self.guard()
            (self.directory / "active.json").unlink()
            (self.directory / "handoff.json").unlink()

    def test_foreign_sdk_subdirectory_isa_name_and_symlink_refuse_with_observation(self):
        regular = self.root / "sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64"
        foreign = self.root / "foreign/qemu-system-aarch64"
        foreign.parent.mkdir()
        foreign.write_text("private argv must not be retained")
        escaped = self.root / "sdk/emulator/escape"
        escaped.symlink_to(foreign)
        candidates = [foreign, self.root / "sdk/emulator/wrong/qemu-system-aarch64",
                      self.root / "sdk/emulator/qemu/darwin-x86_64/qemu-system-aarch64",
                      self.root / "sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64-unknown", escaped]
        for candidate in candidates:
            ps = self.ps.replace(str(regular), str(candidate)).rstrip() + " private-token\n"
            with self.assertRaisesRegex(RuntimeError, "registered SDK"):
                self.active([result("431\n"), result(ps)])
            observed = H.read(self.directory / "process-observation.json")
            self.assertFalse(all(observed["checks"].values()))
            self.assertLessEqual((self.directory / "process-observation.json").stat().st_size, H.CAP)
            self.assertNotIn("private-token", (self.directory / "process-observation.json").read_text())
            self.assertFalse((self.directory / "active.json").exists())
            with self.assertRaises(FileNotFoundError):
                self.guard()
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_used_registration_disappearance_and_replacement_refuse_active_or_guard(self):
        regular = self.root / "sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64"
        self.active()
        regular.unlink()
        with self.assertRaisesRegex(RuntimeError, "registration changed"):
            self.guard()
        (self.directory / "active.json").unlink()
        with self.assertRaisesRegex(RuntimeError, "registered SDK"):
            self.active([result("431\n"), result(self.ps)])
        observed = H.read(self.directory / "process-observation.json")
        self.assertFalse(observed["checks"]["registrationUnchanged"])
        regular.write_text("replaced")
        regular.chmod(0o755)
        with self.assertRaisesRegex(RuntimeError, "registered SDK"):
            self.active([result("431\n"), result(self.ps)])
        self.assertFalse((self.directory / "active.json").exists())

    def test_unused_alternative_may_be_absent_but_actual_registered_backend_required(self):
        headless = self.root / "sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64-headless"
        headless.unlink()
        (self.directory / "prelaunch.json").unlink()
        H.prelaunch(self.directory)
        pre = H.read(self.directory / "prelaunch.json")
        pre["beforeLaunchUnix"] = time.time() - 2
        self.save(self.directory / "prelaunch.json", pre)
        self.active()
        self.guard()
        self.assertIn(str(headless), pre["sdkExecutables"]["absent"])
        (self.root / "sdk/emulator/emulator").unlink()
        with self.assertRaisesRegex(RuntimeError, "missing"):
            H.sdk_registration()

    def test_provisional_duplicate_oversized_or_invalid_identity_never_authorizes(self):
        self.active()
        (self.directory / "active.json").unlink()
        with patch.object(H, "capture", side_effect=[result("431\n"), result(self.ps)]), self.assertRaisesRegex(RuntimeError, "Duplicate"):
            H.active(self.directory, self.observer, time.monotonic() + 28)
        with self.assertRaises(FileNotFoundError):
            self.guard()
        for row in (result("invalid process row"), result(self.ps, truncated=True, droppedBytes={"stdout": 1, "stderr": 0})):
            with self.assertRaises(RuntimeError):
                self.active([result("431\n"), row])
        self.assertFalse((self.directory / "active.json").exists())
        self.assertFalse((self.directory / "handoff.json").exists())

    def test_encoded_provisional_observation_cap_refuses_before_active_publication(self):
        oversized = "/" + '"' * 4500
        original_resolve = Path.resolve
        def resolve(path, *args, **kwargs):
            return path if str(path) == oversized else original_resolve(path, *args, **kwargs)
        identity = {"pid": 431, "started": time.strftime("%a %b %d %H:%M:%S %Y"), "argv": [oversized]}
        with patch.object(Path, "resolve", resolve), patch.object(H, "process_identity", return_value=identity), patch.object(H, "capture", return_value=result("431\n")):
            with self.assertRaisesRegex(RuntimeError, "oversized"):
                H.active(self.directory, self.observer, time.monotonic() + 28)
        self.assertFalse((self.directory / "process-observation.json").exists())
        self.assertFalse((self.directory / "active.json").exists())
        with self.assertRaises(FileNotFoundError):
            self.guard()

    def test_bound_configured_sdk_alias_and_canonical_spelling_share_exact_registered_file(self):
        alias = self.root / "declared-sdk-alias"
        alias.symlink_to(self.root / "sdk")
        with patch.dict(os.environ, ANDROID_HOME=str(alias)):
            (self.directory / "prelaunch.json").unlink()
            H.prelaunch(self.directory)
            pre = H.read(self.directory / "prelaunch.json")
            pre["beforeLaunchUnix"] = time.time() - 2
            self.save(self.directory / "prelaunch.json", pre)
            for ps in (self.ps.replace(str(self.root / "sdk"), str(alias)), self.ps):
                self.active([result("431\n"), result(ps), result("p431\nf3\nn*:5554\nf4\nn127.0.0.1:5555\n")])
                observed = H.read(self.directory / "process-observation.json")
                self.assertTrue(observed["checks"]["declaredSdkSpelling"])
                self.assertEqual(observed["executablePath"], str(self.root / "sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64"))
                self.guard()
                (self.directory / "active.json").unlink()
                (self.directory / "handoff.json").unlink()
            self.active()
        with self.assertRaisesRegex(RuntimeError, "registration changed"):
            self.guard()

    def test_unconfigured_external_alias_resolving_registered_binary_still_refuses(self):
        alias = self.root / "unconfigured-sdk-alias"
        alias.symlink_to(self.root / "sdk")
        ps = self.ps.replace(str(self.root / "sdk"), str(alias))
        with self.assertRaisesRegex(RuntimeError, "registered SDK"):
            self.active([result("431\n"), result(ps)])
        observed = H.read(self.directory / "process-observation.json")
        self.assertTrue(observed["checks"]["registeredFile"])
        self.assertFalse(observed["checks"]["declaredSdkSpelling"])
        self.assertNotEqual(observed["rawExecutablePath"], observed["executablePath"])
        self.assertFalse((self.directory / "active.json").exists())

    def test_strict_descriptor_inventory_keeps_all_ports_with_ipv4_ipv6_and_wildcard(self):
        rows = "p431\nf0\nn*:5554\nf4\nn127.0.0.1:5555\nf5\nn[::]:5554\nf6\nn[::1]:8554\n"
        self.assertEqual(H.listener_ports(rows, 431), [5554, 5555, 8554])
        self.active([result("431\n"), result(self.ps), result(rows)])
        report = H.read(self.directory / "listener-observation.json")
        self.assertEqual(report["rowCount"], 9)
        self.assertEqual(report["unknownRowCount"], 0)
        self.assertTrue(report["provisional"])
        self.guard()
        self.assertEqual(H.read(self.directory / "handoff.json")["portsClosed"], [5554, 5555, 8554])

    def test_orphan_duplicate_foreign_unknown_missing_and_invalid_inventory_refuses(self):
        valid = "p431\nf3\nn*:5554\nf4\nn127.0.0.1:5555\n"
        variants = ["", valid.replace("p431", "p432"), valid + "p431\n", valid.replace("f4", "f3"),
                    valid.replace("f3\n", ""), valid.replace("n*:5554\n", ""), valid + "n*:8554\n",
                    valid.replace("f4", "cprivate-command"), valid.replace("n*:5554", "n10.0.0.1:5554"),
                    valid.replace("f3", "f-1"), valid.replace("f3", "f2147483648"),
                    valid.replace(":5554", ":0"), valid.replace(":5554", ":65536"), valid.replace(":5554", ":8554")]
        for rows in variants:
            with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                self.active([result("431\n"), result(self.ps), result(rows)])
            self.assertTrue((self.directory / "listener-observation.json").exists())
            self.assertFalse((self.directory / "active.json").exists())
        with self.assertRaises(FileNotFoundError):
            self.guard()

    def test_listener_status_and_private_output_observed_without_authorizing(self):
        valid = "p431\nf3\nn*:5554\nf4\nn127.0.0.1:5555\n"
        changes = [{"exit": 1}, {"stderr": "private-filesystem-warning"}, {"outcome": "timeout"},
                   {"truncated": True}, {"joined": False}, {"droppedBytes": {"stdout": 1, "stderr": 0}}]
        for change in changes:
            with self.assertRaises(RuntimeError):
                self.active([result("431\n"), result(self.ps), result(valid, **change)])
            report = H.read(self.directory / "listener-observation.json")
            key = next(iter(change))
            if key == "stderr":
                self.assertEqual(report["stderrBytes"], len(change[key]))
                self.assertEqual(report["stderrRowCount"], 1)
            else:
                self.assertEqual(report["status"][key], change[key])
            self.assertNotIn("private-filesystem-warning", (self.directory / "listener-observation.json").read_text())
        private = "private-unknown-row-with-token"
        with self.assertRaises(RuntimeError):
            self.active([result("431\n"), result(self.ps), result(valid + private + "\n")])
        report = H.read(self.directory / "listener-observation.json")
        self.assertEqual(report["unknownRowCount"], 1)
        self.assertEqual(report["unknownBytes"], len(private))
        self.assertNotIn(private, (self.directory / "listener-observation.json").read_text())
        self.assertFalse((self.directory / "active.json").exists())

    def test_listener_observation_row_cap_duplicate_and_deadline_refuse(self):
        rows = "p431\n" + "".join("f" + str(fd) + "\nn*:" + str(5554 + fd) + "\n" for fd in range(40))
        with self.assertRaisesRegex(RuntimeError, "Truncated"):
            self.active([result("431\n"), result(self.ps), result(rows)])
        report = H.read(self.directory / "listener-observation.json")
        self.assertGreater(report["droppedStructuredRows"], 0)
        self.assertLessEqual((self.directory / "listener-observation.json").stat().st_size, H.CAP)
        (self.directory / "process-observation.json").unlink()
        with patch.object(H, "capture", side_effect=[result("431\n"), result(self.ps), result("p431\nf3\nn*:5554\nf4\nn*:5555\n")]), self.assertRaisesRegex(RuntimeError, "Duplicate"):
            H.active(self.directory, self.observer, time.monotonic() + 28)
        with self.assertRaisesRegex(RuntimeError, "deadline"):
            H.checked_result(result(), time.monotonic() - 1)
        self.assertFalse((self.directory / "active.json").exists())
        self.assertFalse((self.directory / "handoff.json").exists())

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
