# qa

SkillOps 평가셋과 실행기다. 담당자가 Skill 을 고쳤을 때 SkillOps 가 문제를 찾고, 원인을 짚고, 쓸 수 있는 개선안을
내는지를 정답표로 잰다. SkillOps Agent 의 내부(노드, 상태 칸, 프롬프트)는 보지 않고 바깥 API 만 쓰므로, AI 담당이
진단 흐름을 튜닝해도 실행기와 케이스는 그대로 쓸 수 있다.

| 파일 | 내용 |
|---|---|
| `cases/*.yaml` | 케이스 하나에 파일 하나. 수정, 의도, 주문 Agent 기대 반응, 정답, 정답표가 판정을 정한 안, 근거, 지금 구현 결과 |
| `run_qa.py` | 실행기. `list`, `check`, `eval` 세 명령 |
| `qa_logic.py` | 판정 규칙과 지표 계산(서버를 부르지 않는 순수 함수) |
| `tests/` | 판정 규칙 단위 테스트(`test_qa_logic.py`)와 케이스 파일 검사(`test_cases.py`: 수정 글이 게시본 Skill 에 정확히 한 번 있는지, 반응 표가 g-01~g-06 을 모두 적었는지, B 묶음 안이 판정 규칙에 맞는지) |
| `results/` | 실행마다 쓰는 보고서(JSON, Markdown). git 에 올리지 않는다 |

## 실행

```bash
cd qa
uv sync                                   # 처음 한 번
uv run run_qa.py list                     # 케이스 목록과 정답
uv run run_qa.py check                    # 정답표 검증 (모델 비용 없음)
uv run run_qa.py eval                     # SkillOps 평가 (진단에 LLM 을 쓰는 케이스는 비용이 든다)
uv run run_qa.py eval --only C-01 U-02 --repeat 3
uv run pytest && uv run ruff check .      # 실행기 자체 검사 (서버 없이 돈다)
```

서버 주소는 인자나 환경 변수로 바꾼다. 기본값은 로컬 포트다.

| 인자 | 환경 변수 | 기본값 | 쓰는 명령 |
|---|---|---|---|
| `--order-agent` | `QA_ORDER_AGENT_URL` | `http://localhost:8081` | 모두 |
| `--mcp` | `QA_MCP_URL` | `http://localhost:8080/mcp` | check |
| `--agent` | `QA_AGENT_URL` | `http://localhost:8000` | eval |
| `--service` | `QA_SERVICE_URL` | `http://localhost:8080` | eval |
| `--service-user`, `--service-password` | `QA_SERVICE_USER`, `QA_SERVICE_PASSWORD` | `operator`, `operator1234` | eval |

서비스 토큰(`SKILLOPS_SERVICE_TOKEN`)을 쓰는 환경이면 같은 값을 두고 실행한다. 실행기가 service 의 `/mcp`(check)와 Agent 의
`POST /diagnose`(eval)에 `X-Service-Token` 헤더로 보낸다.

### 서버 준비

- 주문 Agent 는 규칙 모드(`order-agent.model-mode=fixed`, 기본값)이고 시연 기능(`ORDER_AGENT_DEMO_ENABLED`, 기본 켜짐)이
  켜져 있어야 한다. 실행기가 fixture 고정, 후보 snapshot 발행, 모의 실행을 쓰기 때문이다.
- service 는 harness 실행기로 띄운다. 기본값인 contract-only 실행기는 주문 Agent 를 부르지 않는 가짜 실행이고 기준 주문도 g-01
  하나뿐이라 check 가 시작할 때 멈춘다.

  ```bash
  cd service
  SKILLOPS_FLOW_RUNNER=harness SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent ./gradlew bootRun
  ```

- eval 의 SkillOps Agent 는 stub 이 아니라 실제 MCP 를 써야 한다(`SKILLOPS_USE_STUB_MCP=false`, 루트 `.env.example` 의 기본값).
  그 MCP 주소가 harness 실행기로 띄운 service 를 가리켜야 한다.
- B 묶음은 `feat/skill-behavior-rules` 의 기능이 모두 들어간 서버에서만 돈다. 주문 Agent 의 `rules` 실행과 저장 요청 `intent`,
  service 의 `run_scenarios` `intent_expectations`(결과의 `intent_scoped`)와 조건 문법 v2, Agent 의 `DiagnoseRequest.intent`·
  `rag_exclude` 가 필요하다. 하나라도 없으면 B 묶음은 ERROR 나 MISMATCH 로 나오고, 나머지 묶음은 지금처럼 돈다.

## check 와 eval

