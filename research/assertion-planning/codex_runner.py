"""Root-granted, finite assertion-planning runner. Python 3.9+, stdlib only."""
import argparse
import sys
import asyncio
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import re
from datetime import datetime, timezone
from collections import deque

PINNED_EXECUTABLE = {"path": "/opt/homebrew/Caskroom/codex/0.159.0/bin/codex", "binarySha256": "e89718aa1969bfc4a471277bdc4679a3a3529293de0a309909822dfd67ddb77a", "cliVersion": "0.159.0"}
CAPS = {"totalAttempts": 33, "qualificationAttempts": 1, "caseAttempts": 32, "retries": 0, "liveReruns": 0, "wallSeconds": 1200, "startupSeconds": 30, "turnSeconds": 30, "cleanupSeconds": 5}
DISABLED_FEATURES = "apps plugins remote_plugin plugin_sharing hooks memories shell_tool unified_exec shell_snapshot multi_agent multi_agent_v2 code_mode code_mode_host code_mode_only browser_use browser_use_external browser_use_full_cdp_access computer_use in_app_browser image_generation view_image skill_search skill_mcp_dependency_install tool_suggest default_mode_request_user_input sleep_tool goals workspace_dependencies realtime_conversation in_app_local_automation prevent_idle_sleep request_permissions_tool context_management current_time_reminder deferred_executor standalone_web_search token_budget remote_control".split()
MANIFEST = {"model": "gpt-6-luna", "model_reasoning_effort": "low", "service_tier": "default", "model_provider": "openai", "forced_login_method": "chatgpt", "approval_policy": "never", "approvals_reviewer": "user", "sandbox_mode": "read-only", "web_search": "disabled", "agents": {"enabled": False}, "notify": [], "apps": {"_default": {"enabled": False}}, "project_doc_max_bytes": 0, "instructions": "Plan synthetic assertions. Never execute tools or return assertion verdicts.", "developer_instructions": "", "features": {name: False for name in DISABLED_FEATURES}, "tools": {"update_plan": {"enabled": False}, "experimental_request_user_input": {"enabled": False}}, "cloud": {"skills": {"enabled": False}}, "skills": {"include_instructions": False}}
CANDIDATE_FILES = ["research/assertion-planning/" + name for name in ("codex_runner.py", "test_codex_runner.py", "README.md")]


class Rejected(RuntimeError):
    """Safe fixed-category pre-launch rejection; never carries provider data."""


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def sha(value):
    return hashlib.sha256(value if isinstance(value, bytes) else canonical(value)).hexdigest()


def reject(category):
    raise Rejected(category)


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                reject("duplicate key")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs, parse_constant=lambda _: reject("nonfinite JSON"))


def read_json(path, category):
    try:
        data = Path(path).read_bytes()
        if len(data) > 8 * 1024 * 1024:
            reject(category)
        return strict_json(data), data
    except (OSError, ValueError, TypeError):
        reject(category)


def keys(value, expected, category):
    if not isinstance(value, dict) or set(value) != set(expected):
        reject(category)


def provider_output_schema(canonical_schema):
    """Explicit five-field strict-provider subset; host retains cross-field checks."""
    pattern = canonical_schema["$defs"]["nonBlankString"]["pattern"]
    return {"type": "object", "additionalProperties": False, "required": ["mode", "target", "confidence", "uncertain", "reason"], "properties": {"mode": {"type": "string", "enum": ["visible", "focused", "tree", "visual"]}, "target": {"anyOf": [{"type": "string", "pattern": pattern, "maxLength": 256}, {"type": "null"}]}, "confidence": {"type": "number", "minimum": 0, "maximum": 1}, "uncertain": {"type": "boolean"}, "reason": {"type": "string", "pattern": pattern, "maxLength": 512}}}


def config_binding(executable_spec, provider_schema_sha256):
    return {"executable": executable_spec, "manifest": MANIFEST, "requireAllInheritedIntegrationsDisabled": True, "providerSchemaSha256": provider_schema_sha256}


def same_file(a, b):
    return a.resolve() == b.resolve() or (a.exists() and b.exists() and os.path.samefile(a, b))


