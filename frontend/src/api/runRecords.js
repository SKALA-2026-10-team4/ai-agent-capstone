// 실행 기록(trace 요약)과 Skill 세트 정보(snapshot manifest)를 읽어 캐시하는 모듈이다.
// frontend/src/components/RunRecordList.vue 가 실행 번호마다 loadRunRecord 를, frontend/src/components/SnapshotLabel.vue
// 가 세트 이름을 정하려고 loadSnapshotManifest 를 부른다. 한 화면에 실행 번호가 여러 개 있어도 같은 실행은
// 한 번만 읽고, 동시에 보내는 요청은 frontend/src/config.js 의 RUN_RECORD_CONCURRENCY 개로 막는다. 캐시는
// 모듈 안에만 있으므로 새로고침하면 비워진다. 요약 규칙은 frontend/src/utils/runRecord.js 에 있다.
// trace 는 frontend/src/api/orderAgent.js 의 getTrace(GET /api/executions/{id}/trace)로, 세트 정보는
// getSnapshot(GET /api/snapshots/{id})으로, 상품 이름은 listProducts(GET /api/products)로 읽는다.
import { getSnapshot, getTrace, listProducts } from './orderAgent'
import { RUN_RECORD_CONCURRENCY } from '@/config'
import { createCache, createLimiter, executionIdOf, summarizeTrace } from '@/utils/runRecord'

const limit = createLimiter(RUN_RECORD_CONCURRENCY)
const traces = createCache((executionId) => limit(() => getTrace(executionId)))
const snapshots = createCache((snapshotId) => limit(() => getSnapshot(snapshotId)))

// sku 마다 상품 이름 { sku: name } 이다. 처음 한 번만 읽고, 읽지 못하면 빈 표로 두어 sku 를 그대로 쓴다.
let productNames = null
function loadProductNames() {
  productNames ??= listProducts()
    .then((r) => Object.fromEntries(r.items.map((p) => [p.sku, p.name])))
    .catch(() => {
      productNames = null
      return {}
    })
  return productNames
}

/**
 * 실행 번호(run_id 나 execution_id) 하나를 실행 기록 요약으로 읽는다. 모양은 frontend/src/utils/runRecord.js 의
 * summarizeTrace 결과다. 실행 번호 모양이 아니거나 trace 를 읽지 못하면 오류를 던진다. 아직 끝나지 않은
 * 실행(RUNNING)은 캐시에 남기지 않아 다음에 다시 읽는다.
 */
export async function loadRunRecord(ref) {
  const executionId = executionIdOf(ref)
  if (!executionId) throw new Error('실행 번호 모양이 아니에요.')
  const [trace, products] = await Promise.all([traces.get(executionId), loadProductNames()])
  if (trace.completion === 'RUNNING') traces.forget(executionId)
  return summarizeTrace(trace, { products })
}

// Skill 세트 하나의 manifest { snapshot_id, created_at, metadata, … } 를 한 번만 읽는다. 실패하면 오류를 던진다.
export const loadSnapshotManifest = (snapshotId) => snapshots.get(snapshotId)

// sku 마다 상품 이름 표 { sku: name } 를 돌려준다. frontend/src/views/ExecutionView.vue 가 주문 요약에 쓴다.
export const loadProducts = () => loadProductNames()
