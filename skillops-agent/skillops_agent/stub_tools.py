"""service 없이 개발하고 테스트할 때 쓰는 C 모양 고정 응답 도구(StubMCPTools)다.

실제 실행이나 판정을 구현하지 않는다. skillops-agent/skillops_agent/tools.py 의 MCPTools 와 같은
이름과 매개변수의 async 메서드를 두고, 진단 노드의 교차 검사를 통과할 만큼만 서로 맞는 응답을
돌려준다. snapshot 해시는 'snapshot-<id>' 규칙(_snapshot_hash)을 따르고, 파일 해시는 BASELINE 이
old 이고 그 밖이 new 다. 그래서 manifest, skill 목록, trace, diff 가 서로 맞는다.

workspace 통과 여부(_workspaces)와 trial trace(_traces)는 클래스 공유 dict 에 남긴다. 노드마다
get_tools() 가 새 인스턴스를 만들어도, 앞 노드가 만든 workspace 와 trial 을 뒤 노드가 읽어야 하기
때문이다.

연결부는 다음과 같다.
- skillops-agent/skillops_agent/tools.py 의 get_tools 가 SKILLOPS_USE_STUB_MCP=true 일 때 이
  클래스를 만든다.
- skillops-agent/tests/test_graph.py 와 skillops-agent/tests/test_flow_characterization.py 가
  skillops-agent/skillops_agent/nodes.py 의 get_tools 를 stub 인스턴스 하나로 바꾸고, _workspaces 를
  직접 조정하며 saved 를 확인한다. skillops-agent/tests/test_refactor_equivalence.py 도 get_tools 를
  바꾸고 _workspaces 를 조정한다.
- skillops-agent/tests/test_stub_tools.py 가 고정 응답과 MCPTools 와의 시그니처 대칭을 고정한다.

통과와 실패 규칙은 create_workspace 와 apply_patch 가 _workspaces 에 넣는 값으로 정한다. 고정
UUID 와 golden 시나리오 목록은 아래 모듈 상수에서 바꾼다.
"""

from datetime import UTC, datetime
from uuid import uuid4

from .contracts import ContractError, execution_uuid

# 고정 UUID 세 개다. BASELINE 은 정상 기준 snapshot, CANDIDATE 는 깨진 저장본 snapshot, EXECUTION 은
# CANDIDATE 에서 실패한 고정 실행이다. 테스트는 StubMCPTools.BASELINE 처럼 클래스 속성으로도 읽는다.
BASELINE = "11111111-1111-4111-8111-111111111111"
CANDIDATE = "22222222-2222-4222-8222-222222222222"
EXECUTION = "33333333-3333-4333-8333-333333333333"
# get_snapshot 과 create_workspace 가 아는 snapshot 이다.
_KNOWN_SNAPSHOTS = (BASELINE, CANDIDATE)
# run_scenarios 가 받는 golden 시나리오 번호다. skillops-agent/skillops_agent/config.py 의
# GOLDEN_IDS 기본값과 같아야 노드의 회귀 시험이 이 stub 에서 돈다.
_GOLDEN_FIXTURE_IDS = ("g-01", "g-02", "g-03")


def _snapshot_hash(snapshot_id):
    """stub snapshot 의 해시를 'snapshot-<id>' 로 만든다.

    manifest, skill 목록, trace, diff 가 모두 이 규칙을 써서
    skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 교차 검사를 통과한다. 문자열 결합이라
    snapshot_id 가 None 이면 TypeError 가 난다.
    """
    return "snapshot-" + snapshot_id