**check (정답표 검증)** 는 케이스에 적힌 사실이 실제와 맞는지 본다. 결과가 어긋나면 SkillOps 가 아니라 케이스나 정답표를
고쳐야 한다. 케이스마다 다음을 차례로 확인한다.

1. 수정본을 후보 snapshot 으로 발행한다. 저장 이벤트를 남기지 않으므로 자동 진단은 시작되지 않는다.
2. 표준 주문 g-01~g-06 을 의도 없이 돌려 주문 Agent 반응(`order_agent`)을 확인한다. 결과, 오류 이름, 지목 Skill, 빠진 값을 본다.
3. SkillOps 사전 검증의 갈림길(검증 통과, 검증 실패, 진단 진행)이 정답 결론과 맞는지 본다. 담당자 의도가 있는 케이스(B 묶음)는
   표준 주문을 의도 조건(`intent_expectations`)을 붙여 한 번 더 돌린다. 오류 없이 의도 범위 주문만 떨어지면 의도 미실현(진단 진행)이고,
   떨어진 주문이 `intent.repro` 와 같은지 본다.
4. 원인을 확인한다. 원인으로 적은 수정을 모두 되돌리면 재현 주문이 통과해야 한다. 수정이 여럿이면 하나씩만
   되돌려 보고 원인 표시(`cause`)와 맞는지 본다. 의도 미실현은 기준본도 의도를 실행하지 않으므로 되돌려 보지 않는다. 원인은 담당자가
   바꾼 Skill 이고 종류는 `incomplete_edit` 다.
5. 정답표의 안(`fixes`)을 하나씩 연습 공간에 넣고 표준 주문 6개와 재현 주문을 돌린다. 기존 케이스는 의도 조건(`intent.checks`)을 붙인
   재현 주문을 한 번 더 돌리고, B 묶음은 모든 주문을 의도 조건으로 채점한다(재현 주문은 실패 실행의 `f-<id>` 이거나, 의도 미실현이면
   떨어진 의도 범위 기준 주문이다). 시험 통과 여부, 되돌리기인지, 검사를 약하게 만들었는지(B 묶음), 적어 둔 실패 이유가 맞는지 본다.

**eval (SkillOps 평가)** 는 SkillOps 진단을 실제로 돌려 정답표로 채점하고 지표를 낸다.

- 기본 시작 방식(`--trigger direct`)은 후보 snapshot 을 발행하고 Agent 의 `POST /diagnose` 로 저장 진단을 요청한다.
  지금 띄운 다른 진단과 섞이지 않게 Agent 를 따로 띄워 평가할 수 있다. 요청에는 칸을 정의한 케이스에만 두 칸을 더한다.
  `intent`(B 묶음의 `{text, expectations}`)와 `rag_exclude`(RAG 사례집에 대응 사례가 있는 케이스, 아래 RAG 정답 누수)다.
- `--trigger save` 는 운영 화면의 저장과 같은 길(`POST /api/snapshots/edits`)로 시작한다. 주문 Agent 가 설정된
  service 로 이벤트를 보내므로 `--agent` 가 그 service 뒤의 Agent 여야 한다. 배포 판정 같은 최종 평가에 쓴다. 저장 요청에는
  `intent` 만 실을 수 있고 `rag_exclude` 는 보낼 길이 없어, 대응 사례가 있는 케이스를 돌리면 실행기가 경고한다. 그때는 Agent 를
  `SKILLOPS_RAG_EXCLUDE` 로 띄운다.
- 결론은 `GET /runs/{thread_id}/events` 의 `diagnosis_status` 로, 제안서는 service 의 `GET /api/proposals/{id}` 로 읽는다.
- 합격으로 나온 후보는 patch 를 수정본에 직접 적용해 결과 파일을 만든다. 그 결과로 되돌리기인지, 검사를 약하게 만든 안인지(B 묶음),
  정답표가 불합격으로 정한 안인지, 정답표가 정한 위치 밖에 규칙을 뒀는지를 실행기가 스스로 판정한다. SkillOps 가 붙인 valid 만 믿지
  않는다.

판정은 PASS, FAIL, CONFLICT 셋이다.

- FAIL: 결론·제안서 상태·원인·원인 종류가 정답과 다르거나, 되돌리기나 검사 약화나 정답표가 불합격으로 정한 안을 합격안으로 냈거나,
  고칠 수 있는 케이스(`fixable: true`)에서 받아들일 합격안을 하나도 내지 못했다(정답이 `no_valid_fix` 도 허용하는 U-01 은 빼고).
- CONFLICT: 정답표에 없는 새 합격안이 나와 정답표와 SkillOps 가운데 누가 틀렸는지 다시 확인해야 한다(R5). 고칠 수 없다고 정한
  케이스에 새 합격안이 나왔거나, B 묶음에서 정답표가 규칙을 둘 수 있다고 본 Skill(`answer.rule_skills`) 밖에 규칙을 둔 합격안이 나온
  경우다.

