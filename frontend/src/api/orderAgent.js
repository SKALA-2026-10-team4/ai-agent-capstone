// order-agent REST API 를 부르는 모듈이다. 요청은 /api 로 가고 frontend/vite.config.js 의
// 프록시가 경로를 그대로 order-agent 로 넘긴다. 명세는 order-agent/docs/api.md,
// order-agent/docs/agent-evidence.md, order-agent/docs/diagnosis-read-api.md 에 있고 응답
// 필드는 snake_case 그대로 쓴다. 받는 쪽 컨트롤러는
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller 의
// ProductController, OrderController, EvidenceController 다. mock 모드에서는
// frontend/src/mocks/index.js 의 orderAgentRoutes 가 같은 경로를 흉내 낸다.
import { apiPath, get, post } from './client'
import { ApiError } from './error'
import { ORDER_RECOVERY } from '@/config'

// 상품 목록 { items: [...] } 를 읽는다. GET /api/products 는 ProductController 의
// list() 가 받는다. frontend/src/views/OrderView.vue 의 loadProducts 가 부른다.
export const listProducts = () => get('/api/products')

// 주문 한 건을 읽는다. GET /api/orders/{id} 는 OrderController 의 get() 이 받는다.
// 이 파일의 createOrder 가 Agent 실행 실패 뒤 FAILED 주문을 다시 읽을 때 부른다.
export const getOrder = (orderId) => get(apiPath`/api/orders/${orderId}`)

// 주문 요청 키(Idempotency-Key)로 주문을 찾는다. GET /api/orders/by-request/{key} 는
// OrderController 의 byRequest() 가 받고, 아직 접수되지 않았으면 404 다. 이 파일의
// recover 가 응답을 못 받은 주문을 찾을 때 부른다.
export const getOrderByRequest = (requestId) => get(apiPath`/api/orders/by-request/${requestId}`)

// ms 밀리초 뒤에 끝나는 Promise 다. recover 의 조회 간격에 쓴다.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
// 아직 끝나지 않은 주문 상태다. 이 상태면 결과가 나올 때까지 다시 조회한다.
const UNFINISHED = new Set(['CREATED', 'PROCESSING'])

/**
 * 응답을 못 받았을 때(시간 초과, 연결 끊김) 같은 Idempotency-Key 로 주문을 찾아 끝날 때까지
 * 기다린다. 주문이 이미 접수됐을 수 있으므로 새 키로 다시 주문하지 않는다. 근거는
 * order-agent/docs/api.md 5장이다. frontend/src/config.js 의 ORDER_RECOVERY.pollMs 간격으로
 * 조회하고, 404(아직 접수 전)와 status 0(연결 실패)은 다시 시도하며 그 밖의 오류는 그대로
 * 던진다. 실제 모델 모드는 주문 한 건에 45초까지 걸릴 수 있어 마감(deadlineMs)을 그보다
 * 길게 둔다. 마감까지 끝난 주문을 찾지 못하면 ORDER_RESULT_PENDING 인 ApiError 를 던진다.
 */
async function recover(requestId, deadlineMs = ORDER_RECOVERY.deadlineMs) {
  const until = Date.now() + deadlineMs
  while (Date.now() < until) {
    await sleep(ORDER_RECOVERY.pollMs)
    try {
      const order = await getOrderByRequest(requestId)
      if (!UNFINISHED.has(order.status)) return order
    } catch (e) {
      if (!(e instanceof ApiError) || (e.status !== 404 && e.status !== 0)) throw e
    }
  }
  throw new ApiError(0, '주문 결과를 아직 확인하지 못했습니다. 실행 기록에서 다시 확인해 주세요.', {
    code: 'ORDER_RESULT_PENDING',
  })
}

/**
 * 주문을 만들고 끝난 주문을 돌려준다. POST /api/orders 는 OrderController 의 create() 가 받고,
 * mock 에서는 frontend/src/mocks/index.js 의 createOrder 가 받는다. snapshotId 가 있으면 그
 * Skill 세트로 실행하도록 본문에 snapshot_id 를 넣는다. frontend/src/views/OrderView.vue 의
 * submit 이 부른다. Idempotency-Key 는 주문마다 새로 만들고, 결과를 다시 조회할 때는 그 키를
 * 그대로 쓴다. Agent 실행 실패(계약 위반 등)는 order_id 가 담긴 500 대 오류로 오므로 그 order_id 로
 * 다시 조회해 FAILED 주문과 execution_id 를 돌려준다. 응답을 못 받았거나(status 0) 미완료 상태로
 * 받으면 recover 로 결과를 기다린다. 그 밖의 오류(입력 오류, 없는 상품 등)는 그대로 던진다.
 */
export async function createOrder(order, { snapshotId = null } = {}) {
  const body = snapshotId ? { ...order, snapshot_id: snapshotId } : order
  const requestId = crypto.randomUUID()
  try {
    const result = await post('/api/orders', body, { 'Idempotency-Key': requestId })
    return UNFINISHED.has(result.status) ? recover(requestId) : result
  } catch (e) {
    if (e instanceof ApiError && e.status >= 500 && e.orderId) return getOrder(e.orderId)
    if (e instanceof ApiError && e.status === 0) return recover(requestId)
    throw e
  }
}

