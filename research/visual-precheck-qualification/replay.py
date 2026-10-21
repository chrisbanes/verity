#!/usr/bin/env python3
"""Qualify Jev screenshot pre-checks on a labelled Android TV corpus (issue #213).

Usage: TYPESAFE_API_KEY=... python3 -I replay.py <corpus>/manifest.json [--out results.json]

Sends one System One request per manifest item, in the request shape recorded in README.md, and
scores the answers against the human labels at the pre-check thresholds. The key is read from the
environment and is only sent in the Authorization header; it is never printed or written. Image
bytes and paths outside the corpus are never written either. Image scaling uses macOS `sips`.
"""

import argparse
import base64
import datetime
import hashlib
import http.client
import json
import os
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

CASES = ("spinner-gone", "poster-loaded", "card-highlighted")
LABELS = ("pass", "fail", "ambiguous")
MIN_COVERAGE = {"pass": 4, "fail": 4, "ambiguous": 2}
MIN_SIDE, MAX_SIDE = 280, 1568
MAX_IMAGE_BYTES = 16 * 1024 * 1024
IMAGES_HEADER = "TypeSafe-Danger-Zone-Images"
SLOW_MS = 2000  # production default pre-check timeout

# Same criteria as SystemOnePrecheck.NOUL_CRITERIA.
NOUL_CRITERIA = {"true": "The current screen satisfies the condition.",
                 "false": "The current screen does not satisfy the condition."}


def load_manifest(path):
    """Returns (platform, items) or raises ValueError. Unknown item fields (e.g. `source`) are ignored."""
    root = os.path.dirname(os.path.abspath(path))
    with open(path, encoding="utf-8") as f:
        manifest = json.load(f)
    items, seen = manifest["items"], set()
    counts = {(c, l): 0 for c in CASES for l in LABELS}
    for item in items:
        if item["case"] not in CASES:
            raise ValueError(f"{item['id']}: unknown case {item['case']!r}")
        if item["label"] not in LABELS:
            raise ValueError(f"{item['id']}: unknown label {item['label']!r}")
        if item["id"] in seen:
            raise ValueError(f"duplicate id {item['id']!r}")
        seen.add(item["id"])
        full = os.path.realpath(os.path.join(root, item["file"]))
        if not full.startswith(os.path.realpath(root) + os.sep) or not os.path.isfile(full):
            raise ValueError(f"{item['id']}: missing file {item['file']!r}")
        if not item.get("condition"):
            raise ValueError(f"{item['id']}: missing condition")
        counts[(item["case"], item["label"])] += 1
    for (case, label), n in counts.items():
        if n < MIN_COVERAGE[label]:
            raise ValueError(f"under-coverage: {case} has {n} {label}, needs {MIN_COVERAGE[label]}")
    return manifest.get("platform", "Android TV"), items


