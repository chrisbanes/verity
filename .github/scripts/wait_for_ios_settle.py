"""Give the selected simulator up to 60 seconds to settle after boot."""

import subprocess
import sys
import time


def simulator_cpu(output, udid):
    processes = [line.split(None, 3) for line in output.splitlines()]
    processes = [fields for fields in processes if len(fields) == 4]
    owner = next(
        (pid for pid, _, _, command in processes
         if "launchd_sim" in command and udid.lower() in command.lower()),
        None,
    )
    if owner is None:
        return None
    return sum(float(cpu) for _, parent, cpu, _ in processes if parent == owner)


def wait_for_settle(udid, timeout_seconds=60):
    deadline = time.monotonic() + timeout_seconds
    consecutive = 0
    print(f"Waiting up to {timeout_seconds}s for simulator {udid} to settle", flush=True)
    while (remaining := deadline - time.monotonic()) > 0:
        cpu = None
        try:
            result = subprocess.run(
                ["ps", "-Aww", "-o", "pid=,ppid=,pcpu=,command="],
                capture_output=True, text=True, check=True,
                timeout=min(5, remaining),
            )
            cpu = simulator_cpu(result.stdout, udid)
        except (OSError, subprocess.SubprocessError, ValueError) as error:
            print(f"::warning::Could not sample simulator CPU: {error}", flush=True)
        consecutive = consecutive + 1 if cpu is not None and cpu < 20 else 0
        print(f"Simulator background CPU={cpu}%; settled samples={consecutive}/3", flush=True)
        if consecutive >= 3:
            print("Simulator settled below 20% CPU for three consecutive samples", flush=True)
            return
        time.sleep(min(2, max(0, deadline - time.monotonic())))
    print(f"::warning::Simulator did not settle within {timeout_seconds}s; continuing to smoke tests", flush=True)


if __name__ == "__main__":
    wait_for_settle(sys.argv[1])
