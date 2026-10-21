<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity } from 'lucide-vue-next'
import { USE_MOCK } from '@/api/client'
import { getChangeDelivery, getDiagnosis, listDiagnoses } from '@/api/diagnoses'
import PageHeader from '@/components/PageHeader.vue'
import { executionPath } from '@/utils/traceRun'

const route = useRoute()
const router = useRouter()
const items = ref([])
const run = ref(null)
const delivery = ref(null)
const error = ref(null)
let timer
let generation = 0
const labels = { BASELINE_FAILED: '기준 주문 검증 불가', VALIDATION_FAILED: '기대 결과 불일치', QUEUED: '검증 대기', RUNNING: '검증·진단 중', VALIDATED: '기본 회귀 검증 통과', EVALUATED: '진단·후보 평가 완료', INTENT_VIOLATION: '변경 의도 위반 발견', ERROR: '검증·진단 오류', NO_SKILL_CHANGES: 'Skill 변경 없음' }
const pending = computed(() => route.query.change_id && !run.value)
const candidates = computed(() => run.value?.proposal?.candidates ?? run.value?.candidates ?? [])
const root = computed(() => run.value?.proposal?.root_cause ?? run.value?.root_cause)
const executionLink = (id) => executionPath(id) ?? `/executions/${id}`
async function refresh(epoch) {
  if (USE_MOCK) return
  try {
    const change = route.query.change_id
    if (route.params.threadId) {
      const result = await getDiagnosis(route.params.threadId)
      if (epoch !== generation) return
      run.value = result
    } else if (change) {
      const result = await getChangeDelivery(change)
      if (epoch !== generation) return
      delivery.value = result
      if (result.thread_id) {
        await router.replace(`/diagnoses/${result.thread_id}`)
        return
      }
    } else {
      const result = await listDiagnoses()
      if (epoch !== generation) return
      items.value = result.items
    }
    error.value = null
  } catch (e) {
    if (epoch === generation) error.value = e.message
  }
  if (epoch === generation && (!run.value || ['QUEUED', 'RUNNING'].includes(run.value.diagnosis_status) || error.value)) {
    timer = setTimeout(() => refresh(epoch), 2000)
  }
}
watch(() => route.fullPath, () => {
  clearTimeout(timer)
  generation++
  run.value = null
  delivery.value = null
  error.value = null
  refresh(generation)
}, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>

<template>
  <PageHeader title="검증·진단 기록" description="저장한 수정본이 실제 주문 흐름에서 어떻게 동작하는지 확인해요." :icon="Activity" />
  <p class="callout">수정본은 새 파일 세트로 저장돼요. 이 세트를 지정한 검증·주문에만 적용되며, 5174의 기본 주문에는 자동 반영되지 않아요.</p>
  <p v-if="USE_MOCK" class="callout">실제 검증·진단 기록은 서버 모드에서 확인할 수 있어요.</p>
  <p v-if="error" class="callout err" role="alert">{{ error }} 잠시 후 다시 조회합니다.</p>
  <section v-if="pending" class="card" role="status">
    <h2>저장 완료 · 자동 검증 요청 전달 대기</h2>
    <p>변경 번호: <code>{{ route.query.change_id }}</code></p>
    <p>주문 Agent가 요청을 전달하면 이 화면에 진행 상태가 나타나요.</p>
    <p v-if="delivery?.last_error">{{ delivery.last_error }} 서버가 자동으로 재전송합니다.</p>
  </section>
  <template v-if="run">
    <RouterLink to="/diagnoses">전체 기록</RouterLink>
    <section class="card stack" role="status">
      <h2>{{ labels[run.diagnosis_status] ?? run.diagnosis_status }}</h2>
      <p>진단 번호: <code>{{ run.thread_id }}</code></p>
      <p>기준 세트: <code>{{ run.baseline_snapshot_id ?? run.trigger.baseline_snapshot_id }}</code></p>
      <p>수정 세트: <code>{{ run.snapshot_id ?? run.trigger.candidate_snapshot_id }}</code></p>
      <p v-if="run.error" class="error-text">{{ run.error }}</p>
      <p v-if="run.diagnosis_status === 'VALIDATED'">등록된 기본 주문 검증을 통과했어요. 자연어 정책 전체의 검증이나 운영 반영을 의미하지 않아요.</p>
      <p v-if="run.proposal?.storage_status === 'NOT_SAVED'">평가 결과를 확인할 수 있어요. 제안 승인·운영 반영은 아직 연결되지 않았어요.</p>
      <p v-if="run.proposal?.storage_status === 'SAVED'">제안서를 저장했어요. 운영자가 검토하면 승인 여부가 정해져요.</p>
      <RouterLink v-if="run.proposal?.id" :to="`/proposals/${run.proposal.id}`">제안서 보기</RouterLink>
    </section>
    <section class="card">
      <h2>진행 로그</h2>
      <ol><li v-for="(event, i) in run.events" :key="i"><time>{{ event.at }}</time> · {{ event.msg }}</li></ol>
    </section>
    <section v-if="root?.skill" class="card"><h2>원인 실험 결과</h2><p>{{ root.skill }}: {{ root.summary }}</p>
      <RouterLink v-for="id in root.proof_run_ids" :key="id" :to="executionLink(id)">{{ id }} </RouterLink>
    </section>
    <section v-if="run.diagnosis" class="card">
      <h2>변경 의도 검증</h2><p>{{ run.diagnosis.explanation }}</p>
      <p v-for="(failure, i) in run.diagnosis.failures" :key="i">{{ failure.reason }} <RouterLink :to="executionLink(failure.run_id)">실행 기록</RouterLink></p>
    </section>
    <section v-for="candidate in candidates" :key="candidate.candidate_no" class="card">
      <h2>후보 {{ candidate.candidate_no }} · {{ candidate.valid === true ? '검증 통과' : candidate.valid === false ? '검증 미통과' : '검증 미완료' }}</h2>
      <p v-if="candidate.valid == null">생성된 수정안입니다. 검증이 완료되지 않아 적용 가능한 수정안으로 확정되지 않았어요.</p>
      <p>{{ candidate.explanation }}</p>
      <p v-if="candidate.validation_error" class="error-text">{{ candidate.validation_error }}</p>
      <p v-if="candidate.results">실패 해결: {{ candidate.results.fixes_failure }} · 회귀 {{ candidate.results.keeps_old_orders.passed }}/{{ candidate.results.keeps_old_orders.total }} · 의도 보존: {{ candidate.results.keeps_intent }} · 계약: {{ candidate.results.no_broken_links }}</p>
      <details v-for="patch in candidate.patches" :key="patch.skill"><summary>{{ patch.skill }} 수정 내용</summary><pre>{{ patch.patch }}</pre></details>
      <details><summary>실행 증거</summary><p v-for="id in candidate.evidence_run_ids" :key="id"><RouterLink :to="executionLink(id)">{{ id }}</RouterLink></p></details>
    </section>
    <section v-if="run.preflight_run_ids?.length" class="card"><h2>저장 직후 검증 기록</h2><p v-for="id in run.preflight_run_ids" :key="id"><RouterLink :to="executionLink(id)">{{ id }}</RouterLink></p></section>
  </template>
  <section v-else-if="!pending && !USE_MOCK" class="stack">
    <p v-if="!items.length">아직 진단 기록이 없어요. Skill 편집에서 수정본을 저장하면 자동으로 시작돼요.</p>
    <RouterLink v-for="item in items" :key="item.thread_id" class="card" :to="`/diagnoses/${item.thread_id}`">
      {{ labels[item.diagnosis_status] ?? item.diagnosis_status }} · {{ item.created_at }}
      <p>수정본 {{ item.trigger.candidate_snapshot_id }} · {{ item.thread_id }}</p>
    </RouterLink>
  </section>
</template>

<style scoped>
.card { margin: 14px 0; }
p, code { overflow-wrap: anywhere; }
li { margin-bottom: 10px; }
time { color: var(--muted); font-size: .8rem; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