## 케이스 파일 형식

`cases/C-01.yaml` 을 보면 기본 모양을, `cases/B-01.yaml` 을 보면 담당자 의도가 있는 모양을 알 수 있다.

| 칸 | 뜻 |
|---|---|
| `id`, `group`, `title` | 케이스 번호, 묶음, 한 줄 설명 |
| `set` | `dev`(공개) 또는 `hidden`(QA 만 보관) |
| `run` | `auto`(자동), `openai`(주문 Agent 를 AI 모드로 띄웠을 때만, `--include-openai`), `manual`(사람이 `steps` 를 따라 확인) |
| `change` | 담당자가 한 수정. 파일, 바꾸기 전 글(`from`), 바꾼 뒤 글(`to`). `from` 은 그 파일(게시본 `order-agent/skills`)에 정확히 한 번 있어야 한다. 원인이 아닌 수정은 `cause: false` |
| `intent` | 담당자 의도. 한 문장(`text`)과 확인 조건(`checks`, 문법은 `<값 이름> ==|!=|in <값>`). 조건으로 쓸 수 없는 의도는 조건을 비운다. `checks` 는 SkillOps 에 보내지 않고 check 가 재현 주문에 붙인다 |
| `intent.expectations` | B 묶음만. 담당자가 저장 때 보내는 의도 조건 `[{scope, checks}]`(계획 2.3). 이 칸이 있는 케이스만 `{text, expectations}` 를 저장 요청과 `POST /diagnose` 로 보낸다. `scope` 는 시험 주문의 처음 값(`order.payment_method`, `order.customer`, `order.shipping.country`, `order.shipping.express`, `order.total`)으로 평가하고, `checks` 는 실행 문맥과 예약 키(`order.final_status`, `order.shipments`)로 본다. 조건 문법 v2(`>`, `>=`, `<`, `<=` 추가) |
| `intent.repro` | B 묶음만. 수정본에 의도를 붙여 돌리면 떨어지는 의도 범위 기준 주문. 보내지 않고 check 가 확인한다 |
| `order_agent` | 표준 주문 g-01~g-06 마다 주문 Agent 기대 반응(의도 없이 돌린 반응). `result` 는 `pass`, `error`, `check_failed`(오류 없이 확인 조건에서 떨어짐) |
| `answer` | 정답. `status`(허용 결론), `proposal`(허용 제안서 상태), `root_cause`(원인 Skill, 하나면 맞음) 또는 `root_cause_all`(모두 짚어야 맞음), `fixable`, `revert_allowed`, `save_rejected` |
| `answer.root_cause_kind` | 제안서 `root_cause.kind` 의 정답. `change`(되돌리면 실패가 사라지는 변경) 또는 `incomplete_edit`(오류 없이 의도만 실행되지 않음). 적은 케이스만 채점한다 |
| `answer.rule_skills` | B 묶음만. 실행 규칙(`rules`)을 둬서 의도를 살릴 수 있는 Skill. 합격안이 이 밖에 규칙을 새로 두면 CONFLICT 다 |
| `fixes` | 정답표가 판정을 미리 정한 안. `tests_pass`(시험 통과), `valid`(정답표 판정), `failure`(떨어질 때 이유에 있어야 할 글), `policy`(시험은 통과해도 불합격인 이유) |
| `evidence` | 정답의 근거. 주장, 근거 종류, 근거 위치 |
| `current` | 지금 구현에서 확인한 결과와 관련 결함 번호 |
| `trigger`, `diagnosis_only` | 정책 확인 요청(diagnosis_only) 케이스에서 돌릴 표준 주문과 조건 |

`intent.expectations` 와 `answer.revert_allowed: true` 는 함께 쓸 수 없다(읽을 때 오류). 의도를 보내면 SkillOps 와 service 가 되돌리기를
코드로 불합격시키기 때문이다. 그래서 오타·중복·형식 오류처럼 되돌리기가 정답인 케이스(X-01, X-02, X-05)와 입력 삭제가 정답인 케이스
(C-02 등)는 지금처럼 의도를 보내지 않는다.

## 기준 주문 g-01~g-06

SkillOps 와 check 가 돌리는 표준 주문은 `scenarios/order-agent/golden` 의 고정 목록 여섯 개다(계획 2.8). 기대값을 코드로 유도한 근거는
`scenarios/README.md` 에 있다.

