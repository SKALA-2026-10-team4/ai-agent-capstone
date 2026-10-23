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
- 모델: `gpt-5.4-mini` (`SKILLOPS_MODEL` 로 변경). gpt-5 계열은 온도를 받지 않아 같은 입력에도 결과가 달라질 수 있다.
- 진단 파이프라인 설명은 `docs/skillops-design.md` 3절에 있고, 단계 순서는 `skillops_agent/graph.py` 의 `FLOW` 가 정한다. `main.py` 의 `/diagnose` 에서 시작한다.

## 수정안 RAG 데이터

수정안 생성에 넣을 참고 자료를 `rag/rag_data.json` 에 둔다. 항목 모양은 팀 RAG 실험의 TSG 설계(도구 계약 + skill 이름으로 조회 + 판단 기준)를 따른다. 사실 데이터는 사람이 쓰지 않고 자동으로 만든다.

| kind | id | 내용 | 만드는 곳 |
|---|---|---|---|
| `tool_contract` | `tool-<skill>` | skill 별 허용 도구, 인자, 보상 도구, 도구가 실제로 돌려주는 값 | `ToolCatalog.java` 의 BINDINGS·RELEASE_TOOL + 게시본으로 성공한 실행 기록의 `TOOL_RESULT` |
| `contract_map` | `contract-map` | 값마다 만드는 skill → 쓰는 skill, 처음 주문 입력 | 게시본 skill 목록의 inputs/outputs, `OrderFlowPolicy.INITIAL_INPUT_KEYS` |
| `policy` | `policy-flow` | 정상 steps, 선행 단계(결제 전 배송 금지 포함), 필수 마지막 단계, 최대 단계 수 | 게시본 steps·depends_on, `OrderFlowPolicy` |
| `policy` | `policy-outcomes` 등 | 정상 거절(REJECTED/HOLD)은 결함이 아님 | `rag/policies/*.md` (사람이 쓰고 version 을 올린다) |
| `guideline` | `guide-*` | 계약 복구와 되돌리기 구분, 계약 오류를 의도로 단정하지 않기 | `rag/guides/*.md` (운영자가 쓰고 version 을 올린다) |
| `case` | `case-*` | 증상 → 원인 skill → 올바른 수정 / 피해야 할 수정 | `rag/cases.json`. 처음에는 `order-agent/docs/scenarios` 의 S2·S3·S4·S6 으로 만들었다 |

```bash
uv run scripts/build_rag_data.py                       # 주문 Agent(:8081) 필요. 게시본 성공 실행을 찾아 쓴다
uv run scripts/build_rag_data.py --execution-id <UUID> # 실행을 직접 지정
```

- 다시 만들 때: 주문 Agent 의 도구·반환 값(`ToolCatalog`, `OrderToolService`), 게시본 SKILL.md, `OrderFlowPolicy` 가 바뀌었을 때, 또는 `rag/guides/`·`rag/policies/`·`rag/cases.json` 을 고쳤을 때.
- 최근 실행 20건에 게시본으로 성공한 실행이 없으면 멈춘다. 주문 화면(:5174)에서 기본 세트로 정상 주문을 한 번 실행한 뒤 다시 돌린다.
- `ToolCatalog` 와 실행 기록이 어긋나면(한쪽에만 있는 skill, 다른 도구) `WARN` 을 찍는다.
- 항목마다 `skills`, `fields`, `error_categories` 를 붙여 skill 이름과 오류 종류로 정확히 꺼낸다. 자동 생성 항목에는 `generated_at`, `bundle_version`(`<release>@<snapshot hash 앞 12자>`)이 붙는다.
- 사례로 평가할 때는 같은 케이스의 사례를 조회 대상에서 뺀다. 답을 미리 보여 주지 않기 위해서다.
### 진단 노드에 붙이기

`skillops_agent/rag.py` 가 진단 상태로 자료를 골라 원인 가설(`hypothesize`), 의도 추출(`extract_intent`), 후보 생성(`generate_candidates`) 프롬프트 맨 끝에 `## 참고 자료 (사내 지식 검색 결과, 판단 근거로만 사용)` 로 붙인다. 설명(`rank_and_explain`)에는 붙이지 않는다. 유사도 검색으로 문서를 고르지 않는다.

| 자리 | 자료 | 넣는 때 |
|---|---|---|
| 고정 | 도구 계약 | 원인 skill·바뀐 skill 과 이름이 같은 것만 |
| 고정 | 계약 지도 | 항상 |
| 고정 | policy-flow | order-flow 가 원인이거나 바뀌었을 때 |
| 고정 | 판단 기준 | 실패 기록의 오류 종류가 `applies_to_errors` 에 들 때 (모르면 모두) |
| 남는 자리 (2개) | 장애 사례 | 오류 종류와 원인·바뀐 skill 로 거른 뒤 글자 겹침이 큰 순으로 최대 2건. 실패 위치만 같은 사례는 고르지 않는다 |
| 남는 자리 | 그 밖의 policy | order-flow 가 바뀌었거나, 오류 종류가 없거나, 주문이 REJECTED·HOLD 로 끝났을 때 |

- 노드마다 붙인 자료 id 가 진단 기록의 `rag_references` 에 남는다.
- `SKILLOPS_RAG_ENABLED=false` 면 붙이지 않는다. 자료 파일이 없어도 자료 없이 진단한다.
- `SKILLOPS_RAG_EXCLUDE=case-S2,…` 로 고르지 않을 id 를 준다. 사례로 평가할 때 같은 케이스의 사례를 빼는 데 쓴다.
- 자료 파일은 프로세스가 처음 읽을 때 한 번 읽는다. 다시 만들었으면 Agent 를 다시 띄운다.

- 현재 C 계약·실행 입력·설정·미지원 경계는 [B/C 연동 안내](docs/c-contract-integration.md)를 따른다.
- 실서버 도구 호출이 기본이다. 오프라인 stub을 쓰려면 `SKILLOPS_USE_STUB_MCP=true`를 명시한다.
- 진단 결과는 MCP `save_proposal` 로 service 에 제안서로 저장한다. 저장은 승인·반영 완료를 뜻하지 않는다.
