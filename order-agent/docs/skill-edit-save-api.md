# 실제 Skill 편집·저장과 주문 실행 연결

> 후속 구현: 저장 이벤트 자동 전달·중복 방지·진행 조회·화면 연결은 `docs/skill-save-auto-diagnosis.md`에 정리했다. 아래의 자동 연동 미구현·메모리 상태 관련 설명은 해당 후속 구현으로 대체된다.

2026-10-21 · #56

## 구현 범위

공용 운영 화면 frontend/의 Skill 편집에서 초안을 서버에 저장하고, 저장된 수정 snapshot을 주문 Agent 실행에 사용할 수 있다. 기존 snapshot과 운영 skills/ 파일은 덮어쓰지 않는다. 저장은 검증 완료·운영 반영을 뜻하지 않는다.

```text
frontend /skills/:skill
 → 전체 수정본 저장
 → POST order-agent /api/snapshots/edits
 → 기준 snapshot 전체 복사 + 변경 원문 적용
 → 불변 수정 snapshot 저장
 → 새 snapshot 선택
 → /orders에서 같은 snapshot으로 주문 실행
 → /api/executions/{execution_id}/trace에서 실제 결과 확인
```

저장 요청은 /api 프록시를 통해 주문 Agent(:8081)로 전달된다. 별도 order-agent/frontend(:5174)의 데모 화면이 아니라 루트 frontend(:5173)의 Skill 편집 화면에 추가한 기능이다. 해당 화면은 기존 로그인 흐름을 사용한다.

## 화면 사용법

1. 주문 Agent와 공용 frontend를 실행하고 로그인한다. 소스 변경 후 주문 Agent backend 이미지를 재빌드해야 새 API를 사용할 수 있다.
2. Skill 편집에서 기존 snapshot을 선택하거나 ID를 입력한다. 처음에는 정상본 고정으로 기준 snapshot을 만들 수 있다.
3. 실제 SKILL.md 내용을 수정한다. 여러 파일을 수정했다면 전체 수정본 저장으로 함께 저장한다.
4. 저장에 성공하면 내 수정본 세트가 목록에 추가되고 선택된다. 저장 중 추가 편집이나 다른 세트 선택을 했다면 그 작업을 유지한다.
5. 주문 실행으로 이동해 저장된 세트와 고정 세트 사용 옵션을 확인한 뒤 주문을 실행한다. 이 실행은 기존 일반 주문 실행이며 운영 데모 재고를 변경할 수 있다. 자동 dry-run은 아니다.
6. 결과의 trace에서 실행 snapshot ID와 오류·도구 결과를 확인한다.

초안은 저장 전까지 브라우저에 남는다. 저장 실패 시 초안을 유지한다. 연결 오류·잘못된 Skill 형식이 있는 후보도 진단을 위해 저장할 수 있다. 화면의 입력·출력 연결 점검은 전체 업무 성공을 보장하지 않는다. mock 모드는 이 저장 API를 흉내 내지 않으며 저장 버튼을 비활성화한다.

## API

POST /api/snapshots/edits → 201 Created

```json
{
  "baseline_snapshot_id": "<기준 snapshot UUID>",
  "baseline_hash": "<GET /api/snapshots/{id}의 hash>",
  "changes": {
    "skills/inventory-check/SKILL.md": "<수정한 전체 UTF-8 원문>",
    "skills/order-flow/SKILL.md": "<수정한 전체 UTF-8 원문>"
  },
  "intent": {
    "text": "선입금 주문만 결제하고 카드 주문은 결제하지 않는다",
    "expectations": [
      {"scope": "order.payment_method == 'card'", "checks": ["order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"]},
      {"scope": "order.payment_method == 'prepaid'", "checks": ["order.final_status == 'SHIPPED'"]}
    ]
  }
}
```

- changes는 unified diff가 아닌 파일별 전체 원문이다. 미지정 파일·resources는 기준본에서 그대로 복사한다.
- 기존 Skill 수정과 새 Skill 파일 추가를 지원한다. 파일 삭제·resources 편집은 이번 API의 범위에 포함하지 않는다.
- 경로는 skills/<소문자·숫자·하이픈 이름>/SKILL.md만 허용한다. 임의 파일 경로·호스트 경로는 받지 않는다.
- 변경 요청은 1~64개, 파일은 UTF-8 기준 1MB 이하이며 결과 묶음은 기존 제한(256파일/5MB)을 따른다.
- 원문이 같은 항목은 changed_paths에서 제외하며 실제 변경이 하나도 없으면 거부한다.
- 기준 ID·해시가 맞아야 저장한다. 기준본 전체와 변경 파일을 하나의 트랜잭션으로 저장하므로 일부 파일만 저장하지 않는다.
- 파싱·계약·업무 정책의 통과 여부를 저장 조건으로 삼지 않는다. 실제 주문 Harness 또는 후속 Runner가 검증한다.

### intent (선택, 2026-10-26)