| id | 주문 | 기대 | 쓰는 B 케이스 |
|---|---|---|---|
| g-01 | 랜턴 1 · card · KR | SHIPPED | B-01 범위 안 |
| g-02 | 머그컵 1 · card · KR(재고 0) | REJECTED(재고) | B-01 범위 안(결제 전에 이미 거절) |
| g-03 | 랜턴 1 · card · KR · c_declined | HOLD(결제 거절) | B-04·B-05 범위 안 |
| g-04 | 랜턴 1 · prepaid · KR | SHIPPED | B-01 범위 밖 |
| g-05 | 랜턴 1 · card · US | SHIPPED | B-02 범위 안 |
| g-06 | 랜턴 4 · card · KR(120000원) | SHIPPED | B-03 범위 안 |

g-04~g-06 은 정상 흐름이 g-01 과 같은 길을 간다. 그래서 기존 케이스의 `order_agent` 표에서 g-04~g-06 칸은 g-01 과 같다. 실행 전
정적 검사에서 멈추는 수정은 여섯 주문 모두 같은 오류이고, 정상 배송 길에서만 걸리는 수정(D-03, V-01, V-02)은 g-01·g-04·g-05·g-06 이
같은 반응이다. 이 칸은 코드로 유도했고 아직 check 로 관찰하지 않았다(근거 종류 "코드 근거").

## B 묶음 (행동 변경)

담당자가 SKILL.md 명세 안에서 행동을 추가·수정한 경우다. 규칙 모드는 본문을 읽지 않으므로 본문에 정책을 적기만 하면 실행되지 않는다.
SkillOps 는 담당자 의도가 실제로 실행되게 만드는 안, 곧 단계 skill frontmatter 의 실행 규칙 `rules` 를 제안해야 한다. 되돌리기는 답이
아니다(계획 `docs/plans/2026-10-26-behavior-edit-root-fix.md` 0절, 2.9).

| 케이스 | 담당자 수정 | 의도(scope → checks) | 정답 안 | 불합격 안 |
|---|---|---|---|---|
| B-01 | 결제 본문에 "선입금 주문만 결제" (T-02 와 같은 수정) | card → `final_status in ['HOLD','REJECTED']`, `payment.status == null` / prepaid → `final_status == 'SHIPPED'` | payment 에 `when: order.payment_method != 'prepaid'`, hold | 되돌리기 |
| B-02 | 배송 예약 본문에 "해외 배송은 받지 않음" | `order.shipping.country != 'KR'` → `final_status == 'REJECTED'`, `payment.status == null` | order-intake 에 `when: order.shipping.country != 'KR'`, reject | shipping-reserve 에 규칙(결제가 PAID 로 남음), 되돌리기 |
| B-03 | 결제 본문에 "10만 원 이상은 보류" | `order.total >= 100000` → `final_status == 'HOLD'`, `payment.status == null` | payment 에 `when: order.total >= 100000`, hold | 되돌리기 |
| B-04 | 결제 본문에 "결제 거절 이력 고객(c_declined)은 결제 시도 전 보류" | `order.customer == 'c_declined'` → `final_status == 'HOLD'`, `payment.status == null` | payment inputs 에 `order.customer` + 규칙, 또는 order-intake 에 규칙(둘 다 정답, R2) | 되돌리기 |
| B-05 | 결제에 고객 조건 규칙을 넣었지만 `order.customer` 를 inputs 에 안 넣음(`RULE_KEY_NOT_INPUT`) | B-04 와 같음 | payment inputs 에 `order.customer` 추가 | 규칙 지우기(되돌리기), 규칙을 order-intake 로 옮기기(시험은 통과하지만 되돌리기이자 약화) |

- 정답 결론은 모두 EVALUATED·proposed 다. B-01~B-04 는 오류 없이 의도 범위 주문만 떨어져 원인 종류가 `incomplete_edit` 이고, B-05 는
  실행 전 계약 오류라 `change` 다.
- 규칙 위치(`rule_skills`)는 결제 도구보다 앞 단계다. 규칙 종료는 결제 상태를 건드리지 않으므로 결제 뒤 단계(shipping-reserve, notify)에
  두면 `payment.status` 가 PAID 로 남는다. `order.total` 은 주문 접수가 내놓는 값이라 B-03 은 order-intake 에 둘 수 없다.
- SkillOps 는 LLM 없이도 결정적 `encode_rule` 후보를 최대 두 곳에 낸다(계획 2.10 V3-6): 담당자가 바꾼 Skill 과, scope 키를 조건으로
  쓸 수 있는 가장 이른 단계(B-01·B-02·B-04·B-05 는 order-intake, B-03 은 payment). `rule_skills` 는 두 위치를 모두 받아들이고, 받지
  않는 위치(B-02 의 shipping-reserve)는 불합격 안으로 까닭을 적는다. `tests/test_cases.py` 가 확인한다.
