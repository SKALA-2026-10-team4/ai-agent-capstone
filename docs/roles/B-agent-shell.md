# B · Agent 껍데기 (1인)

## 0. 전체 구조 (폴더만)

```
ai-agent-capstone/
├── service/                         Spring Boot 4.1 · 주문 처리 서비스 + Spring AI harness + MCP 서버 (:8080)
│   └── src/main/java/com/capstone4/skillops/
│       ├── api/                     REST 컨트롤러. Vue 가 부르는 유일한 입구 (/api/*)
│       ├── harness/                 SKILL.md 로더 + skill 실행기(Spring AI ChatClient). "skill 을 돌리는 엔진"
│       ├── registry/                frontmatter 레지스트리 + 계약 일관성 검사
│       ├── domain/                  주문·재고·결제·배송 도메인 (현업 서비스 본체)
│       ├── adapter/                 재고·결제·배송·알림 mock 어댑터 — harness 가 @Tool 로 호출
│       ├── trace/                   실행 트레이스 저장 (어떤 skill 이 무엇을 받고 무엇을 냈나)
│       ├── workspace/               SKILL.md 버전 저장소 + sandbox workspace (격리 복사본)
│       ├── scenario/                시나리오 러너 (dry-run k회, 기대값 비교)
│       ├── webhook/                 저장·장애 이벤트를 SkillOps Agent 에 POST
│       └── mcp/                     @McpTool 12개 — SkillOps Agent 만 쓰는 창구 (/mcp)
├── skillops-agent/                  Python 3.11 · SkillOps Agent (:8000)
│   ├── skillops_agent/              패키지 — 진단 파이프라인 ①~⑥, MCP 클라이언트, LLM 호출
│   ├── scripts/                     smoke 테스트, stub MCP 서버
│   └── tests/
├── frontend/                        Vue 3.5 + Vite · 담당자/운영자 화면 (:5173, /api → 8080 프록시)
│   └── src/                         views/ components/ stores/ router/ api/
├── skills/                          live SKILL.md 6개 — 서비스가 읽는 원본
├── scenarios/                       golden/ 정상 주문 6건 · failures/ 장애 F1~F3
├── docs/                            설계도, contracts/(계약 5개), roles/(이 문서들)
└── docker-compose.yml               PostgreSQL 17 (:5432)
```

**한 문장 아키텍처.** 담당자가 Vue 에서 `skills/` 의 SKILL.md 를 고치면(저장 = 버전) → service 의 harness 가 그 skill 들로 주문을 처리하고 모든 실행을 trace 로 남긴다 → 주문이 실패하거나 skill 이 저장되면 webhook 이 SkillOps Agent 를 깨운다 → Agent 는 **MCP tool 12개만으로** trace·diff 를 읽고, workspace 에 후보 수정안을 적용해 시나리오를 돌려 검증하고, 통과한 수정안을 proposal 로 저장한다 → 운영자가 Vue 에서 승인하면 service 가 workspace 를 live 로 promote 한다.

경계 두 개만 기억하면 된다: **Vue ↔ service 는 REST(`/api`)**, **Agent ↔ service 는 MCP(`/mcp`, JSON-RPC 2.0)**. 둘 다 `docs/contracts/` 에 있다.

## 1. 내 역할

**SkillOps Agent 본체.** webhook 으로 깨어나서, MCP tool 로 증거를 모으고, LLM 으로 가설·의도·후보·설명을 만들고, 검증은 service 에 시켜서, 통과한 수정안만 `save_proposal` 한다. 원칙: **LLM 은 제안하고 harness 가 판정한다** — 내 코드 어디에도 "LLM 이 괜찮다고 했으니 통과" 가 있으면 안 된다. 파이프라인을 어떤 모듈로 나눌지는 내가 정한다.

## 2. 담당 폴더와 연결부

