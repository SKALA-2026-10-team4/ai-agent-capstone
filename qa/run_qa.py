"""SkillOps 평가셋 실행기다. 케이스 파일(qa/cases/*.yaml)을 읽어 세 명령으로 돌린다.

- list 는 케이스 목록과 정답을 표로 보여 준다.
- check 는 정답표의 사실 칸을 실제로 돌려 확인한다. 주문 Agent 반응, 원인(담당자 수정을 되돌리면
  통과하는지), 정답표의 안이 시험을 통과하는지를 본다. 주문 Agent 가 규칙 모드(fixed)면 모델 비용이
  없다. service 는 harness 실행기로 떠 있어야 한다(SKILLOPS_FLOW_RUNNER=harness,
  SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent).
- eval 은 SkillOps 진단을 실제로 돌려 정답표로 채점하고 지표를 낸다. 진단에 LLM 을 쓰는 케이스는
  비용이 든다.

SkillOps Agent 의 내부(skillops-agent/skillops_agent 의 노드, 상태 칸, 프롬프트)는 보지 않는다. eval
이 쓰는 것은 Agent 의 POST /diagnose 와 GET /runs/{thread_id}/events, service 의 GET
/api/proposals/{id}, 주문 Agent 의 snapshot·모의 실행 API 뿐이다. 그래서 AI 담당이 진단 흐름을
바꿔도 이 실행기는 그대로 돈다. 판정 규칙과 지표 계산은 qa/qa_logic.py 에 있고, 사용법과 규칙 표는
qa/README.md 에 있다.

qa 폴더에서 다음처럼 실행한다.

    uv run run_qa.py list
    uv run run_qa.py check
    uv run run_qa.py eval --only N-01 C-01 --repeat 3
"""

import argparse
import asyncio
import datetime
import json
import os
import sys
import time
import uuid
from pathlib import Path

import httpx
import yaml
from mcp import Client

import qa_logic as L

QA_DIR = Path(__file__).resolve().parent
REPO_DIR = QA_DIR.parent
# eval 의 diagnosis_only 케이스가 모의 실행할 표준 주문 파일이 있는 폴더다.
GOLDEN_DIR = REPO_DIR / "scenarios/order-agent/golden"
# 확인 조건이 맞지 않아 떨어진 이유 앞에 붙는 문구다.
# service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의 failedCheckReasons 가
# 붙인다.
CHECK_FAILURE_PREFIX = "확인 조건 실패:"
# eval 이 진단 상태를 다시 읽는 간격(초)이다.
POLL_SECONDS = 3


def env(name: str, default: str) -> str:
    """환경 변수 값을 읽고, 없거나 비어 있으면 기본값을 돌려준다."""
    return os.getenv(name) or default


class ToolError(Exception):
    """service MCP 도구가 오류 결과를 돌려줬을 때 던지는 예외다. payload 는 도구가 준 오류 JSON
    이다.
    """

    def __init__(self, tool: str, payload):
        self.tool = tool
        self.payload = payload
        super().__init__(f"{tool}: {payload}")


# ── 서버 클라이언트 ──────────────────────────────────────────────────────────


class OrderAgent:
    """주문 Agent HTTP API 를 부르는 작은 클라이언트다.

    상대편은 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ 의
    EvidenceController(snapshot, 실행 기록, 저장 이벤트 전달 상태)와 DryRunController(모의 실행)다.
    시연 기능(order-agent.demo-enabled)이 켜져 있어야 fixture 고정, 후보 발행, 모의 실행이 된다.
    """

    def __init__(self, http: httpx.AsyncClient):
        self.http = http

    async def _json(self, response: httpx.Response) -> dict:
        """실패 상태면 예외를 던지고, 아니면 응답 JSON 을 돌려준다."""
        response.raise_for_status()
        return response.json()

    async def pin_fixture(self, name: str) -> dict:
        """시연 fixture 하나를 새 snapshot 으로 고정하고 manifest 를 돌려준다.

        상대편 경로는 POST /api/snapshots 다.
        """
        return await self._json(await self.http.post("/api/snapshots", json={"fixture": name}))

    async def texts(self, manifest: dict) -> dict[str, str]:
        """snapshot 의 모든 파일 원문을 {경로: 원문} 으로 읽는다(GET /api/snapshots/{id}/files)."""
        result = {}
        for item in manifest["files"]:
            body = await self._json(
                await self.http.get(
                    f"/api/snapshots/{manifest['snapshot_id']}/files", params={"path": item["path"]}
                )
            )
            if body.get("encoding") != "utf-8":
                raise L.CaseError(f"{item['path']} 이 UTF-8 글이 아니라 평가셋에 쓸 수 없습니다")
            result[item["path"]] = body["content"]
        return result

    async def publish(self, files: dict[str, str], metadata: dict) -> dict:
        """파일 묶음을 후보 snapshot 으로 발행한다(POST /api/snapshots/candidates).

        저장 이벤트를 남기지 않으므로 자동 진단이 시작되지 않는다. check 와 eval 의 direct 실행이
        쓴다.
        """
        return await self._json(
            await self.http.post(
                "/api/snapshots/candidates", json={"files": files, "metadata": metadata}
            )
        )

    async def save_edits(self, base: dict, changes: dict[str, str]) -> httpx.Response:
        """운영 화면의 저장과 같은 경로로 수정본을 저장한다(POST /api/snapshots/edits). 응답을
        그대로
        돌려준다.

        저장되면 주문 Agent 가 service 를 거쳐 SkillOps Agent 에 자동 진단을 요청한다.
        """
        return await self.http.post(
            "/api/snapshots/edits",
            json={
                "baseline_snapshot_id": base["id"],
                "baseline_hash": base["hash"],
                "changes": changes,
            },
        )

    async def delivery(self, change_id: str) -> dict:
        """저장 이벤트가 service 로 전달됐는지와 진단 번호(thread_id)를 읽는다(GET
        /api/skill-changes/{id}).
        """
        return await self._json(await self.http.get(f"/api/skill-changes/{change_id}"))

    async def trace(self, execution_id: str) -> dict:
        """실행 기록을 읽는다(GET /api/executions/{id}/trace)."""
        return await self._json(await self.http.get(f"/api/executions/{execution_id}/trace"))

    async def dry_run(self, snapshot_id: str, order: dict) -> dict:
        """주문 하나를 snapshot 으로 모의 실행하고 실행 요약을 돌려준다(POST /api/dry-runs)."""
        return await self._json(
            await self.http.post("/api/dry-runs", json={"snapshot_id": snapshot_id, "order": order})
        )


