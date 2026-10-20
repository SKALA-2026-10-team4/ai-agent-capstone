# MCP 도구와 주문 Agent API 매핑 초안

2026-10-20 / 담당자 협의용 제안. 제공된 화면의 도구 6개를 기준으로 작성했다. 아래 MCP 입력·출력과 추가 API는 확정 계약이 아니다. 이 문서는 조회 연계를 설계하며 MCP 서버나 Runner를 구현하지 않는다.

## 1. 역할과 전제

주문 Agent는 실행 증거를 저장하고 조회 API를 제공한다. MCP 서버는 HTTP로 증거를 조회하고 필요한 변환·비교 결과를 도구 응답으로 반환한다. SkillOps 진단 Agent가 이를 해석해 원인과 수정 후보를 만든다. 기존 기록 조회에는 Runner가 필요 없다.

실패 조사에서 “현재 운영본”과 “실패 당시 파일”을 섞지 않는다. 실행 trace의 snapshot_id를 기준으로 조회한다. 이미지의 version 번호만으로 파일을 식별하기보다는 snapshot_id와 파일 해시를 사용한다. 현재 파일의 version 선언이 같아도 후보 파일 내용은 다를 수 있다. 공용 Registry가 있다면 정식 버전·현재 운영본의 기준은 그 저장소를 따른다.

현재 독립 Docker 서비스이므로 MCP→주문 Agent는 HTTP 경계다. 향후 같은 프로세스에 통합할 때는 기존 내부 서비스를 재사용할 수 있다. DB 직접 조회나 로직 복제는 피한다.

## 2. 도구 6개 매핑

| MCP 도구 | 권장 입력 초안 | 현재 API 활용 | 필요한 추가 작업 |
|---|---|---|---|
| list_failed_runs | since?, cursor?, limit?, failure_kind? | GET /api/executions | 현재 최근 20개뿐이다. 전체 실패 목록을 위해 서버 측 필터·페이지 조회 추가 필요 |
| get_trace | execution_id | GET /api/executions/{execution_id}/trace | 기존 응답을 공용 MCP 형식으로 변환 |
| list_skills | snapshot_id | snapshot manifest + 각 SKILL.md 조회 | 이름·설명·version·owner·inputs·outputs·depends_on·steps를 파싱해 목록 구성 |
| diff_skill | skill_id, before_snapshot_id, after_snapshot_id | 두 snapshot의 manifest와 대상 파일 조회 | MCP에서 구조화 선언 비교·본문 diff 생성, stable hunk_id 부여 |
| get_skill | snapshot_id, skill_id | manifest에서 경로 확인 후 snapshot 파일 조회 | UTF-8 본문·파일 해시 반환, 관련 파일은 manifest 경로로 별도 조회 |
| get_dependency_graph | snapshot_id, entry_skill_id | snapshot 내 flow·단계 Skill 파일 조회 | flow 순서·depends_on·입출력 연결을 분석하고 누락·모호한 연결 표시 |

기존 조회 API:

- GET /api/executions: 최근 최대 20개 실행 요약. since·상태 필터·페이지 기능 없음.
- GET /api/executions/{execution_id}/trace: 실행 요약과 sequence 순서의 events.
- GET /api/traces/{run_id}: 실행 trace 별칭. run_id는 r_ + execution UUID의 하이픈 제거 값이다. 진단 run ID가 아니다.
- GET /api/snapshots/{snapshot_id}: snapshot_id, hash, created_at, metadata, files(path/hash/size).
- GET /api/snapshots/{snapshot_id}/files?path=skills/...: snapshot_id, path, hash, size, encoding, content. encoding은 utf-8 또는 base64.

### 실패 목록의 의미

자동 진단 기본 대상은 실행 오류인 completion=FAILED/INTERRUPTED 등으로 협의한다. REJECTED(재고 부족)·HOLD(결제 거절)는 정상 업무 분기이므로 별도 분류·필터로 제공한다. 주문 상태만으로 오류 여부를 판정하지 않는다. 미선택 등은 trace까지 확인한다.