| 폴더 | 이 폴더가 책임지는 것 | 연결부 |
|---|---|---|
| `skillops-agent/skillops_agent/` | ① webhook 수신(FastAPI `POST /diagnose`) → 파이프라인 비동기 시작 ② 파이프라인 ①원인 가설 → ②ablation(hunk 하나씩 되돌려 재실행으로 원인 확정) → ③의도 추출(문장 + assertion) → ④후보 2~3개(local/contract/flow, unified diff) → ⑤격리 검증(후보마다 workspace → 패치 → 골든 전체+재현+의도 assertion, k회) → ⑥유효 판정(기준 1·2·3)과 순위(4·5), 설명은 evidence 만 인용 ③ 계약 2·3·4 를 그대로 옮긴 모델 ④ LLM 클라이언트, MCP 클라이언트 | ← C-2 `webhook/` 이 깨움(계약 5 webhook) · → service `/mcp` 의 읽기 tool 6개(C-1)·쓰기 tool 5개(C-2) · → `save_proposal`(C-2) → A `api/` 조회 → A 화면 |
| `skillops-agent/scripts/` | 배선 확인(smoke), **C 가 완성되기 전 개발용 stub MCP 서버**(계약대로 canned 응답, 다른 포트) | stub 의 응답 JSON 은 C-1·C-2 가 "서버가 돌려줄 모양" 참고 자료로 쓸 수 있다 |
| `skillops-agent/tests/` | 단계별 단위 테스트. LLM 호출은 fixture 로 대체 | — |

## 3. 지금 있는 파일

| 파일 | 하는 일 | 연결부 |
|---|---|---|
| `skillops_agent/main.py` | FastAPI. `POST /diagnose` 수신, 지금은 202 만 | ← C-2 webhook. 여기서 파이프라인을 시작하도록 바꾼다 |
| `skillops_agent/config.py` | `.env` → `SKILLOPS_MODEL`, `SKILLOPS_SERVICE_URL`, `SKILLOPS_MCP_URL` | stub 전환은 `SKILLOPS_MCP_URL` 로 |
| `skillops_agent/llm.py` | `anthropic.Anthropic()` 팩토리, 모델 `claude-opus-5-5` | 모든 LLM 호출 |
| `skillops_agent/mcp_client.py` | `mcp.Client(url)` — mcp **2.x** 고수준 API(Streamable HTTP) | → service `/mcp` |
| `scripts/mcp_smoke.py` | tool 목록 + `ping` 호출. 1.x 의 `streamablehttp_client` 는 없다 — 이 파일의 패턴을 따른다 | — |
| `tests/test_health.py` | FastAPI 테스트 3개 | 테스트 패턴 |
| `pyproject.toml`, `.python-version`, `uv.lock` | 의존성(anthropic 1.15, mcp 2.3, fastapi, pydantic, pytest, ruff), Python 3.11 | `uv add` 로 추가 시 lock 도 커밋 |

## 4. 다른 역할과 맞출 접점

1. **webhook payload** — `{type: failure, run_id}` / `{type: skill_saved, skill, version}` (계약 5). C-2 와.
2. **12 tool 의 인자·반환** — 계약 5 표. 특히 `diff_skill` 의 hunk 번호(C-1)와 `run_scenarios` 의 결과 모양(C-2)은 내 ablation·verify 가 그대로 의존한다.
3. **재현 시나리오** — 실패 trace 의 주문을 계약 3 형태로 바꿔 `run_scenarios` 인자로 넘긴다(`source_run_id`). 파일로 두지 않는다. C-2 와.
4. **assertion 문법** — 내가 ③에서 생성하는 `payment.status == 'HOLD'` 를 C-2 의 평가기가 읽는다. 연산자 `==`, `!=`, `in` 만.
5. **proposal JSON** — 계약 4. `evidence` 비면 C-2 가 저장을 거부한다. A 의 화면이 이걸 그대로 그린다.

## 5. 마일스톤

- **1일차 13시**: stub MCP 서버 + 계약 모델 + tool 래퍼. smoke 가 stub 에서도 통과.
- **1일차 18시**: ① → ② → ⑥ 만으로 F1 proposal 1개(후보 1개, evidence 포함). 실제 MCP 서버로 전환해 같은 결과.
- **2일차 13시**: ③ 의도, ④ 후보 3종, ⑤ 매트릭스. F1·F2·F3 각각 proposal 생성.
- **잘리는 순서**: ③ 자동 추출(담당자 직접 입력으로 대체) → 후보 3종을 2종으로. ②·⑤ 는 빼지 않는다.
