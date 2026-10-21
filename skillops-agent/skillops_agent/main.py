"""FastAPI 진입점. service 가 저장·장애 이벤트를 POST /diagnose 로 보낸다."""

import uuid
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException
from langgraph.types import Command
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .config import SERVICE_TOKEN
from .contracts import execution_uuid
from .graph import graph
from .run_store import RunStore


@asynccontextmanager
async def lifespan(app):
    RunStore().recover()
    yield


app = FastAPI(title="SkillOps Agent", version="0.1.0", lifespan=lifespan)

# proposal_id → thread_id 매핑 (승인 대기 중인 파이프라인)
_pending_approvals: dict[str, str] = {}


class DiagnoseRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    type: Literal["failure", "skill_saved"]
    execution_id: str | None = None
    baseline_snapshot_id: str | None = None
    candidate_snapshot_id: str | None = None
    changed_paths: list[str] | None = None
    change_id: str | None = None
    entry_skill_id: str = "order-flow"
    diagnosis_only: bool = False
    intent_checks: list[str] = Field(default_factory=list)
    run_id: str | None = None
    skill: str | None = None
    version: int | None = None

    @model_validator(mode="after")
    def validate_references(self):
        if (
            self.execution_id
            and self.run_id
            and execution_uuid(self.execution_id) != execution_uuid(self.run_id)
        ):
            raise ValueError("execution_id and run_id differ")
        value = self.execution_id or self.run_id
        if value:
            self.execution_id = execution_uuid(value)
        if self.type == "failure" and not self.execution_id:
            raise ValueError("failure requires execution_id (run_id alias accepted)")
        if self.type == "skill_saved" and not self.candidate_snapshot_id:
            raise ValueError("skill_saved requires candidate_snapshot_id")
        for value in (self.baseline_snapshot_id, self.candidate_snapshot_id):
            if value:
                uuid.UUID(value)
        if self.diagnosis_only and (not self.execution_id or not self.intent_checks):
            raise ValueError("diagnosis_only requires execution_id and intent_checks")
        return self


class ApproveRequest(BaseModel):
    proposal_id: str
    status: Literal["approved", "rejected"]
    token: str | None = None


async def verify_token(x_service_token: str = Header(default="")) -> None:
    """SERVICE_TOKEN 이 설정된 경우에만 검증한다. 비어 있으면 개발 모드로 통과."""
    if SERVICE_TOKEN and x_service_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid service token")


async def _run_pipeline(req: DiagnoseRequest, thread_id: str) -> None:
    config = {"configurable": {"thread_id": thread_id}}
    initial_state = {"trigger": req.model_dump(), "max_steps": 40, "pipeline_thread_id": thread_id}
    try:
        result = await graph.ainvoke(initial_state, config={**config, "recursion_limit": 100})
        RunStore().update(thread_id, result)
    except Exception as exc:
        RunStore().update(
            thread_id, {"diagnosis_status": "ERROR", "error": f"{type(exc).__name__}: {exc}"}
        )
        return
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


@app.get("/runs/{thread_id}/events", dependencies=[Depends(verify_token)])
async def get_run_events(thread_id: str) -> dict:
    run = RunStore().get(thread_id)
    if run is None:
        raise HTTPException(status_code=404, detail="Diagnosis not found")
    return run


@app.get("/runs", dependencies=[Depends(verify_token)])
async def list_runs():
    return {"items": RunStore().list()}


@app.post("/diagnose", status_code=202, dependencies=[Depends(verify_token)])
async def diagnose(req: DiagnoseRequest, background_tasks: BackgroundTasks) -> dict:
    try:
        thread_id, created = RunStore().accept(req.model_dump())
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    if created:
        background_tasks.add_task(_run_pipeline, req, thread_id)
    return {
        "accepted": True,
        "thread_id": thread_id,
        "duplicate": not created,
        "trigger": req.model_dump(),
    }


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