- eval 은 B 묶음 합격안에 R1 을 예외 없이 적용하고, 검사 약화를 코드로 판정한다(`qa_logic.weakened`). 약화는 계획 2.10 V3-7 의 정의
  하나다: 수정본 `rules` 의 `when` 이 결과에서 사라짐(규칙을 지우거나 `when` 을 바꿈), 또는 `rules` 의 `when`·의도 `scope` 가 쓰는
  값을 덮던 input 을 지움(다른 input 이 여전히 덮어도). `then`·`reason` 만 바꾼 규칙은 약화가 아니다. service 재판정과 SkillOps 의
  `weakens_contract` 와 같은 기준이다.
- eval 의 direct 실행은 후보 snapshot metadata 에 저장 경로와 같은 `intent` 를 담는다(`qa_logic.snapshot_metadata`). service 재판정이
  의도를 그 metadata 에서 읽기 때문이다(계획 2.10 V3-1).
- B 묶음은 지금 구현 중이라 `current` 가 "구현 중(2026-10-26), check 미실행" 이다. 기능이 모두 들어간 서버에서 check 를 처음 돌린 뒤
  `current` 와 근거 종류(코드 근거 → 관찰)를 고친다.

## 판정 규칙

| 규칙 | 내용 |
|---|---|
| R1 | 되돌리기는 개선안이 아니다. 담당자가 바꾼 파일을 기준본으로 돌려놓은 안은 불합격이다. 다만 지킬 의도가 없는 오타, 중복, 형식 오류와 위험한 지시를 지우는 경우는 케이스에 `revert_allowed: true` 를 적어 허용한다. 담당자 의도(`intent.expectations`)가 있는 케이스에는 예외가 없다 |
| R2 | 개선안은 특정 diff 가 아니라 조건으로 판정한다. 표준 주문 g-01~g-06, 재현 주문, 의도 조건을 모두 통과하고 끊긴 연결이 없으면 시험 통과다. 그래서 정답표에 없는 좋은 안도 인정된다 |
| R3 | 입력을 지워서 시험을 통과시키는 안은 검사를 약하게 만드므로 불합격이다. 담당자 의도가 있는 케이스는 `rules` 를 지우거나 규칙·의도 조건이 쓰는 input 을 지우는 안도 같고, 실행기가 코드로 판정한다 |
| R4 | 정답을 고칠 때는 주문 Agent 나 시험 환경의 사실만 근거로 쓴다. SkillOps 가 낸 결론에 맞춰 고치지 않는다 |
| R5 | 정답표와 SkillOps 가 다르면 다수결이 아니라 제3의 확인으로 정한다. 숨긴 주문으로 다시 돌리고 정답표의 증명을 다시 본다. 증명이 틀렸으면 정답표를, 합격 판정이 틀렸으면 거짓 합격으로 기록한다 |

## 정답이 맞는지 어떻게 아는가

정답표의 칸마다 근거 종류가 다르다. 케이스의 `evidence` 에 종류와 위치를 적는다.

| 근거 종류 | 뜻 | 다른 사람이 다시 확인하는 법 |
|---|---|---|
| 관찰 | 실제 모의 실행에서 본 사실 | `check` 를 다시 돌린다. 규칙 모드는 매번 같은 결과가 나온다 |
| 구성 | 우리가 직접 넣은 수정이라 아는 사실 | 케이스의 `change` |
| 되돌려 보기 | 그 수정만 되돌리면 실패가 사라짐 | `check` 의 원인 확인 |
| 존재 증명 | 그 안을 실제로 넣었더니 시험을 통과함 | `check` 의 정답표 안 실행 |
| 불가능 증명 | 코드에 고정된 규칙에서 논리로 유도함. 그럴듯한 안을 시도해 예측한 오류로 떨어지는 것까지 확인한다 | 근거 위치의 코드 |
| 코드 근거 | 결과를 설명하는 코드 규칙 | 근거 위치의 코드 |
| 정의 | 시나리오를 만드는 우리가 담당자 역할로 정한 입력(의도) | 케이스 파일 |
| 합의 | 팀이 정한 판정 규칙(R1~R5) | 이 문서와 그 버전 |

정답은 "지금의 주문 Agent"와 "기존 Skill 파일만 고칠 수 있다"는 조건에서 맞다. 도구 코드를 바꿀 수 있다면 U·D 묶음은
고칠 수 있게 되므로, 주문 Agent 가 바뀌면 `check` 를 다시 돌려 어긋난 케이스를 고친다.

## 지표

eval 보고서는 QA 문서(`SKILLOPS_QA_평가기준_테스트시나리오`) 3장의 지표를 분자와 분모와 함께 낸다.