class ServiceMcp:
    """service MCP 서버의 도구를 부르는 클라이언트다. check 만 쓴다.

    상대편은 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의
    create_workspace, apply_patch, run_scenarios 다. 도구는 결과와 오류를 JSON 글 한 덩어리로
    돌려준다 (service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java).
    """

    def __init__(self, client: Client):
        self.client = client

    async def call(self, name: str, args: dict) -> dict:
        """도구 하나를 부르고 결과 JSON 객체를 돌려준다. 오류 결과이거나 객체가 아니면 ToolError 를
        던진다.
        """
        result = await self.client.call_tool(name, args)
        text = next((b.text for b in (result.content or []) if getattr(b, "text", None)), "")
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            payload = text
        if (
            getattr(result, "is_error", False)
            or getattr(result, "isError", False)
            or not isinstance(payload, dict)
        ):
            raise ToolError(name, payload)
        return payload

    async def create_workspace(self, snapshot_id: str) -> dict:
        return await self.call("create_workspace", {"snapshot_id": snapshot_id})

    async def apply_patch(self, workspace_id: str, patches: list[dict]) -> dict:
        return await self.call("apply_patch", {"workspace_id": workspace_id, "patches": patches})

    async def run_scenarios(
        self, workspace_id: str, ids: list[str], checks: list[str] | None = None
    ) -> dict:
        args = {"workspace_id": workspace_id, "scenario_ids": ids, "repeat": 1}
        if checks:
            args["extra_checks"] = checks
        return await self.call("run_scenarios", args)


class SkillOpsAgent:
    """SkillOps Agent 의 바깥 API 를 부르는 클라이언트다. eval 만 쓴다.

    상대편은 skillops-agent/skillops_agent/main.py 의 POST /diagnose 와 GET /runs/{thread_id}/events
    다. 서비스 토큰(SKILLOPS_SERVICE_TOKEN)이 있으면 X-Service-Token 헤더로 보낸다.
    """

    def __init__(self, http: httpx.AsyncClient):
        self.http = http

    async def diagnose(self, body: dict) -> dict:
        response = await self.http.post("/diagnose", json=body)
        response.raise_for_status()
        return response.json()

    async def run(self, thread_id: str) -> dict:
        response = await self.http.get(f"/runs/{thread_id}/events")
        response.raise_for_status()
        return response.json()


class Service:
    """service REST API 에서 제안서를 읽는 클라이언트다. eval 만 쓴다.

    로그인(POST /api/auth/login)한 뒤 GET /api/proposals/{id} 로 제안서 원본(proposal)과 service 가
    다시 계산한 합격 후보 번호(valid_candidate_nos)를 읽는다. 상대편은
    service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 detail 이다.
    """

    def __init__(self, http: httpx.AsyncClient, user: str, password: str):
        self.http = http
        self.user = user
        self.password = password
        self.token: str | None = None

    async def proposal(self, proposal_id: str) -> dict:
        if self.token is None:
            login = await self.http.post(
                "/api/auth/login", json={"username": self.user, "password": self.password}
            )
            login.raise_for_status()
            self.token = login.json()["access_token"]
        response = await self.http.get(
            f"/api/proposals/{proposal_id}", headers={"Authorization": f"Bearer {self.token}"}
        )
        response.raise_for_status()
        return response.json()


