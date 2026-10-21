<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { createOrder, listFailedExecutions, listProducts } from '@/api/orderAgent'
import { useWorkspaceStore } from '@/stores/workspace'
import { executionLabel } from '@/utils/traceRun'
import { PlayCircle } from 'lucide-vue-next'
import PageHeader from '@/components/PageHeader.vue'
import SnapshotPicker from '@/components/SnapshotPicker.vue'
import StatusBadge from '@/components/StatusBadge.vue'

// 주문 실행. order-agent 의 실제 Harness 가 고른 snapshot 의 Skill 로 주문을 처리하고 trace 를 남긴다.
// 프리셋은 order-agent/docs/team-scenarios.md 의 시나리오 1·2.
const ws = useWorkspaceStore()

const products = ref([])
const form = reactive({
  customer: 'c_1001',
  sku: 'CAMP-LANTERN-LED',
  qty: 1,
  payment_method: 'card',
  country: 'KR',
  express: false,
})
const usePinned = ref(true)
const result = ref(null)
const busy = ref(false)
const error = ref(null)

const PRESETS = [
  { label: '정상 주문', customer: 'c_1001', sku: 'CAMP-LANTERN-LED', payment_method: 'card' },
  { label: '재고 부족', customer: 'c_1001', sku: 'CAMP-MUG-STEEL', payment_method: 'card' },
  { label: '결제 거절', customer: 'c_declined', sku: 'CAMP-LANTERN-LED', payment_method: 'card' },
  { label: '거절 고객 · 선입금', customer: 'c_declined', sku: 'CAMP-LANTERN-LED', payment_method: 'prepaid' },
]

const product = computed(() => products.value.find((p) => p.sku === form.sku))
const snapshotForOrder = computed(() => (usePinned.value ? ws.snapshotId : null))
const won = (n) => `${n.toLocaleString()}원`

// 최근 실행(최대 20건) 또는 실행 실패만(GET /executions/failed — 재고 부족·결제 거절 같은 업무 거절은 빠진다)
const listMode = ref('recent')
const failed = ref([])
const shownRuns = computed(() => (listMode.value === 'failed' ? failed.value : ws.executions))

async function showFailed() {
  listMode.value = 'failed'
  try {
    failed.value = (await listFailedExecutions()).items
  } catch (e) {
    error.value = e.message
  }
}

const runLabel = (x) =>
  x.error ? `${executionLabel({ ...x, error_category: x.error.category })} · ${x.error.skill_id ?? ''}` : executionLabel(x)

function preset(p) {
  Object.assign(form, { customer: p.customer, sku: p.sku, payment_method: p.payment_method, qty: 1 })
  result.value = null
}

async function loadProducts() {
  try {
    products.value = (await listProducts()).items
  } catch (e) {
    error.value = e.message
  }
}

async function submit() {
  busy.value = true
  error.value = null
  result.value = null
  try {
    result.value = await createOrder(
      {
        customer: form.customer,
        items: [{ sku: form.sku, qty: Number(form.qty) }],
        payment_method: form.payment_method,
        shipping: { country: form.country, express: form.express },
      },
      { snapshotId: snapshotForOrder.value },
    )
    await Promise.all([ws.refreshExecutions(), loadProducts()])
  } catch (e) {
    error.value = e.fieldErrors?.length ? e.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ') : e.message
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  loadProducts()
  ws.refreshExecutions()
})
</script>

<template>
  <PageHeader
    title="주문 실행 · 기록"
    description="고른 Skill 세트로 실제 주문 Agent 를 돌려 보고, 실행 기록에서 단계별 결과를 확인해요."
    :icon="PlayCircle"
  />
  <div class="card toolbar">
    <SnapshotPicker />
  </div>

  <div class="grid">
    <section class="card">
      <h2>새 주문</h2>
      <p class="muted small">외부 결제·배송이 발생하지 않는 데모 주문이다. 재고는 실제로 줄어든다.</p>
      <div class="presets">
        <button v-for="p in PRESETS" :key="p.label" @click="preset(p)">{{ p.label }}</button>
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
          <code v-if="ws.snapshotId">{{ ws.labelOf(ws.snapshotId) }}</code>
        </label>
        <label class="check">
          <input v-model="usePinned" type="radio" :value="false" />
          현재 게시본을 새로 고정해 실행
        </label>
      </div>

      <p v-if="product && form.qty > product.stock_quantity" class="warn-text">
        재고({{ product.stock_quantity }})보다 많다 — 정상적인 REJECTED 가 나온다.
      </p>
      <p v-if="error" class="error-text">{{ error }}</p>
      <button class="primary" :disabled="busy" @click="submit">{{ busy ? '실행 중…' : '주문 실행' }}</button>
    </section>

    <section class="card">
      <template v-if="result">
        <div class="head">
          <h2>결과</h2>
          <StatusBadge :status="result.status" />
          <code v-if="result.result_code">{{ result.result_code }}</code>
        </div>
        <p>{{ result.message }}</p>
        <dl>
          <dt>주문</dt>
          <dd><code>{{ result.order_id }}</code> · {{ won(result.total_amount) }}</dd>
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
          실행 trace 보기 →
        </RouterLink>
      </template>

      <div class="list-head" :class="{ spaced: result }">
        <h2>실행 기록</h2>
        <div class="tabs">
          <button class="ghost sm" :class="{ on: listMode === 'recent' }" @click="listMode = 'recent'">최근</button>
          <button class="ghost sm" :class="{ on: listMode === 'failed' }" @click="showFailed">실행 실패만</button>
        </div>
      </div>
      <p v-if="!shownRuns.length" class="muted">
        {{ listMode === 'failed' ? '실행이 실패한 주문이 없어요. (재고 부족·결제 거절은 정상 업무 결과라 여기 없어요)' : '아직 실행 기록이 없어요.' }}
      </p>
      <ul class="recent">
        <li v-for="x in shownRuns" :key="x.execution_id">
          <RouterLink :to="`/executions/${x.execution_id}`">{{ runLabel(x) }}</RouterLink>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.toolbar {
  padding: 10px 16px;
  margin-bottom: 12px;
}

.grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 800px) {
  .grid {
    grid-template-columns: 1fr;
  }
}

.small {
  font-size: 12px;
}

.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-weight: 600;
}

label.check {
  flex-direction: row;
  align-items: center;
  font-weight: 400;
}

input:not([type='checkbox'], [type='radio']) {
  font: inherit;
  padding: 4px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  color: var(--text);
}

.snap {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background: var(--bg);
  border-radius: 6px;
}

.warn-text {
  color: var(--warn);
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.head h2 {
  margin: 0;
}

dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 4px 12px;
}

dt {
  color: var(--muted);
}

dd {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
}

.steps {
  margin: 8px 0;
  padding-left: 20px;
}

.steps li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
}

.go {
  font-weight: 600;
}

.spaced {
  margin-top: 20px;
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.list-head h2 {
  margin: 0;
}

.tabs {
  display: flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
}

.tabs .on {
  background: var(--surface);
  color: var(--accent);
  box-shadow: var(--shadow-sm);
}

.recent {
  list-style: none;
  margin: 0;
  padding: 0;
}

.recent li {
  padding: 4px 0;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}
</style>