현재 최근 20개에서 필터한 결과를 “전체 실패 목록”으로 반환하면 누락된다. 임시 사용 시 scope=recent_20, partial=true를 명시해야 한다. 정식 도구에는 GET /api/executions?since=...&failure_kind=execution_error&cursor=...&limit=... 같은 확장 계약을 협의한다(미구현). UTC 기준 시각·정렬·동일 시각의 ID 보조 정렬·cursor 의미도 정한다.

### Skill 목록·본문

진단은 snapshot_id 필수로 시작한다. 운영본 조회가 필요하면 Registry가 반환한 활성 snapshot 참조를 한 번 해석하고, 같은 참조를 이후 호출에도 전달한다. 현재 앱에는 활성 snapshot 조회 API가 없으며 latest 실행이나 개발 fixture 생성 API를 운영본 조회 대신 사용하지 않는다.

skill_id와 name을 별도 필드로 보존한다. 현재 둘의 값이 같더라도 같은 개념으로 고정하지 않는다. 이름→경로 매핑은 manifest와 메타데이터로 검증한다. 사용자 입력을 호스트 파일 경로로 직접 이어 붙이지 않는다. 파싱 실패 시 raw 파일은 조회 가능하게 유지하고, 목록·그래프에는 해당 파일의 parse_error와 불완전 여부를 표시한다.

### 두 버전 비교

같은 Skill의 before/after snapshot과 파일 해시를 응답에 포함한다. 추가·삭제 파일도 표현하고 inputs/outputs/depends_on/steps 변경과 본문 변경을 구분한다. 번호는 표시 순서일 뿐 영구 식별자가 아니다. 재조회에서도 같은 변경 덩어리를 참조하려면 두 파일 해시와 변경 위치 등을 바탕으로 hunk_id를 만든다.

예시 입력(제안):

```json
{
  "skill_id": "inventory-check",
  "before_snapshot_id": "<기준 snapshot UUID>",
  "after_snapshot_id": "<실패 snapshot UUID>"
}
```

정식 Registry가 버전 번호를 사용한다면 버전→snapshot/파일 참조 변환은 Registry 담당과 합의한다. 번호만 보고 임의로 이전 버전을 추측하지 않는다.

### 연결 관계

정적 그래프는 실제 실행 trace와 구분한다. nodes는 Skill, edges는 flow 순서·명시적 의존성·입출력 연결로 타입을 나눈다. 누락 입력은 소비 Skill, 입력 이름, 앞 단계의 제공자 부재를 반환한다. 같은 출력을 여러 Skill이 제공하면 모호함을 표시한다. 도구가 실제 해당 값을 반환했는지는 trace로 확인한다.

이번 결함은 inventory-check가 inventory.reserved를 선언하지 않아 payment로 이어지는 입력 연결이 끊긴 사례다. 정적 그래프를 만들 수 있어도 실제 업무 도구가 실행됐다는 의미는 아니다.

## 3. 실패 조사 시작에 필요한 최소 데이터

두 진입 방식이 가능하다. 수동 조사에서는 execution_id를 받아 get_trace부터 시작한다. 자동 진단에서는 아래처럼 식별자·고정 파일 참조·오류 요약을 전달한다. 파일 전체와 trace 전체를 시작 이벤트에 중복 포함할 필요는 없다.

```json
{
  "schema_version": "1.0",
  "event_id": "<실패 이벤트 UUID>",
  "event_type": "ORDER_EXECUTION_FAILED",
  "occurred_at": "2026-10-20T08:15:43.330Z",
  "source": "order-agent",
  "execution_id": "<실행 UUID>",
  "order_id": "<주문 UUID>",
  "agent_id": "camping-order-agent",
  "snapshot": {
    "snapshot_id": "<실행 당시 snapshot UUID>",
    "hash_algorithm": "SHA-256",
    "hash": "<snapshot 전체 해시>"
  },
  "baseline_snapshot": null,
  "error": {
    "category": "CONTRACT_VIOLATION",
    "skill_id": "payment",
    "phase": "static_validation",
    "missing_inputs": ["inventory.reserved"],
    "missing_dependencies": [],
    "message": "Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다."
  },
  "diagnosis_run_id": null,
  "evaluation_id": null
}
```

