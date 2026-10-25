<script setup>
// 주문 실행과 실행 기록 화면이다. frontend/src/router/index.js 가 /orders 경로에 연결한다.
// order-agent 의 실제 Harness 가 고른 snapshot 의 Skill 로 주문을 처리하고 trace 를 남긴다.
// 주문은 frontend/src/api/orderAgent.js 의 createOrder(POST /api/orders)로 보내고
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 의
// create() 가 받는다. createOrder 는 주문마다 새 Idempotency-Key 를 만들고,
// 응답을 받지 못하면 같은 키로 결과를 다시 조회한다(간격과 마감은 frontend/src/config.js 의
// ORDER_RECOVERY). 위쪽 frontend/src/components/SnapshotPicker.vue 에서 고른 세트로 실행하거나,
// snapshot_id 없이 보내 order-agent 가 현재 게시본을 새 snapshot 으로 고정해 실행하게 할 수 있다.
// 그 고정은 OrderHarness 가 SnapshotService 의 capture() 로 한다. 두 클래스는
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 와
// order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 다.
// 상품 선택지는 orderAgent.js 의 listProducts(GET /api/products)로 읽고,
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ProductController.java 의
// list() 가 돌려준다.
// 실행 기록은 두 탭으로 보여 준다. '최근' 탭은 GET /api/executions 가 주는 최근 20건으로,
// frontend/src/stores/workspace.js 의 executions 에 들어 있다. '실행 실패만' 탭은 orderAgent.js 의
// listFailedExecutions 로 읽는 GET /api/executions/failed 목록이고,
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
// failed() 가 돌려준다. 목록은 frontend/src/components/RunRecordList.vue 가 실행 번호 대신 시각, 결과,
// 주문 요약, Skill 세트로 그리고, 줄을 누르면 frontend/src/views/ExecutionView.vue 의 실행 상세 화면으로
// 간다. 주문 결과도 주문 번호 대신 주문 요약(frontend/src/utils/runRecord.js 의 orderSummary)으로 보여 준다.
// 프리셋은 order-agent/docs/team-scenarios.md 의 시나리오 1과 2이며, 기본 폼(form)과 함께
// 이 파일 위쪽의 PRESETS 에서 고친다.
import { computed, onMounted, reactive, ref } from 'vue'
import { createOrder, listFailedExecutions, listProducts } from '@/api/orderAgent'
import { useWorkspaceStore } from '@/stores/workspace'
import { won } from '@/utils/format'
import { orderSummary } from '@/utils/runRecord'
import { ArrowRight } from 'lucide-vue-next'
import PageHeader from '@/components/PageHeader.vue'
import RunRecordList from '@/components/RunRecordList.vue'
import SnapshotPicker from '@/components/SnapshotPicker.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const ws = useWorkspaceStore()

// 상품 선택지다. ProductController 의 list() 가 주는 이름, 단가, 재고를 보여 준다.
const products = ref([])
// 주문 폼의 기본값이다(시나리오 1의 정상 주문).
const form = reactive({
  customer: 'c_1001',
  sku: 'CAMP-LANTERN-LED',
  qty: 1,
  payment_method: 'card',
  country: 'KR',
  express: false,
})
// true 면 위에서 고른 snapshot 으로 실행하고,
// false 면 snapshot_id 없이 보내 현재 게시본을 새로 고정해 실행한다.
const usePinned = ref(true)
// 마지막 주문 응답이다. 모양은
// order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java 와 같다.
const result = ref(null)
const busy = ref(false)
const error = ref(null)

// 시연용 입력 예시다. 정상 주문, 재고가 0인 상품, 결제가 거절되는 고객, 같은 고객의 선입금 주문이며
// 모두 order-agent/docs/team-scenarios.md 의 시나리오 1과 2에서 왔다.
const PRESETS = [
  { label: '정상 주문', customer: 'c_1001', sku: 'CAMP-LANTERN-LED', payment_method: 'card' },
  { label: '재고 부족', customer: 'c_1001', sku: 'CAMP-MUG-STEEL', payment_method: 'card' },
  { label: '결제 거절', customer: 'c_declined', sku: 'CAMP-LANTERN-LED', payment_method: 'card' },
  { label: '거절 고객 · 선입금', customer: 'c_declined', sku: 'CAMP-LANTERN-LED', payment_method: 'prepaid' },
]

// 고른 상품이다. 수량이 재고보다 많으면 정상적인 REJECTED 가 나온다는 안내에 쓴다.
const product = computed(() => products.value.find((p) => p.sku === form.sku))
// 주문에 실을 snapshot_id 다. null 이면 order-agent 가 현재 게시본을 새로 고정한다.
const snapshotForOrder = computed(() => (usePinned.value ? ws.snapshotId : null))

