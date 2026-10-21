"""Offline probes of current B function bodies. No server, database or LLM calls.

Run from repository root: python3 docs/reports/b-c-contract-probe.py
AST extraction avoids importing optional LangGraph/MCP dependencies; only selected
unmodified function bodies are executed with explicit test doubles.
"""
from __future__ import annotations
import ast
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]

def extract(path, names, namespace, class_name=None):
    tree = ast.parse((ROOT / path).read_text())
    scope = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == class_name).body if class_name else tree.body
    body = [ast.ImportFrom(module='__future__', names=[ast.alias(name='annotations')], level=0)]
    body += [n for n in scope if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name in names]
    exec(compile(ast.fix_missing_locations(ast.Module(body=body, type_ignores=[])), str(path), 'exec'), namespace)

class FakeTools:
    def __init__(self, trace=None):
        self.trace = trace or {}
        self.calls = []
    async def __aenter__(self): return self
    async def __aexit__(self, *args): pass
    async def get_trace(self, value): return self.trace
    async def create_workspace(self, **kwargs):
        self.calls.append(['create_workspace', kwargs])
        return {'workspace_id': 'offline-workspace'}
    async def apply_patch(self, *args):
        self.calls.append(['apply_patch', args])
        return {'broken_links': []}
    async def run_scenarios(self, *args, **kwargs):
        self.calls.append(['run_scenarios', args, kwargs])
        return {'code': 'INVALID_INPUT', 'message': 'unknown scenario f-01', 'retryable': False}

async def main():
    fake = FakeTools({'snapshot_id': '11111111-1111-4111-8111-111111111111', 'events': [
        {'kind': 'SKILL_SELECTED', 'skill_id': None, 'data': {'raw_name': 'order-flow', 'resolved_skill_id': None}},
        {'kind': 'EXECUTION_ERROR', 'skill_id': 'payment', 'data': {'category': 'CONTRACT_VIOLATION'}}]})
    ns = {'get_tools': lambda: fake, '_tick': lambda *args: {}, '_event': lambda *args: {},
          'Patch': dict, 'AblationRun': dict}
    extract('skillops-agent/skillops_agent/nodes.py', ['fetch_evidence', '_error_event', '_check_intent', '_eval_check', 'ablate'], ns)
    results = []
    try:
        await ns['fetch_evidence']({'trigger': {'run_id': '22222222-2222-4222-8222-222222222222'}, 'events': []})
    except Exception as e:
        results.append({'probe': 'real_trace_shape', 'observed': f'{type(e).__name__}: {e}'})
    fake.trace = {'events': [{'kind': 'SKILL_FINISHED', 'skill_id': 'payment', 'data': {'output': {'payment.status': 'PAID'}}},
                             {'kind': 'EXECUTION_FINISHED', 'data': {'completion': 'COMPLETED', 'final_status': 'SHIPPED'}}]}
    results.append({'probe': 'intent_from_real_trace', 'expected': True,
                    'observed': await ns['_check_intent'](["payment.status == 'PAID'"], ['offline-run'], fake)})
    results.append({'probe': 'boolean_check', 'expected': True, 'observed': ns['_eval_check']('notify.sent == true', {'notify.sent': True})})
    results.append({'probe': 'invalid_check', 'expected': 'reject', 'observed': ns['_eval_check']('invalid', {})})
    state = {'trigger': {'skill': 'inventory-check'}, 'diff': {'hunks': [{'index': 0, 'before': 'old', 'after': 'new'}]},
             'trace': {}, 'skill_versions': {'inventory-check': 1}, 'hypotheses': [{'change_no': 0}]}
    ablation = await ns['ablate'](state)
    results.append({'probe': 'ablation_with_c_error', 'observed': ablation['ablation_runs'], 'calls': fake.calls})
    wrappers = {'json': json}
    extract('skillops-agent/skillops_agent/tools.py', ['_call'], wrappers, 'MCPTools')
    class Client:
        async def call_tool(self, name, args):
            return SimpleNamespace(is_error=True, content=[SimpleNamespace(text='{"code":"INVALID_INPUT","message":"bad request"}')])
    results.append({'probe': 'mcp_error_not_raised', 'observed': await wrappers['_call'](SimpleNamespace(_client=Client()), 'run_scenarios', {})})
    print(json.dumps({'method': 'offline selected-function probes with doubles; not an integration run', 'results': results}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    asyncio.run(main())
