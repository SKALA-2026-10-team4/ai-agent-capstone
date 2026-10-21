"""Explicit offline C-shaped fixtures. Not a real execution or judgment implementation."""

from datetime import UTC, datetime
from uuid import uuid4

from .contracts import ContractError, execution_uuid

BASELINE = "11111111-1111-4111-8111-111111111111"
CANDIDATE = "22222222-2222-4222-8222-222222222222"
EXECUTION = "33333333-3333-4333-8333-333333333333"


class StubMCPTools:
    BASELINE = BASELINE
    CANDIDATE = CANDIDATE
    EXECUTION = EXECUTION
    _traces: dict = {}
    _workspaces: dict = {}

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass

    async def ping(self):
        return f"pong {datetime.now(UTC).isoformat()}"

    async def list_failed_runs(self):
        return {"items": [{"execution_id": EXECUTION, "snapshot_id": CANDIDATE}]}

    async def get_snapshot(self, snapshot_id):
        if snapshot_id not in (BASELINE, CANDIDATE):
            raise ContractError("unknown stub snapshot")
        return {
            "snapshot_id": snapshot_id,
            "hash": "snapshot-" + snapshot_id,
            "metadata": {"baseline_snapshot_id": BASELINE},
            "files": [
                {
                    "path": "skills/inventory-check/SKILL.md",
                    "hash": "old" if snapshot_id == BASELINE else "new",
                }
            ],
        }

    async def list_skills(self, snapshot_id):
        return {
            "snapshot_id": snapshot_id,
            "snapshot_hash": "snapshot-" + snapshot_id,
            "items": [
                {"skill_id": s, "version": 1} for s in ("order-flow", "inventory-check", "payment")
            ],
            "partial": False,
            "parse_errors": [],
        }

    async def get_skill(self, snapshot_id, skill_id):
        return {"snapshot_id": snapshot_id, "skill_id": skill_id, "version": 1,
                "content": "---\nname: " + skill_id + "\noutputs: []\n---\nbody\n"}

    async def get_trace(self, execution_id):
        execution_id = execution_uuid(execution_id)
        if execution_id in self._traces:
            return self._traces[execution_id]
        if execution_id != EXECUTION:
            raise ContractError("unknown stub execution")
        return {
            "execution_id": EXECUTION,
            "snapshot_id": CANDIDATE,
            "snapshot_hash": "snapshot-" + CANDIDATE,
            "completion": "FAILED",
            "final_status": "FAILED",
            "events": [
                {
                    "kind": "SKILL_SELECTED",
                    "skill_id": None,
                    "data": {"raw_name": "order-flow", "resolved_skill_id": None},
                },
                {
                    "kind": "EXECUTION_ERROR",
                    "skill_id": "payment",
                    "data": {
                        "category": "CONTRACT_VIOLATION",
                        "skill_id": "payment",
                        "missing_inputs": ["inventory.reserved"],
                    },
                },
            ],
        }

    async def diff_skill(self, skill_id, before_snapshot_id, after_snapshot_id):
        return {
            "skill_id": skill_id,
            "before": {
                "snapshot_id": before_snapshot_id,
                "snapshot_hash": "snapshot-" + before_snapshot_id,
                "file_hash": "old",
            },
            "after": {
                "snapshot_id": after_snapshot_id,
                "snapshot_hash": "snapshot-" + after_snapshot_id,
                "file_hash": "new",
            },
            "status": "modified",
            "hunks": [
                {
                    "index": 0,
                    "hunk_id": "fixture-hunk",
                    "part": "frontmatter",
                    "header": "@@ -1 +1 @@",
                    "before": "outputs: [inventory.reserved]",
                    "after": "outputs: []",
                    "diff": "@@ -1 +1 @@\n-outputs: [inventory.reserved]\n+outputs: []\n",
                }
            ],
        }

    async def get_dependency_graph(self, snapshot_id, entry_skill_id):
        if entry_skill_id != "order-flow":
            raise ContractError("INVALID_FLOW")
        return {
            "snapshot_id": snapshot_id,
            "entry_skill_id": entry_skill_id,
            "complete": True,
            "steps": ["inventory-check", "payment"],
            "edges": [],
            "missing": [],
            "ambiguous": [],
            "nodes": [],
        }

    async def create_workspace(self, start_versions=None, snapshot_id=None):
        if snapshot_id not in (BASELINE, CANDIDATE):
            raise ContractError("stub requires candidate snapshot")
        workspace_id = "w_" + uuid4().hex
        self._workspaces[workspace_id] = snapshot_id == BASELINE
        return {
            "workspace_id": workspace_id,
            "skill_versions": {"inventory-check": 1, "payment": 1},
        }

    async def apply_patch(self, workspace_id, patches):
        if not patches or workspace_id not in self._workspaces:
            raise ContractError("invalid stub patch request")
        self._workspaces[workspace_id] = True
        return {
            "workspace_id": workspace_id,
            "changed_skills": [
                {"skill": p["skill"], "version": p["base_version"] + 1} for p in patches
            ],
            "broken_links": [],
        }

    async def run_scenarios(
        self, workspace_id, scenario_ids, repeat=2, replay_run_ids=None, extra_checks=None
    ):
        ids = list(scenario_ids or []) + ["f-" + execution_uuid(i) for i in replay_run_ids or []]
        results = []
        for scenario in ids:
            if scenario not in ("g-01", "g-02", "g-03"):
                if not scenario.startswith("f-"):
                    raise ContractError("unknown scenario")
                execution_uuid(scenario[2:])
            passed = self._workspaces[workspace_id]
            runs = []
            for _ in range(repeat):
                execution = str(uuid4())
                run_id = "r_" + execution.replace("-", "")
                runs.append(run_id)
                self._traces[execution] = {
                    "execution_id": execution,
                    "events": [
                        {
                            "kind": "SKILL_FINISHED",
                            "skill_id": "payment",
                            "data": {"output": {"payment.status": "PAID"}},
                        },
                        {
                            "kind": "TOOL_REQUEST",
                            "skill_id": "inventory-check",
                            "data": {"tool": "inventory.reserve"},
                        },
                    ],
                }
            results.append(
                {
                    "scenario_id": scenario,
                    "passed": passed,
                    "run_ids": runs,
                    "failures": []
                    if passed
                    else [
                        {"run_id": i, "skill": "payment", "reason": "CONTRACT_VIOLATION"}
                        for i in runs
                    ],
                }
            )
        return {
            "workspace_id": workspace_id,
            "all_passed": all(r["passed"] for r in results),
            "results": results,
        }