- event_id는 재전송에도 유지해 중복 진단을 방지한다. 자동 실패 이벤트 발행·전달은 미구현이다.
- snapshot은 고정 성공 후 필수다. 고정 자체가 실패하면 null을 허용하고 snapshot 고정 실패로 분류한다. 다른 snapshot을 대신 넣지 않는다.
- baseline_snapshot은 알 때만 {snapshot_id, hash_algorithm, hash}로 전달한다. 기준본이 없으면 변경 원인·발생 시점을 단정하지 않는다.
- skill_id·phase·missing_inputs·missing_dependencies는 계약 오류 상세다. 다른 category에 강제로 같은 필드를 채우지 않는다. 실패 위치를 모르면 skill_id는 null을 허용한다.
- diagnosis_run_id·evaluation_id는 관련 실행이 있을 때만 연결한다. 주문 앱 trace의 run_id와 진단 run ID를 구분한다.
- 상세 trace를 최종 증거로 사용한다. 이벤트가 부분 요약이라는 점을 유지하고 최종 주문 상태·실제 업무 효과는 trace/주문 조회로 확인한다.

get_trace는 현재 응답의 execution_id, run_id, order_id, snapshot_id/hash, agent_id, mode/model_mode/model, completion, raw_selection, resolved_skill_id, error_category, final_status, api_calls, tokens, elapsed_ms, timestamps, events를 보존해 반환하는 것을 권장한다. events의 sequence/kind/skill_id/data/at을 유지한다. 정규화된 단계 요약을 추가하더라도 원래 이벤트를 대체하지 않는다. 미관측 토큰은 null로 유지한다.

## 4. 조회 순서와 Runner 역할

권장 조회 흐름:

1. 실패 이벤트의 execution_id 또는 list_failed_runs 결과로 대상 실행을 고른다.
2. get_trace로 오류·최종 상태·실제 로드·업무 효과를 확인하고 snapshot_id/hash를 확보한다.
3. list_skills(snapshot_id)로 당시 후보와 계약을 확인한다.
4. 기준본이 있으면 diff_skill로 변경 부분을 좁힌다. 기준본이 없으면 이 단계는 생략한다.
5. get_skill로 변경 Skill과 실패 Skill 및 앞 단계를 읽는다. 이번 사례는 inventory-check, payment, order-flow다.
6. get_dependency_graph로 주변 영향과 끊긴 입력·선행 단계를 확인한다.
7. SkillOps 진단 Agent가 증거를 인용해 원인을 설명하고 별도 수정 후보를 만든다.

순서는 조사 필요에 따라 바꿀 수 있다. 스키마·입력 파싱 실패처럼 그래프 생성 자체가 불가능한 경우 raw 파일과 trace부터 조사한다.

```text
기록 조사: SkillOps → MCP 조회 도구 → 주문 Agent의 저장된 trace/snapshot
후보 평가: SkillOps → 평가 요청(필요하면 MCP 도구) → Runner
                                         → TargetAdapter → 동일 Harness
                                         → Judge → 평가 결과·trace 저장
```

Runner는 반복·예산·시간 제한·데이터 초기화·실행·채점 호출·집계를 담당한다. TargetAdapter는 대상 호출과 공통 결과 변환, Judge는 기대값과 실제 결과 비교를 담당한다. 일반 주문과 기록 조회는 Runner를 거치지 않는다. 진단용 조회가 주문 생성·재고 변경·재결제를 유발하면 안 된다. 평가 데이터와 Mock은 일반 주문과 분리하며 기대값은 모델 입력에 전달하지 않는다. 현재 Runner/TargetAdapter/Judge 연동은 후속 작업이다.

## 5. 네트워크·오류 처리 협의