// 실행 기록 탭이다. 'recent' 는 최근 실행(최대 20건)이고, 'failed' 는 실행 실패만 모은 목록이다.
// 실행 실패 목록(GET /api/executions/failed)에는 재고 부족이나 결제 거절 같은
// 업무 거절이 들어가지 않는다.
const listMode = ref('recent')
const failed = ref([])
const shownRuns = computed(() => (listMode.value === 'failed' ? failed.value : ws.executions))
// 실행 기록 목록에 넘길 실행 번호들이다.
const shownRefs = computed(() => shownRuns.value.map((x) => x.execution_id))
// 마지막 주문 결과의 요약('LED 캠핑 랜턴 1개 · 카드 · 국내 일반 배송')이다. 주문 번호 대신 보여 준다.
const resultSummary = computed(() => (result.value ? orderSummary(result.value) : null))

/**
 * '실행 실패만' 탭으로 바꾸고 frontend/src/api/orderAgent.js 의 listFailedExecutions 로
 * 목록을 읽는다. EvidenceController 의 failed() 가 답하며, 탭을 누를 때마다 다시 읽는다.
 */
async function showFailed() {
  listMode.value = 'failed'
  try {
    failed.value = (await listFailedExecutions()).items
  } catch (e) {
    error.value = e.message
  }
}

/**
 * 입력 예시 하나를 폼에 채우고 이전 결과를 지운다. 입력 예시 버튼이 부른다.
 * 수량은 1로 되돌리고 배송 칸은 그대로 둔다.
 */
function preset(p) {
  Object.assign(form, { customer: p.customer, sku: p.sku, payment_method: p.payment_method, qty: 1 })
  result.value = null
}

/**
 * 상품 목록을 읽는다(GET /api/products). 화면을 열 때와 주문을 마친 뒤 부르므로
 * 주문으로 줄어든 재고가 선택지에 바로 보인다.
 */
async function loadProducts() {
  try {
    products.value = (await listProducts()).items
  } catch (e) {
    error.value = e.message
  }
}

/**
 * 폼 값을 POST /api/orders 의 주문 요청 본문으로 바꾼다. submit 이 부른다. 본문 모양은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java 다.
 * 상품은 한 가지만 담고, 수량은 숫자로 바꿔 보낸다.
 */
function toOrderRequest(f) {
  return {
    customer: f.customer,
    items: [{ sku: f.sku, qty: Number(f.qty) }],
    payment_method: f.payment_method,
    shipping: { country: f.country, express: f.express },
  }
}

/**
 * 주문 오류를 화면 문구로 바꾼다. submit 이 createOrder 의 오류를 받으면 부른다.
 * 입력 오류(field_errors)가 있으면 'field: message' 를 ' · ' 로 잇고, 없으면 오류 문구를
 * 그대로 쓴다. fieldErrors 는 frontend/src/api/client.js 가 오류 본문의 field_errors 를 옮겨
 * 담은 값이다.
 */
const orderErrorMessage = (e) =>
  e.fieldErrors?.length ? e.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ') : e.message

/**
 * 폼으로 주문을 실행한다. '주문 실행' 버튼이 부른다.
 * createOrder 가 결과를 끝까지 확인해 돌려주면 결과 칸에 보여 주고,
 * 최근 실행 목록과 상품 재고를 다시 읽는다.
 * Agent 실행이 실패한 주문도 createOrder 가 다시 조회한 FAILED 주문으로 돌려주므로 오류가 아니라
 * 결과로 보이고, 실행 trace 링크로 원인을 확인할 수 있다. 입력 오류나 결과를 끝내 확인하지 못한
 * 경우처럼 createOrder 가 던진 오류는 오류 문구로 보여 준다.
 */
async function submit() {
  busy.value = true
  error.value = null
  result.value = null
  try {
    result.value = await createOrder(toOrderRequest(form), { snapshotId: snapshotForOrder.value })
    await Promise.all([ws.refreshExecutions(), loadProducts()])
  } catch (e) {
    error.value = orderErrorMessage(e)
  } finally {
    busy.value = false
  }
}

// 화면을 열 때 상품 목록과 최근 실행 목록을 함께 읽는다.
onMounted(() => {
  loadProducts()
  ws.refreshExecutions()
})
</script>