담당자가 이번 수정으로 바꾸려는 업무 규칙(의도)이다. 계약은 `docs/plans/2026-10-26-behavior-edit-root-fix.md` 2.3절이며, 저장 요청 → 저장 이벤트 → SkillOps `/diagnose`까지 같은 모양으로 흐른다.

| 칸 | 규칙 |
|---|---|
| intent | 선택. 생략하거나 null이면 의도 없음이며 metadata와 저장 이벤트에 intent 칸을 만들지 않는다(기존 본문과 같다) |
| text | intent를 보낼 때 필수. 공백이 아닌 1~500자 |
| expectations | 선택, 0~10개. 생략하면 빈 목록으로 저장한다. 비어 있으면 SkillOps Agent가 text와 수정 내용으로 만든다 |
| scope | 조건 한 줄 또는 null(생략 시 null). 시험 주문의 처음 값으로 고른다. 값 이름은 order.payment_method, order.customer, order.shipping.country, order.shipping.express, order.total 가운데 하나와 **정확히** 같아야 한다(앞부분 order.shipping, 더 깊은 경로 order.shipping.country.code, 예약 키 order.final_status 거부). 공백 문자열은 거부 |
| checks | 조건 1~10개. 공백 문자열·null은 거부. 값 이름은 Skill마다 달라 저장 때 보지 않는다 |

- 모양이 틀리면 INVALID_SKILL_EDITS(400)이며 snapshot도 저장 이벤트도 남지 않는다. intent 안에 모르는 키가 있으면 다른 본문 키처럼 MALFORMED_JSON(400)이다.
- scope와 checks는 저장 때 조건 문법 v2(`RuleCondition`, 계약 2.1절)로 읽는다(2.10절 V3-2). `payment.status = null`처럼 읽을 수 없는 줄이나 다섯 키가 아닌 scope는 INVALID_SKILL_EDITS(400)다. 예전에는 이런 의도가 201로 저장된 뒤 SkillOps `/diagnose`가 거절해, 그 저장 이벤트가 끝없이 재전송되며 뒤의 모든 저장 이벤트 전달을 막았다.
- 서버 안의 예외에는 처음 걸린 칸과 이유가 details로 남는다(`field`: `intent.expectations[0].checks[1]` 모양, `reason`: `CONDITION_UNREADABLE` 또는 `SCOPE_KEY_NOT_ALLOWED`, `message`: 그 줄을 밝힌 문구). 다른 AgentException처럼 HTTP 본문은 정해진 문구와 빈 field_errors만 싣으므로(api.md 6장) 화면은 보내기 전에 같은 문법으로 미리 검사하는 편이 좋다.
- 저장한 값은 응답과 GET /api/snapshots/{id}의 `metadata.intent`, service로 보내는 skill_saved 이벤트 본문의 `intent`에 같은 모양(text, expectations[{scope, checks}])으로 담긴다. intent가 있으면 metadata가 달라지므로 snapshot hash도 달라진다.

응답은 기존 manifest에 연동 참조를 추가한다.

```json
{
  "snapshot_id": "<새 snapshot UUID>",
  "hash": "<새 snapshot 전체 hash>",
  "created_at": "<UTC 시각>",
  "baseline_snapshot_id": "<기준 snapshot UUID>",
  "change_id": "<이번 저장 UUID>",
  "changed_paths": ["skills/inventory-check/SKILL.md"],
  "validation_status": "NOT_REQUESTED",
  "metadata": {
    "source": "user-edit",
    "change_id": "<동일 change UUID>",
    "baseline_snapshot_id": "<기준 snapshot UUID>",
    "baseline_hash": "<기준 hash>",
    "changed_paths": ["skills/inventory-check/SKILL.md"],
    "validation_status": "NOT_REQUESTED"
  },
  "files": [{"path": "skills/inventory-check/SKILL.md", "hash": "<파일 hash>", "size": 123}]
}
```

files 예시는 일부이며 실제 응답에는 전체 묶음 manifest를 포함한다. metadata에도 참조를 저장하므로 기존 GET /api/snapshots/{id}로 다시 조회할 수 있다.

| 오류 | HTTP | 의미 |
|---|---:|---|
| INVALID_SKILL_EDITS | 400 | 필수값·변경 수·경로·원문 값 오류, intent 모양 오류, 읽을 수 없는 scope·checks, 허용하지 않는 scope 값 이름 |
| BASELINE_HASH_MISMATCH | 400 | 지정한 기준 hash가 저장된 기준본과 다름 |
| NO_SKILL_CHANGES | 400 | 실제 원문 변경이 없음 |
| INVALID_BUNDLE_SIZE | 400 | 파일/묶음 크기·개수 초과 |
| SNAPSHOT_NOT_FOUND | 404 | 기준 snapshot 없음 |
| SNAPSHOT_TAMPERED | 400 | 저장 자료 무결성 오류 |

