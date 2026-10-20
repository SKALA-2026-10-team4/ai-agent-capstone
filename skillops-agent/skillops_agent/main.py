"""FastAPI 진입점. service 가 저장·장애 이벤트를 POST /diagnose 로 보낸다."""

from typing import Literal

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException, Header
from pydantic import BaseModel

from .config import SERVICE_TOKEN

app = FastAPI(title="SkillOps Agent", version="0.1.0")


class DiagnoseRequest(BaseModel):
    type: Literal["failure", "skill_saved"]
    run_id: str | None = None
    skill: str | None = None
    version: int | None = None


async def verify_token(x_service_token: str = Header(default="")) -> None:
    """SERVICE_TOKEN 이 설정된 경우에만 검증한다. 비어 있으면 개발 모드로 통과."""
    if SERVICE_TOKEN and x_service_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid service token")


async def _run_pipeline(req: DiagnoseRequest) -> None:
    # TODO(PB-002): LangGraph Graph 로 교체한다.
    pass


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "skillops-agent"}


@app.post("/diagnose", status_code=202, dependencies=[Depends(verify_token)])
async def diagnose(req: DiagnoseRequest, background_tasks: BackgroundTasks) -> dict:
    background_tasks.add_task(_run_pipeline, req)
    return {"accepted": True, "trigger": req.model_dump()}
