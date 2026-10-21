"""Offline tests for replay.py: python3 -I -m unittest research/visual-precheck-qualification/test_replay.py"""

import json
import os
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import replay  # noqa: E402


def png(width, height):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    raw = b"".join(b"\x00" + b"\x00" * width for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def corpus_items():
    items = []
    for case in replay.CASES:
        for label, n in replay.MIN_COVERAGE.items():
            for i in range(n):
                items.append({"id": f"{case}-{label}-{i}", "case": case, "file": f"items/{case}-{label}-{i}.png",
                              "condition": "the loading spinner is gone", "label": label, "source": "run1-01"})
    return items


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.mkdir(os.path.join(self.dir.name, "items"))
        self.items = corpus_items()
        for item in self.items:
            with open(os.path.join(self.dir.name, item["file"]), "wb") as f:
                f.write(png(300, 300))

    def tearDown(self):
        self.dir.cleanup()

    def write(self, items):
        path = os.path.join(self.dir.name, "manifest.json")
        with open(path, "w") as f:
            json.dump({"platform": "Android TV", "items": items}, f)
        return path

    def test_accepts_minimum_coverage_and_ignores_source_field(self):
        platform, items = replay.load_manifest(self.write(self.items))
        self.assertEqual(platform, "Android TV")
        self.assertEqual(len(items), 30)

    def test_rejects_unknown_case(self):
        self.items[0]["case"] = "logo-shown"
        with self.assertRaisesRegex(ValueError, "unknown case"):
            replay.load_manifest(self.write(self.items))

    def test_rejects_unknown_label(self):
        self.items[0]["label"] = "maybe"
        with self.assertRaisesRegex(ValueError, "unknown label"):
            replay.load_manifest(self.write(self.items))

    def test_rejects_duplicate_id(self):
        self.items[1]["id"] = self.items[0]["id"]
        with self.assertRaisesRegex(ValueError, "duplicate id"):
            replay.load_manifest(self.write(self.items))

    def test_rejects_missing_file(self):
        self.items[0]["file"] = "items/nope.png"
        with self.assertRaisesRegex(ValueError, "missing file"):
            replay.load_manifest(self.write(self.items))

    def test_rejects_file_outside_corpus(self):
        self.items[0]["file"] = "../outside.png"
        with self.assertRaisesRegex(ValueError, "missing file"):
            replay.load_manifest(self.write(self.items))

    def test_rejects_under_coverage(self):
        dropped = next(i for i in self.items if i["case"] == "poster-loaded" and i["label"] == "ambiguous")
        self.items.remove(dropped)
        with self.assertRaisesRegex(ValueError, "under-coverage: poster-loaded has 1 ambiguous"):
            replay.load_manifest(self.write(self.items))


class ScoreTest(unittest.TestCase):
    def test_counts_each_outcome(self):
        rows = [
            {"case": "spinner-gone", "label": "fail", "probability": 0.95, "latency_ms": 900},
            {"case": "spinner-gone", "label": "pass", "probability": 0.05, "latency_ms": 2500},
            {"case": "poster-loaded", "label": "ambiguous", "probability": 0.95, "latency_ms": 1000},
            {"case": "poster-loaded", "label": "pass", "probability": 0.5, "latency_ms": 1100},
            {"case": "card-highlighted", "label": "pass", "probability": 0.92, "latency_ms": 800},
            {"case": "card-highlighted", "label": "fail", "error": "http-503", "latency_ms": 50},
        ]
        summary = replay.score(rows, 0.9, 0.1)
        overall = summary["overall"]
        self.assertEqual(overall["false_passes"], 1)
        self.assertEqual(overall["false_fails"], 1)
        self.assertEqual(overall["ambiguous_accepts"], 1)
        self.assertEqual(overall["uncertain"], 1)
        self.assertEqual(overall["uncertain_rate"], 0.2)
        self.assertEqual(overall["errors"], 1)
        self.assertEqual(overall["over_2s"], 1)
        self.assertEqual(overall["p50_ms"], 1000)
        self.assertEqual(overall["p95_ms"], 2500)
        self.assertEqual(summary["spinner-gone"]["false_passes"], 1)
        self.assertEqual(summary["card-highlighted"]["errors"], 1)
        self.assertEqual(replay.verdict(summary)[0], "fail")

    def test_verdict(self):
        ok = replay.score([{"case": "spinner-gone", "label": "fail", "probability": 0.05, "latency_ms": 1}], 0.9, 0.1)
        self.assertEqual(replay.verdict(ok)[0], "pass")
        errors = replay.score([{"case": "spinner-gone", "label": "pass", "error": "http-400"}], 0.9, 0.1)
        self.assertEqual(replay.verdict(errors)[0], "fail")


class RequestTest(unittest.TestCase):
    def test_build_request_shape(self):
        headers, body = replay.build_request("jev-latest", "Android TV", "the loading spinner is gone", "ref-1", "QUJD")
        self.assertEqual(headers["TypeSafe-Danger-Zone-Images"], "true")
        self.assertNotIn("Authorization", headers)
        self.assertEqual(body["model"], "jev-latest")
        self.assertEqual(body["state"], {"screenshot": "ref-1"})
        self.assertEqual(body["assets"], {"ref-1": {"type": "image", "format": "png", "data_base64": "QUJD"}})
        question = body["questions"]["condition"]
        self.assertEqual(question["type"], "noul")
        self.assertTrue(question["instructions"].startswith("Using the current screenshot of an Android TV app"))
        self.assertTrue(question["instructions"].endswith("Condition: the loading spinner is gone"))
        self.assertEqual(question["criteria"], replay.NOUL_CRITERIA)
        self.assertNotIn("accessibility_tree", json.dumps(body))
        # The reference must appear exactly once outside the assets key: each use adds an image copy.
        self.assertEqual(json.dumps({k: v for k, v in body.items() if k != "assets"}).count("ref-1"), 1)


class ImageTest(unittest.TestCase):
    def test_prepare_image_limits(self):
        with tempfile.TemporaryDirectory() as tmp:
            good, small = os.path.join(tmp, "good.png"), os.path.join(tmp, "small.png")
            with open(good, "wb") as f:
                f.write(png(400, 300))
            with open(small, "wb") as f:
                f.write(png(400, 200))
            data, size = replay.prepare_image(good, tmp)
            self.assertEqual(size, (400, 300))
            self.assertTrue(data)
            with self.assertRaisesRegex(ValueError, "outside"):
                replay.prepare_image(small, tmp)
            with open(small, "wb") as f:
                f.write(b"not an image")
            with self.assertRaisesRegex(ValueError, "not a PNG"):
                replay.prepare_image(small, tmp)

    @unittest.skipUnless(sys.platform == "darwin", "sips is macOS only")
    def test_prepare_image_scales_tv_capture(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "tv.png")
            with open(path, "wb") as f:
                f.write(png(1920, 1080))
            _, size = replay.prepare_image(path, tmp)
            self.assertEqual(size[0], 1568)
            self.assertIn(size[1], (882, 883))


if __name__ == "__main__":
    unittest.main()
