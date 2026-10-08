"""Target binding fixtures; never launch devices."""
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import ci_target_receipt as receipt

UDID = "11111111-1111-4111-8111-111111111111"


class TargetReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "targets/receipt.json"
        env = dict(
            GITHUB_RUN_ID="1", GITHUB_RUN_ATTEMPT="2", GITHUB_JOB="smoke", GITHUB_SHA="head",
            VERITY_PACKAGED_TARGET_RECEIPT=str(self.path),
            VERITY_PACKAGED_ANDROID_SERIAL="emulator-5554", ANDROID_SERIAL="emulator-5554",
            VERITY_PACKAGED_IOS_UDID=UDID,
        )
        patcher = patch.dict(os.environ, env, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_android_binding_and_receipt(self):
        with patch.object(receipt.subprocess, "run", return_value=SimpleNamespace(stdout="List of devices attached\nemulator-5554 device\n")):
            receipt.write_receipt("android")
        binding = json.loads(self.path.read_text())
        self.assertEqual(binding["target"], "emulator-5554")
        self.assertEqual(binding["kind"], "android")
        self.assertEqual([binding[key] for key in ("run", "attempt", "job", "head")], ["1", "2", "smoke", "head"])

    def test_android_rejects_mismatch_offline_and_extra_device(self):
        for serial, output in [
            ("emulator-5556", "emulator-5554 device\n"),
            ("emulator-5554", "emulator-5554 offline\n"),
            ("emulator-5554", "emulator-5554 device\nphysical device\n"),
        ]:
            with self.subTest(serial=serial, output=output), patch.dict(os.environ, ANDROID_SERIAL=serial), patch.object(receipt.subprocess, "run", return_value=SimpleNamespace(stdout="List of devices attached\n" + output)):
                with self.assertRaises(ValueError):
                    receipt.write_receipt("android")
                self.assertFalse(self.path.exists())

    def ios_output(self, runtime="iOS-26-0", state="Booted", udid=UDID):
        return json.dumps({"devices": {"com.apple.CoreSimulator.SimRuntime." + runtime: [
            dict(udid=udid, state=state, name="iPhone 17", isAvailable=True),
        ]}})

    def test_ios_binds_supplied_action_target(self):
        with patch.object(receipt.subprocess, "run", return_value=SimpleNamespace(stdout=self.ios_output())):
            receipt.write_receipt("ios")
        binding = json.loads(self.path.read_text())
        self.assertEqual(binding["target"], UDID)
        self.assertEqual(binding["kind"], "ios")
        self.assertEqual(binding["runtime"], "com.apple.CoreSimulator.SimRuntime.iOS-26-0")

    def test_ios_rejects_wrong_runtime_shutdown_or_missing_target(self):
        for output in [self.ios_output(runtime="tvOS-26-0"), self.ios_output(state="Shutdown"), self.ios_output(udid="other")]:
            with self.subTest(output=output), patch.object(receipt.subprocess, "run", return_value=SimpleNamespace(stdout=output)):
                with self.assertRaises(ValueError):
                    receipt.write_receipt("ios")
                self.assertFalse(self.path.exists())


if __name__ == "__main__":
    unittest.main()
