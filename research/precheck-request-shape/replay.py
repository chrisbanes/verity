#!/usr/bin/env python3
"""A/B the System One pre-check request shape on one shadow-mode `--model-trace` (issue #204).

Usage: TYPESAFE_API_KEY=... python3 -I replay.py <shadow.trace> [--platform "Android TV"] [--out results.json]

Pairs each `P<n>` pre-check with the inspector `tree-eval` verdict that followed it, then replays the
same tree and memory through each request shape. The trace must come from shadow mode (`pass-at: 2`,
`fail-at: -1`): otherwise an accepted check has no inspector verdict and is paired with a later one.
The key is read from the environment and is only sent in the Authorization header; it is never printed
or written.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

MEMORY_HEADER = "\n\nReference context (earlier observations, not proof of the current condition):\n"
TREE_HEADER = "Accessibility tree:\n"


def unescape(text):
    # Reverses ModelTrace's escaping of backslash, \n and \r.
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "r": "\r"}.get(m.group(1), m.group(1)), text)


def parse(path):
    pairs, pending, by_request = [], None, {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split(" ", 3)
            if len(parts) < 4:
                continue
            _, source, tag, rest = parts
            if source == "precheck":
                kind, _, value = rest.partition(" ")
                if kind == "start":
                    pending = {"id": tag}
                elif pending and pending["id"] == tag and kind in ("question", "state"):
                    pending[kind] = value
                elif pending and pending["id"] == tag and kind == "response":
                    pending["recorded"] = float(re.search(r"noul=(\S+)", value).group(1))
                    pending["model"] = re.search(r"model=(\S+)", value).group(1)
            elif source == "request" and rest.startswith("start prompt=tree-eval") and pending and "recorded" in pending:
                by_request[tag] = pending
                pending = None
            elif source == "request" and tag in by_request and rest.startswith("response "):
                entry = by_request.pop(tag)
                verdict = json.loads(rest[rest.index("{"):])
                entry["inspector"] = bool(verdict["passed"])
                pairs.append(entry)
    corpus, truncated = [], 0
    for p in pairs:
        if "…[truncated" in p["state"]:
            truncated += 1
            continue
        state = unescape(p["state"])
        if state.startswith("{"):
            # Traces since #204 record the structured state; rebuild the old string for shape A.
            fields = json.loads(state)
            tree, memory = fields["accessibility_tree"], fields.get("earlier_observations", "")
            state = TREE_HEADER + tree + (MEMORY_HEADER + memory if memory else "")
        else:
            tree, _, memory = state[len(TREE_HEADER):].partition(MEMORY_HEADER)
        corpus.append({"id": p["id"], "condition": unescape(p["question"]), "tree": tree, "memory": memory,
                       "inspector": p["inspector"], "recorded": p["recorded"], "state": state})
    return corpus, truncated


def framed(condition, platform):
    return (f"Using the current accessibility tree of an {platform} app, does the screen satisfy this condition? "
            f"Earlier observations are reference only, not proof. Condition: {condition}")


def structured(entry):
    state = {"accessibility_tree": entry["tree"]}
    if entry["memory"]:
        state["earlier_observations"] = entry["memory"]
    return state


CRITERIA = {"true": "The current screen satisfies the condition.",
            "false": "The current screen does not satisfy the condition."}

SHAPES = {
    "A-current": lambda e, p: (e["state"], {"type": "noul", "instructions": e["condition"]}),
    "B-framed-structured": lambda e, p: (structured(e), {"type": "noul", "instructions": framed(e["condition"], p)}),
    "C-framed-structured-criteria": lambda e, p: (structured(e), {"type": "noul", "instructions": framed(e["condition"], p), "criteria": CRITERIA}),
}


def ask(base_url, model, key, state, question):
    body = json.dumps({"model": model, "state": state, "questions": {"condition": question}}).encode()
    request = urllib.request.Request(f"{base_url}/v1/systemone", data=body, method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return float(json.load(response)["answers"]["condition"]["noul"])
    except (urllib.error.URLError, KeyError, ValueError, TimeoutError) as e:
        return type(e).__name__


def main():
    args = argparse.ArgumentParser()
    args.add_argument("trace")
    args.add_argument("--platform", default="Android TV")
    args.add_argument("--base-url", default="https://api.typesafe.ai")
    args.add_argument("--model", default="jev-latest")
    args.add_argument("--pass-at", type=float, default=0.9)
    args.add_argument("--fail-at", type=float, default=0.1)
    args.add_argument("--out")
    opts = args.parse_args()
    key = os.environ.get("TYPESAFE_API_KEY")
    if not key:
        sys.exit("TYPESAFE_API_KEY is not set")

    corpus, truncated = parse(opts.trace)
    print(f"pairs={len(corpus)} skipped_truncated={truncated} with_memory={sum(1 for e in corpus if e['memory'])}")
    results = {name: [] for name in SHAPES}
    for entry in corpus:
        for name, shape in SHAPES.items():
            state, question = shape(entry, opts.platform)
            results[name].append(ask(opts.base_url, opts.model, key, state, question))

    print(f"{'shape':30} {'accepted':>8} {'pass':>5} {'fail':>5} {'disagree':>8} {'errors':>6}")
    for name, probabilities in results.items():
        accepted = passed = failed = disagree = errors = 0
        for entry, p in zip(corpus, probabilities):
            if isinstance(p, str):
                errors += 1
                continue
            verdict = True if p >= opts.pass_at else False if p <= opts.fail_at else None
            if verdict is None:
                continue
            accepted += 1
            passed += verdict
            failed += not verdict
            disagree += verdict != entry["inspector"]
        print(f"{name:30} {accepted:>8} {passed:>5} {failed:>5} {disagree:>8} {errors:>6}")

    if opts.out:
        rows = [{"id": e["id"], "condition": e["condition"], "has_memory": bool(e["memory"]), "inspector": e["inspector"],
                 "recorded": e["recorded"], **{name: results[name][i] for name in SHAPES}} for i, e in enumerate(corpus)]
        with open(opts.out, "w", encoding="utf-8") as f:
            json.dump(rows, f, indent=2)


if __name__ == "__main__":
    main()
