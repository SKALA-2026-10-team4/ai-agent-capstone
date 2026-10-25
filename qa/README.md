# qa

SkillOps 평가셋과 실행기다. 담당자가 Skill 을 고쳤을 때 SkillOps 가 문제를 찾고, 원인을 짚고, 쓸 수 있는 개선안을
내는지를 정답표로 잰다. SkillOps Agent 의 내부(노드, 상태 칸, 프롬프트)는 보지 않고 바깥 API 만 쓰므로, AI 담당이
진단 흐름을 튜닝해도 실행기와 케이스는 그대로 쓸 수 있다.

| 파일 | 내용 |
|---|---|
| `cases/*.yaml` | 케이스 하나에 파일 하나. 수정, 의도, 주문 Agent 기대 반응, 정답, 정답표가 판정을 정한 안, 근거, 지금 구현 결과 |
| `run_qa.py` | 실행기. `list`, `check`, `eval` 세 명령 |
| `qa_logic.py` | 판정 규칙과 지표 계산(서버를 부르지 않는 순수 함수) |
| `tests/` | 판정 규칙 단위 테스트 |
| `results/` | 실행마다 쓰는 보고서(JSON, Markdown). git 에 올리지 않는다 |

## 실행

```bash
cd qa
uv sync                                   # 처음 한 번
uv run run_qa.py list                     # 케이스 목록과 정답
uv run run_qa.py check                    # 정답표 검증 (모델 비용 없음)
uv run run_qa.py eval                     # SkillOps 평가 (진단에 LLM 을 쓰는 케이스는 비용이 든다)
uv run run_qa.py eval --only C-01 U-02 --repeat 3
uv run pytest && uv run ruff check .      # 실행기 자체 검사
```

서버 주소는 인자나 환경 변수로 바꾼다. 기본값은 로컬 포트다.

| 인자 | 환경 변수 | 기본값 | 쓰는 명령 |
|---|---|---|---|
| `--order-agent` | `QA_ORDER_AGENT_URL` | `http://localhost:8081` | 모두 |
| `--mcp` | `QA_MCP_URL` | `http://localhost:8080/mcp` | check |
| `--agent` | `QA_AGENT_URL` | `http://localhost:8000` | eval |
| `--service` | `QA_SERVICE_URL` | `http://localhost:8080` | eval |
| `--service-user`, `--service-password` | `QA_SERVICE_USER`, `QA_SERVICE_PASSWORD` | `operator`, `operator1234` | eval |

Agent 에 서비스 토큰이 걸려 있으면 `SKILLOPS_SERVICE_TOKEN` 을 같은 값으로 두고 실행한다.

### 서버 준비

- 주문 Agent 는 규칙 모드(`order-agent.model-mode=fixed`, 기본값)이고 시연 기능(`ORDER_AGENT_DEMO_ENABLED`, 기본 켜짐)이
  켜져 있어야 한다. 실행기가 fixture 고정, 후보 snapshot 발행, 모의 실행을 쓰기 때문이다.
- service 는 harness 실행기로 띄운다. 기본값인 contract-only 실행기는 주문 Agent 를 부르지 않는 가짜 실행이라 check 가
  시작할 때 멈춘다.

  ```bash
  cd service
  SKILLOPS_FLOW_RUNNER=harness SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent ./gradlew bootRun
  ```

- eval 의 SkillOps Agent 는 stub 이 아니라 실제 MCP 를 써야 한다(`SKILLOPS_USE_STUB_MCP=false`). 그 MCP 주소가 harness
  실행기로 띄운 service 를 가리켜야 한다.

## check 와 eval

**check (정답표 검증)** 는 케이스에 적힌 사실이 실제와 맞는지 본다. 결과가 어긋나면 SkillOps 가 아니라 케이스나 정답표를
고쳐야 한다. 케이스마다 다음을 차례로 확인한다.

1. 수정본을 후보 snapshot 으로 발행한다. 저장 이벤트를 남기지 않으므로 자동 진단은 시작되지 않는다.
2. 표준 주문 g-01~g-03 을 돌려 주문 Agent 반응(`order_agent`)을 확인한다. 결과, 오류 이름, 지목 Skill, 빠진 값을 본다.
3. SkillOps 사전 검증의 갈림길(검증 통과, 검증 실패, 진단 진행)이 정답 결론과 맞는지 본다.
4. 원인을 확인한다. 원인으로 적은 수정을 모두 되돌리면 재현 주문이 통과해야 한다. 수정이 여럿이면 하나씩만
   되돌려 보고 원인 표시(`cause`)와 맞는지 본다.
5. 정답표의 안(`fixes`)을 하나씩 연습 공간에 넣고 표준 주문 3개, 재현 주문, 의도 조건을 붙인 재현 주문을 돌린다.
   시험 통과 여부, 되돌리기인지, 적어 둔 실패 이유가 맞는지 본다.