| 지표 | 계산 |
|---|---|
| 케이스 정답률 | PASS 수 ÷ 평가 수 |
| 회귀 탐지율 | 문제를 알린 수(VALIDATION_FAILED, EVALUATED, INTENT_VIOLATION) ÷ 정답이 VALIDATED 가 아닌 평가 수 |
| 오탐률 | VALIDATED 가 아닌 수 ÷ 정답이 VALIDATED 인 평가 수 |
| 원인 특정 정확도 | 원인 Skill 을 맞힌 수 ÷ 정답에 원인이 있는 평가 수. 진단까지 가지 않으면 실패로 센다 |
| 유효 개선안 제시율 | 받아들일 수 있는 합격안을 하나 이상 낸 수 ÷ 고칠 수 있는 평가 수 |
| 의도 실현 안 제시율 | 받아들일 수 있는 합격안을 하나 이상 낸 수 ÷ 담당자 의도를 보낸 고칠 수 있는 평가 수(B 묶음) |
| '유효한 안 없음' 정답률 | 진단을 끝까지 하고 no_valid_fix 로 끝낸 수 ÷ 정답 결론이 EVALUATED 하나인 고칠 수 없는 평가 수 |
| 되돌리기 제안률 | 되돌리기를 합격안으로 낸 수 ÷ 합격안 전체 |
| 완주율 | ERROR 와 TIMEOUT 이 아닌 수 ÷ 평가 수 |
| 소요 시간 | 시작부터 결론까지 걸린 시간의 중앙값과 95% 값 |

거짓 합격률(숨긴 주문에서 깨지는 합격안의 비율)은 숨긴 주문이 있어야 재므로 아직 계산하지 않는다.

### 지표 해석

- 받아들일 수 있는 합격안은 SkillOps(그리고 service 재판정)가 합격으로 낸 후보 가운데 되돌리기(R1), 검사 약화(R3, B 묶음),
  정답표가 불합격으로 정한 안, 재검증이 필요한 안(CONFLICT)을 뺀 것이다. 그래서 유효 개선안 제시율은 SkillOps 가 스스로 센 합격 후보
  수보다 낮게 나올 수 있다.
- 원인이 둘인 G-01(`root_cause_all`)은 제안서가 원인을 하나만 담으므로(D3), 짚은 원인이 둘 가운데 하나이고 두 문제를 함께 고친
  합격안(시험 통과가 곧 두 원인이 모두 풀렸다는 뜻)을 냈을 때 원인을 맞힌 것으로 센다. 예전 채점은 원인 하나로는 늘 실패라 G-01 이
  어떤 답으로도 통과할 수 없었다.
- 고칠 수 있는 케이스에서 합격안이 없으면 지금은 FAIL 이다. 예전에는 제안서가 proposed 인데 service 가 다시 계산한 합격 후보가 없거나
  제안서를 읽지 못하면 이유 없이 PASS 가 될 수 있었다.
- 의도 실현 안 제시율이 이번 개선(계획 0절 완료 판정 1~3)의 직접 지표다. B 묶음은 유효 개선안 제시율에도 함께 들어가므로, 두 값을
  나란히 보면 행동 변경과 계약 깨짐 가운데 어디서 안을 못 냈는지 알 수 있다.
- 되돌리기 제안률은 D1 이 고쳐지면 B 묶음에서 0 이어야 한다(의도가 있으면 코드가 되돌리기를 불합격시킨다). 의도를 보내지 않는 기존
  케이스(C·U·D 묶음)에서는 SkillOps 가 만든 의도 조건에 따라 달라진다.
- 지표는 RAG 정답 누수를 막은 상태(아래)에서만 비교한다. 대응 사례를 빼지 않고 낸 값은 원인 특정과 개선안 제시를 부풀린다.

## RAG 정답 누수

SkillOps 는 진단 프롬프트에 RAG 참고 자료(`skillops-agent/rag/rag_data.json`)를 붙인다. 사례(`case-*`) 가운데 평가 케이스와 같은 장애를
다룬 것이 있으면 정답을 미리 보여 주는 셈이라 평가가 부풀려진다. 두 가지로 막는다.

| 경로 | 막는 법 |
|---|---|
| 사람이 쓴 사례(`skillops-agent/rag/cases.json`) | 사례마다 `qa_cases` 칸에 정답이 겹치는 QA 케이스를 적는다. eval 이 그 케이스를 진단할 때 사례 id 를 `POST /diagnose` 의 `rag_exclude` 로 보내 빼게 한다. 진단 기록의 `rag_references` 가 보고서 JSON 에 남으므로 실제로 빠졌는지 확인할 수 있다 |
| 제안서에서 만든 사례(`rag/proposal_cases.json`) | `skillops-agent/scripts/build_rag_data.py` 가 후보 snapshot metadata 에 `qa_case` 가 있는 제안서(QA 실행기가 발행한 수정본)는 사례로 쌓지 않는다. snapshot 을 읽지 못한 제안서도 뺀다 |

