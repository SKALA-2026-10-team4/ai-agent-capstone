# skillops-agent

SkillOps Agent (W3). Python 3.11 + uv.

```bash
uv sync                                  # 의존성 설치 (.venv)
uv run uvicorn skillops_agent.main:app --reload --port 8000
uv run pytest
uv run ruff check .
uv run scripts/mcp_smoke.py              # service(8080) 가 떠 있을 때: MCP tool 목록 + ping
```

- 설정은 루트 `.env` (`.env.example` 복사). `OPENAI_API_KEY` 없이도 테스트와 MCP ping 은 된다.
- 모델: `gpt-4.1-mini` (`SKILLOPS_MODEL` 로 변경).
- 진단 파이프라인 ①~⑦ 은 `docs/skillops-design.md` 3절. `main.py` 의 `/diagnose` 에서 시작한다.

- 현재 C 계약·실행 입력·설정·미지원 경계는 [B/C 연동 안내](docs/c-contract-integration.md)를 따른다.
- #72의 apply_patch v1.2는 `dependency_violations`와 `metadata_errors` 필드를 요구한다. service와 Python을 함께 갱신해야 하며 구버전 응답의 필드 누락은 계약 오류로 처리한다.
- 실서버 도구 호출이 기본이다. 오프라인 stub을 쓰려면 `SKILLOPS_USE_STUB_MCP=true`를 명시한다.
- C에 제안 저장 도구가 없어 평가 결과는 `NOT_SAVED`로 종료한다. 승인·반영 완료를 뜻하지 않는다.

- #72 3단계: `apply_patch` v1.2의 필수 `metadata_errors`를 읽어 후보의 `metadata_valid`를 판정한다. service와 Python을 함께 갱신해야 한다. [필수 정보 변경 내역](../docs/changes/2026-10-23-skill-metadata-validation.md).
