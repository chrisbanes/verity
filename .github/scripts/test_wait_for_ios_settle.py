import contextlib
import io
import subprocess
import unittest
from unittest.mock import patch

from wait_for_ios_settle import simulator_cpu, wait_for_settle


class SimulatorSettleTest(unittest.TestCase):
    def test_cpu_belongs_only_to_selected_simulator(self):
        output = """10 1 0.0 /launchd_sim --udid OWNED
11 10 8.0 /SpringBoard
12 10 7.5 /background-daemon
20 1 0.0 /launchd_sim --udid other
21 20 99.0 /other-daemon
"""
        self.assertEqual(simulator_cpu(output, "owned"), 15.5)
        self.assertIsNone(simulator_cpu(output, "missing"))

    def test_busy_sample_resets_consecutive_settled_samples(self):
        now = [0]
        samples = iter([0, 5, 80, 0, 0, 0])

        def sample(*args, **kwargs):
            return subprocess.CompletedProcess(args, 0, f"10 1 0 /launchd_sim owned\n11 10 {next(samples)} /daemon")

        with patch("wait_for_ios_settle.time.monotonic", side_effect=lambda: now[0]), \
                patch("wait_for_ios_settle.time.sleep", side_effect=lambda seconds: now.__setitem__(0, now[0] + seconds)), \
                patch("wait_for_ios_settle.subprocess.run", side_effect=sample) as run, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            wait_for_settle("owned")
        self.assertEqual(run.call_count, 6)
        self.assertIn("Simulator settled below", output.getvalue())

    def test_unavailable_or_stalled_sampler_stays_within_deadline(self):
        for stalled in [False, True]:
            with self.subTest(stalled=stalled):
                now = [0]

                def sample(*args, timeout, **kwargs):
                    if stalled:
                        now[0] += timeout
                        raise subprocess.TimeoutExpired(args, timeout)
                    return subprocess.CompletedProcess(args, 0, "")

                with patch("wait_for_ios_settle.time.monotonic", side_effect=lambda: now[0]), \
                        patch("wait_for_ios_settle.time.sleep", side_effect=lambda seconds: now.__setitem__(0, now[0] + seconds)), \
                        patch("wait_for_ios_settle.subprocess.run", side_effect=sample), \
                        contextlib.redirect_stdout(io.StringIO()) as output:
                    wait_for_settle("owned", timeout_seconds=9)
                self.assertEqual(now[0], 9)
                self.assertIn("continuing to smoke tests", output.getvalue())


if __name__ == "__main__":
    unittest.main()
