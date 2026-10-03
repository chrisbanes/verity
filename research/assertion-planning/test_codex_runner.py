import asyncio
import copy
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import codex_runner as runner


def digest(value):
    if not isinstance(value, bytes):
        value = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(value).hexdigest()


CANONICAL_SCHEMA = json.loads(pathlib.Path(__file__).with_name('planner.schema.json').read_text())
EXPECTED_WIRE_SCHEMA = {'type':'object','additionalProperties':False,'required':['mode','target','confidence','uncertain','reason'],'properties':{'mode':{'type':'string','enum':['visible','focused','tree','visual']},'target':{'anyOf':[{'type':'string','pattern':CANONICAL_SCHEMA['$defs']['nonBlankString']['pattern'],'maxLength':256},{'type':'null'}]},'confidence':{'type':'number','minimum':0,'maximum':1},'uncertain':{'type':'boolean'},'reason':{'type':'string','pattern':CANONICAL_SCHEMA['$defs']['nonBlankString']['pattern'],'maxLength':512}}}


class Fixture:
    def __init__(self, root):
        self.root = pathlib.Path(root).resolve()
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.research = self.repo / "research/assertion-planning"
        self.research.mkdir(parents=True)
        self.marker = self.root / "child-observed.jsonl"
        self.child = self.root / "fake.py"
        self.child.write_text("import pathlib\npathlib.Path(" + repr(str(self.marker)) + ").write_text('launched')\n")
        self.spec = {"path": str(self.child), "binarySha256": digest(self.child.read_bytes()), "cliVersion": "0.159.0"}
        self.argv = [sys.executable, str(self.child)]
        for name in ("codex_runner.py", "test_codex_runner.py", "README.md", "corpus.json", "prompt.txt", "planner.schema.json"):
            (self.research / name).write_text('{}' if name.endswith('.json') else 'synthetic fixture\n')
        (self.research / 'planner.schema.json').write_text(json.dumps(CANONICAL_SCHEMA))
        self.provider_sha = digest(EXPECTED_WIRE_SCHEMA)
        for name in ("context/01-app.md", "context/02-controls.markdown"):
            p = self.research / name
            p.parent.mkdir(exist_ok=True)
            p.write_text('synthetic context\n')
        subprocess.run(['git', 'init', '-q'], cwd=self.repo, check=True)
        subprocess.run(['git', 'add', '.'], cwd=self.repo, check=True)
        subprocess.run(['git', '-c', 'user.name=Fake', '-c', 'user.email=fake@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Synthetic fixture'], cwd=self.repo, check=True)
        self.requests = self.root / 'requests.json'
        self.output = self.root / 'output.json'
        self.ledger = self.root / 'ledger.json'
        self.grant = self.root / 'grant.json'
        self.candidate_review = self.root / 'candidate-review.json'
        self.corpus_review = self.root / 'corpus-review.json'
        self.candidate_review.write_text('synthetic independent candidate review\n')
        self.corpus_review.write_text('synthetic independent corpus review\n')
        self.request = {'formatVersion': 2, 'corpusId': 'fake', 'corpusRevision': 1, 'caseCount': 40, 'authorityBypassCaseCount': 8, 'implicitCaseCount': 32, 'corpusSha256': digest((self.research / 'corpus.json').read_bytes()), 'contextSha256': {name: digest((self.research / name).read_bytes()) for name in ('context/01-app.md', 'context/02-controls.markdown')}, 'implementationSha256': {}, 'promptSha256': digest((self.research / 'prompt.txt').read_bytes()), 'schemaSha256': digest((self.research / 'planner.schema.json').read_bytes()), 'cases': [{'caseId': 'case-%02d' % i, 'authorityBypass': i >= 32, 'modelInput': {'rawStep': '[?] Home', 'journeySteps': ['Launch app'], 'platform': 'android_mobile', 'projectContext': 'Synthetic context'}} for i in range(40)]}
        self.requests.write_text(json.dumps(self.request))
        self.paths = {'requests': str(self.requests), 'output': str(self.output), 'ledger': str(self.ledger), 'ledgerLock': str(self.ledger) + '.lock', 'candidateReview': str(self.candidate_review), 'corpusReview': str(self.corpus_review)}
        self.grant_data = {'formatVersion': 1, 'grantId': 'fake-grant', 'ledgerId': 'fake-ledger', 'issue': 59, 'candidate': {'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=self.repo, text=True).strip(), 'tree': subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=self.repo, text=True).strip(), 'filesSha256': {'research/assertion-planning/' + name: digest((self.research / name).read_bytes()) for name in ('codex_runner.py', 'test_codex_runner.py', 'README.md')}}, 'candidateReviewSha256': digest(self.candidate_review.read_bytes()), 'corpusReviewSha256': digest(self.corpus_review.read_bytes()), 'requestSha256': digest(self.requests.read_bytes()), 'freezeSha256': digest({k: v for k, v in self.request.items() if k.endswith('Sha256')}), 'providerSchemaSha256': self.provider_sha, 'configurationSha256': digest({'executable': self.spec, 'manifest': runner.MANIFEST, 'requireAllInheritedIntegrationsDisabled': True, 'providerSchemaSha256': self.provider_sha}), 'paths': self.paths, 'pathsSha256': digest(self.paths), 'caps': {'totalAttempts': 33, 'qualificationAttempts': 1, 'caseAttempts': 32, 'retries': 0, 'liveReruns': 0, 'wallSeconds': 1200, 'startupSeconds': 30, 'turnSeconds': 30, 'cleanupSeconds': 5}, 'allowInitialLedgerCreation': True}
        self.grant_data['capsSha256'] = digest(self.grant_data['caps'])
        self.save_grant()

    def save_grant(self):
        self.grant.write_text(json.dumps(self.grant_data))

    def child_script(self, body):
        self.child.write_text(body)
        self.spec['binarySha256'] = digest(self.child.read_bytes())
        self.grant_data['configurationSha256'] = digest({'executable':self.spec,'manifest':runner.MANIFEST,'requireAllInheritedIntegrationsDisabled':True,'providerSchemaSha256':self.provider_sha})
        self.save_grant()

    async def run(self, **kwargs):
        return await runner.run_study(self.requests, self.output, self.ledger, self.grant, repository_root=self.repo, executable_spec=self.spec, child_argv=self.argv, **kwargs)


class RunnerTest(unittest.TestCase):
    def test_missing_grant_rejects_before_launch(self):
        with tempfile.TemporaryDirectory(prefix="verity-fake-") as directory:
            root = pathlib.Path(directory)
            result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name("codex_runner.py")), "--requests", str(root / "requests.json"), "--output", str(root / "output.json"), "--ledger", str(root / "ledger.json"), "--grant", str(root / "missing-grant.json")], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stderr.strip(), "runner rejected: grant")
            self.assertFalse((root / "ledger.json").exists())

    def test_invalid_grant_never_launches_owned_child(self):
        mutations = ('missing_review', 'review_bytes', 'request_bytes', 'freeze', 'candidate', 'configuration', 'caps', 'paths', 'exhausted', 'reused', 'unfinalized', 'ledger_identity', 'output_collision')
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f = Fixture(d)
                if mutation == 'missing_review':
                    f.grant_data.pop('corpusReviewSha256')
                elif mutation == 'review_bytes':
                    f.corpus_review.write_text('changed')
                elif mutation == 'request_bytes':
                    f.requests.write_text('{}')
                elif mutation == 'freeze':
                    f.grant_data['freezeSha256'] = '0' * 64
                elif mutation == 'candidate':
                    f.grant_data['candidate']['tree'] = '0' * 40
                elif mutation == 'configuration':
                    f.grant_data['configurationSha256'] = '0' * 64
                elif mutation == 'caps':
                    f.grant_data['caps']['totalAttempts'] = 34
                elif mutation == 'paths':
                    f.grant_data['paths']['requests'] = str(f.root / 'other')
                elif mutation == 'output_collision':
                    f.output.unlink(missing_ok=True)
                    os.link(f.corpus_review, f.output)
                else:
                    history = {'formatVersion': 1, 'ledgerId': 'fake-ledger', 'runs': [{'grantId': 'old-grant', 'grantSha256': 'a' * 64, 'requestSha256': 'b' * 64, 'freezeSha256': 'c' * 64, 'configurationSha256': 'd' * 64, 'state': 'COMPLETE', 'attempts': []}]}
                    if mutation == 'exhausted':
                        history['runs'][0]['attempts'] = [{'sequence': i + 1, 'kind': 'QUALIFICATION' if i == 0 else 'CASE', 'caseId': None if i == 0 else 'old-%d' % i, 'recordedAt': 'synthetic', 'state': 'CONSUMED'} for i in range(33)]
                    elif mutation == 'reused':
                        history['runs'][0]['grantId'] = 'fake-grant'
                    elif mutation == 'unfinalized':
                        history['runs'][0]['state'] = 'STARTED'
                    elif mutation == 'ledger_identity':
                        history['ledgerId'] = 'wrong'
                    f.ledger.write_text(json.dumps(history))
                f.save_grant()
                expected = {'missing_review': 'grant', 'review_bytes': 'review', 'request_bytes': 'requests', 'freeze': 'freeze', 'candidate': 'candidate', 'configuration': 'configuration', 'caps': 'caps', 'paths': 'paths', 'exhausted': 'budget', 'reused': 'reused grant', 'unfinalized': 'reused grant', 'ledger_identity': 'ledger', 'output_collision': 'output collision'}[mutation]
                with self.assertRaisesRegex(runner.Rejected, '^' + expected + '$'):
                    asyncio.run(f.run())
                self.assertFalse(f.marker.exists())

    def test_inert_bootstrap_and_final_drift_stop_before_metadata(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            policy = copy.deepcopy(runner.MANIFEST)
            policy['mcp_servers'] = {'a.b"\\name': {'enabled': True}}
            policy['plugins'] = {'plug.in': {'enabled': True}}
            policy['apps']['app.name'] = {'enabled': True}
            f.child_script(FAKE_BOOTSTRAP.replace('POLICY_LITERAL', repr(policy)).replace('MARKER_LITERAL', repr(str(f.marker))))
            old = os.environ.get('OPENAI_API_KEY')
            os.environ['OPENAI_API_KEY'] = 'synthetic-secret'
            os.environ['CODEX_API_KEY'] = 'synthetic-secret'
            try:
                output = asyncio.run(f.run())
            finally:
                os.environ.pop('CODEX_API_KEY', None)
                if old is None:
                    os.environ.pop('OPENAI_API_KEY', None)
                else:
                    os.environ['OPENAI_API_KEY'] = old
            self.assertFalse(output['completed'])
            self.assertTrue(output['cleanupVerified'])
            self.assertEqual(output['counters']['totalAttempts'], 0)
            records = [json.loads(line) for line in f.marker.read_text().splitlines()]
            launches = [r for r in records if r['kind'] == 'launch']
            self.assertEqual(len(launches), 2)
            for launch in launches:
                self.assertEqual(launch['cwdFiles'], [])
                self.assertNotIn('OPENAI_API_KEY', launch['environment'])
                self.assertNotIn('CODEX_API_KEY', launch['environment'])
                self.assertEqual(launch['environment'].get('HOME'), os.environ.get('HOME'))
                self.assertEqual(launch['environment'].get('CODEX_HOME'), os.environ.get('CODEX_HOME'))
                self.assertFalse(pathlib.Path(launch['cwd']).exists())
                with self.assertRaises(ProcessLookupError):
                    os.kill(launch['pid'], 0)
            for pid in {r['pid'] for r in launches}:
                methods = [r['method'] for r in records if r['kind'] == 'request' and r['pid'] == pid]
                self.assertEqual(methods, ['initialize', 'initialized', 'config/read'])
            overrides = launches[1]['argv']
            self.assertIn(r'mcp_servers={"a.b\"\\name"={enabled=false}}', overrides)
            self.assertIn('plugins={"plug.in"={enabled=false}}', overrides)
            self.assertIn('apps={"_default"={enabled=false},"app.name"={enabled=false}}', overrides)
            self.assertEqual(json.loads(f.ledger.read_text())['runs'][0]['state'], 'STOPPED')

    def test_complete_run_uses_later_catalog_page_and_durable_order(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            f.child_script(FAKE_STUDY.replace('POLICY_LITERAL', repr(runner.MANIFEST)).replace('MARKER_LITERAL', repr(str(f.marker))).replace('LEDGER_LITERAL', repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertTrue(output['completed'])
            self.assertTrue(output['cleanupVerified'])
            self.assertEqual(output['stopReason'], 'COMPLETE')
            self.assertEqual(output['counters'], {'totalAttempts':33,'qualificationAttempts':1,'caseAttempts':32,'bypassCases':8,'unattemptedCases':0})
            self.assertEqual(output, json.loads(f.output.read_text()))
            self.assertEqual(output['qualification']['status'], 'SUCCESS')
            for row in [output['qualification']] + output['cases'][:32]:
                self.assertEqual(row['storedTextSha256'], digest(row['responseText'].encode()))
                self.assertEqual(row['redactionStatus'], 'NOT_REQUIRED')
            self.assertTrue(all(row['status'] == 'BYPASS' for row in output['cases'][32:]))
            history = json.loads(f.ledger.read_text())
            self.assertEqual(history['ledgerId'], 'fake-ledger')
            self.assertEqual(history['runs'][0]['state'], 'COMPLETE')
            self.assertEqual(len(history['runs'][0]['attempts']), 33)
            records = [json.loads(line) for line in f.marker.read_text().splitlines()]
            turns = [r for r in records if r['kind'] == 'request' and r['method'] == 'turn/start']
            self.assertEqual([r['durableAttempts'] for r in turns], list(range(1,34)))
            threads = [r for r in records if r['kind'] == 'request' and r['method'] == 'thread/start']
            self.assertEqual(len(threads), 33)
            for r in records:
                if r['kind'] == 'launch':
                    self.assertFalse(pathlib.Path(r['cwd']).exists())
                    with self.assertRaises(ProcessLookupError):
                        os.kill(r['pid'], 0)
            self.assertEqual(os.stat(f.output).st_mode & 0o777, 0o600)
            self.assertEqual(os.stat(f.ledger).st_mode & 0o777, 0o600)

    def test_nonexecuting_early_and_raw_events_do_not_replace_final_answer(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace('POLICY_LITERAL', repr(runner.MANIFEST)).replace('MARKER_LITERAL', repr(str(f.marker))).replace('LEDGER_LITERAL', repr(str(f.ledger)))
            script = script.replace("reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})", "notify('item/started', {'threadId':params['threadId'],'turnId':tid,'item':{'type':'reasoning','id':'reason'}})\n        reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})")
            script = script.replace("notify('item/completed',", "notify('rawResponseItem/completed', {'threadId':params['threadId'],'turnId':tid,'item':{'type':'message','role':'assistant','phase':'final_answer','content':[{'type':'output_text','text':'raw text must never replace final answer'}]}})\n        notify('rawResponse/completed', {'threadId':params['threadId'],'turnId':tid,'responseId':'synthetic','usage':{'inputTokens':3,'outputTokens':5,'totalTokens':8},'usageMetadata':None})\n        notify('item/agentMessage/delta', {'threadId':params['threadId'],'turnId':tid,'itemId':'answer','delta':'not final'})\n        notify('item/completed',")
            f.child_script(script)
            output = asyncio.run(f.run())
            self.assertTrue(output['completed'])
            self.assertEqual(output['qualification']['tokenUsage'], {'inputTokens':3,'outputTokens':5})
            self.assertIn('Literal visible target', output['qualification']['responseText'])
            self.assertNotIn('raw text', output['qualification']['responseText'])

    def test_metadata_and_thread_mismatch_poison_without_turn(self):
        mutations = {
            'api_key': ("'type':'chatgpt'", "'type':'apiKey'"),
            'signed_out': ("'account':{'type':'chatgpt','email':'synthetic-private@example.invalid','planType':'plus'}", "'account':None"),
            'no_model': ("'model':'gpt-6-luna','inputModalities'", "'model':'missing-model','inputModalities'"),
            'no_text': ("'inputModalities':['text']", "'inputModalities':['image']"),
            'no_low': ("'reasoningEffort':'low'", "'reasoningEffort':'high'"),
            'repeated_cursor': ("'nextCursor':None", "'nextCursor':'page-2'"),
            'duplicate_model_id': ("'id':'gpt-6-luna','model'", "'id':'other','model'"),
            'provider_echo': ("'modelProvider':'openai'", "'modelProvider':'other'"),
            'model_echo': ("'model':'gpt-6-luna','modelProvider'", "'model':'other','modelProvider'"),
            'effort_echo': ("'reasoningEffort':'low','runtimeWorkspaceRoots'", "'reasoningEffort':'high','runtimeWorkspaceRoots'"),
            'tier_echo': ("'serviceTier':'default','reasoningEffort'", "'serviceTier':'fast','reasoningEffort'"),
            'policy_echo': ("'approvalPolicy':'never'", "'approvalPolicy':'on-request'"),
            'repository_instructions': ("'instructionSources':[]", "'instructionSources':[str(pathlib.Path(LEDGER_LITERAL).parent / 'repo/AGENTS.md')]"),
            'integration_drift': ("reply(request, {'config':dict(policy,tools={}), 'origins':{}, 'layers':[{'name':{'type':'sessionFlags'},'version':'fake','config':policy}]})", "policy['mcp_servers'] = {'new-server':{'enabled':True}}\n        reply(request, {'config':dict(policy,tools={}), 'origins':{}, 'layers':[{'name':{'type':'sessionFlags'},'version':'fake','config':policy}]})"),
        }
        for name, (old, new) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f = Fixture(d)
                script = FAKE_STUDY.replace(old, new).replace('POLICY_LITERAL', repr(runner.MANIFEST)).replace('MARKER_LITERAL', repr(str(f.marker))).replace('LEDGER_LITERAL', repr(str(f.ledger)))
                f.child_script(script)
                output = asyncio.run(f.run(timeout_limits={'startupSeconds':1,'turnSeconds':1,'cleanupSeconds':0.2,'wallSeconds':5}))
                self.assertFalse(output['completed'])
                self.assertTrue(output['cleanupVerified'])
                self.assertEqual(output['counters']['totalAttempts'], 0)
                self.assertNotIn('synthetic-private@example.invalid', f.output.read_text())

    def test_executable_events_and_callbacks_invalidate_late_valid_text(self):
        events = [
            ('item/started', {'type':'commandExecution'}),
            ('item/completed', {'type':'functionCallOutput'}),
            ('item/completed', {'type':'mcpToolCall'}),
            ('item/completed', {'type':'dynamicToolCall'}),
            ('item/completed', {'type':'unknownExecutable'}),
        ] + [('rawResponseItem/completed', {'type':kind}) for kind in ('function_call','function_call_output','local_shell_call','custom_tool_call','custom_tool_call_output','tool_search_call','tool_search_output','web_search_call','image_generation_call','other','configuration_update')]
        events.append(('callback', {}))
        for method, item in events:
            with self.subTest(method=method,item=item), tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f = Fixture(d)
                payload = "print(json.dumps({'id':'server-callback','method':'item/tool/call','params':{}}),flush=True)" if method == 'callback' else "notify(" + repr(method) + ", {'threadId':params['threadId'],'turnId':tid,'item':" + repr(item) + "})"
                script = FAKE_STUDY.replace("reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})", payload + "\n        reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})")
                f.child_script(script.replace('POLICY_LITERAL', repr(runner.MANIFEST)).replace('MARKER_LITERAL', repr(str(f.marker))).replace('LEDGER_LITERAL', repr(str(f.ledger))))
                output = asyncio.run(f.run(timeout_limits={'cleanupSeconds':0.3,'turnSeconds':1,'wallSeconds':5,'startupSeconds':1}))
                self.assertFalse(output['completed'])
                self.assertEqual(output['stopReason'], 'POISONED')
                self.assertEqual(output['counters']['totalAttempts'], 1)
                self.assertIsNone(output['qualification']['responseText'])
                self.assertTrue(all(row['status'] in ('UNATTEMPTED','BYPASS') for row in output['cases']))
                self.assertTrue(output['cleanupVerified'])
                if method == 'callback':
                    refusals = [json.loads(line) for line in f.marker.read_text().splitlines() if json.loads(line)['kind'] == 'refusal']
                    self.assertEqual(refusals[0]['error'], {'code':-32601,'message':'Research runner refuses server callbacks'})

    def test_malformed_completion_objects_are_poisoned(self):
        corruptions = [
            ("notify('turn/completed', {'threadId':params['threadId'],'turn':{'id':tid,'status':'completed','items':[None],'error':None}})"),
            ("notify('turn/completed', {'threadId':params['threadId'],'turn':{'id':tid,'status':'completed','items':['bad'],'error':None}})"),
            ("notify('turn/completed', {'threadId':params['threadId'],'turn':None})"),
            ("notify('item/completed', {'threadId':params['threadId'],'turnId':tid,'item':None})"),
            ("notify('turn/started', {'threadId':params['threadId'],'turn':None})"),
        ]
        for bad in corruptions:
            with self.subTest(frame=bad), tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f = Fixture(d)
                script = FAKE_STUDY.replace("notify('item/completed',", bad + "\n        notify('item/completed',")
                f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
                output = asyncio.run(f.run())
                self.assertFalse(output['completed'])
                self.assertEqual(output['stopReason'],'POISONED')
                self.assertEqual(output['counters']['totalAttempts'],1)
                self.assertTrue(output['cleanupVerified'])
                self.assertIsNone(output['qualification']['responseText'])
                self.assertEqual(json.loads(f.output.read_text())['stopReason'],'POISONED')
                self.assertTrue(all(row['status'] in ('UNATTEMPTED','BYPASS') for row in output['cases']))

    def test_bad_frames_terminal_failures_and_deadlines_preserve_consumption(self):
        corruptions = {
            'malformed': "print('{bad',flush=True)",
            'invalid_utf8': r"sys.stdout.buffer.write(b'\xff\n');sys.stdout.buffer.flush()",
            'oversized': "print('x' * (8 * 1024 * 1024 + 1),flush=True)",
            'eof': "sys.exit(0)",
            'failed_ack': "print(json.dumps({'id':request['id'],'error':{'code':1,'message':'synthetic-secret-provider-error'}}),flush=True)\n        continue",
            'wrong_thread': "notify('item/completed', {'threadId':'wrong','turnId':tid,'item':{'type':'agentMessage','id':'a','phase':'final_answer','text':'bad'}})",
            'wrong_turn': "notify('item/completed', {'threadId':params['threadId'],'turnId':'wrong','item':{'type':'agentMessage','id':'a','phase':'final_answer','text':'bad'}})",
            'oversized_final': "notify('item/completed', {'threadId':params['threadId'],'turnId':tid,'item':{'type':'agentMessage','id':'a','phase':'final_answer','text':'x'*16385}})",
            'queue_overflow': r"sys.stdout.write(''.join(json.dumps({'method':'item/started','params':{'threadId':params['threadId'],'turnId':tid,'item':{'type':'reasoning','id':'r'}}})+'\n' for _ in range(1000)));sys.stdout.flush()",
            'missing_terminal': "import time;time.sleep(2);continue",
        }
        for name, bad in corruptions.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f = Fixture(d)
                script = FAKE_STUDY.replace("reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})", bad + "\n        reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})")
                f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
                output = asyncio.run(f.run(timeout_limits={'turnSeconds':0.2,'cleanupSeconds':0.2,'startupSeconds':1,'wallSeconds':4}))
                self.assertFalse(output['completed'])
                self.assertTrue(output['cleanupVerified'])
                self.assertEqual(output['counters']['totalAttempts'],1)
                self.assertEqual(len(json.loads(f.ledger.read_text())['runs'][0]['attempts']),1)
                self.assertNotIn('synthetic-secret-provider-error', f.output.read_text())

    def test_cancellation_identity_and_stubborn_owned_child_cleanup(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})", "import signal, time\n        signal.signal(signal.SIGTERM, signal.SIG_IGN)\n        time.sleep(10)\n        reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            async def exercise():
                captures = []
                original_wait = asyncio.wait_for
                async def cancellation_boundary(awaitable, timeout):
                    try:
                        return await original_wait(awaitable, timeout)
                    except asyncio.CancelledError as error:
                        captures.append(error)
                        raise
                with patch('asyncio.wait_for', cancellation_boundary):
                    async def owner():
                        try:
                            await f.run(timeout_limits={'cleanupSeconds':0.3,'wallSeconds':5,'turnSeconds':2,'startupSeconds':1})
                        except asyncio.CancelledError as error:
                            return error
                        self.fail('caller cancellation was swallowed')
                    task = asyncio.create_task(owner())
                    for _ in range(200):
                        if f.marker.exists() and 'turn/start' in f.marker.read_text():
                            break
                        await asyncio.sleep(0.005)
                    else:
                        self.fail('owned fake turn not reached')
                    sentinel = object()
                    task.cancel(sentinel)
                    error = await task
                    self.assertIs(error, captures[0])
                    self.assertIs(error.args[0], sentinel)
                    self.assertEqual([t for t in asyncio.all_tasks() if t is not asyncio.current_task() and not t.done()], [])
            asyncio.run(exercise())
            history = json.loads(f.ledger.read_text())
            self.assertEqual(history['runs'][0]['state'], 'CANCELLED')
            self.assertEqual(len(history['runs'][0]['attempts']), 1)
            output = json.loads(f.output.read_text())
            self.assertFalse(output['completed'])
            self.assertTrue(output['cleanupVerified'])
            for record in map(json.loads, f.marker.read_text().splitlines()):
                if record['kind'] == 'launch':
                    self.assertFalse(pathlib.Path(record['cwd']).exists())
                    with self.assertRaises(ProcessLookupError):
                        os.kill(record['pid'], 0)

    def test_frozen_supported_wire_schema_is_identical_for_all_turns(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("assert params['model'] == 'gpt-6-luna'", "assert params['outputSchema'] == WIRE_LITERAL\n        record({'kind':'wire','sha':__import__('hashlib').sha256(json.dumps(params['outputSchema'],sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()})\n        assert params['model'] == 'gpt-6-luna'")
            f.child_script(script.replace('WIRE_LITERAL',repr(EXPECTED_WIRE_SCHEMA)).replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertTrue(output['completed'])
            wire = [r for r in map(json.loads,f.marker.read_text().splitlines()) if r['kind'] == 'wire']
            self.assertEqual(len(wire),33)
            self.assertEqual({r['sha'] for r in wire},{f.provider_sha})

    def test_application_invalid_case_text_is_preserved_after_successful_terminal(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("notify('item/completed',", "if turn_number == 2: response = ''\n        if turn_number == 3: response = '{bad json'\n        notify('item/completed',")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertTrue(output['completed'])
            self.assertEqual(output['cases'][0]['status'],'SUCCESS')
            self.assertEqual(output['cases'][0]['responseText'],'')
            self.assertEqual(output['cases'][1]['responseText'],'{bad json')
            self.assertEqual(output['cases'][1]['storedTextSha256'],digest(b'{bad json'))

    def test_new_grant_cannot_relaunch_a_previous_live_run(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            f.ledger.write_text(json.dumps({'formatVersion':1,'ledgerId':'fake-ledger','runs':[{'grantId':'previous-grant','grantSha256':'a'*64,'requestSha256':'b'*64,'freezeSha256':'c'*64,'configurationSha256':'d'*64,'state':'STOPPED','attempts':[{'sequence':1,'kind':'QUALIFICATION','caseId':None,'recordedAt':'synthetic','state':'CONSUMED'}]}]}))
            before = f.ledger.read_bytes()
            with self.assertRaisesRegex(runner.Rejected,'^live rerun$'):
                asyncio.run(f.run())
            self.assertEqual(f.ledger.read_bytes(),before)
            self.assertFalse(f.marker.exists())

    def test_one_zero_attempt_startup_recovery_preserves_history_and_budget(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            f.child_script(FAKE_STUDY.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            previous = {'grantId':'previous-startup','grantSha256':'a'*64,'requestSha256':f.grant_data['requestSha256'],'freezeSha256':f.grant_data['freezeSha256'],'configurationSha256':f.grant_data['configurationSha256'],'state':'STOPPED','attempts':[]}
            f.ledger.write_text(json.dumps({'formatVersion':1,'ledgerId':'fake-ledger','runs':[previous]}))
            output = asyncio.run(f.run())
            history = json.loads(f.ledger.read_text())
            self.assertTrue(output['completed'])
            self.assertTrue(output['cleanupVerified'])
            self.assertEqual(history['runs'][0],previous)
            self.assertEqual(len(history['runs']),2)
            self.assertEqual(sum(len(r['attempts']) for r in history['runs']),33)
            self.assertEqual(history['runs'][1]['attempts'][0]['sequence'],1)
            self.assertEqual(output['counters']['qualificationAttempts'],1)
            self.assertEqual(output['counters']['caseAttempts'],32)

    def test_startup_recovery_rejects_changed_inputs_cancelled_or_used_turn(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            previous = {'grantId':'previous-startup','grantSha256':'a'*64,'requestSha256':f.grant_data['requestSha256'],'freezeSha256':f.grant_data['freezeSha256'],'configurationSha256':f.grant_data['configurationSha256'],'state':'STOPPED','attempts':[]}
            for mutation in ('requestSha256','freezeSha256','cancelled','used'):
                with self.subTest(mutation=mutation):
                    runs = [copy.deepcopy(previous)]
                    if mutation == 'cancelled':runs[0]['state']='CANCELLED'
                    elif mutation == 'used':runs[0]['attempts']=[{'sequence':1,'kind':'QUALIFICATION','caseId':None,'recordedAt':'synthetic','state':'CONSUMED'}]
                    else:runs[0][mutation]='0'*64
                    f.ledger.write_text(json.dumps({'formatVersion':1,'ledgerId':'fake-ledger','runs':runs}))
                    before=f.ledger.read_bytes()
                    with self.assertRaisesRegex(runner.Rejected,'^live rerun$'):
                        asyncio.run(f.run())
                    self.assertEqual(f.ledger.read_bytes(),before)
                    self.assertFalse(f.marker.exists())

    def test_literal_cli_paths_and_nonempty_integration_table_values(self):
        args=runner.launch_overrides(runner.MANIFEST,{'mcp_servers':{'a.b'},'plugins':{'x'},'apps':{'y'}})
        self.assertIn('model="gpt-6-luna"',args)
        self.assertIn('features.remote_control=false',args)
        self.assertIn('tools.update_plan.enabled=false',args)
        self.assertIn('mcp_servers={"a.b"={enabled=false}}',args)
        self.assertIn('apps={"_default"={enabled=false},"y"={enabled=false}}',args)
        self.assertFalse(any(arg.startswith('"') for arg in args))

    def test_disabled_remote_status_is_passive_but_other_states_poison(self):
        for status in ('disabled','connecting','connected','errored',None):
            with self.subTest(status=status),tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f=Fixture(d)
                script=FAKE_STUDY.replace("reply(request, {'userAgent':'fake'})", "notify('remoteControl/status/changed',{'status':STATUS_LITERAL,'installationId':'synthetic-private-id','serverName':'synthetic-private-server'})\n        reply(request, {'userAgent':'fake'})").replace('STATUS_LITERAL',repr(status))
                f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
                output=asyncio.run(f.run())
                self.assertEqual(output['completed'],status=='disabled')
                self.assertEqual(output['counters']['totalAttempts'],33 if status=='disabled' else 0)
                self.assertTrue(output['cleanupVerified'])
                self.assertNotIn('synthetic-private',f.output.read_text())

    def test_account_notification_checks_chatgpt_and_discards_plan(self):
        for auth in ('chatgpt','apikey',None):
            with self.subTest(auth=auth),tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f=Fixture(d)
                script=FAKE_STUDY.replace("reply(request, {'userAgent':'fake'})", "notify('account/updated',{'authMode':AUTH_LITERAL,'planType':'synthetic-private-plan'})\n        reply(request, {'userAgent':'fake'})").replace('AUTH_LITERAL',repr(auth))
                f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
                output=asyncio.run(f.run())
                self.assertEqual(output['completed'],auth=='chatgpt')
                self.assertEqual(output['counters']['totalAttempts'],33 if auth=='chatgpt' else 0)
                self.assertTrue(output['cleanupVerified'])
                self.assertNotIn('synthetic-private-plan',f.output.read_text())
        message={'method':'account/updated','params':{'authMode':'chatgpt','planType':'synthetic-private-plan'}}
        runner.check_event(message)
        self.assertEqual(message['params'],{'authMode':'chatgpt'})

    def test_read_only_sandbox_accepts_default_or_explicit_false_and_rejects_widening(self):
        for extra in ({},{'networkAccess':False},{'networkAccess':True},{'networkAccess':0},{'writableRoots':['/synthetic']}):
            with self.subTest(extra=extra),tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f=Fixture(d)
                script=FAKE_STUDY.replace("'sandbox':{'type':'readOnly'}", "'sandbox':"+repr(dict(type='readOnly',**extra)))
                f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
                output=asyncio.run(f.run())
                expected=not extra or extra=={'networkAccess':False} and type(extra['networkAccess']) is bool
                self.assertEqual(output['completed'],expected)
                self.assertEqual(output['counters']['totalAttempts'],33 if expected else 0)
                self.assertTrue(output['cleanupVerified'])

    def test_hidden_tool_flags_use_highest_active_layer_and_never_retain_raw_layers(self):
        for enabled,disabled in ((False,False),(True,False),(True,True)):
            with self.subTest(enabled=enabled,disabled=disabled),tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f=Fixture(d)
                script=FAKE_STUDY.replace("'layers':[{'name':{'type':'sessionFlags'},'version':'fake','config':policy}]", "'layers':[{'name':{'type':'sessionFlags'},'version':'higher','disabledReason':DISABLED_LITERAL,'config':{'tools':{'update_plan':{'enabled':ENABLED_LITERAL}},'secret':'synthetic-private-layer'}},{'name':{'type':'sessionFlags'},'version':'fake','config':policy}]").replace('DISABLED_LITERAL',repr('disabled' if disabled else None)).replace('ENABLED_LITERAL',repr(enabled))
                f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
                output=asyncio.run(f.run())
                expected=not enabled or disabled
                self.assertEqual(output['completed'],expected)
                self.assertEqual(output['counters']['totalAttempts'],33 if expected else 0)
                self.assertNotIn('synthetic-private-layer',f.output.read_text())

    def test_reviewed_startup_profile_can_change_before_first_turn_without_reset(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f=Fixture(d)
            f.child_script(FAKE_STUDY.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            previous=[{'grantId':'startup-'+str(i),'grantSha256':'a'*64,'requestSha256':f.grant_data['requestSha256'],'freezeSha256':f.grant_data['freezeSha256'],'configurationSha256':'b'*64,'state':'STOPPED','attempts':[]} for i in range(2)]
            f.ledger.write_text(json.dumps({'formatVersion':1,'ledgerId':'fake-ledger','runs':previous}))
            output=asyncio.run(f.run())
            history=json.loads(f.ledger.read_text())
            self.assertTrue(output['completed'])
            self.assertEqual(history['runs'][:2],previous)
            self.assertEqual(sum(len(r['attempts']) for r in history['runs']),33)

    def test_qualification_cross_field_mismatch_stops_after_one_attempt(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("'target':'Home'", "'target':None")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertEqual(output['stopReason'],'QUALIFICATION_FAILED')
            self.assertFalse(output['completed'])
            self.assertEqual(output['counters']['totalAttempts'],1)
            self.assertEqual(output['counters']['caseAttempts'],0)
            self.assertTrue(output['cleanupVerified'])

    def test_terminal_case_failure_and_redaction_remain_typed(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("'status':'completed','items':[],'error':None", "'status':'failed' if turn_number == 2 else 'completed','items':[],'error':None")
            script = script.replace("'reason':'Literal visible target'", "'reason':'Literal visible target' if turn_number != 3 else 'sk-abcdefghijklmnopqrstuv' ")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertTrue(output['completed'])
            self.assertEqual(output['cases'][0]['status'],'FAILURE')
            self.assertEqual(output['cases'][0]['failureKind'],'TRANSPORT')
            self.assertIsNone(output['cases'][0]['responseText'])
            row = output['cases'][1]
            self.assertEqual(row['status'],'SUCCESS')
            self.assertEqual(row['redactionStatus'],'REDACTED')
            self.assertEqual(row['storedTextSha256'],digest(row['responseText'].encode()))
            self.assertIsNotNone(row['originalResponseSha256'])
            self.assertNotIn('sk-abcdefghijklmnopqrstuv',f.output.read_text())

    def test_late_tool_after_final_text_revokes_study_success(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("notify('turn/completed',", "notify('item/completed', {'threadId':params['threadId'],'turnId':tid,'item':{'type':'functionCallOutput'}})\n        notify('turn/completed',")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertFalse(output['completed'])
            self.assertIsNone(output['qualification']['responseText'])
            self.assertEqual(output['counters']['totalAttempts'],1)
            self.assertTrue(output['cleanupVerified'])

    def test_owned_turn_timeout_recovers_only_after_interrupt_and_unsubscribe(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("response = json.dumps(", "if turn_number == 2: continue\n        response = json.dumps(")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run(timeout_limits={'turnSeconds':0.2,'cleanupSeconds':0.3,'startupSeconds':1,'wallSeconds':5}))
            self.assertTrue(output['completed'])
            self.assertEqual(output['cases'][0]['status'],'FAILURE')
            self.assertEqual(output['cases'][0]['failureKind'],'TIMEOUT')
            self.assertIsNone(output['cases'][0]['responseText'])
            self.assertEqual(output['counters']['totalAttempts'],33)
            methods = [r['method'] for r in map(json.loads,f.marker.read_text().splitlines()) if r['kind']=='request']
            index = methods.index('turn/interrupt')
            self.assertEqual(methods[index:index+3],['turn/interrupt','thread/unsubscribe','thread/start'])

    def test_shutdown_callback_prevents_success_even_after_all_answers(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY + "\nif thread_number > 0:\n    print(json.dumps({'id':'shutdown-callback','method':'item/tool/call','params':{}}),flush=True)\n"
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertFalse(output['completed'])
            self.assertEqual(output['stopReason'],'POISONED')
            self.assertTrue(output['cleanupVerified'])
            self.assertEqual(output['counters']['totalAttempts'],33)

    def test_failed_qualification_retains_verified_instruction_counts(self):
        for failure in ('poison','timeout'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
                f=Fixture(d)
                source=str(pathlib.Path.home()/'.codex/AGENTS.md')
                script=FAKE_STUDY.replace("'instructionSources':[]", "'instructionSources':"+repr([source]))
                stop="notify('item/tool/call',{});continue" if failure=='poison' else 'continue'
                script=script.replace("tid = 'turn-%d'%turn_number", "tid = 'turn-%d'%turn_number\n        "+stop)
                f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
                output=asyncio.run(f.run(timeout_limits={'turnSeconds':0.3,'cleanupSeconds':0.3,'startupSeconds':1,'wallSeconds':3}))
                self.assertFalse(output['completed'])
                self.assertEqual(output['counters']['totalAttempts'],1)
                self.assertEqual(output['counters']['caseAttempts'],0)
                self.assertEqual(output['runMetadata']['globalInstructionSources'],[{'source':'USER_GLOBAL','count':1}])
                self.assertEqual(json.loads(f.output.read_text())['runMetadata']['globalInstructionSources'],[{'source':'USER_GLOBAL','count':1}])
                self.assertNotIn(source,f.output.read_text())
                self.assertTrue(output['cleanupVerified'])

    def test_startup_deadline_is_one_owned_budget_not_a_wall_limit(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("reply(request, {'userAgent':'fake'})", "import time;time.sleep(2)\n        reply(request, {'userAgent':'fake'})")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run(timeout_limits={'startupSeconds':0.15,'cleanupSeconds':0.2,'turnSeconds':1,'wallSeconds':3}))
            self.assertEqual(output['stopReason'],'QUALIFICATION_FAILED')
            self.assertEqual(output['counters']['totalAttempts'],0)
            self.assertTrue(output['cleanupVerified'])
            self.assertLess(output['runMetadata']['wallDurationMillis'],1000)

    def test_reused_ephemeral_thread_is_rejected_before_second_attempt(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("'thread':{'id':'thread-%d'%thread_number}","'thread':{'id':'thread-1'}")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertFalse(output['completed'])
            self.assertEqual(output['counters']['totalAttempts'],1)
            self.assertEqual(output['counters']['caseAttempts'],0)

    def test_ledger_cannot_overwrite_a_review_artifact(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            f.ledger.write_text(json.dumps({'formatVersion':1,'ledgerId':'fake-ledger','runs':[]}))
            f.grant_data['paths']['candidateReview'] = str(f.ledger)
            f.grant_data['candidateReviewSha256'] = digest(f.ledger.read_bytes())
            f.grant_data['pathsSha256'] = digest(f.grant_data['paths'])
            f.save_grant()
            before = f.ledger.read_bytes()
            with self.assertRaisesRegex(runner.Rejected,'^input collision$'):
                asyncio.run(f.run())
            self.assertEqual(f.ledger.read_bytes(),before)
            self.assertFalse(f.marker.exists())

    def test_more_than_64_streamed_deltas_without_queue_overflow_are_allowed(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            stream = "if turn_number == 1:\n            import time\n            for _ in range(80):\n                notify('item/agentMessage/delta', {'threadId':params['threadId'],'turnId':tid,'itemId':'answer','delta':'x'})\n                time.sleep(0.003)\n        notify('item/completed',"
            script = FAKE_STUDY.replace("notify('item/completed',",stream)
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertTrue(output['completed'])
            self.assertEqual(output['counters']['totalAttempts'],33)

    def test_correlated_model_errors_and_cli_retry_intent_do_not_poison(self):
        with tempfile.TemporaryDirectory(prefix='verity-fake-') as d:
            f = Fixture(d)
            script = FAKE_STUDY.replace("notify('item/completed',", "if turn_number in (2,3):\n            notify('error', {'threadId':params['threadId'],'turnId':tid,'error':{'message':'synthetic-private-provider-error'},'willRetry':turn_number == 3})\n        notify('item/completed',")
            script = script.replace("'status':'completed','items':[],'error':None", "'status':'failed' if turn_number == 2 else 'completed','items':[],'error':None")
            f.child_script(script.replace('POLICY_LITERAL',repr(runner.MANIFEST)).replace('MARKER_LITERAL',repr(str(f.marker))).replace('LEDGER_LITERAL',repr(str(f.ledger))))
            output = asyncio.run(f.run())
            self.assertTrue(output['completed'])
            self.assertEqual(output['cases'][0]['status'],'FAILURE')
            self.assertEqual(output['cases'][1]['status'],'SUCCESS')
            self.assertEqual(output['counters']['totalAttempts'],33)
            self.assertNotIn('synthetic-private-provider-error',f.output.read_text())


FAKE_BOOTSTRAP = r"""
import json, os, pathlib, sys
policy = POLICY_LITERAL
marker = pathlib.Path(MARKER_LITERAL)
def record(value):
    value['pid'] = os.getpid()
    with marker.open('a') as f:
        f.write(json.dumps(value) + '\n')
record({'kind':'launch','argv':sys.argv[1:],'cwd':os.getcwd(),'cwdFiles':os.listdir('.'),'environment':{k:os.environ[k] for k in ('HOME','CODEX_HOME','OPENAI_API_KEY','CODEX_API_KEY') if k in os.environ}})
for line in sys.stdin:
    request = json.loads(line)
    record({'kind':'request','method':request['method']})
    if 'id' not in request:
        continue
    if request['method'] == 'initialize':
        assert request['params']['capabilities'] == {'experimentalApi':True,'explicitGatewayOauth':True}
        result = {'userAgent':'fake'}
    elif request['method'] == 'config/read':
        if any('mcp_servers=' in arg for arg in sys.argv):
            policy['model'] = 'wrong-model'
        result = {'config':dict(policy,tools={}), 'origins':{}, 'layers':[{'name':{'type':'sessionFlags'},'version':'fake','config':policy}]}
    else:
        raise AssertionError(request['method'])
    print(json.dumps({'id':request['id'],'result':result}),flush=True)
"""


FAKE_STUDY = r"""
import json, os, pathlib, sys
policy = POLICY_LITERAL
marker = pathlib.Path(MARKER_LITERAL)
ledger = pathlib.Path(LEDGER_LITERAL)
turn_number = 0
thread_number = 0
def record(value):
    value['pid'] = os.getpid()
    with marker.open('a') as f:
        f.write(json.dumps(value) + '\n')
def reply(request, result):
    print(json.dumps({'id':request['id'],'result':result}),flush=True)
def notify(method, params):
    print(json.dumps({'method':method,'params':params}),flush=True)
record({'kind':'launch','argv':sys.argv[1:],'cwd':os.getcwd(),'cwdFiles':os.listdir('.')})
for line in sys.stdin:
    request = json.loads(line)
    if 'method' not in request:
        record({'kind':'refusal','error':request.get('error')})
        continue
    method = request['method']
    params = request.get('params', {})
    record_data = {'kind':'request','method':method}
    if method == 'turn/start':
        turn_number += 1
        record_data['durableAttempts'] = len(json.loads(ledger.read_text())['runs'][-1]['attempts'])
        assert record_data['durableAttempts'] == turn_number
    record(record_data)
    if 'id' not in request:
        continue
    if method == 'initialize':
        assert params['capabilities'] == {'experimentalApi':True,'explicitGatewayOauth':True}
        reply(request, {'userAgent':'fake'})
    elif method == 'config/read':
        reply(request, {'config':dict(policy,tools={}), 'origins':{}, 'layers':[{'name':{'type':'sessionFlags'},'version':'fake','config':policy}]})
    elif method == 'account/read':
        assert params == {'refreshToken':False}
        reply(request, {'account':{'type':'chatgpt','email':'synthetic-private@example.invalid','planType':'plus'},'requiresOpenaiAuth':True})
    elif method == 'model/list':
        if params.get('cursor') is None:
            reply(request, {'data':[{'id':'other','model':'other','inputModalities':['text'],'supportedReasoningEfforts':[{'reasoningEffort':'low'}]}],'nextCursor':'page-2'})
        else:
            assert params['cursor'] == 'page-2'
            reply(request, {'data':[{'id':'gpt-6-luna','model':'gpt-6-luna','inputModalities':['text'],'supportedReasoningEfforts':[{'reasoningEffort':'low'}]}],'nextCursor':None})
    elif method == 'thread/start':
        thread_number += 1
        assert params['ephemeral'] is True and params['allowProviderModelFallback'] is False and params['experimentalRawEvents'] is True
        assert params['dynamicTools'] == params['environments'] == params['runtimeWorkspaceRoots'] == params['selectedCapabilityRoots'] == []
        assert params['cwd'] == os.getcwd() and params['baseInstructions'] == policy['instructions'] and params['developerInstructions'] == ''
        assert params['config'] == policy
        reply(request, {'thread':{'id':'thread-%d'%thread_number},'model':'gpt-6-luna','modelProvider':'openai','approvalPolicy':'never','approvalsReviewer':'user','sandbox':{'type':'readOnly'},'cwd':os.getcwd(),'serviceTier':'default','reasoningEffort':'low','runtimeWorkspaceRoots':[],'instructionSources':[]})
    elif method == 'turn/start':
        assert params['model'] == 'gpt-6-luna' and params['effort'] == 'low' and params['serviceTier'] == 'default'
        assert len(params['input']) == 1 and params['input'][0]['type'] == 'text'
        assert params['input'][0]['text_elements'] == []
        assert params['environments'] == params['runtimeWorkspaceRoots'] == []
        text = params['input'][0]['text']
        assert 'acceptableChecks' not in text and 'evidence' not in text and 'caseId' not in text
        assert params['threadId'] == 'thread-%d'%thread_number
        tid = 'turn-%d'%turn_number
        reply(request, {'turn':{'id':tid,'status':'inProgress','items':[]}})
        response = json.dumps({'mode':'visible','target':'Home','confidence':1.0,'uncertain':False,'reason':'Literal visible target'})
        notify('item/completed', {'threadId':params['threadId'],'turnId':tid,'item':{'type':'agentMessage','id':'answer','phase':'final_answer','text':response}})
        notify('turn/completed', {'threadId':params['threadId'],'turn':{'id':tid,'status':'completed','items':[],'error':None}})
    elif method in ('thread/unsubscribe','turn/interrupt'):
        reply(request, {})
    else:
        raise AssertionError(method)
"""


if __name__ == '__main__':
    unittest.main()