def validate_inputs(requests, output, ledger, grant_path, repository_root, spec):
    grant, grant_bytes = read_json(grant_path, "grant")
    keys(grant, "formatVersion grantId ledgerId issue candidate candidateReviewSha256 corpusReviewSha256 requestSha256 freezeSha256 configurationSha256 providerSchemaSha256 paths pathsSha256 caps capsSha256 allowInitialLedgerCreation".split(), "grant")
    if grant["formatVersion"] != 1 or grant["issue"] != 59 or any(not isinstance(grant[k], str) or not grant[k] for k in ("grantId", "ledgerId")):
        reject("grant")
    paths = grant["paths"]
    keys(paths, "requests output ledger ledgerLock candidateReview corpusReview".split(), "paths")
    if any(not isinstance(v, str) or str(Path(v).resolve()) != v for v in paths.values()):
        reject("paths")
    if paths["requests"] != str(requests.resolve()) or paths["output"] != str(output.resolve()) or paths["ledger"] != str(ledger.resolve()) or paths["ledgerLock"] != str(ledger.resolve()) + ".lock" or sha(paths) != grant["pathsSha256"]:
        reject("paths")
    protected = [Path(p) for k, p in paths.items() if k != "output"] + [grant_path]
    for key in ("candidateReview", "corpusReview"):
        try:
            if sha(Path(paths[key]).read_bytes()) != grant[key + "Sha256"]:
                reject("review")
        except OSError:
            reject("review")
    if grant["caps"] != CAPS or sha(CAPS) != grant["capsSha256"]:
        reject("caps")
    canonical_schema, _ = read_json(repository_root / "research/assertion-planning/planner.schema.json", "schema")
    wire_schema = provider_output_schema(canonical_schema)
    if grant["providerSchemaSha256"] != sha(wire_schema) or grant["configurationSha256"] != sha(config_binding(spec, sha(wire_schema))):
        reject("configuration")
    try:
        if sha(Path(spec["path"]).read_bytes()) != spec["binarySha256"] or spec["cliVersion"] != "0.159.0":
            reject("executable")
    except OSError:
        reject("executable")
    candidate = grant["candidate"]
    keys(candidate, "commit tree filesSha256".split(), "candidate")
    if set(candidate["filesSha256"]) != set(CANDIDATE_FILES):
        reject("candidate")
    try:
        for field, revision in (("commit", "HEAD"), ("tree", "HEAD^{tree}")):
            observed = subprocess.check_output(["git", "rev-parse", revision], cwd=repository_root, stderr=subprocess.DEVNULL, text=True).strip()
            if candidate[field] != observed:
                reject("candidate")
        if subprocess.check_output(["git", "status", "--porcelain=v1", "--untracked-files=no"], cwd=repository_root, stderr=subprocess.DEVNULL):
            reject("candidate")
        for name, expected in candidate["filesSha256"].items():
            path = repository_root / name
            protected.append(path)
            if sha(path.read_bytes()) != expected:
                reject("candidate")
    except (OSError, subprocess.CalledProcessError):
        reject("candidate")
    request, request_bytes = read_json(requests, "requests")
    keys(request, "formatVersion corpusId corpusRevision caseCount authorityBypassCaseCount implicitCaseCount corpusSha256 contextSha256 implementationSha256 promptSha256 schemaSha256 cases".split(), "requests")
    freeze = {k: v for k, v in request.items() if k.endswith("Sha256")}
    if sha(request_bytes) != grant["requestSha256"] or sha(freeze) != grant["freezeSha256"]:
        reject("freeze")
    frozen = {"research/assertion-planning/corpus.json": request["corpusSha256"], "research/assertion-planning/prompt.txt": request["promptSha256"], "research/assertion-planning/planner.schema.json": request["schemaSha256"]}
    frozen.update({"research/assertion-planning/" + k: v for k, v in request["contextSha256"].items()})
    frozen.update(request["implementationSha256"])
    for name, expected in frozen.items():
        path = repository_root / name
        if not path.resolve().is_relative_to(repository_root.resolve()):
            reject("freeze")
        protected.append(path)
        try:
            if sha(path.read_bytes()) != expected:
                reject("freeze")
        except OSError:
            reject("freeze")
    lock_path = Path(paths["ledgerLock"])
    readonly = [Path(paths[key]) for key in ("requests", "candidateReview", "corpusReview")] + [grant_path] + [repository_root / name for name in list(candidate["filesSha256"]) + list(frozen)]
    if any(same_file(target, path) for target in (ledger, lock_path) for path in readonly) or same_file(ledger, lock_path):
        reject("input collision")
    if any(same_file(output, path) for path in protected) or output.exists():
        reject("output collision")
    if request["formatVersion"] != 2 or request["caseCount"] != 40 or request["authorityBypassCaseCount"] != 8 or request["implicitCaseCount"] != 32 or len(request["cases"]) != 40:
        reject("requests")
    ids = set()
    for case in request["cases"]:
        keys(case, "caseId authorityBypass modelInput".split(), "requests")
        keys(case["modelInput"], "rawStep journeySteps platform projectContext".split(), "requests")
        if not isinstance(case["caseId"], str) or not case["caseId"] or case["caseId"] in ids or type(case["authorityBypass"]) is not bool:
            reject("requests")
        ids.add(case["caseId"])
        model_input = case["modelInput"]
        if any(not isinstance(model_input[k], str) or not model_input[k] for k in ("rawStep", "platform", "projectContext")) or not isinstance(model_input["journeySteps"], list) or any(not isinstance(step, str) for step in model_input["journeySteps"]):
            reject("requests")
    if sum(case["authorityBypass"] for case in request["cases"]) != 8:
        reject("requests")
    prompt_bytes = (repository_root / "research/assertion-planning/prompt.txt").read_bytes()
    schema_bytes = (repository_root / "research/assertion-planning/planner.schema.json").read_bytes()
    if sha(prompt_bytes) != request["promptSha256"] or sha(schema_bytes) != request["schemaSha256"] or provider_output_schema(strict_json(schema_bytes)) != wire_schema:
        reject("freeze")
    return grant, sha(grant_bytes), request, prompt_bytes.decode("utf-8"), wire_schema


