# C-1 · MCP 구성 — 읽기 tool · 저장소 (1인)

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

**Agent 가 "읽는" 모든 것을 책임진다.** trace 와 SKILL.md 버전을 DB 에 쌓고, 그걸 MCP 읽기 tool 6개로 내놓는다. 내 tool 이 돌려주는 JSON 모양이 곧 B 가 보는 세상이므로 **계약 2(trace)·계약 5(tool 표)와 글자 하나까지 맞춘다.** C-2 와 둘이 `mcp/`·`workspace/` 를 나눠 쓴다 — 나는 읽기와 영구 저장, C-2 는 쓰기와 임시 복사본.

## 2. 담당 폴더와 연결부

| 폴더 | 이 폴더가 책임지는 것 | 연결부 |
|---|---|---|
| `service/…/trace/` | harness 가 step 마다 부르는 **기록 API**, 실행 종료 시 저장(JPA, steps 는 JSON 컬럼), 계약 2 JSON 으로 변환 | ← A `harness/` 가 호출 · → 내 `mcp/` 읽기 tool · → A `api/`(GET trace) · → C-2 `scenario/`(기대값 비교에 trace 사용) |
| `service/…/workspace/` 중 **버전 저장소** | `(skill, version) → 본문·frontmatter·저장시각`. 저장은 version +1 이고 **live 디렉토리 `skills/<name>/SKILL.md` 도 덮어쓴다** | ← A `api/`(PUT skills) · ← C-2 promote(승격 시 저장) · → 내 `diff_skill`, `get_skill(version)` · → C-2 sandbox 가 base 버전 본문을 여기서 읽음 |
| `service/…/mcp/` 중 **읽기 tool 6개** | `list_skills`, `get_skill`, `diff_skill`(hunk 단위), `get_dependency_graph`, `list_failed_runs`, `get_trace` | ← A `registry/`(정의·steps·violations) · ← 내 저장소들 · → B. 파일은 tool 묶음 단위로 C-2 와 나눈다 |

## 3. 지금 있는 파일

| 파일 | 하는 일 | 연결부 |
|---|---|---|
| `…/mcp/PingTools.java` | `ping` — `@McpTool` 작성 예시(`org.springframework.ai.mcp.annotation.McpTool`, `@Component` 면 자동 등록) | B `scripts/mcp_smoke.py` 가 호출 |
| `…/trace/package-info.java`, `…/workspace/package-info.java` | 패키지 책임 한 줄 | workspace 는 C-2 와 공유 |
| `service/src/main/resources/application.yml` 의 `spring.ai.mcp.server` | `name`, `protocol: STREAMABLE`, `type: SYNC`, `annotation-scanner` | C-2 와 공유. 바꾸면 알린다 |
| `docs/contracts/trace.schema.json`, `mcp-tools-and-rest.md` | 내 출력의 정답지 | — |

## 4. 다른 역할과 맞출 접점 (1일차 11시까지)

1. **trace 기록 API** — 시작(주문·모드·workspace·버전들) / step(skill, version, input, output, tools_called, status, error, ms) / 종료(final_status). A 와.
2. **버전 저장소 API** — 저장(이름, 본문 → 새 version)과 조회(이름, version → 본문). A(PUT), C-2(promote, base 읽기) 와.
3. **hunk 번호 체계** — `diff_skill` 이 돌려주는 hunk `index` 를 B 의 ablation 이 "그 hunk 만 다시 적용" 에 쓴다. diff 라이브러리(예: java-diff-utils) 추가는 A 에 공지.
4. **`mcp/` 파일 분담** — 나는 읽기 tool 묶음, C-2 는 쓰기 tool 묶음. 같은 파일을 둘이 만지지 않는다.

## 5. 마일스톤

- **1일차 11시**: 접점 1·2 확정.
- **1일차 15시**: `list_skills`, `get_skill`, `get_trace` 가 Python 에서 호출됨(DB 에 수동 fixture).
- **1일차 18시**: 주문 한 건 실행 → trace 저장 → `list_failed_runs`·`get_trace`·`diff_skill`·`get_dependency_graph` 로 B 가 F1 을 진단.
- **2일차**: 성능·페이징은 하지 않는다. `since` 필터 정도만.