# ── 케이스 읽기 ──────────────────────────────────────────────────────────────


def load_cases(cases_dir: Path, only: list[str] | None) -> list[dict]:
    """케이스 폴더의 YAML 파일을 모두 읽어 검사한 케이스 목록을 ID 순서로 돌려준다. only 가 있으면
    그 ID 만 남긴다.
    """
    files = sorted(cases_dir.glob("*.yaml"))
    if not files:
        raise L.CaseError(f"{cases_dir} 에 케이스 파일(*.yaml)이 없습니다")
    cases = []
    for path in files:
        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            raise L.CaseError(f"{path.name}: YAML 을 읽을 수 없습니다. {exc}") from exc
        cases.append(L.load_case(data, path.name))
    L.validate_case_ids(cases)
    if only:
        unknown = set(only) - {c["id"] for c in cases}
        if unknown:
            raise L.CaseError(f"없는 케이스 ID 입니다: {sorted(unknown)}")
        cases = [c for c in cases if c["id"] in only]
    return cases


def runnable(case: dict, include_openai: bool) -> bool:
    """이번 실행에서 자동으로 돌릴 케이스인지 본다. 수동 케이스는 늘 빼고, AI 모드 케이스는 요청했을
    때만 넣는다.
    """
    return case["run"] == "auto" or (case["run"] == "openai" and include_openai)


async def pin_base(oa: OrderAgent, cases: list[dict]) -> dict:
    """케이스가 쓰는 기준 fixture 마다 snapshot 을 한 번 고정하고 원문을 읽어 둔다."""
    bases = {}
    for name in sorted({c["base"] for c in cases}):
        manifest = await oa.pin_fixture(name)
        bases[name] = {
            "id": manifest["snapshot_id"],
            "hash": manifest["hash"],
            "manifest": manifest,
            "texts": await oa.texts(manifest),
        }
    return bases


def patches_for(before: dict[str, str], after: dict[str, str], versions: dict) -> list[dict]:
    """두 파일 묶음의 차이를 apply_patch 입력(skill, base_version, patch)으로 바꾼다. 차이 없는
    파일은 뺀다.
    """
    patches = []
    for path in sorted(after):
        if before.get(path) == after[path]:
            continue
        skill = path.split("/")[1]
        patches.append(
            {
                "skill": skill,
                "base_version": versions[skill],
                "patch": L.unified_patch(path, before[path], after[path]),
            }
        )
    return patches


def failing_reasons(report: dict) -> list[str]:
    """run_scenarios 결과에서 떨어진 실행의 이유 글을 모두 모은다."""
    return [f["reason"] for r in report["results"] for f in r["failures"]]


async def save_rejection(oa: OrderAgent, base: dict, path: str) -> str | None:
    """바꾸지 않은 파일 하나로 저장을 요청하고, 거부되면 오류 code 를 돌려준다.

    저장되면 None 이다. N-07 처럼 저장 단계에서 막혀야 하는 케이스에 쓴다.
    """
    response = await oa.save_edits(base, {path: base["texts"][path]})
    if response.status_code < 400:
        return None
    return response.json().get("code")


# ── check: 정답표 검증 ───────────────────────────────────────────────────────


async def check_environment(mcp: ServiceMcp, base: dict) -> None:
    """service 가 harness 실행기로 떠 있고 기준본이 표준 주문 3개를 통과하는지 확인한다.

    contract-only 실행기는 주문 Agent 를 부르지 않는 가짜 실행이라 run_id 가 r_fake 로 시작하고,
    표준 주문도 g-01 하나뿐이다. 기준본이 떨어지면 재고 같은 환경 문제라 정답표를 판정할 수
    없다(SkillOps 의 BASELINE_FAILED 와 같다).
    """
    try:
        ws = await mcp.create_workspace(base["id"])
        report = await mcp.run_scenarios(ws["workspace_id"], list(L.GOLDEN_IDS))
    except ToolError as exc:
        raise SystemExit(
            f"service MCP 를 쓸 수 없습니다. harness 실행기로 띄웠는지 확인하세요: {exc}"
        ) from exc
    run_ids = [i for r in report["results"] for i in r["run_ids"]]
    if any(i.startswith("r_fake") for i in run_ids):
        raise SystemExit(
            "service 가 contract-only 실행기로 떠 있습니다. "
            "SKILLOPS_FLOW_RUNNER=harness 로 다시 띄우세요"
        )
    if not report["all_passed"]:
        raise SystemExit(
            "기준본도 표준 주문을 통과하지 못합니다. 재고 등 시험 환경을 확인하세요: "
            f"{failing_reasons(report)}"
        )