def _failed_execution_trace():
    """고정 실패 실행(EXECUTION)의 trace 를 부를 때마다 새로 만든다.

    CANDIDATE snapshot 에서 payment 가 inventory.reserved 입력을 받지 못해 CONTRACT_VIOLATION 으로
    멈춘 실패를 흉내 낸다. 부르는 쪽이 결과를 고쳐도 다음 응답이 그대로이도록 매번 새 dict 를
    만든다.
    """
    return {
        "execution_id": EXECUTION,
        "snapshot_id": CANDIDATE,
        "snapshot_hash": _snapshot_hash(CANDIDATE),
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


def _passing_trial_trace(execution):
    """run_scenarios 가 trial 마다 기록하는 통과 trace 를 만든다.

    payment 가 payment.status=PAID 를 내놓고 inventory-check 가 inventory.reserve 도구를 요청한
    기록이다. 노드는 이 기록에서 context 값과 부른 도구를
    읽는다(skillops-agent/skillops_agent/nodes.py 의 _known_values 와 _verify_candidate).
    """
    return {
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


def _planned_scenarios(scenario_ids, replay_run_ids):
    """받은 시나리오 뒤에 replay 실행을 f-<하이픈 UUID> 시나리오로 붙인 목록을 만든다.

    실행 전에 목록을 모두 만들므로, 잘못된 replay 번호는 trial 을 하나도 남기기 전에
    ContractError 가 된다. run_scenarios 가 쓴다.
    """
    return list(scenario_ids or []) + ["f-" + execution_uuid(i) for i in replay_run_ids or []]


def _check_scenario(scenario):
    """시나리오 번호가 golden 세 개나 f-<UUID> 가 아니면 ContractError 를 던진다.

    f- 뒤가 UUID 가 아니면 skillops-agent/skillops_agent/contracts.py 의 execution_uuid 가
    ContractError 를 던진다. run_scenarios 가 시나리오마다 실행 직전에 부른다.
    """
    if scenario not in _GOLDEN_FIXTURE_IDS:
        if not scenario.startswith("f-"):
            raise ContractError("unknown scenario")
        execution_uuid(scenario[2:])


def _scenario_result(scenario, passed, run_ids):
    """시나리오 하나의 결과 dict 를 만든다.

    실패면 run 마다 payment 의 CONTRACT_VIOLATION 실패를 남긴다. 칸은 service run_scenarios 결과의
    results 항목을 따르지만 replay_of_run_id 칸은 없다.
    """
    return {
        "scenario_id": scenario,
        "passed": passed,
        "run_ids": run_ids,
        "failures": []
        if passed
        else [
            {"run_id": i, "skill": "payment", "reason": "CONTRACT_VIOLATION"}
            for i in run_ids
        ],
    }


class StubMCPTools:
    """skillops-agent/skillops_agent/tools.py 의 MCPTools 를 흉내 내는 오프라인 도구다.

    실제 실행이나 판정은 하지 않는다. BASELINE, CANDIDATE, EXECUTION 을 클래스 속성으로도 내보낸다.
    _traces 와 _workspaces 는 클래스 공유 dict 이고, saved 는 인스턴스마다 따로다.
    skillops-agent/tests/ 의 test_graph.py, test_flow_characterization.py,
    test_refactor_equivalence.py 가 이 이름과 수준(클래스와 인스턴스)에 기대므로 바꾸지 않는다. 공개
    async 메서드의 이름, 매개변수, 기본값은 MCPTools 와 같아야 한다.
    """

    BASELINE = BASELINE
    CANDIDATE = CANDIDATE
    EXECUTION = EXECUTION
    # run_scenarios 가 남긴 trial trace 다. 키는 하이픈 UUID 실행 번호다.
    _traces: dict = {}
    # workspace_id 에서 통과 여부로의 매핑이다. run_scenarios 가 이 값으로 passed 를 정한다.
    _workspaces: dict = {}

    def __init__(self):
        """인스턴스마다 save_proposal 로 받은 제안서를 쌓을 목록(saved)을 새로 만든다."""
        # save_proposal 로 받은 제안서다. 테스트가 노드가 보낸 값을 이 목록으로 확인한다.
        self.saved: list[dict] = []

    async def __aenter__(self):
        """열 연결이 없으므로 자기 자신을 그대로 돌려준다."""
        return self

    async def __aexit__(self, *args):
        """닫을 연결이 없으므로 아무것도 하지 않는다."""
        pass

    async def ping(self):
        """service 의 ping 도구처럼 'pong <UTC ISO 시각>' 을 돌려준다.

        흉내 내는 도구는 service/src/main/java/com/capstone4/skillops/mcp/PingTools.java 의
        ping() 이다.
        """
        return f"pong {datetime.now(UTC).isoformat()}"

    async def list_failed_runs(self):
        """고정 실패 실행(EXECUTION) 하나만 담은 실패 목록을 돌려준다."""
        return {"items": [{"execution_id": EXECUTION, "snapshot_id": CANDIDATE}]}

    async def get_snapshot(self, snapshot_id):
        """BASELINE 이나 CANDIDATE 의 manifest 를 돌려주고, 모르는 snapshot 이면 ContractError 다.

        파일 하나(skills/inventory-check/SKILL.md)의 해시가 BASELINE 은 old, CANDIDATE 는 new 라서
        두 snapshot 의 차이가 이 파일 하나로 잡힌다. metadata.baseline_snapshot_id 는 늘
        BASELINE 이다.
        """
        if snapshot_id not in _KNOWN_SNAPSHOTS:
            raise ContractError("unknown stub snapshot")
        return {
            "snapshot_id": snapshot_id,
            "hash": _snapshot_hash(snapshot_id),
            "metadata": {"baseline_snapshot_id": BASELINE},
            "files": [
                {
                    "path": "skills/inventory-check/SKILL.md",
                    "hash": "old" if snapshot_id == BASELINE else "new",
                }
            ],
        }

    async def list_skills(self, snapshot_id):
        """어떤 snapshot 이든 order-flow, inventory-check, payment 세 skill 을 돌려준다.

        version 은 모두 1 이고, snapshot_hash 는 _snapshot_hash 규칙을 따른다.
        """
        return {
            "snapshot_id": snapshot_id,
            "snapshot_hash": _snapshot_hash(snapshot_id),
            "items": [
                {"skill_id": s, "version": 1} for s in ("order-flow", "inventory-check", "payment")
            ],
            "partial": False,
            "parse_errors": [],
        }

    async def get_skill(self, snapshot_id, skill_id):
        """skill_id 이름만 넣은 최소 SKILL.md 를 돌려준다.

        frontmatter 에는 name 과 빈 outputs 만 있고 본문은 body 한 줄이다.
        """
        return {"snapshot_id": snapshot_id, "skill_id": skill_id, "version": 1,
                "content": "---\nname: " + skill_id + "\noutputs: []\n---\nbody\n"}

    async def get_trace(self, execution_id):
        """실행 기록을 돌려준다. r_ 별칭도 하이픈 UUID 로 바꿔 찾는다.

        run_scenarios 가 남긴 trial trace 를 먼저 찾아 그 객체를 그대로 돌려주고, 없으면
        EXECUTION 일 때만 고정 실패 trace 를 새로 만든다. 그 밖의 실행이면 ContractError 다.
        """
        execution_id = execution_uuid(execution_id)
        if execution_id in self._traces:
            return self._traces[execution_id]
        if execution_id != EXECUTION:
            raise ContractError("unknown stub execution")
        return _failed_execution_trace()

    async def diff_skill(self, skill_id, before_snapshot_id, after_snapshot_id):
        """outputs 에서 inventory.reserved 가 빠진 덩어리 하나를 C-1 hunks 형식으로 돌려준다.

        인자와 상관없이 같은 frontmatter 덩어리다. file_hash 는 before 가 old, after 가 new 로
        고정이고 snapshot 해시만 인자를 따른다.
        """
        return {
            "skill_id": skill_id,
            "before": {
                "snapshot_id": before_snapshot_id,
                "snapshot_hash": _snapshot_hash(before_snapshot_id),
                "file_hash": "old",
            },
            "after": {
                "snapshot_id": after_snapshot_id,
                "snapshot_hash": _snapshot_hash(after_snapshot_id),
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
        """order-flow 만 아는 완전한 흐름(inventory-check 다음 payment)을 돌려준다.

        다른 시작 skill 이면 INVALID_FLOW ContractError 를 던진다.
        """
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
        """snapshot_id 로 연습 공간을 만들어 _workspaces 에 통과 여부와 함께 등록한다.

        BASELINE 이면 통과 상태, CANDIDATE 면 실패 상태로 등록하고 번호와 skill_versions 를
        돌려준다. 알려진 snapshot 이 아니면 uuid4 를 쓰기 전에 ContractError 를 던진다.
        start_versions 는 받기만 하고 쓰지 않는다.
        """
        if snapshot_id not in _KNOWN_SNAPSHOTS:
            raise ContractError("stub requires candidate snapshot")
        workspace_id = "w_" + uuid4().hex
        self._workspaces[workspace_id] = snapshot_id == BASELINE
        return {
            "workspace_id": workspace_id,
            "skill_versions": {"inventory-check": 1, "payment": 1},
        }

    async def apply_patch(self, workspace_id, patches):
        """patches 가 있고 아는 workspace 면 그 workspace 를 통과 상태로 바꾼다.

        패치한 skill 마다 base_version + 1 을 changed_skills 로 돌려준다. 패치 내용은 보지 않으므로
        어떤 수정이든 고친 것으로 보고 입력·선행 단계 오류 목록은 늘 비어 있다. 패치가 없거나 모르는
        workspace 면 ContractError 다.
        """
        if not patches or workspace_id not in self._workspaces:
            raise ContractError("invalid stub patch request")
        self._workspaces[workspace_id] = True
        return {
            "workspace_id": workspace_id,
            "changed_skills": [
                {"skill": p["skill"], "version": p["base_version"] + 1} for p in patches
            ],
            "broken_links": [],
            "dependency_violations": [],
            "metadata_errors": [],
            "flow_errors": [],
        }

    async def run_scenarios(
        self, workspace_id, scenario_ids, repeat=2, replay_run_ids=None, extra_checks=None
    ):
        """시나리오마다 repeat 번 가짜 trial 을 기록하고 workspace 통과 상태로 결과를 만든다.

        시나리오 검사가 workspace 조회보다 먼저라서 모르는 시나리오는 ContractError, 모르는
        workspace 는 KeyError 다. 시나리오마다 검사한 뒤 바로 trial 을 남기므로, 뒤 시나리오가
        틀려도 앞 시나리오의 trial 은 _traces 에 남는다. extra_checks 는 받기만 하고 보지 않는다.
        """
        results = []
        for scenario in _planned_scenarios(scenario_ids, replay_run_ids):
            _check_scenario(scenario)
            passed = self._workspaces[workspace_id]
            runs = [self._record_trial() for _ in range(repeat)]
            results.append(_scenario_result(scenario, passed, runs))
        return {
            "workspace_id": workspace_id,
            "all_passed": all(r["passed"] for r in results),
            "results": results,
        }

    def _record_trial(self):
        """가짜 trial 하나의 통과 trace 를 클래스 공유 _traces 에 남기고 run_id 를 돌려준다.

        uuid4 로 새 실행 번호를 만들고, run_id 는 service 처럼 r_ + UUID hex 모양으로 만든다.
        """
        execution = str(uuid4())
        self._traces[execution] = _passing_trial_trace(execution)
        return "r_" + execution.replace("-", "")

    async def save_proposal(self, proposal):
        """받은 제안서를 이 인스턴스의 saved 에 쌓고 p_stub 번호와 valid 후보 번호를 돌려준다.

        service 와 달리 근거 실행과 합격 조건을 검사하지 않는다.
        """
        self.saved.append(proposal)
        return {
            "proposal_id": "p_stub" + uuid4().hex[:12],
            "status": proposal["status"],
            "valid_candidate_nos": [
                c["candidate_no"] for c in proposal["candidates"] if c.get("valid")
            ],
        }