def png_size(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("not a PNG")
    return struct.unpack(">II", data[16:24])


def prepare_image(path, tmpdir):
    """Scales a PNG to at most MAX_SIDE per side (never upscales) and returns its base64."""
    with open(path, "rb") as f:
        data = f.read()
    width, height = png_size(data)
    if max(width, height) > MAX_SIDE:
        scaled = os.path.join(tmpdir, "scaled.png")
        subprocess.run(["sips", "-s", "format", "png", "--resampleHeightWidthMax", str(MAX_SIDE), path, "--out", scaled],
                       check=True, capture_output=True)
        with open(scaled, "rb") as f:
            data = f.read()
        width, height = png_size(data)
    if min(width, height) < MIN_SIDE or max(width, height) > MAX_SIDE:
        raise ValueError(f"image {width}x{height} outside {MIN_SIDE}-{MAX_SIDE} px")
    if len(data) > MAX_IMAGE_BYTES:
        raise ValueError("image over 16 MiB")
    return base64.b64encode(data).decode("ascii"), (width, height)


def instructions(platform, condition):
    return (f"Using the current screenshot of an {platform} app, does the screen satisfy this condition? "
            f"Earlier observations are reference only, not proof. Condition: {condition}")


def build_request(model, platform, condition, reference, image_base64):
    """Returns (headers without Authorization, body dict)."""
    headers = {"Content-Type": "application/json", IMAGES_HEADER: "true"}
    body = {
        "model": model,
        "state": {"screenshot": reference},
        "assets": {reference: {"type": "image", "format": "png", "data_base64": image_base64}},
        "questions": {"condition": {"type": "noul", "instructions": instructions(platform, condition),
                                    "criteria": NOUL_CRITERIA}},
    }
    return headers, body


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # urllib would forward the Authorization header to a redirect target; refuse redirects like the production client.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def error_detail(error):
    # Only a short server message; never the raw body, which could echo request content.
    try:
        payload = json.loads(error.read(4096))
        detail = next((payload[k] for k in ("error", "message", "detail") if isinstance(payload.get(k), str)), None)
        return detail[:200] if detail else None
    except Exception:
        return None


def ask(base_url, key, headers, body, timeout=15):
    """Returns a result dict with either probability/model or error kind; `retryable` and `auth` flag errors."""
    request = urllib.request.Request(f"{base_url}/v1/systemone", data=json.dumps(body).encode(), method="POST",
                                     headers={**headers, "Authorization": f"Bearer {key}"})
    started = time.monotonic()
    result = {}
    try:
        with OPENER.open(request, timeout=timeout) as response:
            answer = json.load(response)
        p = answer["answers"]["condition"]["noul"]
        if isinstance(p, bool) or not isinstance(p, (int, float)) or not 0.0 <= p <= 1.0:
            raise ValueError("noul out of range")
        result = {"probability": float(p), "model": answer.get("model")}
    except urllib.error.HTTPError as e:
        result = {"error": f"http-{e.code}", "retryable": e.code >= 500, "auth": e.code in (401, 403)}
        detail = error_detail(e)
        if detail:
            result["detail"] = detail
    except (urllib.error.URLError, http.client.HTTPException, TimeoutError, OSError) as e:
        result = {"error": type(e).__name__, "retryable": True}
    except (KeyError, TypeError, ValueError):
        result = {"error": "invalid-response"}
    result["latency_ms"] = round((time.monotonic() - started) * 1000)
    return result


def percentile(values, q):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, -(-len(ordered) * q // 100) - 1)]  # nearest rank


def score(rows, pass_at, fail_at):
    """Summarises rows (with case, label and probability or error) per case and overall."""
    def summarise(group):
        answered = [r for r in group if "probability" in r]
        confident = lambda r: r["probability"] >= pass_at or r["probability"] <= fail_at
        latencies = [r["latency_ms"] for r in answered if "latency_ms" in r]
        uncertain = sum(1 for r in answered if not confident(r))
        return {
            "items": len(group),
            "false_passes": sum(1 for r in answered if r["label"] == "fail" and r["probability"] >= pass_at),
            "false_fails": sum(1 for r in answered if r["label"] == "pass" and r["probability"] <= fail_at),
            "ambiguous_accepts": sum(1 for r in answered if r["label"] == "ambiguous" and confident(r)),
            "uncertain": uncertain,
            "uncertain_rate": round(uncertain / len(answered), 3) if answered else None,
            "errors": len(group) - len(answered),
            "p50_ms": percentile(latencies, 50),
            "p95_ms": percentile(latencies, 95),
            "over_2s": sum(1 for v in latencies if v > SLOW_MS),
        }
    summary = {case: summarise([r for r in rows if r["case"] == case]) for case in CASES}
    summary["overall"] = summarise(rows)
    return summary


def verdict(summary):
    overall = summary["overall"]
    answered = overall["items"] - overall["errors"]
    if answered == 0:
        return "fail", "every request errored: the image contract is not usable"
    if overall["false_passes"]:
        return "fail", f"{overall['false_passes']} false pass(es) at the thresholds"
    return "pass", f"0 false passes over {answered} answered requests"


def run_item(item, root, platform, opts, key):
    with tempfile.TemporaryDirectory() as tmpdir:
        try:
            image, size = prepare_image(os.path.join(root, item["file"]), tmpdir)
        except (ValueError, OSError, subprocess.CalledProcessError) as e:
            return {"error": "image", "detail": str(e)[:200]}, False
    headers, body = build_request(opts.model, platform, item["condition"], str(uuid.uuid4()), image)
    return {**ask(opts.base_url, key, headers, body, opts.timeout), "sent_size": f"{size[0]}x{size[1]}"}, True


def main():
    args = argparse.ArgumentParser()
    args.add_argument("manifest")
    args.add_argument("--base-url", default="https://api.typesafe.ai")
    args.add_argument("--model", default="jev-latest")
    args.add_argument("--pass-at", type=float, default=0.9)
    args.add_argument("--fail-at", type=float, default=0.1)
    args.add_argument("--timeout", type=float, default=15)
    args.add_argument("--out")
    opts = args.parse_args()
    key = os.environ.get("TYPESAFE_API_KEY")
    if not key:
        sys.exit("TYPESAFE_API_KEY is not set")

    with open(opts.manifest, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    platform, items = load_manifest(opts.manifest)
    root = os.path.dirname(os.path.abspath(opts.manifest))
    run_date = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    print(f"manifest_sha256={sha} items={len(items)} run_date={run_date} model={opts.model}", flush=True)

    rows, requests, retried, aborted = [], 0, [], None
    for item in items:
        result, sent = run_item(item, root, platform, opts, key)
        requests += sent
        rows.append({"id": item["id"], "case": item["case"], "label": item["label"], **result})
        print(f"{item['id']:24} {item['label']:9} {result.get('probability', result.get('error'))} "
              f"{result.get('latency_ms', '')}ms", flush=True)
        if result.get("auth"):
            aborted = f"authentication error {result['error']} on {item['id']}"
            break
    if not aborted:
        # One retry of transport errors, 5xx and timeouts only; never for an unwanted probability.
        for i, row in enumerate(rows):
            if not row.get("retryable"):
                continue
            item = next(it for it in items if it["id"] == row["id"])
            result, sent = run_item(item, root, platform, opts, key)
            requests += sent
            retried.append({"id": row["id"], "first_error": row["error"]})
            rows[i] = {"id": row["id"], "case": row["case"], "label": row["label"], **result, "retried": True}
            print(f"retry {row['id']:18} {result.get('probability', result.get('error'))}", flush=True)
            if result.get("auth"):
                aborted = f"authentication error {result['error']} on retry of {row['id']}"
                break

    summary = score(rows, opts.pass_at, opts.fail_at)
    print(f"\n{'case':18} {'n':>3} {'falsePass':>9} {'falseFail':>9} {'ambigAcc':>8} {'uncert%':>7} "
          f"{'errors':>6} {'p50ms':>6} {'p95ms':>6} {'>2s':>4}")
    for name, s in summary.items():
        rate = "-" if s["uncertain_rate"] is None else f"{s['uncertain_rate'] * 100:.0f}"
        print(f"{name:18} {s['items']:>3} {s['false_passes']:>9} {s['false_fails']:>9} {s['ambiguous_accepts']:>8} "
              f"{rate:>7} {s['errors']:>6} {s['p50_ms'] or '-':>6} {s['p95_ms'] or '-':>6} {s['over_2s']:>4}")
    outcome, reason = ("fail", f"aborted: {aborted}") if aborted else verdict(summary)
    models = sorted({r["model"] for r in rows if r.get("model")})
    print(f"\nrequests={requests} retried={len(retried)} models={models} verdict={outcome} ({reason})")

    if opts.out:
        with open(opts.out, "w", encoding="utf-8") as f:
            json.dump({"manifest_sha256": sha, "run_date": run_date, "platform": platform,
                       "requested_model": opts.model, "returned_models": models,
                       "pass_at": opts.pass_at, "fail_at": opts.fail_at, "timeout_s": opts.timeout,
                       "requests_used": requests, "retried": retried, "aborted": aborted,
                       "verdict": outcome, "verdict_reason": reason, "summary": summary,
                       "rows": [{k: v for k, v in r.items() if k not in ("retryable", "auth")} for r in rows]},
                      f, indent=2)
            f.write("\n")


if __name__ == "__main__":
    main()