// 최근 실행 목록 { items: [...] } 를 읽는다. GET /api/executions 는
// EvidenceController 의 recent() 가 받는다. frontend/src/stores/workspace.js 의
// refreshExecutions 가 부르고, 이 목록이 snapshot 선택지와 주문 화면의 실행 기록이 된다.
export const listExecutions = () => get('/api/executions')

// 실행 실패(completion 이 FAILED 나 INTERRUPTED)만 읽는다. 재고 부족, 결제 거절 같은
// 업무 거절은 들어가지 않는다. GET /api/executions/failed 는 EvidenceController 의
// failed() 가 받는다. frontend/src/views/ExecutionListView.vue 의 showFailed 가 부른다.
export const listFailedExecutions = () => get('/api/executions/failed')

// 실행 한 건의 trace(실행 정보와 events)를 읽는다.
// GET /api/executions/{id}/trace 는 EvidenceController 의 trace() 가
// 받는다. frontend/src/views/ExecutionView.vue 가 실행 화면을 열 때 부른다.
export const getTrace = (executionId) =>
  get(apiPath`/api/executions/${executionId}/trace`)

// snapshot 의 manifest { snapshot_id, hash, created_at, metadata, files } 를
// 읽는다. GET /api/snapshots/{id} 는 EvidenceController 의 snapshot() 이
// 받는다. frontend/src/api/skillSets.js 의 loadSkillSet 이 부른다.
export const getSnapshot = (snapshotId) => get(apiPath`/api/snapshots/${snapshotId}`)

// snapshot 안 파일 하나의 원문을 읽는다. GET /api/snapshots/{id}/files?path= 는
// EvidenceController 의 file() 이 받는다. 쿼리의 path 도 apiPath 가 인코딩한다.
// frontend/src/api/skillSets.js(서버가 읽지 못한 SKILL.md 원문)와
// frontend/src/views/SnapshotCompareView.vue(Skill 이 아닌 파일 원문)가 부른다.
export const getSnapshotFile = (snapshotId, path) =>
  get(apiPath`/api/snapshots/${snapshotId}/files?path=${path}`)

// 서버가 실행 로더와 같은 규칙으로 파싱한 Skill 메타데이터 목록을 읽는다. 파싱에 실패한 파일은
// items 에서 빠지고 parse_errors 에 남는다. GET /api/snapshots/{id}/skills 는 EvidenceController 의
// skills() 가 받고, 응답은
// order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java 의
// list() 가 만든다. frontend/src/api/skillSets.js 의 loadSkillSet 이 부른다.
export const listSnapshotSkills = (snapshotId) => get(apiPath`/api/snapshots/${snapshotId}/skills`)

// Skill 하나의 메타데이터와 SKILL.md 원문(content)을 읽는다.
// GET /api/snapshots/{id}/skills/{skillId} 는 EvidenceController 의 skill() 이
// 받는다. frontend/src/api/skillSets.js 의 loadSkillSet 이 Skill 마다 부른다.
export const getSnapshotSkill = (snapshotId, skillId) =>
  get(apiPath`/api/snapshots/${snapshotId}/skills/${skillId}`)

// 개발용으로 미리 준비한 Skill 세트 이름이다. SkillOps 가 만든 수정안이 아니다.
// frontend/src/components/SnapshotPicker.vue 가 고정 버튼으로 그린다. 서버가 받는 이름 목록은
// order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의
// DEMO_FIXTURES 다.
export const FIXTURES = ['baseline', 'broken-contract', 'repaired']
// 준비된 fixture 로 새 snapshot 을 만든다. POST /api/snapshots 는 EvidenceController 의
// publish() 가 받고, 서버의 시연 기능(order-agent.demo-enabled)이 꺼져 있으면 404 다.
// frontend/src/stores/workspace.js 의 pin 이 부른다.
export const pinFixture = (fixture) => post('/api/snapshots', { fixture })

// 브라우저 초안을 기준 snapshot 위에 덮어 새 snapshot 으로 저장한다. changes 는 경로마다 수정한
// 전체 원문을 담은 객체다. POST /api/snapshots/edits 는 EvidenceController 의 saveEdits() 가 받아
// 201 과 함께 change_id 와 validation_status QUEUED 를 돌려준다. 기준 hash 가 서버와 다르면
// BASELINE_HASH_MISMATCH 로 거절된다. 저장한 변경은 order-agent 가 service 를 거쳐 진단 Agent 로
// 전달하고, 화면은 change_id 로 frontend/src/views/DiagnosisView.vue 에서 진행을 본다.
// frontend/src/stores/workspace.js 의 saveDrafts 가 부른다. mock 에는 이 경로가 없고 workspace 가
// mock 모드에서 저장을 막는다. 명세는 order-agent/docs/skill-edit-save-api.md 에 있다.
export const saveSkillEdits = (baselineSnapshotId, baselineHash, changes) =>
  post('/api/snapshots/edits', {
    baseline_snapshot_id: baselineSnapshotId,
    baseline_hash: baselineHash,
    changes,
  })
