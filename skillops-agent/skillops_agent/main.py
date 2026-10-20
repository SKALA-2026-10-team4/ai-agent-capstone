"""FastAPI 진입점. service 가 저장·장애 이벤트를 POST /diagnose 로 보낸다."""

import uuid
from typing import Literal

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException, Header
from langgraph.types import Command
from pydantic import BaseModel

from .config import SERVICE_TOKEN
from .graph import graph

app = FastAPI(title="SkillOps Agent", version="0.1.0")

# proposal_id → thread_id 매핑 (승인 대기 중인 파이프라인)
_pending_approvals: dict[str, str] = {}


class DiagnoseRequest(BaseModel):
    type: Literal["failure", "skill_saved"]
    run_id: str | None = None
    skill: str | None = None
    version: int | None = None


class ApproveRequest(BaseModel):
    proposal_id: str
    status: Literal["approved", "rejected"]
    token: str | None = None


async def verify_token(x_service_token: str = Header(default="")) -> None:
    """SERVICE_TOKEN 이 설정된 경우에만 검증한다. 비어 있으면 개발 모드로 통과."""
    if SERVICE_TOKEN and x_service_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid service token")


async def _run_pipeline(req: DiagnoseRequest) -> None:
    thread_id = str(uuid.uuid4())
    config = {"configurable": {"thread_id": thread_id}}
    initial_state = {"trigger": req.model_dump(), "max_steps": 40}
    result = await graph.ainvoke(initial_state, config=config)
    # interrupt 지점에서 반환된 state 에서 proposal_id 추출 후 매핑 저장
    proposal_id = (result.get("proposal") or {}).get("id")
    if proposal_id:
        _pending_approvals[proposal_id] = thread_id


async def _resume_pipeline(req: ApproveRequest, thread_id: str) -> None:
    config = {"configurable": {"thread_id": thread_id}}
    await graph.ainvoke(
        Command(resume={"status": req.status, "token": req.token}),
        config=config,
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "skillops-agent"}


@app.post("/diagnose", status_code=202, dependencies=[Depends(verify_token)])
async def diagnose(req: DiagnoseRequest, background_tasks: BackgroundTasks) -> dict:
    background_tasks.add_task(_run_pipeline, req)
    return {"accepted": True, "trigger": req.model_dump()}


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