<template>
  <PageHeader
    title="주문 실행 · 기록"
    description="고른 Skill 세트로 실제 주문 Agent 를 돌려 보고, 실행 기록에서 단계별 결과를 확인해요."
  />
  <div class="card toolbar">
    <SnapshotPicker />
  </div>

  <div class="grid">
    <section class="card">
      <h2><span class="sec-no">01</span> 새 주문</h2>
      <p class="muted small">외부 결제·배송이 발생하지 않는 데모 주문이에요. 재고는 실제로 줄어들어요.</p>
      <div class="presets">
        <button v-for="p in PRESETS" :key="p.label" class="sm" @click="preset(p)">{{ p.label }}</button>
      </div>

      <div class="form">
        <label>고객 <input v-model.trim="form.customer" /></label>
        <label>
          상품
          <select v-model="form.sku">
            <option v-for="p in products" :key="p.sku" :value="p.sku">
              {{ p.name }} · {{ won(p.unit_price) }} · 재고 {{ p.stock_quantity }}
            </option>
          </select>
        </label>
        <label>수량 <input v-model.number="form.qty" type="number" min="1" max="99" /></label>
        <label>
          결제
          <select v-model="form.payment_method">
            <option value="card">card</option>
            <option value="prepaid">prepaid</option>
          </select>
        </label>
        <label>
          배송 국가
          <select v-model="form.country">
            <option>KR</option>
            <option>US</option>
            <option>JP</option>
          </select>
        </label>
        <label class="check"><input v-model="form.express" type="checkbox" /> 빠른 배송</label>
      </div>

      <div class="snap">
        <label class="check">
          <input v-model="usePinned" type="radio" :value="true" :disabled="!ws.snapshotId" />
          위에서 고른 Skill 세트로 실행
          <strong v-if="ws.snapshotId" class="set">{{ ws.labelOf(ws.snapshotId) }}</strong>
        </label>
        <label class="check">
          <input v-model="usePinned" type="radio" :value="false" />
          현재 게시본을 새로 고정해 실행
        </label>
      </div>

      <p v-if="product && form.qty > product.stock_quantity" class="warn-text">
        재고({{ product.stock_quantity }})보다 많아요 — 정상적인 REJECTED 가 나와요.
      </p>
      <p v-if="error" class="error-text">{{ error }}</p>
      <button class="primary lg" :disabled="busy" @click="submit">{{ busy ? '실행 중…' : '주문 실행' }}</button>
    </section>

    <section class="card">
      <template v-if="result">
        <div class="head">
          <h2><span class="sec-no">02</span> 결과</h2>
          <StatusBadge :status="result.status" />
          <code v-if="result.result_code">{{ result.result_code }}</code>
        </div>
        <p class="message">{{ result.message }}</p>
        <dl>
          <dt>주문</dt>
          <dd :title="`주문 ID ${result.order_id}`">{{ resultSummary ?? '주문' }} · {{ won(result.total_amount) }}</dd>
          <dt>결제</dt>
          <dd><StatusBadge :status="result.payment.status" /></dd>
          <dt>배송</dt>
          <dd>{{ result.shipment?.estimated_delivery_date ?? '—' }}</dd>
          <dt>알림</dt>
          <dd><StatusBadge :status="result.notification.status" /></dd>
        </dl>
        <ol class="steps">
          <li v-for="s in result.steps" :key="s.sequence">
            <span>{{ s.step }}</span>
            <StatusBadge :status="s.status" />
            <span class="muted">{{ s.result_code ?? '' }} {{ s.message }}</span>
          </li>
        </ol>
        <RouterLink v-if="result.execution_id" :to="`/executions/${result.execution_id}`" class="go">
          실행 기록 자세히 보기 <ArrowRight :size="14" />
        </RouterLink>
      </template>

      <div class="list-head" :class="{ spaced: result }">
        <h2><span class="sec-no">{{ result ? '03' : '02' }}</span> 실행 기록</h2>
        <div class="seg" role="tablist">
          <button role="tab" :aria-selected="listMode === 'recent'" :class="{ on: listMode === 'recent' }" @click="listMode = 'recent'">최근</button>
          <button role="tab" :aria-selected="listMode === 'failed'" :class="{ on: listMode === 'failed' }" @click="showFailed">실행 실패만</button>
        </div>
      </div>
      <p v-if="!shownRuns.length" class="muted small">
        {{ listMode === 'failed' ? '실행이 실패한 주문이 없어요. (재고 부족·결제 거절은 정상 업무 결과라 여기 없어요)' : '아직 실행 기록이 없어요.' }}
      </p>
      <RunRecordList v-else :key="listMode" :refs="shownRefs" :limit="6" highlight="first" />
    </section>
  </div>
</template>

<style scoped>
.toolbar {
  padding: 14px 20px;
  margin-bottom: 16px;
}

.grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 900px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

h2 {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 14px 0 16px;
}

.form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-2);
}

label.check {
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-weight: 400;
}

.snap {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  margin-bottom: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: rgb(0 0 0 / 12%);
}

.snap .set {
  color: var(--text);
  font-weight: 600;
}

.head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
}

.head h2 {
  margin: 0;
}

.message {
  margin: 10px 0 12px;
  color: var(--text-2);
}

dl {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  margin: 0 0 12px;
  border-top: 1px solid var(--border);
}

dt,
dd {
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
}

dt {
  padding-right: 24px;
  color: var(--muted);
}

dd {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
}

.steps {
  margin: 8px 0 12px;
  padding-left: 0;
  list-style: none;
}

.steps li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: var(--fs-sm);
}

.steps li > span:first-child {
  min-width: 96px;
  font-family: var(--mono);
  font-size: 12px;
  color: var(--text-2);
}

.go {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
}

.spaced {
  margin-top: 26px;
  padding-top: 22px;
  border-top: 1px solid var(--border);
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.list-head h2 {
  margin: 0;
}
</style>
