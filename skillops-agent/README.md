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
| `case` | `case-p_*` | 운영자가 승인·반려한 제안서에서 만든 사례. 승인은 승인한 후보가 올바른 수정, 반려는 추천 후보가 피해야 할 수정이다 | service 의 `/api/approvals`·`/api/proposals` → `rag/proposal_cases.json` 에 쌓는다 (저장소에 올리지 않는다) |

```bash
uv run scripts/build_rag_data.py                       # 주문 Agent(:8081) 필요. 게시본 성공 실행을 찾아 쓴다
uv run scripts/build_rag_data.py --execution-id <UUID> # 실행을 직접 지정
```

- 다시 만들 때: 주문 Agent 의 도구·반환 값(`ToolCatalog`, `OrderToolService`), 게시본 SKILL.md, `OrderFlowPolicy` 가 바뀌었을 때, 또는 `rag/guides/`·`rag/policies/`·`rag/cases.json` 을 고쳤을 때.
- 최근 실행 20건에 게시본으로 성공한 실행이 없으면 멈춘다. 주문 화면(:5174)에서 기본 세트로 정상 주문을 한 번 실행한 뒤 다시 돌린다.
- `ToolCatalog` 와 실행 기록이 어긋나면(한쪽에만 있는 skill, 다른 도구) `WARN` 을 찍는다.
- 제안서 사례는 `SKILLOPS_RAG_SERVICE_USERNAME`·`SKILLOPS_RAG_SERVICE_PASSWORD` 를 주고 돌릴 때 읽는다. 결정 전(proposed)·유효 안 없음 제안서는 쓰지 않는다. 같은 제안서는 한 번만 쌓이고, 계정 없이 돌리면 이미 쌓인 사례만 쓴다.
- 사례의 `confidence` 는 `확인됨(시나리오 문서)`, `확인됨(원인 실험·운영자 승인)`, `추정` 가운데 하나다. 반려는 사유가 남지 않아 항상 `추정` 이다.
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
- `SKILLOPS_RAG_EXCLUDE=case-S2,…` 로 고르지 않을 id 를 준다. 사례로 평가할 때 같은 케이스의 사례를 빼는 데 쓴다. `/diagnose` 요청의 `rag_exclude` 목록도 이 값에 더해 뺀다.
- 자료 파일은 프로세스가 처음 읽을 때 한 번 읽는다. 다시 만들었으면 Agent 를 다시 띄운다.

- 현재 C 계약·실행 입력·설정·미지원 경계는 [B/C 연동 안내](docs/c-contract-integration.md)를 따른다.
- #72의 apply_patch v1.3은 `dependency_violations`, `metadata_errors`, `flow_errors` 필드를 요구한다. service와 Python을 함께 갱신해야 하며 구버전 응답의 필드 누락은 계약 오류로 처리한다.
- 실서버 도구 호출이 기본이다. 오프라인 stub을 쓰려면 `SKILLOPS_USE_STUB_MCP=true`를 명시한다.
- #72 partial 목록은 원문·해시·오류·diff를 수집해 상세 실행 기록에 저장하고 종료한다. 이 경로에서는 자동 수정·LLM·실행 평가를 호출하지 않는다. [진단 증거 조회 계약](../docs/contracts/diagnosis-evidence.md).
- 실서버 partial 증거 수집 검증은 `scripts/partial_evidence_smoke.py`로 재실행할 수 있다. 별도 service DB가 필요하며 테스트 snapshot 4개를 추가한다. [실제 연결 검증 결과와 실행 방법](../docs/reports/2026-10-23-partial-evidence-integration.md).


- #72 3단계: `apply_patch` v1.2의 필수 `metadata_errors`를 읽어 후보의 `metadata_valid`를 판정한다. service와 Python을 함께 갱신해야 한다. [필수 정보 변경 내역](../docs/changes/2026-10-23-skill-metadata-validation.md).

- #72 4단계: `flow_errors`로 `flow_valid`를 별도 판정하고 구조 오류 후보의 추천을 막는다. [변경 내역](../docs/changes/2026-10-23-flow-structure-validation.md).

- 진단 결과는 MCP `save_proposal` 로 service 에 제안서로 저장한다. 저장은 승인·반영 완료를 뜻하지 않는다.

## 행동 변경 수정본과 담당자 의도

계획은 [행동 변경 근본 개선](../docs/plans/2026-10-26-behavior-edit-root-fix.md) 2절이다.

- `/diagnose` 는 선택 칸 `intent: {text, expectations: [{scope, checks}]}` 와 `rag_exclude: [id]` 를 받는다. 조건은 문법 v2(`==`, `!=`, `in`, `>`, `>=`, `<`, `<=`)이며 읽을 수 없으면 422 다.
- 의도가 있으면 기준본 사전 검증은 의도 없이, 수정본 사전 검증과 후보 검증은 `run_scenarios` 의 `intent_expectations` 로 돌린다. 원인 실험에는 붙이지 않는다.
- 저장 직후 요청에 의도 글(`text`)만 있고 `expectations` 가 비면, `fetch_evidence` 가 수정본 사전 검증 전에 LLM 을 한 번 불러(`prompts/infer_expectations.md`) expectations 를 만든다. 프롬프트에는 의도 글, 변경 hunk, 수정본 frontmatter, scope 값 이름, checks 값 이름(선언 inputs·outputs + 예약 키), 기준 주문 표가 들어간다. Agent 에는 기준 주문을 읽는 도구가 없어 표는 `config.GOLDEN_ORDERS`(기준 주문 파일과 상품 단가를 옮긴 값, 테스트가 두 파일과 대조)에서 만든다. 문법·값 이름이 맞지 않는 줄과 상한(expectations 10개, 항목마다 checks 10개)을 넘는 줄은 이유와 함께 `intent.dropped_checks` 에 남기고, 남은 것을 `intent`(출처 `inferred`)로 두어 `extract_intent` 가 다시 만들지 않는다. LLM 이 실패하거나 쓸 줄이 없으면 예전처럼 의도 시험 없이 사전 검증하고 `diagnosis.intent_note` 에 이유를 남긴다. 담당자가 expectations 를 주었거나 의도가 없으면 이 호출은 없다.
- 수정본이 오류 없이 돌고 의도 범위 기준 주문만 떨어지면 `diagnosis.finding=INTENT_NOT_REALIZED`, `root_cause.kind=incomplete_edit` 로 원인 실험 없이 후보를 만든다. 의도 없이 결과만 다르면 그 기준 주문(`repro_scenario_ids`)으로 원인 실험을 잇고, 확정하지 못하면 `VALIDATION_FAILED` 다.
- 의도 expectations(담당자가 준 것 또는 Agent 가 만든 것)가 있으면 LLM 과 별개로 결정적 `encode_rule` 후보(단계 skill 의 `rules`)를 하나 만든다(`skillops_agent/skill_rules.py`). Agent 가 만든 expectations 로 만든 후보도 같은 후보 검증으로 판정한다. 의도가 있으면 되돌리기(`is_revert`)와 계약 약화(`weakens_contract`) 후보는 코드가 불합격시킨다.
- 기준 주문 기본값은 `g-01`~`g-06` 이다. contract-only 실행기(`scenarios/golden`)로 돌릴 때는 `SKILLOPS_GOLDEN_IDS=g-01` 로 둔다.
- `SKILLOPS_SERVICE_TOKEN` 이 있으면 MCP 요청에도 `X-Service-Token` 헤더를 붙인다. apply_patch v1.4 의 `metadata_errors.field=rules` 와 `CONTRACT_VIOLATION/RULE_KEY_NOT_INPUT` 은 후보 무효로 읽는다.