async def observe_goldens(
    mcp: ServiceMcp, oa: OrderAgent, workspace_id: str
) -> tuple[list[dict], list[dict]]:
    """연습 공간에서 표준 주문 3개를 한 번씩 돌리고 주문마다 관찰한 반응과 run_scenarios 결과를
    돌려준다.
    """
    report = await mcp.run_scenarios(workspace_id, list(L.GOLDEN_IDS))
    observations, results = [], []
    for result in report["results"]:
        trace = None
        if not result["passed"]:
            trace = await oa.trace(L.execution_uuid(result["failures"][0]["run_id"]))
        reasons = [f["reason"] for f in result["failures"]]
        observations.append(L.observe_golden(result["passed"], trace, reasons))
        results.append(result)
    return observations, results


async def replay_passes(
    mcp: ServiceMcp, snapshot_id: str, cand: dict, target: dict, replay_id: str
) -> bool:
    """수정본(cand)을 target 으로 바꾼 새 연습 공간에서 재현 주문이 통과하는지 본다. 원인
    확인(되돌려 보기)에 쓴다.
    """
    ws = await mcp.create_workspace(snapshot_id)
    patches = patches_for(cand, target, ws["skill_versions"])
    if patches:
        await mcp.apply_patch(ws["workspace_id"], patches)
    report = await mcp.run_scenarios(ws["workspace_id"], [replay_id])
    return report["results"][0]["passed"]


async def run_fix(
    mcp: ServiceMcp, snapshot_id: str, cand: dict, target: dict, replay_id: str, checks: list[str]
) -> dict:
    """정답표의 안 하나를 새 연습 공간에 넣고 표준 주문 3개, 재현 주문, 의도 조건을 붙인 재현 주문을
    돌린다.

    SkillOps 의 후보 판정과 같은 시험이다(표준 주문 유지, 실패 해결, 의도 유지, 끊긴 연결 없음).
    다만 의도 조건은 정답표에 적은 그대로 보낸다. apply_patch 가 거절하면 시험 통과 실패로 본다.
    """
    ws = await mcp.create_workspace(snapshot_id)
    try:
        applied = await mcp.apply_patch(
            ws["workspace_id"], patches_for(cand, target, ws["skill_versions"])
        )
    except ToolError as exc:
        return {"tests_pass": False, "failing": [f"apply_patch 거절: {exc.payload}"]}
    reports = [await mcp.run_scenarios(ws["workspace_id"], [*L.GOLDEN_IDS, replay_id])]
    if checks:
        reports.append(await mcp.run_scenarios(ws["workspace_id"], [replay_id], checks))
    failing = [reason for report in reports for reason in failing_reasons(report)]
    if applied.get("broken_links"):
        failing.append(f"끊긴 연결: {applied['broken_links']}")
    return {"tests_pass": not failing, "failing": failing}


def preflight_problems(preflight: str, case: dict) -> list[str]:
    """관찰한 사전 검증 갈림길이 정답의 결론 목록과 맞는지 본다."""
    statuses = case["answer"]["status"]
    expected = {
        "VALIDATED": "VALIDATED",
        "VALIDATION_FAILED": "VALIDATION_FAILED",
        "DIAGNOSE": "EVALUATED",
    }[preflight]
    if expected not in statuses:
        return [f"사전 검증 갈림길 {preflight} 와 정답 결론 {statuses} 가 맞지 않습니다"]
    return []