지금 연결은 다음과 같다. `case-S2` → C-01, U-01, G-01, G-02(재고 outputs 에서 inventory.reserved 를 뺀 장애), `case-S3` → D-01(배송을
결제보다 앞으로), `case-S4` → D-03(알림 단계 삭제), `case-S6` → U-01, U-02, U-03(도구가 만들지 않는 값을 선언한 장애. U 묶음 전부의
정답 논리와 겹친다). 비슷하지만 정답이 다른 케이스(예: S3 와 D-02)는 실제 운영처럼 검색되게 두었다.

남은 위험이 하나 있다. `--trigger save` 로 만든 제안서는 저장 경로가 snapshot metadata 를 받지 않아 `qa_case` 표시가 없다. 운영자가 그
제안서를 승인·반려해야 사례가 되므로, save 평가로 생긴 제안서는 결정하지 않고 둔다.

## 알려진 결함

정답은 그대로 두고 구현이 따라와야 하는 것이다. 2026-10-23 에 SkillOps 파이프라인의 규칙 부분을 그대로 돌려 확인했다.
LLM 이 정하는 부분은 정답표의 이상적인 답으로 채웠으므로, AI 가 완벽하게 답해도 생기는 문제다.

| 번호 | 상태 | 결함 | 영향 케이스 | 위치 |
|---|---|---|---|---|
| D1 | 수정 중 — 브랜치 feat/skill-behavior-rules | 되돌리기를 코드가 막지 않는다. 의도 조건은 원인을 되돌린 실행에 나온 값 이름만 쓸 수 있어 새 값 이름을 쓴 의도가 버려진다. 값을 지우기만 한 변경은 프롬프트가 조건을 비우게 하고, 순서 의도는 조건 문법으로 쓸 수 없다. 조건이 비면 되돌리기도 합격으로 판정된다. 수정: 담당자 의도(`intent`)를 저장부터 진단까지 전하고, 값 이름 후보를 수정본의 모든 선언 값으로 넓히고, 의도가 있으면 되돌리기·약화·빈 의도를 코드가 불합격시킨다(계획 2.3, 2.6) | C-01, C-04, D-01, D-02, U-01~U-03, G-02, B 묶음 (조건이 빠지면 C-02, C-03) | `skillops-agent/skillops_agent/nodes.py` 의 `_known_values`, `skillops-agent/skillops_agent/contracts.py` 의 `check_problem`, `skillops-agent/prompts/extract_intent.md` |
| D2 | 수정 중 — 브랜치 feat/skill-behavior-rules | 지목된 Skill 이 없는 실패(INCOMPLETE_FLOW, UNKNOWN_SKILL, INVALID_FLOW)는 재현 확인에서 ERROR 로 멈춘다. 실패 이유 글에 오류 이름이 남지 않기 때문이다. 수정: service 가 skill 없는 오류에도 오류 이름을 실패 이유에 남긴다(계획 3절 W2 4) | D-03, D-04, X-02 | `service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java` 의 `attachError`, `skillops-agent/skillops_agent/nodes.py` 의 `_reproduce_failure` |
| D3 | 열림 | 원인 실험이 덩어리를 하나씩만 되돌려서 원인 두 개가 겹치면 찾지 못한다. 제안서 형식도 원인을 하나만 담는다 | G-01 | `skillops-agent/skillops_agent/nodes.py` 의 `_ablate_change`, `docs/contracts/proposal.schema.json` |
| D4 | 열림 | 형식이 깨진 Skill 을 저장하면 원인 설명 없이 증거 수집에서 ERROR 로 끝난다 | X-05 | `skillops-agent/skillops_agent/contracts.py` 의 `check_skill_listing` |

## 알려진 한계

설계상 지금 범위 밖인 것이다. 결함으로 세지 않지만 지표를 해석할 때 먼저 본다.

