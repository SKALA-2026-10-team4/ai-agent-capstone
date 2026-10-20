# D · 현업 서비스 구현 (1인)

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

**SkillOps Agent 가 고칠 "현업 서비스"를 실제로 만든다.** 주문 도메인, 재고·결제·배송·알림 mock 어댑터, 그리고 담당자 말투로 쓴 SKILL.md 6개와 그 위에서 돌아갈 정상 주문 6건 + 장애 3건. 데모의 서사(담당자가 무엇을 바꿨고 왜 깨졌고 어떻게 고쳐야 하는가)는 전부 내 손에서 나온다. 계약 문서 관리자이기도 하다.

## 2. 담당 폴더와 연결부

| 폴더 | 이 폴더가 책임지는 것 | 연결부 |
|---|---|---|
| `skills/` | SKILL.md 6개의 **본문**. 개발자 말이 아니라 업무 담당자가 후임에게 설명하듯. 어댑터는 자연어로만 지칭(코드 이름 금지 — harness 가 tool description 으로 연결). 각 단계가 무엇을 확인·호출·기록하는지 3~6줄 | ← 계약 1 frontmatter(이미 있음) · → A `harness/` 로더가 읽음 · → `adapter/` tool 설명과 어휘를 맞춤 · F1(inventory-check outputs)·F2(order-flow steps)·F3(payment 조건)이 여기를 고친다 |
| `scenarios/` | `golden/` g-01~g-06(정상·복수상품·선입금·재고부족→REJECTED·결제실패·해외배송), `failures/` F1~F3(담당자 변경 diff·의도 한 문장·깨지는 곳·기대 수정안 kind). 기대값은 `skills_called`·`final_status`·`shipments`·`assertions` 만 | → C-2 `scenario/` 러너 · → B(무회귀 전체 실행, F diff 는 테스트 fixture) · **숫자는 `adapter/` mock 데이터와 맞아야 한다** |
| `service/…/domain/` | 주문·주문항목 엔티티(`status ∈ SHIPPED/REJECTED/HOLD/FAILED`, run_id 보관), 주문 서비스: 저장 → **A 흐름 실행기를 live 로 호출** → 결과 상태 저장 → **FAILED 면 C-2 webhook(failure)** | ← A `api/`(주문 생성) · → A `harness/` · → C-2 `webhook/` · → C-1 `trace/`(run_id) |
| `service/…/adapter/` | 재고·결제·배송·알림 mock 4개를 Spring AI `@Tool` 로. **LIVE/DRY_RUN 모드**(DRY_RUN 은 결정적 응답), 고정 mock 데이터(SKU 재고표, 고객, 국가별 ETA). 예: 재고 부족 SKU 하나(g-04), 금액 조건으로 결제 실패(g-05), 해외면 ETA +5일(g-06) | → A `harness/` 가 ChatClient 에 주입 · ← C-2 `scenario/` 가 DRY_RUN 지정 · trace `tools_called` 에 tool 이름이 기록됨 |
| `docs/contracts/` | 계약 5개 **관리**: 1일차 09시 동결 주관, 변경 요청 모아 13시·18시 반영 | 전원 |
| `docs/` 데모 대본 | g-01 정상 → F1 저장 → 실패 → 진단 → 수정안 비교 → 승인 → 재실행 통과. F3 는 "되돌리기 유혹" 장면 | 전원 · 발표 |

## 3. 지금 있는 파일

| 파일 | 하는 일 | 연결부 |
|---|---|---|
| `skills/*/SKILL.md` ×6 | frontmatter(inputs/outputs/depends_on/steps) 완성, 본문은 TODO | 계약 1 |
| `skills/README.md` | 흐름 그림과 규칙 | — |
| `scenarios/golden/g-01.json` | 단일 상품 카드 정상. 나머지 5개의 템플릿 | 계약 3 |
| `scenarios/failures/F1.md` | 필드명 변경 장애: 의도·깨지는 곳·기대 수정안 3종(되돌리기는 불합격) | 1일차 18시 데모 |
| `scenarios/README.md` | 폴더 규칙, 골든 6개 목록 | — |
| `…/domain/package-info.java`, `…/adapter/package-info.java` | 패키지 책임 한 줄 | — |
| `docs/contracts/*` (5개 + README) | 계약 초안 v0 | 전원 |

## 4. 다른 역할과 맞출 접점 (1일차 11시까지)

1. **어댑터 주입과 모드 전달** — `@Tool` 빈을 A 가 어떻게 ChatClient 에 넘기고, DRY_RUN 을 어떻게 받는지. A·C-2 와.
2. **tool 어휘** — 어댑터 `@Tool` 의 이름·설명 ↔ SKILL.md 본문의 자연어 ↔ trace `tools_called` ↔ 골든 기대값. 넷이 한 세트다.
3. **mock 숫자 ↔ 골든 기대값 ↔ SKILL.md 규칙** — 셋 중 하나를 바꾸면 나머지 둘을 같이 본다. 어긋나면 B 의 "무회귀" 판정이 거짓 실패를 낸다.
4. **failure webhook 호출** — 주문 FAILED 시 C-2 의 webhook 을 어떻게 부르는지. C-2 와.
5. **계약 동결** — 09시 회의 주관, 변경 창구.

## 5. 마일스톤

- **1일차 09시**: 계약 5개 동결 회의.
- **1일차 13시**: 어댑터 4개 + mock 데이터 + 모드(A 가 바로 주입할 수 있게).
- **1일차 18시**: SKILL.md 6개 본문, g-01~g-06, F1 diff. `POST /api/orders` 가 g-01 로 SHIPPED.
- **2일차 13시**: F2·F3 diff, 데모 대본, 리허설 진행.
- **잘리는 순서**: g-06 해외 배송 → F3 → 데모 대본 축약. F1·g-01~g-05·어댑터는 빼지 않는다.
