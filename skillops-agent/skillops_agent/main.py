"""FastAPI 진입점. service 가 저장·장애 이벤트를 POST /diagnose 로 보낸다.

진단 파이프라인 ①~⑦ 은 W3 의 1일차 작업이다. 지금은 요청을 받아 202 로 응답만 한다.
"""

from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="SkillOps Agent", version="0.1.0")


class DiagnoseRequest(BaseModel):
    type: Literal["failure", "skill_saved"]
    run_id: str | None = None
    skill: str | None = None
    version: int | None = None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "skillops-agent"}


@app.post("/diagnose", status_code=202)
def diagnose(req: DiagnoseRequest) -> dict:
    # TODO(W3): 트리거를 큐에 넣고 파이프라인을 비동기로 시작한다.
    return {"accepted": True, "trigger": req.model_dump()}
