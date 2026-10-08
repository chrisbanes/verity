"""Bind an action-managed smoke-test target to this CI job, commit and attempt."""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import uuid


def target_binding(kind):
    if kind == "android":
        target = os.environ["VERITY_PACKAGED_ANDROID_SERIAL"]
        if target != os.environ["ANDROID_SERIAL"] or not target.startswith("emulator-"):
            raise ValueError("Target must match the emulator action's serial")
        output = subprocess.run(["adb", "devices"], check=True, capture_output=True, text=True).stdout
        online = [row.split()[0] for row in output.splitlines()[1:]
                  if len(row.split()) >= 2 and row.split()[1] == "device"]
        if online != [target]:
            raise ValueError("Expected exactly the configured online emulator")
        details = {}
    else:
        target = os.environ["VERITY_PACKAGED_IOS_UDID"]
        uuid.UUID(target)
        output = subprocess.run(
            ["xcrun", "simctl", "list", "devices", "available", "-j"],
            check=True, capture_output=True, text=True,
        ).stdout
        matches = [(runtime, device) for runtime, devices in json.loads(output)["devices"].items()
                   for device in devices if device["udid"] == target]
        if len(matches) != 1:
            raise ValueError("Expected the simulator action's available target")
        runtime, device = matches[0]
        if not runtime.startswith("com.apple.CoreSimulator.SimRuntime.iOS-") or device["state"] != "Booted":
            raise ValueError("Expected a booted iOS simulator")
        details = dict(runtime=runtime, model=device["name"])
    return dict(
        target=target, kind=kind, host=platform.system(), arch=platform.machine(),
        run=os.environ["GITHUB_RUN_ID"], attempt=os.environ["GITHUB_RUN_ATTEMPT"],
        job=os.environ["GITHUB_JOB"], head=os.environ["GITHUB_SHA"], **details,
    )


def write_receipt(kind):
    binding = target_binding(kind)
    path = Path(os.environ["VERITY_PACKAGED_TARGET_RECEIPT"])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(binding) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("android", "ios"))
    write_receipt(parser.parse_args().kind)
