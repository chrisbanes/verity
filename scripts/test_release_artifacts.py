"""Offline fixtures: publication metadata/downloads are simulated, never live."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest

import release_artifacts as release

ROOT = Path(__file__).resolve().parents[1]
RUBY_HARNESS = r'''
require "json"
module OS
  def self.mac?; ENV.fetch("HOST_OS") == "mac"; end
  def self.linux?; ENV.fetch("HOST_OS") == "linux"; end
end
module Hardware
  module CPU
    def self.arm?; ENV.fetch("HOST_CPU") == "arm"; end
    def self.intel?; ENV.fetch("HOST_CPU") == "intel"; end
  end
end
class Sink
  def initialize(path); @path = path; end
  def to_s; @path; end
  def /(name); Sink.new(@path + "/" + name); end
  def install(mapping)
    raise "wrong canonical install" unless mapping.values == ["verity.jar"]
    source = mapping.keys.fetch(0)
    raise "wrong selected basename" unless File.file?(source)
    Dir.mkdir(@path) unless Dir.exist?(@path)
    File.write(@path + "/verity.jar", File.read(source))
  end
  def write(text)
    raise "launcher target changed" unless text.include?('libexec/verity.jar') && text.include?('"$@"')
    Dir.mkdir("bin") unless Dir.exist?("bin")
    File.write(@path, text)
  end
end
class Formula
  class << self
    attr_reader :selected_url, :selected_sha, :strategy
    def desc(*); end
    def homepage(*); end
    def license(*); end
    def depends_on(*); end
    def test; end
    def version(value = nil); @version = value if value; @version; end
    def url(value, using:); @selected_url = value; @strategy = using; end
    def sha256(value); @selected_sha = value; end
    def on_macos; yield if OS.mac?; end
    def on_linux; yield if OS.linux?; end
    def on_arm; yield if Hardware::CPU.arm?; end
    def on_intel; yield if Hardware::CPU.intel?; end
    def [](name); Struct.new(:opt_prefix).new("jdk"); end
  end
  def version; self.class.version; end
  def libexec; Sink.new("libexec"); end
  def bin; Sink.new("bin"); end
end
load ARGV.fetch(0)
raise "JAR unpacking enabled" unless Verity.strategy == :nounzip
Verity.new.install
puts JSON.generate(url: Verity.selected_url, sha256: Verity.selected_sha, version: Verity.version,
                   installed: File.read("libexec/verity.jar"), launcher: File.read("bin/verity"))
'''


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.files = []
        for suffix in release.SUFFIXES:
            path = self.root / f"verity-0.1.0{suffix}.jar"
            path.write_bytes(("simulated-jar" + suffix).encode())
            self.files.append(path)
        self.manifest = release.build_asset_manifest("0.1.0", self.files)
        (self.root / release.checksum_name("0.1.0")).write_bytes(release.checksum_bytes(self.manifest))
        self.provider = dict(tag_name="v0.1.0", draft=False, prerelease=False, assets=[
            dict(name=a["name"], size=a["size"], state="uploaded", browser_download_url=release.download_url("v0.1.0", a["name"]))
            for a in release.expected_assets(self.manifest)])
        self.receipt = release.verify_published_assets(self.manifest, self.provider, self.root)
        self.template = (ROOT / "Formula/verity.rb").read_text()

    def test_manifest_is_deterministic_and_exact(self):
        self.assertEqual(release.canonical(self.manifest), release.canonical(release.build_asset_manifest("0.1.0", reversed(self.files))))
        for asset in self.manifest["assets"]:
            self.assertEqual(asset, release.asset_record(self.root / asset["name"]))
        lines = release.checksum_bytes(self.manifest).decode().splitlines()
        self.assertEqual(len(lines), 3)
        for line in lines:
            digest, name = line.split("  ")
            self.assertEqual(digest, release.asset_record(self.root / name)["sha256"])
        self.assertEqual(release.checksum_name("0.1.0"), "verity-0.1.0-checksums.sha256")
        self.assertEqual(len(release.expected_assets(self.manifest)), 4)
        self.assertFalse(any(a["name"].endswith(".json") for a in release.expected_assets(self.manifest)))
        for files in (self.files[:-1], self.files + [self.files[0]]):
            with self.assertRaises(ValueError): release.build_asset_manifest("0.1.0", files)
        for version in ("v0.1.0", "../0.1.0", "0.1.0\n", "$(bad)"):
            with self.assertRaises(ValueError): release.checked_version(version)

    def test_provider_and_download_mismatches_fail(self):
        variants = []
        for field, value in (("tag_name", "v0.2.0"), ("draft", True), ("prerelease", True)):
            changed = copy.deepcopy(self.provider); changed[field] = value; variants.append(changed)
        for field, value in (("size", 999), ("browser_download_url", "https://invalid/old.jar"), ("state", "new")):
            changed = copy.deepcopy(self.provider); changed["assets"][0][field] = value; variants.append(changed)
        changed = copy.deepcopy(self.provider); changed["assets"].pop(); variants.append(changed)
        changed = copy.deepcopy(self.provider); changed["assets"].append(changed["assets"][0]); variants.append(changed)
        for changed in variants:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                release.verify_published_assets(self.manifest, changed, self.root)
        path = self.files[0]; original = path.read_bytes(); path.write_bytes(b"x" * len(original))
        with self.assertRaises(ValueError): release.verify_published_assets(self.manifest, self.provider, self.root)
        path.write_bytes(original)
        path.unlink()
        with self.assertRaises(ValueError): release.verify_published_assets(self.manifest, self.provider, self.root)
        path.write_bytes(original)
        extra = self.root / "unexpected.jar"
        extra.write_bytes(b"extra")
        with self.assertRaises(ValueError): release.verify_published_assets(self.manifest, self.provider, self.root)
        extra.unlink()
        checksum = self.root / release.checksum_name("0.1.0")
        checksum.write_bytes(b"bad checksum download")
        with self.assertRaises(ValueError): release.verify_published_assets(self.manifest, self.provider, self.root)

    def test_every_receipt_failure_prevents_formula_output(self):
        for field in ("verified", "tag", "version", "schema", "assets"):
            changed = copy.deepcopy(self.receipt); changed.pop(field)
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.render_formula(self.manifest, changed, self.template)
        for field in ("name", "size", "sha256", "url"):
            changed = copy.deepcopy(self.receipt); changed["assets"][0][field] = "wrong"
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.render_formula(self.manifest, changed, self.template)
        changed = copy.deepcopy(self.manifest); changed["tag"] = "v0.2.0"
        with self.assertRaises(ValueError): release.render_formula(changed, self.receipt, self.template)
        rendered = release.render_formula(self.manifest, self.receipt, self.template)
        self.assertEqual(rendered, release.render_formula(self.manifest, self.receipt, self.template))
        self.assertNotIn("PLACEHOLDER", rendered)
        self.assertLess(rendered.index('version "0.1.0"'), rendered.index('url "'))

    def provider_runner(self, existing=True, corrupt_download=False, lookup_failure=False, create_failure=False):
        calls = []
        def run(args, **kwargs):
            self.assertEqual(args[0], "gh"); self.assertEqual(kwargs["timeout"], 600)
            calls.append(args[1:])
            command = args[1:]
            if command[0] == "api":
                if lookup_failure: return SimpleNamespace(returncode=1, stdout="", stderr="(HTTP 403)")
                if not existing and len(calls) == 1: return SimpleNamespace(returncode=1, stdout="", stderr="(HTTP 404)")
                return SimpleNamespace(returncode=0, stdout=json.dumps(self.provider), stderr="")
            if command[:2] == ["release", "create"]:
                if create_failure: return SimpleNamespace(returncode=1, stdout="", stderr="upload failed")
                self.assertEqual(sorted(Path(p).name for p in command[3:7]), sorted(a['name'] for a in release.expected_assets(self.manifest)))
            elif command[:2] == ["release", "download"]:
                target = Path(command[command.index("--dir") + 1]); name = command[command.index("--pattern") + 1]
                shutil.copyfile(self.root / name, target / name)
                if corrupt_download: (target / name).write_bytes(b"corrupt")
            else: self.fail("Unexpected remote fixture command")
            self.assertNotIn("--clobber", command)
            return SimpleNamespace(returncode=0, stdout="", stderr="")
        return run, calls

    def test_new_release_upload_then_readback_and_exact_existing_reuse(self):
        for existing in (False, True):
            with self.subTest(existing=existing):
                runner, calls = self.provider_runner(existing)
                receipt = self.root.parent / (self.root.name + "-receipt.json")
                self.addCleanup(receipt.unlink, missing_ok=True)
                result = release.publish_assets(self.manifest, self.root, receipt, runner)
                self.assertEqual(result, self.receipt)
                self.assertEqual(json.loads(receipt.read_text()), self.receipt)
                self.assertEqual(sum(c[:2] == ["release", "create"] for c in calls), 0 if existing else 1)
                download = next(i for i,c in enumerate(calls) if c[:2] == ["release", "download"])
                self.assertEqual(calls[download-1][0], "api")

    def test_publication_failures_remove_stale_receipt_and_never_overwrite(self):
        cases = [dict(existing=False, lookup_failure=True), dict(existing=False, create_failure=True), dict(existing=True, corrupt_download=True)]
        for options in cases:
            runner, calls = self.provider_runner(**options)
            receipt = self.root.parent / (self.root.name + "-bad-receipt.json")
            receipt.write_text("stale")
            with self.assertRaises((ValueError, RuntimeError)):
                release.publish_assets(self.manifest, self.root, receipt, runner)
            self.assertFalse(receipt.exists())
        self.provider["assets"].pop()
        runner, calls = self.provider_runner()
        receipt = self.root.parent / (self.root.name + "-bad-receipt.json")
        with self.assertRaises(ValueError): release.publish_assets(self.manifest, self.root, receipt, runner)
        self.assertEqual(len(calls), 1)

    def test_ruby_executes_selection_and_actual_install_for_matrix_and_fallbacks(self):
        import os
        rendered = release.render_formula(self.manifest, self.receipt, self.template)
        for host, cpu, suffix in (("mac","arm","-macos-aarch64"), ("mac","intel",""), ("linux","intel","-linux-x86_64"), ("linux","arm",""), ("other","intel",""), ("other","arm","")):
            with self.subTest(host=host,cpu=cpu), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp); formula = directory / 'verity.rb'; formula.write_text(rendered)
                harness = directory / 'harness.rb'; harness.write_text(RUBY_HARNESS)
                name=f"verity-0.1.0{suffix}.jar"; (directory/name).write_bytes((self.root/name).read_bytes())
                result=subprocess.run(['ruby',str(harness),str(formula)],cwd=directory,env=dict(os.environ,HOST_OS=host,HOST_CPU=cpu),capture_output=True,text=True,timeout=30)
                self.assertEqual(result.returncode,0,result.stderr)
                actual=json.loads(result.stdout)
                self.assertEqual(actual['url'],release.download_url('v0.1.0',name))
                self.assertEqual(actual['sha256'],release.asset_record(self.root/name)['sha256'])
                self.assertEqual(actual['installed'],(self.root/name).read_text())
                self.assertEqual(actual['version'],'0.1.0')
                self.assertIn('libexec/verity.jar',actual['launcher'])
                # A decompression or wrong selected basename must fail this same executed harness.
                for invalid in (rendered.replace('using: :nounzip','using: :zip'),rendered.replace('libexec.install "verity-#{version}#{suffix}.jar"','libexec.install "missing.jar"')):
                    formula.write_text(invalid)
                    failed=subprocess.run(['ruby',str(harness),str(formula)],cwd=directory,env=dict(os.environ,HOST_OS=host,HOST_CPU=cpu),capture_output=True,text=True,timeout=30)
                    self.assertNotEqual(failed.returncode,0)

    def test_build_cli_repeats_exact_four_assets_and_keeps_metadata_internal(self):
        import sys
        output = self.root / "release-output"
        command = [sys.executable, str(ROOT / "scripts/release_artifacts.py"), "build", "--version", "0.1.0", "--output", str(output), *map(str, self.files)]
        snapshots = []
        for _ in range(2):
            result = subprocess.run(command, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            assets = output / "assets"
            snapshots.append({p.name: p.read_bytes() for p in assets.iterdir()})
            self.assertEqual(len(snapshots[-1]), 4)
            self.assertEqual(json.loads((output / "manifest.json").read_text()), self.manifest)
            self.assertNotIn("manifest.json", snapshots[-1])
        self.assertEqual(*snapshots)
        bad_receipt = output / "bad.json"
        bad_receipt.write_text("{}")
        formula = output / "verity.rb"
        result = subprocess.run([sys.executable, str(ROOT / "scripts/release_artifacts.py"), "formula", "--manifest", str(output / "manifest.json"), "--receipt", str(bad_receipt), "--template", str(ROOT / "Formula/verity.rb"), "--output", str(formula)], capture_output=True, text=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(formula.exists())

    def test_workflow_receipt_gates_tap_and_serializes_tag(self):
        workflow=(ROOT/'.github/workflows/release.yml').read_text()
        ordered=[':verity:cli:packageRelease','scripts/release_artifacts.py publish','scripts/release_artifacts.py formula','repository: chrisbanes/homebrew-tap','cp verity/cli/build/release/verity.rb','git push']
        indexes=[workflow.index(s) for s in ordered]
        self.assertEqual(indexes,sorted(indexes))
        self.assertIn('group: release-${{ github.ref }}',workflow)
        self.assertIn('cancel-in-progress: false',workflow)
        self.assertIn('--no-scan',workflow)
        self.assertIn('-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g',workflow)
        self.assertNotIn('sed -e',workflow)
        self.assertNotIn('if: always()',workflow)


if __name__ == '__main__':
    unittest.main()