**eval (SkillOps 평가)** 는 SkillOps 진단을 실제로 돌려 정답표로 채점하고 지표를 낸다.

- 기본 시작 방식(`--trigger direct`)은 후보 snapshot 을 발행하고 Agent 의 `POST /diagnose` 로 저장 진단을 요청한다.
  지금 띄운 다른 진단과 섞이지 않게 Agent 를 따로 띄워 평가할 수 있다.
- `--trigger save` 는 운영 화면의 저장과 같은 길(`POST /api/snapshots/edits`)로 시작한다. 주문 Agent 가 설정된
  service 로 이벤트를 보내므로 `--agent` 가 그 service 뒤의 Agent 여야 한다. 배포 판정 같은 최종 평가에 쓴다.
- 결론은 `GET /runs/{thread_id}/events` 의 `diagnosis_status` 로, 제안서는 service 의 `GET /api/proposals/{id}` 로 읽는다.
- 합격으로 나온 후보는 patch 를 수정본에 직접 적용해 결과 파일을 만든다. 그 결과로 되돌리기인지, 정답표가 불합격으로
  정한 안인지를 실행기가 스스로 판정한다. SkillOps 가 붙인 valid 만 믿지 않는다.

판정은 PASS, FAIL, CONFLICT 셋이다. CONFLICT 는 고칠 수 없다고 정한 케이스에 정답표에 없는 새 합격안이 나온 경우다.
정답표와 SkillOps 가운데 누가 틀렸는지 다시 확인해야 한다(R5).

## 케이스 파일 형식

`cases/C-01.yaml` 을 보면 전체 모양을 알 수 있다.

| 칸 | 뜻 |
|---|---|
| `id`, `group`, `title` | 케이스 번호, 묶음, 한 줄 설명 |
| `set` | `dev`(공개) 또는 `hidden`(QA 만 보관) |
| `run` | `auto`(자동), `openai`(주문 Agent 를 AI 모드로 띄웠을 때만, `--include-openai`), `manual`(사람이 `steps` 를 따라 확인) |
| `change` | 담당자가 한 수정. 파일, 바꾸기 전 글(`from`), 바꾼 뒤 글(`to`). `from` 은 그 파일에 정확히 한 번 있어야 한다. 원인이 아닌 수정은 `cause: false` |
| `intent` | 담당자 의도. 한 문장(`text`)과 확인 조건(`checks`, 문법은 `<값 이름> ==|!=|in <값>`). 조건으로 쓸 수 없는 의도는 조건을 비운다 |
| `order_agent` | 표준 주문마다 주문 Agent 기대 반응. `result` 는 `pass`, `error`, `check_failed`(오류 없이 확인 조건에서 떨어짐) |
| `answer` | 정답. `status`(허용 결론), `proposal`(허용 제안서 상태), `root_cause`(원인 Skill, 하나면 맞음) 또는 `root_cause_all`(모두 짚어야 맞음), `fixable`, `revert_allowed`, `save_rejected` |
| `fixes` | 정답표가 판정을 미리 정한 안. `tests_pass`(시험 통과), `valid`(정답표 판정), `failure`(떨어질 때 이유에 있어야 할 글), `policy`(시험은 통과해도 불합격인 이유) |
| `evidence` | 정답의 근거. 주장, 근거 종류, 근거 위치 |
| `current` | 지금 구현에서 확인한 결과와 관련 결함 번호 |
| `trigger`, `diagnosis_only` | 정책 확인 요청(diagnosis_only) 케이스에서 돌릴 표준 주문과 조건 |

## 판정 규칙

| 규칙 | 내용 |
|---|---|
| R1 | 되돌리기는 개선안이 아니다. 담당자가 바꾼 파일을 기준본으로 돌려놓은 안은 불합격이다. 다만 지킬 의도가 없는 오타, 중복, 형식 오류와 위험한 지시를 지우는 경우는 케이스에 `revert_allowed: true` 를 적어 허용한다 |
| R2 | 개선안은 특정 diff 가 아니라 조건으로 판정한다. 표준 주문 3개, 재현 주문, 의도 조건을 모두 통과하고 끊긴 연결이 없으면 시험 통과다. 그래서 정답표에 없는 좋은 안도 인정된다 |
| R3 | 입력을 지워서 시험을 통과시키는 안은 검사를 약하게 만드므로 불합격이다 |
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
| '유효한 안 없음' 정답률 | 진단을 끝까지 하고 no_valid_fix 로 끝낸 수 ÷ 정답 결론이 EVALUATED 하나인 고칠 수 없는 평가 수 |
| 되돌리기 제안률 | 되돌리기를 합격안으로 낸 수 ÷ 합격안 전체 |
| 완주율 | ERROR 와 TIMEOUT 이 아닌 수 ÷ 평가 수 |
| 소요 시간 | 시작부터 결론까지 걸린 시간의 중앙값과 95% 값 |

