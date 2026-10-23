"""SkillOps Agent 의 FastAPI 진입점이다.

진단 요청을 POST /diagnose 로 받아 RunStore 에 접수하고, LangGraph 진단 파이프라인
(skillops-agent/skillops_agent/graph.py 의 graph)을 백그라운드로 돌린다. 진행 상황은 GET /runs 와
GET /runs/{thread_id}/events 로 조회한다. /health 를 뺀 모든 경로가 verify_token 으로 서비스 토큰을
본다.

연결부는 다음과 같다.
- uvicorn 이 skillops_agent.main:app 으로 이 앱을 띄운다(skillops-agent/README.md).
- service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 의 changed() 가
  주문 Agent 의 skill 저장 이벤트(type=skill_saved)를 POST /diagnose 로 중계한다. 같은 클래스의
  list() 와 status() 는 GET /runs 와 GET /runs/{thread_id}/events 를 중계하고, 화면
  frontend/src/views/DiagnosisView.vue 가 그 중계 경로를 폴링한다.
- type=failure 요청을 보내는 production 코드는 아직 없고, 테스트가 이 형식을 보낸다.

파이프라인 한도(MAX_STEPS_DEFAULT, RECURSION_LIMIT)와 기본 시작 skill(DEFAULT_ENTRY_SKILL_ID)은
skillops-agent/skillops_agent/config.py 에서 바꾼다. 요청 검증 규칙은 DiagnoseRequest 의
validate_references 가 차례로 부르는 메서드에 있다.
"""

import uuid
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException
from langgraph.types import Command
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .config import DEFAULT_ENTRY_SKILL_ID, MAX_STEPS_DEFAULT, RECURSION_LIMIT, SERVICE_TOKEN
from .contracts import execution_uuid
from .graph import graph
from .run_store import RunStore
from .state import DiagnosisStatus


@asynccontextmanager
async def lifespan(app):
    """앱이 시작할 때 지난 프로세스에서 끝나지 못한 진단을 ERROR 로 정리한다.

    백그라운드 파이프라인은 이 프로세스 메모리에서만 돌기 때문에 재시작하면 이어서 돌릴 방법이 없다.
    그래서 RunStore().recover() 로 QUEUED 나 RUNNING 으로 남은 실행을 ERROR 와 중단 문구로 바꿔,
    끝나지 않을 실행이 진행 중으로 남지 않게 한다. FastAPI 가 아래 app 생성 때 lifespan 으로
    등록한다.
    """
    RunStore().recover()
    yield


# uvicorn 이 띄우는 앱이다. title 과 version 은 OpenAPI 문서에 나온다.
app = FastAPI(title="SkillOps Agent", version="0.1.0", lifespan=lifespan)

# 승인 대기 중인 파이프라인을 찾는 proposal_id 에서 thread_id 로의 매핑이다.
# _remember_pending_approval 이 넣고 approve 가 꺼낸다. 프로세스 메모리에만 있어서 재시작하면
# 사라진다.
_pending_approvals: dict[str, str] = {}