async def check_case(case: dict, oa: OrderAgent, mcp: ServiceMcp, base: dict) -> dict:
    """케이스 하나의 사실 칸을 실제로 돌려 확인하고 어긋난 점(problems)을 돌려준다.

    차례는 주문 Agent 반응, 사전 검증 갈림길, 원인(되돌려 보기), 정답표의 안이다. 정답표의 사실이
    실제와 다르면 시나리오나 정답표를 고쳐야 한다. SkillOps 의 결론과 맞추려고 고치지 않는다(R4).
    """
    problems: list[str] = []
    observed: dict = {}
    answer = case["answer"]
    if answer.get("save_rejected"):
        code = await save_rejection(oa, base, case["save_file"])
        observed["save_rejected"] = code
        if code != answer["save_rejected"]:
            problems.append(f"저장 거부 기대 {answer['save_rejected']}, 실제 {code}")
        return {"problems": problems, "observed": observed}
    cand = L.apply_changes(base["texts"], case["change"])
    snapshot = await oa.publish(cand, {"baseline_snapshot_id": base["id"], "qa_case": case["id"]})
    snapshot_id = snapshot["snapshot_id"]
    observed["candidate_snapshot_id"] = snapshot_id
    ws = await mcp.create_workspace(snapshot_id)
    observations, results = await observe_goldens(mcp, oa, ws["workspace_id"])
    observed["goldens"] = dict(zip(L.GOLDEN_IDS, observations, strict=True))
    for golden, obs in observed["goldens"].items():
        if golden in case["order_agent"]:
            problems += [
                f"{golden}: {p}" for p in L.match_expectation(case["order_agent"][golden], obs)
            ]
    if case["trigger"] == "diagnosis_only":
        order_id = case["diagnosis_only"]["order"]
        run_id = next(r for r in results if r["scenario_id"] == order_id)["run_ids"][0]
        report = await mcp.run_scenarios(
            ws["workspace_id"], [f"f-{L.execution_uuid(run_id)}"], case["diagnosis_only"]["checks"]
        )
        status = (
            "INTENT_VIOLATION"
            if any(CHECK_FAILURE_PREFIX in r for r in failing_reasons(report))
            else "EVALUATED"
        )
        observed["diagnosis_only"] = status
        if status not in answer["status"]:
            problems.append(f"정책 확인 결과 {status} (정답 {answer['status']})")
        return {"problems": problems, "observed": observed}
    preflight = L.preflight_status(observations)
    observed["preflight"] = preflight
    problems += preflight_problems(preflight, case)
    if preflight != "DIAGNOSE":
        return {"problems": problems, "observed": observed}
    first = next(r for r in results if not r["passed"])
    replay_id = f"f-{L.execution_uuid(first['failures'][0]['run_id'])}"
    causes = [c for c in case["change"] if c["cause"]]
    keep = [c for c in case["change"] if not c["cause"]]
    together = await replay_passes(
        mcp, snapshot_id, cand, L.apply_changes(base["texts"], keep), replay_id
    )
    observed["revert_causes_passes"] = together
    if not together:
        problems.append("원인으로 적은 수정을 모두 되돌려도 재현 주문이 통과하지 않습니다")
    if len(case["change"]) > 1:
        for change in case["change"]:
            others = [c for c in case["change"] if c is not change]
            passes = await replay_passes(
                mcp, snapshot_id, cand, L.apply_changes(base["texts"], others), replay_id
            )
            expected = change["cause"] and len(causes) == 1
            if passes != expected:
                problems.append(
                    f"{change['file']} 하나만 되돌렸을 때 통과 기대 {expected}, 실제 {passes}"
                )
    owner_files = L.changed_files(case["change"])
    observed["fixes"] = {}
    for fix in case["fixes"]:
        target = L.apply_changes(cand, fix["change"])
        outcome = await run_fix(mcp, snapshot_id, cand, target, replay_id, case["intent"]["checks"])
        outcome["reverted"] = bool(L.reverted_files(base["texts"], target, owner_files))
        outcome["revert_allowed"] = answer["revert_allowed"]
        observed["fixes"][fix["name"]] = outcome
        problems += L.check_fix(fix, outcome)
    return {"problems": problems, "observed": observed}


async def run_check(args) -> int:
    """check 명령의 본체다. 모든 자동 케이스를 확인하고 보고서를 쓴 뒤, 어긋난 케이스가 있으면 1 을
    돌려준다.
    """
    cases = load_cases(args.cases, args.only)
    targets = [c for c in cases if runnable(c, args.include_openai)]
    results = []
    async with (
        httpx.AsyncClient(base_url=args.order_agent, timeout=120) as http,
        Client(args.mcp) as client,
    ):
        oa, mcp = OrderAgent(http), ServiceMcp(client)
        bases = await pin_base(oa, targets)
        for base in bases.values():
            await check_environment(mcp, base)
        for case in targets:
            started = time.monotonic()
            try:
                outcome = await check_case(case, oa, mcp, bases[case["base"]])
                verdict = "OK" if not outcome["problems"] else "MISMATCH"
            except Exception as exc:  # noqa: BLE001 - 한 케이스가 막혀도 나머지는 확인한다
                outcome, verdict = (
                    {"problems": [f"{type(exc).__name__}: {exc}"], "observed": {}},
                    "ERROR",
                )
            seconds = round(time.monotonic() - started, 1)
            results.append({"id": case["id"], "verdict": verdict, "seconds": seconds, **outcome})
            problems = "; ".join(outcome["problems"])[:160]
            print(f"{case['id']:7s} {verdict:8s} {seconds:5.1f}s  {problems}", flush=True)
    paths = write_report("check", args, cases, results, None)
    bad = [r for r in results if r["verdict"] != "OK"]
    print(f"\n확인 {len(results)}개, 어긋남 {len(bad)}개. 보고서: {paths[1]}")
    return 1 if bad else 0


# ── eval: SkillOps 평가 ──────────────────────────────────────────────────────


def golden_order(order_id: str) -> dict:
    """표준 주문 파일(scenarios/order-agent/golden/<id>.json)의 order 칸을 읽는다."""
    return json.loads((GOLDEN_DIR / f"{order_id}.json").read_text(encoding="utf-8"))["order"]


