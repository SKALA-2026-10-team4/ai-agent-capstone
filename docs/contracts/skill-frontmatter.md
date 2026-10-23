# 계약 1 · SKILL.md frontmatter

SKILL.md 는 맨 위 `---` 두 줄 사이의 YAML(frontmatter)과 그 뒤 자연어 본문으로 이뤄진다. 이 문서는 지금 저장소의 skill 파일과 파서가 실제로 따르는 형식을 적는다. 기준 구현은 주문 Agent 의 `order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java` 이고, 다른 파서는 이 규칙을 복제한다.

## 단계 skill

```yaml
---
name: payment
description: 예약된 주문 금액을 결제한다
inputs: [order.id, order.total, order.payment_method, inventory.reserved]
outputs: [payment.id, payment.status]
depends_on: [inventory-check]
owner: 주문운영팀
version: 1
---
# 예약된 주문 금액을 결제한다

재고 예약 성공을 확인합니다. 결제 도구에 주문번호·서버 총액·결제 방식을 전달합니다. ...
```

## flow skill (`order-flow`)

```yaml
---
name: order-flow
description: 캠핑용품 주문의 처리 순서
steps: [order-intake, inventory-check, payment, shipping-reserve, notify]
owner: 주문운영팀
version: 1
---
# 캠핑용품 주문의 처리 순서

위 steps 순서대로 처리합니다. ...
```

`steps` 가 있으면 flow skill 이고, 없으면 단계 skill 이다. 루트 flow 이름은 `order-flow` 로 고정돼 있다(`OrderFlowPolicy.ROOT_FLOW_ID`).

## 칸 규칙

`SkillLoader` 가 아래 규칙 가운데 하나라도 어기면 `INVALID_SKILL_FORMAT` 으로 거절한다. 이름 규칙 위반은 `INVALID_SKILL_NAME`, 파일이 없으면 `UNKNOWN_SKILL` 이다.

| 칸 | 단계 skill | flow skill | 규칙 |
|---|---|---|---|
| `name` | 필수 | 필수 | 폴더 이름과 같아야 한다. 소문자로 시작하고 소문자·숫자만 쓰며, 하이픈은 단어 사이에 하나씩만 둔다. 64자 이하다. |
| `description` | 필수 | 필수 | 비어 있지 않은 문자열이다. |
| `owner` | 필수 | 필수 | 비어 있지 않은 문자열이다. 이 skill 을 고치는 업무 담당 조직이다. |
| `version` | 필수 | 필수 | 1 이상의 정수다. |
| `inputs` | 필수 | 생략 가능 | 문자열 목록이다. 빈 목록은 되지만 빈 문자열이나 문자열이 아닌 항목은 안 된다. |
| `outputs` | 필수 | 생략 가능 | `inputs` 와 같은 규칙이다. |
| `depends_on` | 필수 | 생략 가능 | 앞에 끝나 있어야 하는 skill 이름 목록이다. 같은 규칙이다. |
| `steps` | 없음 | 필수 | 비어 있지 않고 중복이 없으며 12개 이하인 skill 이름 목록이다(`OrderFlowPolicy.MAX_FLOW_STEPS`). |

- 구분선은 첫 줄의 `---` 와 그 뒤 처음 나오는 `---` 줄이다. CRLF 는 LF 로 바꿔 읽고, 엄격한 UTF-8 이 아니면 거절한다.
- YAML 은 SnakeYAML `SafeConstructor` 로 읽고 중복 키와 컬렉션 alias 를 허용하지 않는다. 목록은 `[a, b]` 한 줄 형식과 `- a` 여러 줄 형식 모두 YAML 로는 같지만, 아래 "파서별 차이"의 `SkillHeader` 는 한 줄 형식만 읽는다. 저장소의 skill 은 모두 한 줄 형식을 쓴다.
- 본문은 앞뒤 공백을 지운 뒤 비어 있으면 안 된다. 본문의 내용은 검사하지 않는다. `openai` 결정 모드에서는 본문이 그대로 모델 질문에 들어간다.
- 위에 없는 키는 읽지 않고 무시한다.

## version

`version` 은 담당자가 적은 값을 그대로 읽는다. 저장할 때 이 값을 자동으로 올리는 코드는 없다. 주문 Agent 의 수정 저장(`POST /api/snapshots/edits`)은 파일을 고치지 않고 새 snapshot 을 만들며, snapshot 과 파일 해시가 실제 변경을 구별한다. service 의 연습 공간은 `create_workspace` 가 돌려준 `skill_versions` 를 `apply_patch` 의 `base_version` 기준으로 쓰고, 결과의 `changed_skills[].version` 은 시작 버전에 수정 횟수를 더해 계산한 값이다. 이때도 SKILL.md 의 `version` 줄은 바뀌지 않는다.

## 컨텍스트 키 규칙

