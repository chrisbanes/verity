"""Deterministic release inputs and fail-closed publication/formula gates.

Only the explicit `publish` command contacts GitHub. Build, verify and formula
commands are offline; publication tests use simulated provider/download data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

REPOSITORY = "chrisbanes/verity"
SUFFIXES = ("", "-macos-aarch64", "-linux-x86_64")


def canonical(value):
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


def checked_version(version):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?", version):
        raise ValueError("Invalid release version")
    return version


def checksum_name(version):
    return f"verity-{checked_version(version)}-checksums.sha256"


def checksum_bytes(manifest):
    return "".join(f'{a["sha256"]}  {a["name"]}\n' for a in sorted(manifest["assets"], key=lambda a: a["name"])).encode()


def asset_record(path):
    path = Path(path)
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return dict(name=path.name, size=path.stat().st_size, sha256=digest.hexdigest())


def build_asset_manifest(version, files):
    checked_version(version)
    records = [asset_record(path) for path in files]
    names = [f"verity-{version}{suffix}.jar" for suffix in SUFFIXES]
    if sorted(r["name"] for r in records) != sorted(names):
        raise ValueError("Expected exactly three versioned host/universal JARs")
    return dict(schema=1, version=version, tag="v" + version,
                assets=sorted(records, key=lambda r: r["name"]))


def expected_assets(manifest):
    version = checked_version(manifest["version"])
    if manifest.get("schema") != 1 or manifest.get("tag") != "v" + version:
        raise ValueError("Manifest tag/version mismatch")
    assets = manifest["assets"]
    names = [f"verity-{version}{suffix}.jar" for suffix in SUFFIXES]
    if sorted(r["name"] for r in assets) != sorted(names):
        raise ValueError("Manifest JAR set mismatch")
    for asset in assets:
        if set(asset) != {"name", "size", "sha256"} or asset["size"] <= 0 or not re.fullmatch("[a-f0-9]{64}", asset["sha256"]):
            raise ValueError("Malformed manifest asset")
    data = checksum_bytes(manifest)
    checksum = dict(name=checksum_name(version), size=len(data), sha256=hashlib.sha256(data).hexdigest())
    return sorted([*assets, checksum], key=lambda r: r["name"])


def download_url(tag, name):
    return f"https://github.com/{REPOSITORY}/releases/download/{tag}/{name}"


def check_provider(manifest, release):
    expected = expected_assets(manifest)
    if release.get("tag_name") != manifest["tag"] or release.get("draft") or release.get("prerelease"):
        raise ValueError("Wrong release tag or unpublished release")
    assets = release["assets"]
    if sorted(a["name"] for a in assets) != sorted(a["name"] for a in expected):
        raise ValueError("Published release must have exactly four matching assets")
    by_name = {a["name"]: a for a in assets}
    for asset in expected:
        actual = by_name[asset["name"]]
        if actual["size"] != asset["size"] or actual["browser_download_url"] != download_url(manifest["tag"], asset["name"]) or actual.get("state") != "uploaded":
            raise ValueError("Published asset size/URL/upload state mismatch")
    return expected


def verify_published_assets(manifest, release, downloads):
    expected = check_provider(manifest, release)
    downloads = Path(downloads)
    if sorted(p.name for p in downloads.iterdir()) != sorted(a["name"] for a in expected):
        raise ValueError("Downloaded asset set mismatch")
    for asset in expected:
        if asset_record(downloads / asset["name"]) != asset:
            raise ValueError("Downloaded bytes disagree with build/upload inputs")
    return dict(schema=1, verified=True, tag=manifest["tag"], version=manifest["version"],
                assets=[dict(**a, url=download_url(manifest["tag"], a["name"])) for a in expected])


def render_formula(manifest, receipt, template):
    expected = dict(schema=1, verified=True, tag=manifest["tag"], version=manifest["version"],
                    assets=[dict(**a, url=download_url(manifest["tag"], a["name"])) for a in expected_assets(manifest)])
    if receipt != expected:
        raise ValueError("A matching independently downloaded publication receipt is required")
    by_name = {a["name"]: a for a in manifest["assets"]}
    tokens = {"VERSION_PLACEHOLDER": manifest["version"]}
    for suffix, token in zip(SUFFIXES, ("UNIVERSAL_SHA_PLACEHOLDER", "MACOS_SHA_PLACEHOLDER", "LINUX_SHA_PLACEHOLDER")):
        tokens[token] = by_name[f'verity-{manifest["version"]}{suffix}.jar']["sha256"]
    for token, value in tokens.items():
        if template.count(token) != 1:
            raise ValueError("Formula template token missing or duplicated: " + token)
        template = template.replace(token, value)
    if "PLACEHOLDER" in template:
        raise ValueError("Unresolved formula token")
    return template


def publish_assets(manifest, directory, receipt_path, run=subprocess.run):
    """New release upload or exact verified reuse; never overwrite assets."""
    directory = Path(directory)
    receipt_path = Path(receipt_path)
    receipt_path.unlink(missing_ok=True)  # A failed attempt cannot reuse a stale gate.
    assets = expected_assets(manifest)
    for asset in assets:
        if asset_record(directory / asset["name"]) != asset:
            raise ValueError("Local publication input changed")
    def gh(*args, check=True):
        result = run(["gh", *args], capture_output=True, text=True, timeout=600)
        if check and result.returncode:
            raise RuntimeError("GitHub operation failed: " + result.stderr)
        return result
    endpoint = f"repos/{REPOSITORY}/releases/tags/{manifest['tag']}"
    result = gh("api", endpoint, check=False)
    if result.returncode:
        if "(HTTP 404)" not in result.stderr:
            raise RuntimeError("Release lookup failed: " + result.stderr)
        gh("release", "create", manifest["tag"], *[str(directory / a["name"]) for a in assets],
           "--repo", REPOSITORY, "--verify-tag", "--generate-notes")
    else:
        check_provider(manifest, json.loads(result.stdout))
    release = json.loads(gh("api", endpoint).stdout)
    check_provider(manifest, release)
    with tempfile.TemporaryDirectory(prefix="verity-release-readback-") as temporary:
        for asset in assets:
            gh("release", "download", manifest["tag"], "--repo", REPOSITORY,
               "--dir", temporary, "--pattern", asset["name"])
        receipt = verify_published_assets(manifest, release, temporary)
    receipt_path.write_bytes(canonical(receipt))
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build")
    build.add_argument("--version", required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("files", type=Path, nargs=3)
    publish = commands.add_parser("publish")
    publish.add_argument("--manifest", type=Path, required=True)
    publish.add_argument("--receipt", type=Path, required=True)
    publish.add_argument("--assets", type=Path, required=True)
    formula = commands.add_parser("formula")
    formula.add_argument("--manifest", type=Path, required=True)
    formula.add_argument("--receipt", type=Path, required=True)
    formula.add_argument("--template", type=Path, required=True)
    formula.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "build":
        manifest = build_asset_manifest(args.version, args.files)
        assets = args.output / "assets"
        assets.mkdir(parents=True, exist_ok=True)
        # Keep the upload directory an exact four-file set across version changes.
        for path in assets.iterdir():
            if not path.is_file():
                raise ValueError("Unexpected directory in release output")
            path.unlink()
        for path in args.files:
            shutil.copyfile(path, assets / path.name)
        (assets / checksum_name(args.version)).write_bytes(checksum_bytes(manifest))
        (args.output / "manifest.json").write_bytes(canonical(manifest))
    elif args.command == "publish":
        publish_assets(json.loads(args.manifest.read_text()), args.assets, args.receipt)
    else:
        rendered = render_formula(json.loads(args.manifest.read_text()), json.loads(args.receipt.read_text()), args.template.read_text())
        args.output.write_text(rendered)


if __name__ == "__main__":
    main()