async def start_diagnosis(
    case: dict, args, oa: OrderAgent, agent: SkillOpsAgent, base: dict, cand: dict
) -> str:
    """케이스 하나의 진단을 시작하고 진단 번호(thread_id)를 돌려준다.

    diagnosis_only 케이스는 수정본으로 표준 주문 하나를 모의 실행한 뒤 그 실행에 정책 확인 조건을
    붙여 요청한다. 나머지는 trigger 에 따라 direct(후보 snapshot 을 발행하고 POST /diagnose 로
    skill_saved 요청)나 save(운영 화면과 같은 저장 경로)로 시작한다. save 는 주문 Agent 가 설정된
    service 로 보내므로 --agent 가 그 service 뒤의 Agent 여야 한다.
    """
    meta = {"baseline_snapshot_id": base["id"], "qa_case": case["id"]}
    if case["trigger"] == "diagnosis_only":
        snapshot = await oa.publish(cand, meta)
        run = await oa.dry_run(
            snapshot["snapshot_id"], golden_order(case["diagnosis_only"]["order"])
        )
        body = {
            "type": "failure",
            "execution_id": run["execution_id"],
            "diagnosis_only": True,
            "intent_checks": case["diagnosis_only"]["checks"],
        }
        return (await agent.diagnose(body))["thread_id"]
    files = L.changed_files(case["change"])
    if args.trigger == "direct":
        snapshot = await oa.publish(cand, meta)
        body = {
            "type": "skill_saved",
            "candidate_snapshot_id": snapshot["snapshot_id"],
            "baseline_snapshot_id": base["id"],
            "changed_paths": files,
            "change_id": str(uuid.uuid4()),
        }
        return (await agent.diagnose(body))["thread_id"]
    saved = await oa.save_edits(base, {f: cand[f] for f in files})
    saved.raise_for_status()
    change_id = saved.json()["change_id"]
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        delivery = await oa.delivery(change_id)
        if delivery.get("thread_id"):
            return delivery["thread_id"]
        await asyncio.sleep(POLL_SECONDS)
    raise TimeoutError(f"저장 이벤트 {change_id} 가 2분 안에 진단으로 전달되지 않았습니다")


async def wait_for_run(agent: SkillOpsAgent, thread_id: str, timeout: float) -> dict:
    """진단이 끝날 때까지 진단 기록을 다시 읽는다. 시간을 넘기면 diagnosis_status 를 TIMEOUT 으로
    둔다.
    """
    deadline = time.monotonic() + timeout
    while True:
        run = await agent.run(thread_id)
        if run.get("diagnosis_status") not in L.ACTIVE_STATUSES:
            return run
        if time.monotonic() > deadline:
            return {**run, "diagnosis_status": "TIMEOUT"}
        await asyncio.sleep(POLL_SECONDS)


async def read_proposal(service: Service, run: dict) -> tuple[dict | None, list[int], str]:
    """진단 기록의 제안서 번호로 service 의 제안서를 읽어 (원본, 합격 후보 번호, 출처)를 돌려준다.

    service 조회가 실패하면 진단 기록에 남은 제안서 원본을 쓰고 출처에 그 사실을 적는다.
    """
    saved = run.get("proposal") or {}
    if not saved.get("id"):
        return None, [], "없음"
    try:
        detail = await service.proposal(saved["id"])
        return detail["proposal"], list(detail.get("valid_candidate_nos") or []), "service"
    except httpx.HTTPError as exc:
        return (
            saved,
            list(saved.get("valid_candidate_nos") or []),
            f"진단 기록(service 조회 실패: {exc})",
        )


async def eval_case(case: dict, args, oa, agent, service, base: dict) -> dict:
    """케이스 하나를 SkillOps 로 진단하고 정답표로 채점한 결과를 돌려준다."""
    started = time.monotonic()
    if case["answer"].get("save_rejected"):
        code = await save_rejection(oa, base, case["save_file"])
        grade = L.grade_eval(case, None, None, [], base["texts"], base["texts"], save_rejected=code)
        return {"grade": grade, "seconds": round(time.monotonic() - started, 1)}
    cand = L.apply_changes(base["texts"], case["change"])
    thread_id = await start_diagnosis(case, args, oa, agent, base, cand)
    run = await wait_for_run(agent, thread_id, args.timeout)
    proposal, valid_nos, source = await read_proposal(service, run)
    grade = L.grade_eval(
        case, run.get("diagnosis_status"), proposal, valid_nos, base["texts"], cand
    )
    return {
        "grade": grade,
        "seconds": round(time.monotonic() - started, 1),
        "thread_id": thread_id,
        "proposal_id": (run.get("proposal") or {}).get("id"),
        "proposal_source": source,
        "error": run.get("error"),
    }


