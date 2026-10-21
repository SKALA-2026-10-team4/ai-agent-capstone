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
| INVALID_SKILL_EDITS | 400 | 필수값·변경 수·경로·원문 값 오류 |
| BASELINE_HASH_MISMATCH | 400 | 지정한 기준 hash가 저장된 기준본과 다름 |
| NO_SKILL_CHANGES | 400 | 실제 원문 변경이 없음 |
| INVALID_BUNDLE_SIZE | 400 | 파일/묶음 크기·개수 초과 |
| SNAPSHOT_NOT_FOUND | 404 | 기준 snapshot 없음 |
| SNAPSHOT_TAMPERED | 400 | 저장 자료 무결성 오류 |

자동 POST 재시도·Idempotency-Key는 이번 저장 API에 없다. 응답 유실 후 재전송하면 별도 snapshot/change_id가 만들어질 수 있다. 화면의 저장 중 버튼 비활성화는 이벤트 중복 방지를 대신하지 않는다.

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