자동 POST 재시도·Idempotency-Key는 이번 저장 API에 없다. 응답 유실 후 재전송하면 별도 snapshot/change_id가 만들어질 수 있다. 화면의 저장 중 버튼 비활성화는 이벤트 중복 방지를 대신하지 않는다.

### 저장 이벤트 거절 (2026-10-26)

`SkillChangeDispatcher`는 thread_id가 없는 가장 오래된 행부터 하나씩 보내므로, 계속 실패하는 행 하나가 뒤의 모든 행을 막을 수 있다. 그래서 받는 쪽(service `POST /internal/skill-changes`)이 그 행의 본문을 거절하면 다시 보내지 않는다.

| 응답 | 처리 | GET /api/skill-changes/{id}의 delivery_status |
|---|---|---|
| 200 + thread_id | thread_id 기록, last_error 비움 | ACCEPTED |
| 400, 409, 413, 415, 422 | attempts+1, last_error `진단 요청 거절(HTTP n): 이 저장은 다시 보내지 않습니다`, `failed_at` 기록. 다음 차례부터 고르지 않는다 | FAILED |
| 401, 403, 404, 408, 429 등 나머지 4xx, 5xx, 연결 실패, thread_id 없는 응답 | attempts+1, last_error `진단 요청 전달 실패: <예외 이름> (HTTP n)`, 다음 차례에 다시 보냄 | PENDING |

- 토큰·주소·부하 문제(401, 403, 404, 408, 429)는 행이 아니라 연결 설정의 문제라 고치면 모든 행이 다시 가야 하므로 거절로 보지 않는다. 이런 실패가 이어지는 동안에는 지금처럼 뒤의 행도 기다린다.
- 지금 service 중계는 SkillOps Agent의 409·422를 502로 바꾸므로, 실제로 FAILED가 되는 것은 중계 자신의 400(저장 이벤트 모양 오류)이다. 읽을 수 없는 의도 조건은 위 intent 검사가 저장 때 막는다.
- `skill_change_events.failed_at`(nullable)은 `schema.sql`의 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`로 더한다. 응답 키는 그대로이며 failed_at 자체는 싣지 않는다. 거절된 행을 다시 보내려면 DB에서 그 행의 failed_at을 비운다(API 없음).
- 공용 화면(frontend/src/views/DiagnosisView.vue)은 last_error를 "자동으로 재전송합니다" 안내와 함께 보여 주므로 FAILED에서는 그 안내가 맞지 않는다. 화면 쪽에서 FAILED를 따로 보여 줘야 한다.

## Runner·SkillOps 연결 시 전달할 내용

저장 결과의 change_id, baseline_snapshot_id, snapshot_id(후보), hash, changed_paths를 사용한다. 기준본과 후보의 버전 번호를 추정하거나 다시 편집 중인 파일을 읽지 않는다. metadata.source=user-edit로 사용자 저장을 구분할 수 있다.

현재 validation_status=NOT_REQUESTED는 저장 당시 자동 검증을 요청하지 않았다는 뜻이며, 수동 주문 실행 후에도 갱신되지 않는다. evaluation_id/execution_id/diagnosis_id는 저장 시 생성하지 않는다.

후속 연동에서 필요한 작업:

- 저장 트랜잭션에 outbox 이벤트를 기록하고 Runner 측 검증 조정 서비스로 전달한다.
- event_id/change_id 중복 처리, 재전송, 진행 상태·평가 ID 조회를 구현한다.
- 후보 snapshot을 고정해 dry-run과 Judge를 실행한다. 기대값은 모델에 전달하지 않는다.
- 실패 시 변경·평가·실행 참조를 SkillOps에 전달한다. S5는 의도 위반 검출·설명까지 수행한다.
- 후보 검증이 다시 사용자 저장 이벤트를 발생시키지 않도록 origin을 구분한다.

이 API는 해당 연동을 위한 저장 경계와 증거 참조를 마련한 것이며 webhook·Runner·SkillOps 호출은 아직 추가하지 않았다.

## 검증

전용 PostgreSQL에서 저장 API → 실제 snapshot 조회 → 수정본으로 Harness 실행 → 계약 오류 trace까지 검증했다. 변경 없음·잘못된 경로·기준 hash 불일치를 거부하고 snapshot이 일부 생성되지 않는지 확인했다. 기존 S1~S6와 주문 Agent 전체 테스트를 함께 실행했다.

```bash
# 저장소 루트
docker compose -f order-agent/compose.yml --profile test up -d --wait db-test
cd order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test
```

공용 frontend의 production build와 변경 파일 ESLint를 확인했다. 실제 브라우저에서 로그인→편집→저장→주문 실행까지의 클릭 검증은 아직 수행하지 않았다.

```bash
cd frontend
npm ci
npm run build
npx eslint src/api/orderAgent.js src/stores/workspace.js src/views/SkillDraftView.vue
```

2026-10-21 실행 결과: 주문 Agent 전체 45개 테스트 통과(실패·오류·건너뜀 0), frontend build 및 변경 파일 ESLint 통과.
