// 운영 화면 흐름의 튜닝 값을 한곳에 모은 파일이다. 시연 환경이나 실제 모델 모드(주문 한 건에
// 최대 45초)에 맞춰 시간을 바꿀 때 이 파일만 고친다. 값마다 읽는 곳을 적어 두었다.
// 이 파일 밖의 튜닝 지점은 다섯 곳이다. trace 이벤트 표시는 frontend/src/utils/eventView.js 의
// EVENT_KINDS, 진단 상태 라벨은 frontend/src/utils/diagnosisView.js 의 DIAGNOSIS_LABEL,
// 첫 화면과 로그인 경로는 frontend/src/router/paths.js, 진입 Skill 이름은
// frontend/src/utils/skillSet.js 의 ENTRY_SKILL, 메뉴와 구역 번호는 frontend/src/router/nav.js 에 있다.

// HTTP 요청 시간 제한(밀리초)이다. 응답 헤더를 받을 때까지만 잰다. frontend/src/api/client.js 의
// send 가 메서드별로 읽고, 표에 없는 메서드는 HTTP_TIMEOUT_FALLBACK_MS 를 쓴다. 주문 생성 POST 가
// 이 시간을 넘기면 frontend/src/api/orderAgent.js 의 createOrder 가 ORDER_RECOVERY 로 결과를
// 다시 조회한다.
export const HTTP_TIMEOUT_MS = { GET: 5000, POST: 15000 }
export const HTTP_TIMEOUT_FALLBACK_MS = 5000

// 주문 응답을 못 받았을 때 같은 Idempotency-Key 로 다시 조회하는 간격과 마감(밀리초)이다.
// frontend/src/api/orderAgent.js 의 recover 가 읽는다. 마감은 실제 모델 모드의 최대 실행
// 시간보다 길어야 한다.
export const ORDER_RECOVERY = { pollMs: 1000, deadlineMs: 60000 }

// 검증·진단 기록 화면이 진행 상태를 다시 읽는 간격(밀리초)이다.
// frontend/src/views/DiagnosisView.vue 의 refresh 가 다음 조회를 예약할 때 쓰고,
// 다시 읽을지는 frontend/src/utils/diagnosisView.js 의 shouldPollAgain 이 정한다.
export const DIAGNOSIS_POLL_MS = 2000

// 화면이 기억하는 고정 snapshot 이력 개수다. frontend/src/stores/workspace.js 의 remember 가
// 이력을 이 개수로 자르고, 이 이력이 snapshot 선택지(knownSnapshots)의 앞부분이 된다.
export const PINNED_SNAPSHOT_LIMIT = 12

// 제안서 patch 를 초안에 적용할 때 허용하는 문맥 줄 어긋남(diff 패키지 applyPatch 의
// fuzzFactor)이다. frontend/src/stores/workspace.js 의 applyPatchesToDraft 가 쓴다. 0 이면 문맥
// 줄이 모두 같아야 적용된다. 지울 줄과 삽입 바로 앞뒤의 문맥 줄은 이 값과 상관없이 같아야 한다.
export const PATCH_FUZZ_FACTOR = 1

// 실행 기록 목록이 trace 와 Skill 세트 정보를 한꺼번에 읽을 때 동시에 보내는 요청 수의 상한이다.
// frontend/src/api/runRecords.js 가 frontend/src/utils/runRecord.js 의 createLimiter 에 넘긴다.
export const RUN_RECORD_CONCURRENCY = 4