| 번호 | 한계 | 영향 |
|---|---|---|
| L1 | 규칙 모드는 Skill 본문과 설명을 실행에 쓰지 않는다. 실행되는 업무 규칙은 frontmatter 의 `rules` 뿐이다 | 본문·설명 수정은 의도를 보내지 않으면 규칙 모드에서 늘 VALIDATED 다. 본문 정책은 정책 확인 요청(T-02b)이나, 의도와 함께 저장한 B 묶음으로 잡는다 |
| L2 | (수정 중 — 계획 2.5) 오류 없이 결과만 다른 실패는 진단하지 않고 검증 실패에서 멈춘다. 고친 뒤에는 그 기준 주문을 재현 주문으로 원인 실험을 잇고, 확정하지 못할 때만 검증 실패로 멈춘다 | V 묶음은 탐지에는 세지만 원인 특정에서는 실패로 센다. 정답 결론이 VALIDATION_FAILED 와 EVALUATED 를 함께 허용하므로 고친 뒤에도 정답은 그대로다 |
| L3 | 새 Skill 파일을 만드는 개선안은 지원하지 않는다 | D-01, D-04 처럼 새 단계가 필요한 의도는 살릴 수 없다 |
| L4 | 원인 실험 단위는 문맥 3줄 diff 덩어리라, 한 파일의 frontmatter 수정은 사실상 한 덩어리다 | 같은 파일 frontmatter 안의 두 수정은 원인을 나눌 수 없다. 그래서 G-02 는 다른 파일의 수정을 함께 저장한다 |
| L5 | 승인해도 운영 Skill 은 바뀌지 않는다 | S-06 |
| L6 | AI 모드(openai)에서 본문 자연어를 규칙처럼 따르게 하는 것은 확률적이라 판정하지 않는다 | B 묶음은 규칙 모드에서만 채점한다 |

## AI 담당과 지킬 약속

실행기가 기대는 것은 아래 셋뿐이다. 셋 중 하나를 바꾸는 것은 튜닝이 아니라 계약 변경이므로 문서를 고치고 QA 에 알린다.

1. 입력: `POST /diagnose` 요청 모양(`skillops-agent/skillops_agent/main.py` 의 `DiagnoseRequest`, 선택 칸 `intent`·`rag_exclude` 포함)과
   저장 이벤트 경로(저장 요청의 선택 칸 `intent`).
2. 상태: `diagnosis_status` 값 목록(`skillops-agent/skillops_agent/state.py` 의 `DiagnosisStatus`)과, 진단 기록에서 제안서 번호를
   찾을 수 있는 `proposal.id`. 의도 미실현은 새 상태가 아니라 EVALUATED 로 끝난다.
3. 출력: 제안서 형식(`docs/contracts/proposal.schema.json`, `root_cause.kind`·`repro_scenario_ids` 포함)과 service 의
   `GET /api/proposals/{id}` 응답(`valid_candidate_nos` 는 service 가 장부로 다시 계산한 값).

## 숨긴 셋

숨긴 셋은 같은 형식의 케이스를 QA 가 저장소 밖에 보관하고 `set: hidden` 으로 표시한다. 팀원 모두 저장소를 볼 수 있으므로
저장소 안에 두면 숨긴 셋이 되지 않는다. 최종 판정 때만 `--cases <숨긴 셋 폴더>` 로 돌린다. 공개 셋의 변형 규칙은 QA 문서
6-10 을 따른다.

## QA 문서 v0.1 대비 바뀐 정답

check 로 사실을 돌려 보며 고친 것이다. 모두 주문 Agent 나 시험 환경의 사실을 근거로 고쳤다(R4).

| 케이스 | v0.1 | 지금 | 근거 |
|---|---|---|---|
| N-07 | "변경 없음"으로 끝나거나 저장 단계에서 안내 | 저장이 NO_SKILL_CHANGES 로 거부되고 진단이 생기지 않음 | 주문 Agent 저장 규칙 |
| C-03 | 고칠 수 있음(배송·알림을 함께 고침) | 고칠 수 없음. 그 안은 g-01 의 `payment.status == 'PAID'` 에서 떨어짐 | 표준 주문 기대값과 의도의 충돌 |
| U-01 | 유효한 안 없음, 단 도구 이름 안도 인정 | 도구 이름으로 바로잡은 안이 정답, 유효한 안 없음도 틀린 답은 아님 | 의도를 이름이 아니라 뜻으로 정의 |
| G-01 | 지금은 원인 하나만 담김 | 목표 정답은 그대로 두고, 지금 결과를 원인 미확정으로 고침. 원인 채점은 위 지표 해석의 규칙으로 고침(2026-10-26) | 원인 실험 단위(D3), 제안서 형식 |
| G-02 | 같은 파일의 설명과 outputs 를 함께 수정 | 다른 파일(결제 본문)의 수정과 함께 저장 | 원인 실험 단위(L4) |
| 모든 자동 케이스 | 표준 주문 g-01~g-03 | g-01~g-06. g-04~g-06 칸은 코드로 유도 | 계획 2.8, `scenarios/README.md` |
| 새로 추가 | | X-05(형식 오류), T-02b(정책 확인 요청), V-01·V-02(검증 실패), S-07(불합격 후보 승인), B-01~B-05(행동 변경, 2026-10-26) | |