- 키는 `<네임스페이스>.<필드>` 모양이다. 주문 입력에서 오는 키는 `order.*` 이고, 그 밖의 키는 앞선 단계 skill 이 `outputs` 에 선언한 값이다.
- 처음부터 공급되는 키는 `order.id`, `order.items`, `order.customer`, `order.payment_method`, `order.shipping` 다섯 개다(`OrderFlowPolicy.INITIAL_INPUT_KEYS`). `order.total` 은 서버가 계산한 금액이라 처음에는 숨기고 `order-intake` 가 출력으로 올린다.
- 정적 검증(`SkillLoader.validateFlow`)은 업무 도구를 실행하기 전에 `steps` 순서대로 각 단계의 `inputs` 가 처음 키와 앞선 단계 `outputs` 안에 있는지, `depends_on` 의 skill 이 앞에서 끝났는지 본다. 빠진 값이 있으면 `CONTRACT_VIOLATION`(`missing_inputs`, `missing_dependencies`)이다. 단계 자리에 flow skill 이 오면 `NESTED_FLOW_NOT_SUPPORTED` 다. 실행 중에도 단계마다 같은 검사를 다시 한다.
- 주문 Agent 는 마지막에 `notify` 단계가 끝나지 않은 flow 를 `INCOMPLETE_FLOW` 로 실패시킨다(`OrderFlowPolicy.REQUIRED_FINAL_STEP`).
- 이 검사는 frontmatter 선언만 본다. 그래서 본문만 바꾸고 선언을 그대로 두면 정적 검증은 통과하고, 어긋남은 실제 도구 실행 결과에서 드러난다.

## 파서별 차이

같은 SKILL.md 를 여러 모듈이 읽는다. `SkillLoader` 클래스 주석에 복제 관계가 정리돼 있다.

| 파서 | 읽는 방법 | 쓰는 곳 |
|---|---|---|
| `order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java` | SnakeYAML 안전 옵션과 글자 수 한도(100000), 위 칸 규칙 전체 | 주문 실행, skill 조회 |
| `service/src/main/java/com/capstone4/skillops/snapshot/Frontmatter.java` | `SkillLoader` 와 같은 안전 옵션으로 YAML 을 읽는다. 글자 수 한도는 두지 않는다. | `SkillDiffer`(frontmatter 키 변화), `DependencyAnalyzer`(연결 그래프) |
| `service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java` | `Frontmatter` 결과로 `name`, `steps`, `inputs`, `outputs`, `depends_on` 을 `validateFlow` 와 같은 규칙으로 검사하되 첫 위반에서 멈추지 않고 끝까지 계산한다. steps 중복, 개수 한도, description, version, owner, 본문 존재는 보지 않는다. | `get_dependency_graph`, `apply_patch` 의 끊긴 연결 검사 |
| `service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java` | YAML 라이브러리 없이 `key: value` 줄을 읽는 간이 파서다. `name`, `version`, `inputs`, `outputs`, `steps` 만 읽고 `depends_on`, `description`, `owner` 는 읽지 않는다. | 연습 공간의 시작 버전, 가짜 실행기 `ContractOnlyFlowRunner` |
| `frontend/src/utils/frontmatter.js`, `skillSet.js` | js-yaml `CORE_SCHEMA` 로 읽고 `skillSet.js` 가 필수 값을 검사한다. | 운영 화면의 편집기와 연결 점검 |

알려진 차이가 하나 있다. `SkillHeader` 는 `[a, b]` 한 줄 목록만 목록으로 읽으므로, `- a` 여러 줄 YAML 목록으로 쓴 `inputs`, `outputs`, `steps` 는 빈 목록이 된다. 그래서 YAML 로 읽는 `DependencyAnalyzer` 와 같은 파일을 다르게 해석할 수 있다. 값 뒤에 공백을 두고 붙인 `#` 주석은 떼고 읽는다.

## 위치

| 폴더 | 내용 | 읽는 곳 |
|---|---|---|
| `order-agent/skills/<name>/SKILL.md` | 주문 Agent 의 게시 번들(release `camping-v1`)이다. `bundle.json` 에 파일별 해시를 봉인하며, 고친 뒤에는 `order-agent/scripts/publish-skills.py` 로 다시 봉인한다. `inventory-check/resources/policy.md` 같은 resources 도 함께 든다. | 주문 Agent 가 snapshot 으로 발행해 실행한다. 진단과 `harness` 실행기는 이 snapshot 을 쓴다. |
| `skills/<name>/SKILL.md` | 저장소 루트의 운영 폴더다. 주문 Agent 번들과 칸 구조는 같지만 description, owner, 본문이 다르고 resources 가 없다. | service 의 `skillops.skills-dir`(`SKILLOPS_SKILLS_DIR`, 기본 `../skills`)이다. snapshot 없이 만든 연습 공간과 service 테스트가 읽는다. |

주문 Agent 안에서 snapshot 의 skill 경로는 `skills/<name>/SKILL.md` 다(`SkillLoader.skillPath`).

## 상태 값

주문의 최종 상태는 `SHIPPED`(정상 완료), `REJECTED`(재고 부족이나 엔트리 미선택으로 거절), `HOLD`(결제 거절로 보류), `FAILED`(실행 오류나 재시작 중단) 가운데 하나다(`order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/OrderStatus.java`). 처리 중인 주문은 `CREATED`, `PROCESSING` 이다. `payment.status` 는 skill 출력 값이라 이 목록과 따로이며, 지금 결제 skill 은 승인이면 `PAID`, 거절이면 `DECLINED` 를 기록한다.
