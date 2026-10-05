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


def checked(argv, deadline):
    result = capture(argv, deadline=deadline, limit=CAP)
    if result["outcome"] != "completed" or not result["joined"] or result["truncated"] or any(result["droppedBytes"].values()):
        raise RuntimeError("Unknown process/socket inspection")
    if time.monotonic() >= deadline:
        raise RuntimeError("Handoff deadline expired")
    return result


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
                                        "config": config, "beforeLaunchUnix": time.time()})


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
    sdk = Path(os.environ["ANDROID_HOME"]).resolve() / "emulator"
    executable = Path(argv[0]).resolve()
    if sdk not in executable.parents or executable.name not in ("emulator", "qemu-system-aarch64"):
        raise RuntimeError("Console listener is not the configured SDK emulator")
    if argv.count("-avd") != 1 or argv[argv.index("-avd") + 1] != AVD or argv.count("-port") != 1 or argv[argv.index("-port") + 1] != "5554":
        raise RuntimeError("Emulator argv does not identify the owned AVD/port")
    started = time.mktime(time.strptime(identity["started"], "%a %b %d %H:%M:%S %Y"))
    if started < int(pre["beforeLaunchUnix"]) or started > time.time():
        raise RuntimeError("Process predates owned launch")
    ports = checked(["lsof", "-nP", "-a", "-p", str(pid), "-iTCP", "-sTCP:LISTEN", "-Fn"], deadline)
    rows = ports["stdout"].splitlines()
    if ports["exit"] != 0 or ports["stderr"].strip() or any(not re.fullmatch(r"(?:p[0-9]+|n(?:\*|127\.0\.0\.1|\[::\]|\[::1\]):[0-9]+)", row) for row in rows):
        raise RuntimeError("Unknown owned listener inventory")
    if {row for row in rows if row.startswith("p")} != {"p" + str(pid)}:
        raise RuntimeError("Listener process collision")
    owned = sorted({int(row.rsplit(":", 1)[1]) for row in rows if row.startswith("n")})
    if not set(PORTS).issubset(owned):
        raise RuntimeError("Declared emulator ports not owned by exact process")
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