async def run_eval(args) -> int:
    """eval 명령의 본체다. 자동 케이스를 repeat 번씩 진단하고 채점해 지표와 보고서를 낸다."""
    cases = load_cases(args.cases, args.only)
    targets = [c for c in cases if runnable(c, args.include_openai)]
    token = os.getenv("SKILLOPS_SERVICE_TOKEN", "")
    agent_headers = {"X-Service-Token": token} if token else {}
    results = []
    async with (
        httpx.AsyncClient(base_url=args.order_agent, timeout=120) as oa_http,
        httpx.AsyncClient(base_url=args.agent, timeout=60, headers=agent_headers) as agent_http,
        httpx.AsyncClient(base_url=args.service, timeout=60) as service_http,
    ):
        oa = OrderAgent(oa_http)
        agent = SkillOpsAgent(agent_http)
        service = Service(service_http, args.service_user, args.service_password)
        bases = await pin_base(oa, targets)
        for case in targets:
            for attempt in range(1, args.repeat + 1):
                try:
                    outcome = await eval_case(case, args, oa, agent, service, bases[case["base"]])
                except Exception as exc:  # noqa: BLE001 - 한 케이스가 막혀도 나머지는 평가한다
                    grade = {
                        "verdict": "FAIL",
                        "status": "ERROR",
                        "reasons": [f"실행기 오류 {type(exc).__name__}: {exc}"],
                    }
                    outcome = {"grade": grade, "seconds": None}
                results.append({"case": case, "attempt": attempt, **outcome})
                g = outcome["grade"]
                print(
                    f"{case['id']:7s} #{attempt} {g['verdict']:8s} {str(g.get('status')):18s} "
                    f"{'; '.join(g.get('reasons', []))[:140]}",
                    flush=True,
                )
    metrics = L.summarize(results)
    paths = write_report("eval", args, cases, results, metrics)
    print(f"\n평가 {len(results)}회. 보고서: {paths[1]}")
    return 0


# ── list ─────────────────────────────────────────────────────────────────────


def answer_text(case: dict) -> str:
    """케이스의 정답을 한 줄로 줄인다. list 와 보고서가 쓴다."""
    if case["run"] == "manual":
        return "수동 확인"
    answer = case["answer"]
    if answer.get("save_rejected"):
        return f"저장 거부({answer['save_rejected']})"
    text = "/".join(answer["status"])
    if answer.get("proposal"):
        text += " " + "/".join(answer["proposal"])
    root = answer.get("root_cause_all") or answer.get("root_cause")
    if root:
        text += f" 원인 {'+'.join(root) if answer.get('root_cause_all') else '/'.join(root)}"
    return text


def run_list(args) -> int:
    """list 명령의 본체다. 케이스 목록을 표로 찍는다."""
    cases = load_cases(args.cases, args.only)
    for case in cases:
        title = case["title"][:44]
        print(f"{case['id']:7s} {case['run']:7s} {case['set']:6s} {title:44s}  {answer_text(case)}")
    kinds = {k: sum(c["run"] == k for c in cases) for k in sorted(L.RUN_KINDS)}
    print(f"\n케이스 {len(cases)}개: {kinds}")
    return 0


# ── 보고서 ───────────────────────────────────────────────────────────────────


def percent(rate: dict) -> str:
    """{value, num, den} 비율을 '62.5% (5/8)' 모양 글로 바꾼다. 분모가 0 이면 '대상 없음' 이다."""
    if not rate["den"]:
        return "대상 없음"
    return f"{rate['value'] * 100:.1f}% ({rate['num']}/{rate['den']})"


METRIC_LABELS = [
    ("pass_rate", "케이스 정답률"),
    ("detection", "회귀 탐지율"),
    ("false_alarm", "오탐률"),
    ("root_cause", "원인 특정 정확도"),
    ("fix_presented", "유효 개선안 제시율"),
    ("no_fix_correct", "'유효한 안 없음' 정답률"),
    ("revert_rate", "되돌리기 제안률"),
    ("completion", "완주율"),
]


def render_markdown(
    mode: str, args, cases: list[dict], results: list[dict], metrics: dict | None
) -> str:
    """보고서 Markdown 글을 만든다. 지표(eval), 케이스별 결과, 이번에 돌리지 않은 케이스와 수동
    절차를 담는다.
    """
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    lines = [f"# SkillOps 평가셋 {mode} 결과 ({now})", ""]
    lines.append(f"- 주문 Agent: {args.order_agent}")
    if mode == "check":
        lines.append(f"- service MCP: {args.mcp}")
    else:
        lines += [
            f"- SkillOps Agent: {args.agent}",
            f"- service: {args.service}",
            f"- 시작 방식: {args.trigger}, 반복 {args.repeat}회",
        ]
    lines.append("")
    if metrics:
        lines += ["## 지표", "", "| 지표 | 값 |", "|---|---|"]
        lines += [f"| {label} | {percent(metrics[key])} |" for key, label in METRIC_LABELS]
        p50, p95 = metrics["seconds_p50"], metrics["seconds_p95"]
        lines += [
            f"| 소요 시간 | 중앙값 {p50}초, 95% {p95}초 |"
            if p50 is not None
            else "| 소요 시간 | 대상 없음 |",
            "",
        ]
        lines.append("거짓 합격률은 숨긴 주문이 있어야 재므로 이 보고서에는 없습니다.")
        lines.append("")
    lines += ["## 케이스별 결과", ""]
    if mode == "check":
        lines += ["| ID | 결과 | 걸린 시간 | 어긋난 점 |", "|---|---|---|---|"]
        for r in results:
            problems = "<br>".join(r["problems"]) or ""
            lines.append(f"| {r['id']} | {r['verdict']} | {r['seconds']}초 | {problems} |")
    else:
        lines += [
            "| ID | 회차 | 판정 | 결론 | 제안서 | 원인 | 이유 |",
            "|---|---|---|---|---|---|---|",
        ]
        for r in results:
            g = r["grade"]
            reasons = "<br>".join(g.get("reasons", []))
            lines.append(
                f"| {r['case']['id']} | {r['attempt']} | {g['verdict']} | {g.get('status')} | "
                f"{g.get('proposal_status') or ''} | {g.get('root_cause') or ''} | {reasons} |"
            )
    lines.append("")
    skipped = [c for c in cases if not runnable(c, args.include_openai)]
    if skipped:
        lines += ["## 이번에 자동으로 돌리지 않은 케이스", ""]
        for case in skipped:
            if case["run"] == "openai":
                lines.append(
                    f"- **{case['id']}** {case['title']}: 주문 Agent 를 AI 모드로 띄우고 "
                    "`--include-openai` 로 돌립니다."
                )
            else:
                lines.append(f"- **{case['id']}** {case['title']} (수동)")
                lines += [f"  {i}. {step}" for i, step in enumerate(case["steps"], 1)]
                lines.append(f"  - 기대 결과: {case['expected']}")
        lines.append("")
    return "\n".join(lines)