거짓 합격률(숨긴 주문에서 깨지는 합격안의 비율)은 숨긴 주문이 있어야 재므로 아직 계산하지 않는다.

## 알려진 결함

정답은 그대로 두고 구현이 따라와야 하는 것이다. 2026-10-23 에 SkillOps 파이프라인의 규칙 부분을 그대로 돌려 확인했다.
LLM 이 정하는 부분은 정답표의 이상적인 답으로 채웠으므로, AI 가 완벽하게 답해도 생기는 문제다.

| 번호 | 결함 | 영향 케이스 | 위치 |
|---|---|---|---|
| D1 | 되돌리기를 코드가 막지 않는다. 의도 조건은 원인을 되돌린 실행에 나온 값 이름만 쓸 수 있어 새 값 이름을 쓴 의도가 버려진다. 값을 지우기만 한 변경은 프롬프트가 조건을 비우게 하고, 순서 의도는 조건 문법으로 쓸 수 없다. 조건이 비면 되돌리기도 합격으로 판정된다 | C-01, C-04, D-01, D-02, U-01~U-03, G-02 (조건이 빠지면 C-02, C-03) | `skillops-agent/skillops_agent/nodes.py` 의 `_known_values`, `skillops-agent/skillops_agent/contracts.py` 의 `check_problem`, `skillops-agent/prompts/extract_intent.md` |
| D2 | 지목된 Skill 이 없는 실패(INCOMPLETE_FLOW, UNKNOWN_SKILL, INVALID_FLOW)는 재현 확인에서 ERROR 로 멈춘다. 실패 이유 글에 오류 이름이 남지 않기 때문이다 | D-03, D-04, X-02 | `service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java` 의 `attachError`, `skillops-agent/skillops_agent/nodes.py` 의 `_reproduce_failure` |
| D3 | 원인 실험이 덩어리를 하나씩만 되돌려서 원인 두 개가 겹치면 찾지 못한다. 제안서 형식도 원인을 하나만 담는다 | G-01 | `skillops-agent/skillops_agent/nodes.py` 의 `_ablate_change`, `docs/contracts/proposal.schema.json` |
| D4 | 형식이 깨진 Skill 을 저장하면 원인 설명 없이 증거 수집에서 ERROR 로 끝난다 | X-05 | `skillops-agent/skillops_agent/contracts.py` 의 `check_skill_listing` |

## 알려진 한계

설계상 지금 범위 밖인 것이다. 결함으로 세지 않지만 지표를 해석할 때 먼저 본다.

| 번호 | 한계 | 영향 |
|---|---|---|
| L1 | 규칙 모드는 Skill 본문과 설명을 실행에 쓰지 않는다 | 본문·설명 수정은 규칙 모드에서 늘 VALIDATED 다. 본문 정책은 정책 확인 요청(T-02b)으로 잡는다 |
| L2 | 오류 없이 결과만 다른 실패는 진단하지 않고 검증 실패에서 멈춘다 | V 묶음은 탐지에는 세지만 원인 특정에서는 실패로 센다 |
| L3 | 새 Skill 파일을 만드는 개선안은 지원하지 않는다 | D-01, D-04 처럼 새 단계가 필요한 의도는 살릴 수 없다 |
| L4 | 원인 실험 단위는 문맥 3줄 diff 덩어리라, 한 파일의 frontmatter 수정은 사실상 한 덩어리다 | 같은 파일 frontmatter 안의 두 수정은 원인을 나눌 수 없다. 그래서 G-02 는 다른 파일의 수정을 함께 저장한다 |
| L5 | 승인해도 운영 Skill 은 바뀌지 않는다 | S-06 |

## AI 담당과 지킬 약속

실행기가 기대는 것은 아래 셋뿐이다. 셋 중 하나를 바꾸는 것은 튜닝이 아니라 계약 변경이므로 문서를 고치고 QA 에 알린다.

1. 입력: `POST /diagnose` 요청 모양(`skillops-agent/skillops_agent/main.py` 의 `DiagnoseRequest`)과 저장 이벤트 경로.
2. 상태: `diagnosis_status` 값 목록(`skillops-agent/skillops_agent/state.py` 의 `DiagnosisStatus`)과, 진단 기록에서 제안서 번호를
   찾을 수 있는 `proposal.id`.
3. 출력: 제안서 형식(`docs/contracts/proposal.schema.json`)과 service 의 `GET /api/proposals/{id}` 응답.

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
| G-01 | 지금은 원인 하나만 담김 | 목표 정답은 그대로 두고, 지금 결과를 원인 미확정으로 고침 | 원인 실험 단위(D3) |
| G-02 | 같은 파일의 설명과 outputs 를 함께 수정 | 다른 파일(결제 본문)의 수정과 함께 저장 | 원인 실험 단위(L4) |
| 새로 추가 | | X-05(형식 오류), T-02b(정책 확인 요청), V-01·V-02(검증 실패), S-07(불합격 후보 승인) | |