def validate_history(history, grant):
    keys(history, "formatVersion ledgerId runs".split(), "ledger")
    if history["formatVersion"] != 1 or history["ledgerId"] != grant["ledgerId"] or not isinstance(history["runs"], list):
        reject("ledger")
    attempts = 0
    grant_ids = set()
    for run in history["runs"]:
        keys(run, "grantId grantSha256 requestSha256 freezeSha256 configurationSha256 state attempts".split(), "ledger")
        if run["grantId"] in grant_ids or run["grantId"] == grant["grantId"] or run["state"] not in ("COMPLETE", "STOPPED", "CANCELLED"):
            reject("reused grant")
        grant_ids.add(run["grantId"])
        if not isinstance(run["attempts"], list):
            reject("ledger")
        identities = set()
        for attempt in run["attempts"]:
            attempts += 1
            keys(attempt, "sequence kind caseId recordedAt state".split(), "ledger")
            identity = (attempt["kind"], attempt["caseId"])
            if attempt["sequence"] != attempts or attempt["state"] != "CONSUMED" or identity in identities or attempt["kind"] not in ("QUALIFICATION", "CASE") or (attempt["kind"] == "QUALIFICATION") != (attempt["caseId"] is None):
                reject("ledger")
            identities.add(identity)
    if attempts >= CAPS["totalAttempts"]:
        reject("budget")
    if history["runs"]:
        # Startup repair may change the reviewed profile only before any model turn.
        if attempts != 0 or any(previous["state"] != "STOPPED" or any(previous[key] != grant[key] for key in ("requestSha256", "freezeSha256")) for previous in history["runs"]):
            reject("live rerun")
    return attempts


def now():
    return datetime.now(timezone.utc).isoformat()