| 항목 | 현재 상태와 권장 협의 |
|---|---|
| 주소 | MCP 환경 설정 ORDER_AGENT_BASE_URL 사용 제안. 현재 호스트 실행은 http://localhost:8081 |
| Docker | 현재 backend는 호스트 127.0.0.1:8081에 공개. 별도 Compose 간 공유 external network와 고유 DNS 별칭 구성 권장. 예: http://order-agent-backend:8080(별칭 설정 필요) |
| localhost | MCP 컨테이너의 localhost는 MCP 자신이다. host.docker.internal 접근 가능 여부도 현재 바인딩·환경에서 검증 필요 |
| 인증 | 현재 독립 로컬 데모에는 인증 없음. 공용 환경 연결 전 서비스 인증·읽기 권한·필요 시 TLS 방식 합의 |
| 요청 제한 | 연결/응답 timeout·전체 도구 호출 예산·본문 크기·최대 파일 수 합의. 서버 snapshot 한도는 파일당 1MB, 전체 5MB, 최대 256개 |
| 조회 재시도 | GET 연결 오류·일시 5xx·429에만 제한된 backoff 재시도 제안. 429는 Retry-After 존중. 400/401/403/404·해시 불일치는 자동 재시도하지 않음 |
| 오류 구분 | 조회 transport/tool error와 trace 안의 주문 실행 오류를 분리. FAILED 실행을 정상 조회한 것은 도구 조회 성공 |
| 오류 응답 | 404 EXECUTION_NOT_FOUND/SNAPSHOT_NOT_FOUND/SNAPSHOT_FILE_NOT_FOUND 보존. 현재 AgentException은 NOT_FOUND이면 404, 그 외 보통 400이며 상세 계약 오류는 trace에 저장됨 |
| 무결성 | snapshot 조회는 서버가 저장 파일·전체 해시 검사. SNAPSHOT_TAMPERED는 조사 중단·명시 오류. 전체 해시는 메타데이터도 포함하므로 파일별 해시 연결만으로 임의 재계산하지 않음 |
| 부분 결과 | 최근 20개 제한·파일 파싱 오류·크기 잘림은 partial 및 이유 명시. 빈 목록이나 완전한 그래프로 위장하지 않음 |
| 비밀·권한 | base URL은 서버 설정으로 고정하고 임의 URL 입력 금지. snapshot manifest의 경로만 조회. 고객·모델 입력·파일에 포함될 수 있는 비밀의 접근·마스킹·보존 정책 합의 |
| 캐시 | 검증된 snapshot_id/hash 기준 캐시 가능. 실행 중 trace와 최신 실패 목록은 불변으로 취급하지 않음 |
| 이벤트 전달 | push/webhook/queue/polling 중 선택, 전달 주체·재전송·중복 제거·보존 정책 협의. HTTP 성공 응답과 진단 접수 성공을 구분 |

MCP 오류 래퍼는 code, message, retryable, upstream_status, execution_id/snapshot_id 같은 참조를 보존하도록 합의한다. 정확한 SDK 반환 형식은 기존 MCP 서버 규칙을 따른다. 원인 분석을 MCP 오류 메시지에 임의로 덧붙이지 않는다.

## 6. 담당별 필요한 작업과 연결 확인

- 주문 Agent: 실패 목록의 필터·페이지 계약, 안정적인 실패 전달, 테스트 실행 경계 제공. 현재 조회 API는 재사용한다.
- MCP/공용 Registry: 6개 도구, snapshot 기반 파싱·diff·그래프, 버전 참조 변환과 조회 오류 매핑. 이미 있는 구현이 있다면 어댑터로 연결한다.
- SkillOps 진단 Agent: 조사 도구 호출·증거 기반 원인 설명·수정 후보 생성. 오류의 skill_id가 원인 파일이라고 단정하지 않는다.
- Runner 담당: SEALED 후보 참조·시나리오·반복·예산을 받아 격리 실행하고 평가 결과 반환.
- 공동 확인: 같은 execution의 snapshot으로 모든 조회가 고정되는지, 결함본에서 payment의 누락 입력이 보이는지, 기준/결함 diff와 그래프가 일치하는지, 없는 ID·단절·변조를 명시 오류로 반환하는지 확인한다.

우선 get_trace와 get_skill/get_snapshot 조회부터 연결하고, 실패 목록 확장·diff·그래프·자동 이벤트 전달을 순차 합의하는 것을 제안한다. 현재 화면의 baseline/broken-contract/repaired 버튼은 준비된 시연 fixture이며 실제 SkillOps 후보 접수 인터페이스가 아니다.
