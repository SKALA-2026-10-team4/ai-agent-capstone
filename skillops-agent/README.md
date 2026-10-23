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

## 수정안 RAG 데이터

수정안 생성에 넣을 참고 자료를 `rag/rag_data.json` 에 둔다. 사실 데이터는 사람이 쓰지 않고 자동으로 만든다.

| kind | 내용 | 만드는 곳 |
|---|---|---|
| `tool_contract` | skill 별 도구, 인자, 도구가 실제로 돌려주는 값 | 게시본으로 성공한 실행 기록의 `TOOL_REQUEST`·`TOOL_RESULT` |
| `contract_map` | 값마다 만드는 skill → 쓰는 skill, 처음 주문 입력 | 게시본 skill 목록의 inputs/outputs, `OrderFlowPolicy.INITIAL_INPUT_KEYS` |
| `flow_policy` | 정상 steps, 선행 단계, 필수 마지막 단계, 최대 단계 수 | 게시본 steps·depends_on, `OrderFlowPolicy` |
| `guideline` | 계약 복구와 되돌리기 구분, 계약 오류를 의도로 단정하지 않기 | `rag/guides/*.md` (운영자가 쓰고 version 을 올린다) |

```bash
uv run scripts/build_rag_data.py                       # 주문 Agent(:8081) 필요. 게시본 성공 실행을 찾아 쓴다
uv run scripts/build_rag_data.py --execution-id <UUID> # 실행을 직접 지정
```

- 다시 만들 때: 주문 Agent 의 도구·반환 값, 게시본 SKILL.md, `OrderFlowPolicy` 가 바뀌었을 때, 또는 `rag/guides/` 를 고쳤을 때.
- 최근 실행 20건에 게시본으로 성공한 실행이 없으면 멈춘다. 주문 화면(:5174)에서 기본 세트로 정상 주문을 한 번 실행한 뒤 다시 돌린다.
- 항목마다 `skills`, `fields`, `error_categories` 를 붙여 skill 이름과 오류 종류로 정확히 꺼낸다. 결과에는 생성 시각, 근거 실행, 게시본 snapshot 이 함께 남는다.
- 진단 노드에 붙이는 조회는 #79 에서 한다.

- 현재 C 계약·실행 입력·설정·미지원 경계는 [B/C 연동 안내](docs/c-contract-integration.md)를 따른다.
- 실서버 도구 호출이 기본이다. 오프라인 stub을 쓰려면 `SKILLOPS_USE_STUB_MCP=true`를 명시한다.
- C에 제안 저장 도구가 없어 평가 결과는 `NOT_SAVED`로 종료한다. 승인·반영 완료를 뜻하지 않는다.