# POST /diagnose 요청 본문이다. skill 저장 이벤트(type=skill_saved)와 실패 진단
# 요청(type=failure)을 받고, extra="forbid" 로 정의하지 않은 칸을 422 로 막는다. model_dump()
# 결과가 그대로 trigger 가 되어 RunStore 의 중복 판정 fingerprint 와 파이프라인 초기 state 에
# 들어간다. 그래서 칸을 더하거나 이름이나 기본값을 바꾸면 같은 이벤트라도 fingerprint 가 달라져,
# 바꾸기 전에 접수한 change_id 의 재전송이 409 가 된다. 이 클래스와 메서드에는 docstring 을 넣지
# 않는다. docstring 이 OpenAPI 문서에 들어간다.
class DiagnoseRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    type: Literal["failure", "skill_saved"]
    # 진단할 실행 번호다. failure 에는 꼭 있어야 하고, 값이 있으면 검증 뒤에는 하이픈 UUID 다.
    execution_id: str | None = None
    # 비교 기준 snapshot 이다. 없으면 후보 snapshot manifest 의 metadata.baseline_snapshot_id 를
    # 쓴다.
    baseline_snapshot_id: str | None = None
    # 검증할 저장본 snapshot 이다. skill_saved 에는 꼭 있어야 하고, 실행 번호와 함께 오면
    # 그 실행 기록의 snapshot 과 같아야 한다.
    candidate_snapshot_id: str | None = None
    # 보낸 쪽이 알린 변경 파일 경로다. 있으면 두 snapshot manifest 의 실제 차이와 같아야 하고,
    # 다르면 skillops-agent/skillops_agent/nodes.py 의 _collect_changes 가 ContractError 를 던진다.
    changed_paths: list[str] | None = None
    # 주문 Agent 가 붙이는 저장 변경 번호다. RunStore 가 이 값으로 재전송을 알아본다.
    change_id: str | None = None
    # get_dependency_graph 가 흐름을 읽기 시작할 skill 이다.
    entry_skill_id: str = DEFAULT_ENTRY_SKILL_ID
    # true 면 실행을 재현하며 intent_checks 만 확인하고 수정 후보를 만들지 않는다.
    diagnosis_only: bool = False
    # diagnosis_only 에서 재현 주문에 덧붙일 확인 조건이다(예: payment.status != 'PAID').
    intent_checks: list[str] = Field(default_factory=list)
    # execution_id 의 별칭(r_ + UUID hex 또는 UUID)이다. 받은 글 그대로 trigger 에 남는다.
    run_id: str | None = None
    # skill 과 version 은 v0 요청 칸이다. trigger 에 남지만 노드는 읽지 않는다.
    skill: str | None = None
    version: int | None = None

    # 실행 번호 정규화와 규칙 검사 세 개를 아래 순서로 부르고, 먼저 실패한 검사의 메시지가 422
    # 응답에 실린다. ValueError 와 그 하위 클래스인 ContractError 를 pydantic 이 검증 오류로 바꾼다.
    # 새 trigger type 의 필수 참조는 _require_type_references 에 더한다. 이 순서는
    # skillops-agent/tests/test_main_api.py 의
    # test_invalid_references_return_the_first_failing_rule 이 고정한다.
    @model_validator(mode="after")
    def validate_references(self):
        self._normalize_execution_id()
        self._require_type_references()
        self._require_snapshot_uuids()
        self._require_diagnosis_only_inputs()
        return self

    # execution_id 와 run_id 가 둘 다 있으면 같은 실행을 가리키는지 보고, 다르면 거절한다. 둘 중
    # 있는 값을 skillops-agent/skillops_agent/contracts.py 의 execution_uuid 로 하이픈 UUID 로 바꿔
    # execution_id 에 넣는다. 읽을 수 없는 값이면 execution_uuid 의 ContractError 가 422 가 된다.
    def _normalize_execution_id(self) -> None:
        if (
            self.execution_id
            and self.run_id
            and execution_uuid(self.execution_id) != execution_uuid(self.run_id)
        ):
            raise ValueError("execution_id and run_id differ")
        value = self.execution_id or self.run_id
        if value:
            self.execution_id = execution_uuid(value)

    # type 별 필수 참조를 본다. failure 에는 execution_id 가, skill_saved 에는
    # candidate_snapshot_id 가 있어야 한다. 새 trigger type 을 더하면 그 type 의 필수 참조를 여기에
    # 적는다.
    def _require_type_references(self) -> None:
        if self.type == "failure" and not self.execution_id:
            raise ValueError("failure requires execution_id (run_id alias accepted)")
        if self.type == "skill_saved" and not self.candidate_snapshot_id:
            raise ValueError("skill_saved requires candidate_snapshot_id")

    # 들어온 snapshot 번호가 UUID 인지 본다. uuid.UUID 가 던지는 ValueError 의 문구가 그대로 422
    # 메시지에 실린다.
    def _require_snapshot_uuids(self) -> None:
        for value in (self.baseline_snapshot_id, self.candidate_snapshot_id):
            if value:
                uuid.UUID(value)

    # diagnosis_only 에는 실행 번호와 의도 확인 조건이 함께 있어야 한다. 이 모드는 그 실행을
    # 재현하며 조건만 확인한다(skillops-agent/skillops_agent/nodes.py 의 _evaluate_intent_checks).
    def _require_diagnosis_only_inputs(self) -> None:
        if self.diagnosis_only and (not self.execution_id or not self.intent_checks):
            raise ValueError("diagnosis_only requires execution_id and intent_checks")


