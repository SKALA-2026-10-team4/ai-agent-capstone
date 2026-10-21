// order-agent REST (order-agent/docs/api.md, agent-evidence.md, diagnosis-read-api.md). 응답 필드는 snake_case 그대로 쓴다.
import { get, post } from './client'
import { ApiError } from './error'

export const listProducts = () => get('/api/products')

export const getOrder = (orderId) => get(`/api/orders/${encodeURIComponent(orderId)}`)

export const getOrderByRequest = (requestId) => get(`/api/orders/by-request/${encodeURIComponent(requestId)}`)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const UNFINISHED = new Set(['CREATED', 'PROCESSING'])

// 응답을 못 받았을 때(시간 초과·연결 끊김) 같은 Idempotency-Key 로 주문을 찾아 끝날 때까지 기다린다.
// 주문이 이미 접수됐을 수 있으므로 새 키로 다시 주문하지 않는다(api.md 5장). 실제 모델 모드는 45초까지 걸릴 수 있다.
async function recover(requestId, deadlineMs = 60000) {
  const until = Date.now() + deadlineMs
  while (Date.now() < until) {
    await sleep(1000)
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

// Idempotency-Key 는 주문마다 새로 만든다. Agent 실행 실패(계약 위반 등)는 500 + order_id 로 오므로
// 그 order_id 로 다시 조회해 FAILED 주문과 execution_id 를 돌려준다.
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

export const listExecutions = () => get('/api/executions')

// 실행 실패(FAILED/INTERRUPTED)만. 재고 부족·결제 거절 같은 업무 거절은 들어가지 않는다.
export const listFailedExecutions = () => get('/api/executions/failed')

export const getTrace = (executionId) =>
  get(`/api/executions/${encodeURIComponent(executionId)}/trace`)

export const getSnapshot = (snapshotId) => get(`/api/snapshots/${encodeURIComponent(snapshotId)}`)

export const getSnapshotFile = (snapshotId, path) =>
  get(`/api/snapshots/${encodeURIComponent(snapshotId)}/files?path=${encodeURIComponent(path)}`)

// 서버가 실행 로더와 같은 규칙으로 파싱한 Skill 메타데이터. 파싱 실패는 items 에서 빠지고 parse_errors 에 남는다.
export const listSnapshotSkills = (snapshotId) => get(`/api/snapshots/${encodeURIComponent(snapshotId)}/skills`)

export const getSnapshotSkill = (snapshotId, skillId) =>
  get(`/api/snapshots/${encodeURIComponent(snapshotId)}/skills/${encodeURIComponent(skillId)}`)

// 개발용 준비 후보. SkillOps 가 만든 수정안이 아니다.
export const FIXTURES = ['baseline', 'broken-contract', 'repaired']
export const pinFixture = (fixture) => post('/api/snapshots', { fixture })