def write_report(
    mode: str, args, cases: list[dict], results: list[dict], metrics: dict | None
) -> tuple[Path, Path]:
    """결과를 qa/results/ 아래 JSON 과 Markdown 파일로 쓰고 두 경로를 돌려준다."""
    args.out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = args.out / f"{stamp}-{mode}.json"
    md_path = args.out / f"{stamp}-{mode}.md"
    serializable = [{**r, "case": r["case"]["id"]} if "case" in r else r for r in results]
    json_path.write_text(
        json.dumps(
            {"mode": mode, "metrics": metrics, "results": serializable},
            ensure_ascii=False,
            indent=1,
            default=str,
        ),
        encoding="utf-8",
    )
    md_path.write_text(render_markdown(mode, args, cases, results, metrics), encoding="utf-8")
    return json_path, md_path


# ── 명령 ─────────────────────────────────────────────────────────────────────


def parse_args(argv: list[str]) -> argparse.Namespace:
    """명령줄 인자를 읽는다. 서버 주소는 인자가 없으면 환경 변수, 그것도 없으면 로컬 기본 포트를
    쓴다.
    """
    parser = argparse.ArgumentParser(description="SkillOps 평가셋 실행기 (qa/README.md 참고)")
    sub = parser.add_subparsers(dest="command", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--cases", type=Path, default=QA_DIR / "cases", help="케이스 폴더 (숨긴 셋이면 그 폴더)"
    )
    common.add_argument("--only", nargs="+", metavar="ID", help="이 ID 의 케이스만 돌린다")
    common.add_argument(
        "--include-openai",
        action="store_true",
        help="AI 모드 케이스도 돌린다(주문 Agent 가 openai 모드일 때)",
    )
    common.add_argument("--order-agent", default=env("QA_ORDER_AGENT_URL", "http://localhost:8081"))
    common.add_argument("--out", type=Path, default=QA_DIR / "results", help="보고서를 쓸 폴더")
    sub.add_parser("list", parents=[common], help="케이스 목록과 정답을 보여 준다")
    check = sub.add_parser(
        "check", parents=[common], help="정답표의 사실 칸을 실제로 돌려 확인한다"
    )
    check.add_argument(
        "--mcp",
        default=env("QA_MCP_URL", "http://localhost:8080/mcp"),
        help="harness 실행기로 띄운 service 의 MCP 주소",
    )
    ev = sub.add_parser("eval", parents=[common], help="SkillOps 진단을 돌려 정답표로 채점한다")
    ev.add_argument(
        "--agent", default=env("QA_AGENT_URL", "http://localhost:8000"), help="SkillOps Agent 주소"
    )
    ev.add_argument(
        "--service",
        default=env("QA_SERVICE_URL", "http://localhost:8080"),
        help="제안서를 읽을 service 주소",
    )
    ev.add_argument("--service-user", default=env("QA_SERVICE_USER", "operator"))
    ev.add_argument("--service-password", default=env("QA_SERVICE_PASSWORD", "operator1234"))
    ev.add_argument(
        "--trigger",
        choices=["direct", "save"],
        default="direct",
        help="direct: POST /diagnose, save: 운영 화면 저장 경로",
    )
    ev.add_argument("--repeat", type=int, default=1, help="케이스마다 진단을 돌릴 횟수")
    ev.add_argument("--timeout", type=float, default=900, help="진단 하나를 기다리는 최대 시간(초)")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    try:
        if args.command == "list":
            return run_list(args)
        if args.command == "check":
            return asyncio.run(run_check(args))
        return asyncio.run(run_eval(args))
    except L.CaseError as exc:
        print(f"케이스 오류: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