# POST /approve 요청 본문이다. status 는 approved 와 rejected 만 받고, 다른 값은 422 다.
# token 은 _resume_pipeline 이 graph 재개 값에 그대로 넣는다. docstring 은 OpenAPI 문서에 들어가므로
# 넣지 않는다.
class ApproveRequest(BaseModel):
    proposal_id: str
    status: Literal["approved", "rejected"]
    token: str | None = None


async def verify_token(x_service_token: str = Header(default="")) -> None:
    """X-Service-Token 헤더를 SERVICE_TOKEN 과 비교하는 FastAPI 의존성이다.

    SERVICE_TOKEN(환경 변수 SKILLOPS_SERVICE_TOKEN)이 비어 있으면 개발 모드로 보고 검사하지 않는다.
    값이 있는데 헤더가 없거나 다르면 401 을 돌려준다. /health 를 뺀 네 경로가 dependencies 로 이
    함수를 건다. 브라우저 요청은
    service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 를 거치고, 그
    컨트롤러가 같은 토큰을 헤더에 붙여 보낸다. 그래서 브라우저는 이 토큰을 갖지 않는다.
    """
    if SERVICE_TOKEN and x_service_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid service token")


def _thread_config(thread_id: str) -> dict:
    """LangGraph checkpointer 에서 실행 thread 를 찾는 config 를 만든다.

    RunStore 가 발급한 thread_id 를 LangGraph thread_id 로 그대로 쓴다. _run_pipeline 과
    _resume_pipeline 이 쓴다.
    """
    return {"configurable": {"thread_id": thread_id}}


def _initial_state(req: DiagnoseRequest, thread_id: str) -> dict:
    """진단 파이프라인의 초기 state 를 만든다.

    trigger 는 요청의 model_dump() 그대로이고 max_steps 는
    skillops-agent/skillops_agent/config.py 의 MAX_STEPS_DEFAULT 다. pipeline_thread_id 가 있어야
    skillops-agent/skillops_agent/graph.py 의 guarded 가 노드마다 부분 결과를 RunStore 에
    기록해, 진행 중에도 GET /runs/{thread_id}/events 로 볼 수 있다.
    """
    return {
        "trigger": req.model_dump(),
        "max_steps": MAX_STEPS_DEFAULT,
        "pipeline_thread_id": thread_id,
    }


def _remember_pending_approval(result: dict, thread_id: str) -> None:
    """graph 결과에 제안서 id 가 있으면 _pending_approvals 에 thread_id 와 함께 등록한다.

    원래는 승인 대기(interrupt) 지점에서 돌아온 state 의 proposal.id 를 담는 것을 전제로 했다.
    지금 graph 에는 interrupt 가 없어서 끝난 state 의 proposal.id 가 들어간다. id 가 없거나 비었으면
    등록하지 않는다. _run_pipeline 이 graph 실행과 저장에 성공한 뒤에만 부른다.
    """
    proposal_id = (result.get("proposal") or {}).get("id")
    if proposal_id:
        _pending_approvals[proposal_id] = thread_id


async def _run_pipeline(req: DiagnoseRequest, thread_id: str) -> None:
    """접수한 진단 하나를 graph 로 끝까지 돌리고 최종 state 를 RunStore 에 저장한다.

    diagnose 가 BackgroundTasks 로 예약한다. 노드 사이의 진행 기록은
    skillops-agent/skillops_agent/graph.py 의 guarded 가 남기고, 여기서는 최종 결과를 합쳐 넣는다.
    recursion_limit 은 skillops-agent/skillops_agent/config.py 의 RECURSION_LIMIT 로 바꾼다. graph
    실행이나 결과 저장이 예외를 던지면 diagnosis_status=ERROR 와 '<예외 타입>: <메시지>' 를
    저장하고 승인 대기에 등록하지 않는다. ExceptionGroup 도 풀지 않고 str 그대로 쓴다. 백그라운드
    작업이라 예외를 받을 호출자가 없으므로, 실패를 RunStore 에 남겨 조회 경로에서 보이게 한다.
    """
    config = _thread_config(thread_id)
    initial_state = _initial_state(req, thread_id)
    try:
        result = await graph.ainvoke(
            initial_state, config={**config, "recursion_limit": RECURSION_LIMIT}
        )
        RunStore().update(thread_id, result)
    except Exception as exc:
        RunStore().update(
            thread_id,
            {"diagnosis_status": DiagnosisStatus.ERROR, "error": f"{type(exc).__name__}: {exc}"},
        )
        return
    _remember_pending_approval(result, thread_id)