def atomic_json(path, value, exclusive=False):
    path = Path(path)
    if not path.parent.is_dir():
        reject("parent directory")
    data = canonical(value) + b"\n"
    if exclusive:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    else:
        fd, name = tempfile.mkstemp(prefix=".verity-owned-", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(name, path)
        finally:
            if os.path.exists(name):
                os.unlink(name)
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class Poisoned(Exception):
    """Transport/isolation poison with no raw payload attached."""
PASSIVE_EVENTS = {"warning", "configWarning", "deprecationNotice", "account/rateLimits/updated", "thread/started", "thread/status/changed", "remoteControl/status/changed", "account/updated"}
TURN_DELTAS = {"item/agentMessage/delta", "item/reasoning/summaryTextDelta", "item/reasoning/summaryPartAdded", "item/reasoning/textDelta"}


def check_event(message):
    method = message.get("method")
    params = message.get("params")
    if not isinstance(method, str) or not isinstance(params, dict):
        raise Poisoned()
    if method == "account/updated":
        if params.get("authMode") != "chatgpt":
            raise Poisoned()
        message["params"] = {"authMode": "chatgpt"}
        return
    if method == "remoteControl/status/changed":
        if params.get("status") != "disabled":
            raise Poisoned()
        message["params"] = {"status": "disabled"}
        return
    if method == "error":
        if type(params.get("willRetry")) is not bool:
            raise Poisoned()
        message["params"] = {key: params.get(key) for key in ("threadId", "turnId", "willRetry")}
        return
    if method in PASSIVE_EVENTS or method in TURN_DELTAS or method in ("turn/started", "turn/completed", "thread/tokenUsage/updated", "rawResponse/completed"):
        return
    if method in ("item/started", "item/completed"):
        item = params.get("item")
        if not isinstance(item, dict) or item.get("type") not in ("agentMessage", "reasoning", "userMessage"):
            raise Poisoned()
        return
    if method == "rawResponseItem/completed":
        item = params.get("item")
        if not isinstance(item, dict):
            raise Poisoned()
        if item.get("type") == "reasoning":
            return
        if item.get("type") == "message" and item.get("role") == "assistant" and isinstance(item.get("content"), list) and all(isinstance(part, dict) and part.get("type") == "output_text" for part in item["content"]):
            return
    raise Poisoned()


class Child:
    def __init__(self, whole_end, cleanup_seconds):
        self.whole_end = whole_end
        self.cleanup_seconds = cleanup_seconds
        self.process = None
        self.directory = None
        self.cwd = None
        self.readers = []
        self.queue = asyncio.Queue(maxsize=64)
        self.poisoned = False
        self.closing = False
        self.sequence = 0
        self.deferred = deque()
        self.thread_id = None
        self.seen_thread_ids = set()
        self.turn_id = None

    def poison(self):
        self.poisoned = True
        while not self.queue.empty():
            self.queue.get_nowait()
        self.queue.put_nowait(None)

    async def launch(self, argv, end):
        self.directory = tempfile.TemporaryDirectory(prefix="verity-assertion-owned-")
        self.cwd = str(Path(self.directory.name).resolve())
        env = dict(os.environ)
        env.pop("OPENAI_API_KEY", None)
        env.pop("CODEX_API_KEY", None)
        try:
            self.process = await asyncio.wait_for(asyncio.create_subprocess_exec(*argv, cwd=self.cwd, env=env, stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, limit=8 * 1024 * 1024 + 1), max(0, end - time.monotonic()))
        except asyncio.TimeoutError:
            raise
        except OSError:
            raise Poisoned() from None
        self.readers = [asyncio.create_task(self.read_stdout()), asyncio.create_task(self.discard_stderr())]

    async def discard_stderr(self):
        try:
            while await self.process.stderr.read(65536):
                pass
        except (OSError, ValueError):
            self.poison()

    async def send(self, value):
        if self.process is None or self.process.stdin.is_closing():
            raise Poisoned()
        self.process.stdin.write(canonical(value) + b"\n")
        await self.process.stdin.drain()

    async def read_stdout(self):
        try:
            while True:
                data = await self.process.stdout.readline()
                if not data:
                    if not self.closing:
                        self.poison()
                    return
                if len(data) > 8 * 1024 * 1024 or not data.endswith(b"\n"):
                    raise Poisoned()
                message = strict_json(data.decode("utf-8"))
                if not isinstance(message, dict):
                    raise Poisoned()
                if "method" in message and "id" in message:
                    self.poisoned = True
                    await self.send({"id": message["id"], "error": {"code": -32601, "message": "Research runner refuses server callbacks"}})
                    self.poison()
                    return
                method = message.get("method")
                if method is not None:
                    check_event(message)
                if self.queue.qsize() + len(self.deferred) >= 64:
                    raise Poisoned()
                self.queue.put_nowait(message)
        except (Poisoned, OSError, ValueError, UnicodeError, Rejected, ConnectionError):
            self.poison()

    async def receive(self, end, use_deferred=True):
        if self.poisoned:
            raise Poisoned()
        if use_deferred and self.deferred:
            return self.deferred.popleft()
        try:
            message = await asyncio.wait_for(self.queue.get(), max(0, end - time.monotonic()))
        except asyncio.TimeoutError:
            raise
        if message is None or self.poisoned:
            raise Poisoned()
        return message

    async def request(self, method, params, end):
        self.sequence += 1
        request_id = self.sequence
        try:
            await asyncio.wait_for(self.send({"id": request_id, "method": method, "params": params}), max(0, end - time.monotonic()))
            while True:
                message = await self.receive(end, use_deferred=False)
                if "method" in message:
                    if message["method"] in PASSIVE_EVENTS:
                        continue
                    if len(self.deferred) >= 64:
                        raise Poisoned()
                    self.deferred.append(message)
                    continue
                if message.get("id") != request_id or "result" not in message or "error" in message:
                    raise Poisoned()
                return message["result"]
        except asyncio.TimeoutError:
            raise
        except (OSError, ConnectionError):
            raise Poisoned() from None

    async def initialize(self, end):
        await self.request("initialize", {"clientInfo": {"name": "verity_assertion_research", "version": "1"}, "capabilities": {"experimentalApi": True, "explicitGatewayOauth": True}}, end)
        await asyncio.wait_for(self.send({"method": "initialized", "params": {}}), max(0, end - time.monotonic()))

    async def cleanup(self):
        end = min(self.whole_end, time.monotonic() + self.cleanup_seconds)
        verified = True
        process = self.process
        self.closing = True
        try:
            if process is not None:
                if not process.stdin.is_closing():
                    process.stdin.close()
                remaining = max(0, end - time.monotonic())
                try:
                    await asyncio.wait_for(process.wait(), remaining / 3)
                except asyncio.TimeoutError:
                    if process.returncode is None:
                        process.terminate()
                    try:
                        await asyncio.wait_for(process.wait(), max(0, end - time.monotonic()) / 2)
                    except asyncio.TimeoutError:
                        if process.returncode is None:
                            process.kill()
                        await asyncio.wait_for(process.wait(), max(0, end - time.monotonic()))
                verified = process.returncode is not None
        except (OSError, asyncio.TimeoutError):
            verified = False
        finally:
            for task in self.readers:
                if not task.done():
                    task.cancel()
            if self.readers:
                await asyncio.gather(*self.readers, return_exceptions=True)
            verified = verified and all(task.done() for task in self.readers)
            if self.directory is not None:
                name = self.directory.name
                self.directory.cleanup()
                verified = verified and not os.path.exists(name)
        return verified


def launch_overrides(manifest, inherited=None):
    result = []
    def flatten(value, prefix):
        for name, item in value.items():
            key = prefix + ("." if prefix else "") + name
            if isinstance(item, dict):
                flatten(item, key)
            else:
                result.extend(["-c", key + "=" + json.dumps(item, ensure_ascii=False, separators=(",", ":"))])
    flatten(manifest, "")
    for table, names in (inherited or {}).items():
        entries = set(names)
        if table == "apps":
            entries.add("_default")
        if entries:
            # This CLI splits key paths literally; quoted names belong in the TOML value.
            value = "{" + ",".join(json.dumps(name, ensure_ascii=False) + "={enabled=false}" for name in sorted(entries)) + "}"
            result.extend(["-c", table + "=" + value])
    return result


def inspect_policy(response, inherited=None):
    config = response.get("config") if isinstance(response, dict) else None
    if not isinstance(config, dict):
        raise Poisoned()
    def verify(expected, observed):
        for key, value in expected.items():
            if key not in observed:
                raise Poisoned()
            actual = observed[key]
            if isinstance(value, dict):
                if not isinstance(actual, dict):
                    raise Poisoned()
                verify(value, actual)
            elif type(actual) is not type(value) or actual != value:
                raise Poisoned()
    # ConfigRead's ToolsV2 drops these flags; active layers are returned high to low.
    layers = response.get("layers")
    if not isinstance(layers, list):
        raise Poisoned()
    controls = {}
    for tool in MANIFEST["tools"]:
        for layer in layers:
            if not isinstance(layer, dict):
                raise Poisoned()
            if layer.get("disabledReason") is not None:
                continue
            raw = layer.get("config")
            if not isinstance(raw, dict) or not isinstance(raw.get("tools", {}), dict):
                raise Poisoned()
            tools = raw.get("tools", {})
            if tool in tools:
                setting = tools[tool]
                if not isinstance(setting, dict) or type(setting.get("enabled")) is not bool:
                    raise Poisoned()
                controls[tool] = {"enabled": setting["enabled"]}
                break
        if tool not in controls:
            raise Poisoned()
    verify(MANIFEST, dict(config, tools=controls))
    names = {}
    for table in ("mcp_servers", "plugins", "apps"):
        entries = config.get(table, {})
        if not isinstance(entries, dict):
            raise Poisoned()
        names[table] = set(entries) - ({"_default"} if table == "apps" else set())
        if any(not isinstance(name, str) or not name or any(ord(c) < 32 for c in name) for name in names[table]):
            raise Poisoned()
        if inherited is not None:
            if names[table] != inherited[table]:
                raise Poisoned()
            if any(not isinstance(entries[name], dict) or entries[name].get("enabled") is not False for name in names[table]):
                raise Poisoned()
    return names


def empty_row(status, case_id=None):
    row = {"status": status, "responseText": None, "storedTextSha256": None, "originalResponseSha256": None, "redactionStatus": None, "failureKind": None, "setup": {"startedAt": None, "endedAt": None, "durationMillis": None}, "turn": {"startedAt": None, "endedAt": None, "durationMillis": None}, "cleanup": {"startedAt": None, "endedAt": None, "durationMillis": None}, "tokenUsage": {"inputTokens": None, "outputTokens": None}}
    if case_id is not None:
        row["caseId"] = case_id
    return row
def valid_response(text):
    try:
        value = strict_json(text)
        keys(value, "mode target confidence uncertain reason".split(), "response")
        if value["mode"] not in ("visible", "focused", "tree", "visual") or type(value["confidence"]) not in (int, float) or not 0 <= value["confidence"] <= 1 or type(value["uncertain"]) is not bool:
            return False
        nonblank = re.compile(r"[^\t\n\u000B\f\r\u001C-\u001F\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]")
        if not isinstance(value["reason"], str) or len(value["reason"]) > 512 or not nonblank.search(value["reason"]):
            return False
        target = value["target"]
        return (isinstance(target, str) and len(target) <= 256 and bool(nonblank.search(target))) if value["mode"] in ("visible", "focused") else target is None
    except (Rejected, ValueError, TypeError, KeyError):
        return False


def retain_text(row, text):
    # Only the bounded final answer is eligible for storage; raw errors never are.
    patterns = [r"(?i)\b(?:sk|sess|tok)-[A-Za-z0-9_-]{12,}", r"(?i)\bBearer\s+[A-Za-z0-9._~-]+", r"(?i)\b(?:password|api[_-]?key|access[_-]?token|refresh[_-]?token)\s*[:=]\s*[^\s,;\"]+"]
    stored = text
    for pattern in patterns:
        stored = re.sub(pattern, "[REDACTED]", stored)
    row.update(responseText=stored, storedTextSha256=sha(stored.encode()), originalResponseSha256=sha(text.encode()) if stored != text else None, redactionStatus="REDACTED" if stored != text else "NOT_REQUIRED")


async def qualify_metadata(child, end):
    account = await child.request("account/read", {"refreshToken": False}, end)
    if not isinstance(account, dict) or not isinstance(account.get("account"), dict) or account["account"].get("type") != "chatgpt":
        raise Poisoned()
    # Drop the raw account immediately; never retain identity or plan details.
    del account
    cursor = None
    cursors = set()
    models = set()
    selected = False
    pages = 0
    while True:
        pages += 1
        if pages > 64:
            raise Poisoned()
        page = await child.request("model/list", {"cursor": cursor, "includeHidden": True}, end)
        if not isinstance(page, dict) or not isinstance(page.get("data"), list):
            raise Poisoned()
        for model in page["data"]:
            if not isinstance(model, dict) or not isinstance(model.get("id"), str) or model["id"] in models:
                raise Poisoned()
            models.add(model["id"])
            if model.get("model") == "gpt-6-luna":
                if selected or "text" not in model.get("inputModalities", []) or not any(isinstance(e, dict) and e.get("reasoningEffort") == "low" for e in model.get("supportedReasoningEfforts", [])):
                    raise Poisoned()
                selected = True
        cursor = page.get("nextCursor")
        if cursor is None:
            break
        if not isinstance(cursor, str) or not cursor or cursor in cursors:
            raise Poisoned()
        cursors.add(cursor)
    if not selected:
        raise Poisoned()


def instruction_counts(paths, root, cwd):
    if not isinstance(paths, list):
        raise Poisoned()
    counts = {}
    home = Path(os.environ.get("HOME", "")).resolve()
    for name in set(paths):
        if not isinstance(name, str) or not Path(name).is_absolute():
            raise Poisoned()
        path = Path(name).resolve()
        if path.is_relative_to(root.resolve()) or path.is_relative_to(Path(cwd).resolve()):
            raise Poisoned()
        if path.parent == home / ".codex" and path.name in ("AGENTS.md", "AGENTS.override.md"):
            category = "USER_GLOBAL"
        elif path.is_relative_to(home / ".codex") or path.is_relative_to(home / ".agents"):
            category = "SKILL_INSTRUCTIONS" if path.name == "SKILL.md" else "DEVELOPER_GLOBAL"
        else:
            raise Poisoned()
        counts[category] = counts.get(category, 0) + 1
    return [{"source": key, "count": value} for key, value in sorted(counts.items())]


def verify_thread(response, child, root):
    expected = {"model": "gpt-6-luna", "modelProvider": "openai", "approvalPolicy": "never", "approvalsReviewer": "user", "cwd": child.cwd, "serviceTier": "default", "reasoningEffort": "low", "runtimeWorkspaceRoots": []}
    if not isinstance(response, dict) or any(response.get(k) != v for k, v in expected.items()):
        raise Poisoned()
    sandbox = response.get("sandbox")
    if not isinstance(sandbox, dict) or sandbox.get("type") != "readOnly" or set(sandbox) - {"type", "networkAccess"} or ("networkAccess" in sandbox and sandbox["networkAccess"] is not False):
        raise Poisoned()
    thread = response.get("thread")
    if not isinstance(thread, dict) or not isinstance(thread.get("id"), str) or not thread["id"]:
        raise Poisoned()
    if thread["id"] in child.seen_thread_ids:
        raise Poisoned()
    child.seen_thread_ids.add(thread["id"])
    child.thread_id = thread["id"]
    return instruction_counts(response.get("instructionSources", []), root, child.cwd)


async def assertion_attempt(child, model_input, row, root, schema, prompt, end, consume, run_metadata=None):
    start = time.monotonic()
    row["setup"]["startedAt"] = now()
    counts = None
    try:
        response = await child.request("thread/start", {"model": "gpt-6-luna", "modelProvider": "openai", "serviceTier": "default", "approvalPolicy": "never", "approvalsReviewer": "user", "sandbox": "read-only", "cwd": child.cwd, "baseInstructions": MANIFEST["instructions"], "developerInstructions": "", "ephemeral": True, "allowProviderModelFallback": False, "experimentalRawEvents": True, "dynamicTools": [], "environments": [], "runtimeWorkspaceRoots": [], "selectedCapabilityRoots": [], "config": MANIFEST}, end)
        counts = verify_thread(response, child, root)
        if run_metadata is not None:
            run_metadata["globalInstructionSources"] = counts
        row["setup"].update(endedAt=now(), durationMillis=int((time.monotonic() - start) * 1000))
        turn_start = time.monotonic()
        row["turn"]["startedAt"] = now()
        consume()
        row["status"] = "FAILURE"
        row["failureKind"] = "TRANSPORT"
        response = await child.request("turn/start", {"threadId": child.thread_id, "input": [{"type": "text", "text": prompt + "\n" + canonical(model_input).decode(), "text_elements": []}], "outputSchema": schema, "model": "gpt-6-luna", "effort": "low", "serviceTier": "default", "environments": [], "runtimeWorkspaceRoots": []}, end)
        turn = response.get("turn") if isinstance(response, dict) else None
        if not isinstance(turn, dict) or not isinstance(turn.get("id"), str) or not turn["id"]:
            raise Poisoned()
        child.turn_id = turn["id"]
        final_text = None
        while True:
            message = await child.receive(end)
            method = message.get("method")
            params = message.get("params")
            if method in PASSIVE_EVENTS:
                continue
            if not isinstance(params, dict) or params.get("threadId") != child.thread_id:
                raise Poisoned()
            if method in TURN_DELTAS or method in ("rawResponseItem/completed", "rawResponse/completed", "turn/started", "thread/tokenUsage/updated", "error"):
                if method == "turn/started":
                    turn = params.get("turn")
                    if not isinstance(turn, dict) or turn.get("id") != child.turn_id:
                        raise Poisoned()
                elif method != "thread/tokenUsage/updated" and params.get("turnId") != child.turn_id:
                    raise Poisoned()
                if method == "rawResponse/completed" and params.get("usage") is not None:
                    usage = params["usage"]
                    if not isinstance(usage, dict):
                        raise Poisoned()
                    for key in ("inputTokens", "outputTokens"):
                        value = usage.get(key)
                        if value is not None and (type(value) is not int or value < 0):
                            raise Poisoned()
                        row["tokenUsage"][key] = value
                continue
            if method in ("item/started", "item/completed"):
                if params.get("turnId") != child.turn_id:
                    raise Poisoned()
                item = params.get("item", {})
                if not isinstance(item, dict) or item.get("type") not in ("agentMessage", "reasoning", "userMessage"):
                    raise Poisoned()
                if method == "item/completed" and item.get("type") == "agentMessage" and item.get("phase") == "final_answer":
                    text = item.get("text")
                    if not isinstance(text, str) or len(text.encode()) > 16 * 1024 or final_text is not None:
                        raise Poisoned()
                    final_text = text
            elif method == "turn/completed":
                turn = params.get("turn", {})
                if not isinstance(turn, dict) or turn.get("id") != child.turn_id or not isinstance(turn.get("items"), list):
                    raise Poisoned()
                if any(not isinstance(item, dict) or item.get("type") not in ("agentMessage", "reasoning", "userMessage") for item in turn["items"]):
                    raise Poisoned()
                if turn.get("status") != "completed" or turn.get("error") is not None:
                    break
                if final_text is None or child.poisoned:
                    raise Poisoned()
                retain_text(row, final_text)
                row.update(status="SUCCESS", failureKind=None)
                break
            else:
                raise Poisoned()
        row["turn"].update(endedAt=now(), durationMillis=int((time.monotonic() - turn_start) * 1000))
        return counts
    except asyncio.TimeoutError:
        if row["status"] == "FAILURE":
            row["failureKind"] = "TIMEOUT"
        if child.turn_id is None or counts is None or time.monotonic() >= child.whole_end - child.cleanup_seconds:
            raise
        return counts
    except asyncio.CancelledError:
        # Prevent an unresponsive cleanup RPC from replacing caller cancellation.
        child.poisoned = True
        raise
    finally:
        cleanup_start = time.monotonic()
        row["cleanup"]["startedAt"] = now()
        if child.thread_id is not None and not child.poisoned:
            cleanup_end = min(child.whole_end, cleanup_start + child.cleanup_seconds)
            if child.turn_id is not None and row["status"] != "SUCCESS":
                await child.request("turn/interrupt", {"threadId": child.thread_id, "turnId": child.turn_id}, cleanup_end)
            await child.request("thread/unsubscribe", {"threadId": child.thread_id}, cleanup_end)
            while child.deferred:
                message = child.deferred.popleft()
                params = message.get("params", {})
                if params.get("threadId") != child.thread_id or (params.get("turnId") not in (None, child.turn_id)):
                    raise Poisoned()
        child.thread_id = None
        child.turn_id = None
        row["cleanup"].update(endedAt=now(), durationMillis=int((time.monotonic() - cleanup_start) * 1000))


async def run_study(requests, output, ledger, grant, *, repository_root=None, executable_spec=None, child_argv=None, timeout_limits=None):
    root = Path(repository_root) if repository_root else Path(__file__).resolve().parents[2]
    spec = executable_spec or PINNED_EXECUTABLE
    grant, grant_sha, request, prompt, schema = validate_inputs(Path(requests), Path(output), Path(ledger), Path(grant), root, spec)
    limits = dict(CAPS)
    for key, value in (timeout_limits or {}).items():
        if key not in ("wallSeconds", "startupSeconds", "turnSeconds", "cleanupSeconds") or type(value) not in (int, float) or not 0 < value <= CAPS[key]:
            reject("limits")
        limits[key] = value
    lock_fd = os.open(grant["paths"]["ledgerLock"], os.O_RDWR | os.O_CREAT, 0o600)
    try:
        try:
            fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            reject("ledger busy")
        if Path(ledger).exists():
            history, _ = read_json(ledger, "ledger")
            validate_history(history, grant)
        else:
            if grant["allowInitialLedgerCreation"] is not True:
                reject("ledger creation")
            history = {"formatVersion": 1, "ledgerId": grant["ledgerId"], "runs": []}
            atomic_json(ledger, history, exclusive=True)
        run = {"grantId": grant["grantId"], "grantSha256": grant_sha, "requestSha256": grant["requestSha256"], "freezeSha256": grant["freezeSha256"], "configurationSha256": grant["configurationSha256"], "state": "STARTED", "attempts": []}
        history["runs"].append(run)
        atomic_json(ledger, history)
        started = time.monotonic()
        whole_end = started + limits["wallSeconds"]
        startup_end = min(whole_end - limits["cleanupSeconds"], started + limits["startupSeconds"])
        saved = {"formatVersion": 1, "bindings": {"requestSha256": grant["requestSha256"], "corpusSha256": request["corpusSha256"], "contextSha256": request["contextSha256"], "hostSha256": request["implementationSha256"], "promptSha256": request["promptSha256"], "schemaSha256": request["schemaSha256"]}, "runMetadata": {"model": "gpt-6-luna", "effort": "low", "tier": "default", "cliVersion": spec["cliVersion"], "modelRevision": None, "startupDurationMillis": None, "wallDurationMillis": None, "globalInstructionSources": []}, "counters": {"totalAttempts": 0, "qualificationAttempts": 0, "caseAttempts": 0, "bypassCases": 8, "unattemptedCases": 32}, "completed": False, "stopReason": "QUALIFICATION_FAILED", "cleanupVerified": True, "qualification": empty_row("UNATTEMPTED"), "cases": [empty_row("BYPASS" if case["authorityBypass"] else "UNATTEMPTED", case["caseId"]) for case in request["cases"]]}
        children = []
        cancelled = None
        try:
            base_argv = child_argv or [spec["path"]]
            bootstrap = Child(whole_end, limits["cleanupSeconds"])
            children.append(bootstrap)
            await bootstrap.launch(base_argv + ["app-server"] + launch_overrides(MANIFEST), startup_end)
            await bootstrap.initialize(startup_end)
            inherited = inspect_policy(await bootstrap.request("config/read", {"includeLayers": True}, startup_end))
            saved["cleanupVerified"] = await bootstrap.cleanup()
            if not saved["cleanupVerified"]:
                raise Poisoned()
            final = Child(whole_end, limits["cleanupSeconds"])
            children.append(final)
            await final.launch(base_argv + ["app-server"] + launch_overrides(MANIFEST, inherited), startup_end)
            await final.initialize(startup_end)
            inspect_policy(await final.request("config/read", {"includeLayers": True}, startup_end), inherited)
            await qualify_metadata(final, startup_end)
            saved["runMetadata"]["startupDurationMillis"] = int((time.monotonic() - started) * 1000)
            def consume(kind, case_id):
                cumulative = sum(len(previous["attempts"]) for previous in history["runs"])
                if cumulative >= CAPS["totalAttempts"] or any(a["kind"] == kind and a["caseId"] == case_id for a in run["attempts"]):
                    reject("budget")
                run["attempts"].append({"sequence": cumulative + 1, "kind": kind, "caseId": case_id, "recordedAt": now(), "state": "CONSUMED"})
                atomic_json(ledger, history)
                saved["counters"]["totalAttempts"] += 1
                saved["counters"]["qualificationAttempts" if kind == "QUALIFICATION" else "caseAttempts"] += 1
                if kind == "CASE":
                    saved["counters"]["unattemptedCases"] -= 1
            qualification_input = {"rawStep": "[?] Home", "journeySteps": ["Launch synthetic application"], "platform": "android_mobile", "projectContext": "Synthetic application has a visible literal Home label."}
            end = min(whole_end - limits["cleanupSeconds"], time.monotonic() + limits["turnSeconds"])
            counts = await assertion_attempt(final, qualification_input, saved["qualification"], root, schema, prompt, end, lambda: consume("QUALIFICATION", None), run_metadata=saved["runMetadata"])
            if saved["qualification"]["status"] != "SUCCESS" or not valid_response(saved["qualification"]["responseText"]):
                saved["stopReason"] = "QUALIFICATION_FAILED"
            else:
                for case, row in zip(request["cases"], saved["cases"]):
                    if case["authorityBypass"]:
                        continue
                    if time.monotonic() >= whole_end - limits["cleanupSeconds"]:
                        saved["stopReason"] = "WALL_LIMIT"
                        break
                    end = min(whole_end - limits["cleanupSeconds"], time.monotonic() + limits["turnSeconds"])
                    next_counts = await assertion_attempt(final, case["modelInput"], row, root, schema, prompt, end, lambda case=case: consume("CASE", case["caseId"]))
                    if next_counts != counts:
                        raise Poisoned()
                else:
                    saved["completed"] = True
                    saved["stopReason"] = "COMPLETE"
        except asyncio.CancelledError as error:
            cancelled = error
            saved["stopReason"] = "INTERRUPTED"
        except asyncio.TimeoutError:
            saved["stopReason"] = "WALL_LIMIT" if time.monotonic() >= whole_end - limits["cleanupSeconds"] else ("QUALIFICATION_FAILED" if saved["qualification"]["status"] != "SUCCESS" else "TURN_FAILED")
        except (Poisoned, Rejected, ValueError, TypeError, KeyError, OSError):
            saved["stopReason"] = "POISONED"
        finally:
            async def cleanup_all():
                for child in children:
                    if child.directory is not None:
                        saved["cleanupVerified"] = await child.cleanup() and saved["cleanupVerified"]
            cleanup_task = asyncio.create_task(cleanup_all())
            while not cleanup_task.done():
                try:
                    await asyncio.shield(cleanup_task)
                except asyncio.CancelledError as error:
                    if cancelled is None:
                        cancelled = error
            saved["runMetadata"]["wallDurationMillis"] = int((time.monotonic() - started) * 1000)
            if not saved["cleanupVerified"] or any(child.poisoned for child in children):
                saved["completed"] = False
                saved["stopReason"] = "POISONED"
            if cancelled:
                saved["completed"] = False
                saved["stopReason"] = "INTERRUPTED"
            run["state"] = "CANCELLED" if cancelled else ("COMPLETE" if saved["completed"] else "STOPPED")
            atomic_json(ledger, history)
            atomic_json(output, saved, exclusive=True)
        if cancelled is not None:
            raise cancelled
        return saved
    finally:
        os.close(lock_fd)


def main():
    parser = argparse.ArgumentParser()
    for name in ("requests", "output", "ledger", "grant"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        asyncio.run(run_study(args.requests, args.output, args.ledger, args.grant))
        return 0
    except Rejected as error:
        print("runner rejected: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