async def _resume_pipeline(req: ApproveRequest, thread_id: str) -> None:
    """승인 결정을 Command(resume={status, token}) 로 graph thread 에 넘겨 파이프라인을 재개한다.

    approve 가 BackgroundTasks 로 예약한다. thread config 만 넘기고 recursion_limit 은 주지
    않으며, 결과를 RunStore 에 저장하지 않는다. 지금 skillops-agent/skillops_agent/graph.py 의
    FLOW 에는 승인 대기(interrupt) 노드가 없어서 실제로 멈춰 있는 thread 가 없다.
    """
    await graph.ainvoke(
        Command(resume={"status": req.status, "token": req.token}),
        config=_thread_config(thread_id),
    )


# 프로세스가 떠 있는지만 보는 확인 경로다. 토큰 없이 고정 본문을 돌려준다. 저장소 안에서는 테스트만
# 부른다. 엔드포인트 함수에는 docstring 을 넣지 않는다(OpenAPI 문서에 들어간다).
@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "skillops-agent"}


# 진단 한 건의 현재 상태를 돌려준다. 응답은 RunStore.get 결과 그대로이며, 행 칸(thread_id,
# change_id, trigger, created_at) 위에 state 를 펼친 dict 다. 없으면 404 다.
# service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 의 status() 가
# GET /api/diagnoses/{threadId} 요청을 이 경로로 중계한다. 진행 조회 경로는 이것 하나이고
# /runs/{thread_id} 별칭은 두지 않는다.
@app.get("/runs/{thread_id}/events", dependencies=[Depends(verify_token)])
async def get_run_events(thread_id: str) -> dict:
    run = RunStore().get(thread_id)
    if run is None:
        raise HTTPException(status_code=404, detail="Diagnosis not found")
    return run


# 최근 진단 목록을 {items: [...]} 로 돌려준다. RunStore.list 가 created_at 내림차순으로 최대
# RECENT_RUNS_LIMIT(skillops-agent/skillops_agent/run_store.py) 건을 읽는다.
# service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 의 list() 가
# GET /api/diagnoses 요청을 이 경로로 중계한다.
@app.get("/runs", dependencies=[Depends(verify_token)])
async def list_runs():
    return {"items": RunStore().list()}


# 진단 요청을 접수하고 202 를 돌려준다. 응답은 처리 완료가 아니라 접수를 뜻한다.
# RunStore.accept 가 change_id 와 fingerprint 로 중복을 판정해, 새 요청일 때만 _run_pipeline 을
# 백그라운드로 예약한다. 같은 요청의 재전송이면 기존 thread_id 와 duplicate=true 를 돌려주고,
# 같은 change_id 에 다른 내용이 오면 409 다. 주문 Agent 의
# order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java 는
# thread_id 를 기록할 때까지 같은 이벤트를 service 를 거쳐 다시 보내므로 이 중복 판정이 필요하다.
@app.post("/diagnose", status_code=202, dependencies=[Depends(verify_token)])
async def diagnose(req: DiagnoseRequest, background_tasks: BackgroundTasks) -> dict:
    try:
        trigger = req.model_dump()
        thread_id, created = RunStore().accept(trigger)
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    if created:
        background_tasks.add_task(_run_pipeline, req, thread_id)
    return {
        "accepted": True,
        "thread_id": thread_id,
        "duplicate": not created,
        "trigger": trigger,
    }


# 승인 결정을 받아 파이프라인 재개를 예약하고 200 을 돌려준다. _pending_approvals 에서
# proposal_id 를 꺼내므로 같은 제안서는 한 번만 받고, 없거나 이미 처리했으면 404 다. 지금은 쓰이지
# 않는 경로다. graph 에 승인 대기 노드가 없고, 승인은
# service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 가 service 안에서
# 기록하므로 이 경로를 부르는 production 코드가 없다.
@app.post("/approve", status_code=200, dependencies=[Depends(verify_token)])
async def approve(req: ApproveRequest, background_tasks: BackgroundTasks) -> dict:
    thread_id = _pending_approvals.pop(req.proposal_id, None)
    if not thread_id:
        raise HTTPException(
            status_code=404,
            detail=f"Proposal '{req.proposal_id}' not found or already processed",
        )
    background_tasks.add_task(_resume_pipeline, req, thread_id)
    return {"accepted": True, "proposal_id": req.proposal_id, "status": req.status}
