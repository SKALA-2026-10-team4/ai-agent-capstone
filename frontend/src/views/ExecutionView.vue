<script setup>
// 주문 Agent 실행 한 건의 증거 화면이다.
// frontend/src/router/index.js 가 /executions/:id 경로에 연결한다.
// 실행 결과와 주문 요약, 실행 시각, 실행에 쓴 Skill 세트, 오류 상세, 실행 당시 snapshot 으로 그린 단계 흐름,
// 시간 순서의 이벤트를 보여 준다. order-agent/docs/team-scenarios.md 의 "담당자 간 전달할 증거"(실행, 주문,
// snapshot, 오류 상세, 최종 상태)를 모두 담되, 원래 id(execution_id, run_id, order_id, snapshot_id)는 글로 찍지
// 않고 맨 아래 frontend/src/components/DevInfo.vue 의 '개발자용 정보' 에 접어 둔다(복사 가능).
// 이 화면을 여는 곳은 frontend/src/views/OrderView.vue 의 결과와 실행 기록, 그리고
// frontend/src/components/RunRecordList.vue 의 실행 기록 줄이다. 실행 번호 주소는
// frontend/src/utils/traceRun.js 의 executionPath 가 만든다.
// trace 는 frontend/src/api/orderAgent.js 의 getTrace 로 읽는다. GET /api/executions/{id}/trace 는
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
// trace() 가 받는다. 실행에 쓴 Skill 세트는 frontend/src/stores/workspace.js 의 ensureSet 으로
// 읽는다. 결과 문장과 주문 요약은 frontend/src/utils/runRecord.js 의 summarizeTrace 가, 상품 이름은
// frontend/src/api/runRecords.js 의 loadProducts 가 준다.
// 단계 상태 계산은 frontend/src/utils/traceRun.js 의 traceRun, 상태 이름은 같은 파일의 RUN_LABEL,
// 상태 아이콘은 이 파일의 STEP_ICON 에서 고친다. 이벤트 목록은
// frontend/src/components/EventTimeline.vue 가 그린다.
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getTrace } from '@/api/orderAgent'
import { loadProducts } from '@/api/runRecords'
import { useWorkspaceStore } from '@/stores/workspace'
import { stampOf } from '@/utils/format'
import { failureLabel, summarizeTrace } from '@/utils/runRecord'
import { flowSteps } from '@/utils/skillSet'
import { RUN_LABEL, traceRun } from '@/utils/traceRun'
import DevInfo from '@/components/DevInfo.vue'
import EventTimeline from '@/components/EventTimeline.vue'
import {
  ChevronRight,
  CircleAlert,
  CircleCheck,
  CircleDashed,
  CirclePause,
  CircleX,
  LoaderCircle,
} from 'lucide-vue-next'
import PageHeader from '@/components/PageHeader.vue'
import SnapshotLabel from '@/components/SnapshotLabel.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const route = useRoute()
const ws = useWorkspaceStore()

const trace = ref(null)
// sku 마다 상품 이름이다. 주문 요약에 쓰고, 읽지 못하면 빈 표라 sku 를 그대로 쓴다.
const products = ref({})
// 실행에 쓴 snapshot 의 Skill 세트다. 파일 고정 전에 실패한 실행이면 null 이다.
const set = ref(null)
// 단계 버튼으로 고른 skill_id 다. EventTimeline 에 넘겨 그 단계의 이벤트만 보게 한다.
const selected = ref(null)
const error = ref(null)

// 단계 순서다. 흐름 계약 검사를 통과하면 FLOW_VALIDATED 이벤트의 steps 가 실제 실행 순서다.
// 정적 검사에서 막히면 그 이벤트가 없으므로 실행에 쓴 snapshot 의 order-flow 에서 순서를 읽는다
// (frontend/src/utils/skillSet.js 의 flowSteps). 세트도 읽지 못했으면 빈 목록이다.
const steps = computed(() => {
  const validated = trace.value?.events.find((e) => e.kind === 'FLOW_VALIDATED')
  return validated?.data.steps ?? (set.value ? flowSteps(set.value.skills) : [])
})
// run 은 trace 를 단계별 상태(bySkill)와 실행 오류(error)로 계산한 결과다.
// failure 는 실행 오류 상자에 보일 EXECUTION_ERROR 이벤트의 data 다.
const run = computed(() => (trace.value ? traceRun(trace.value, steps.value) : null))
const failure = computed(() => run.value?.error ?? null)
// 담당자가 읽는 요약(결과 문장, 주문 요약, 시각)이다.
const summary = computed(() => (trace.value ? summarizeTrace(trace.value, { products: products.value }) : null))
// 머리 아래 한 줄 설명이다. 주문 요약과 실행 시각을 잇는다.
const headline = computed(() => {
  if (!summary.value) return '주문 Agent 가 어떤 Skill 파일로 무엇을 했는지, 어디서 왜 멈췄는지 남긴 기록이에요.'
  const when = stampOf(trace.value.started_at, { seconds: true })
  return [summary.value.order, when && `${when} 실행`].filter(Boolean).join(' — ')
})
// 개발자용 정보에 접어 둘 원래 id 들이다.
const devRows = computed(() => {
  const t = trace.value
  if (!t) return [{ label: '요청한 실행 번호', value: route.params.id }]
  return [
    { label: 'execution_id', value: t.execution_id },
    { label: 'run_id (trace 조회 별칭)', value: t.run_id },
    { label: 'order_id', value: t.order_id },
    { label: 'snapshot_id', value: t.snapshot_id },
  ]
})

// 실행 번호가 바뀔 때마다(처음 포함) 화면 값을 비우고 trace 를 새로 읽는다. trace 에 snapshot_id 가
// 있으면 그 세트도 읽어 단계 순서에 쓴다. 상품 이름은 따로 읽어 오는 대로 주문 요약에 반영한다.
watch(
  () => route.params.id,
  async (id) => {
    trace.value = null
    set.value = null
    error.value = null
    selected.value = null
    loadProducts().then((p) => (products.value = p))
    try {
      trace.value = await getTrace(id)
      if (trace.value.snapshot_id) set.value = await ws.ensureSet(trace.value.snapshot_id)
    } catch (e) {
      error.value = e
    }
  },
  { immediate: true },
)

// 단계 상태별 아이콘이다.
// 상태 값은 traceRun 이 정하는 ok, stopped, error, running, blocked, skipped 다.
const STEP_ICON = {
  ok: CircleCheck,
  stopped: CirclePause,
  error: CircleX,
  running: LoaderCircle,
  blocked: CircleDashed,
  skipped: CircleDashed,
}
/**
 * 단계 하나의 상태를 돌려준다. 계산 결과에 없는 단계는 skipped 로 본다.
 * template 의 단계 버튼이 색, 아이콘(STEP_ICON), 상태 이름(RUN_LABEL)을 고를 때 쓴다.
 */
const statusOf = (id) => run.value?.bySkill[id]?.status ?? 'skipped'
</script>

<template>
  <PageHeader
    :title="summary ? summary.outcome : '실행 기록'"
    :description="headline"
    :back="{ to: '/orders', label: '주문 실행 · 기록' }"
  >
    <template v-if="trace" #badges>
      <StatusBadge :status="trace.final_status ?? trace.completion" />
    </template>
  </PageHeader>

  <div v-if="error" class="callout err" role="alert">
    <CircleAlert :size="18" />
    <p>
      <strong>실행 기록을 찾지 못했어요.</strong> 지난 기록이 지워졌거나 다른 서버의 실행일 수 있어요.
      <span class="muted small">({{ error.code ?? error.message }})</span>
    </p>
  </div>

  <template v-if="trace">
    <section class="card info">
      <div class="head">
        <h2><span class="sec-no">01</span> 실행 정보</h2>
        <StatusBadge v-if="trace.final_status && trace.final_status !== trace.completion" :status="trace.completion" />
      </div>
      <dl>
        <dt>결과</dt>
        <dd class="strong" :class="summary.tone">{{ summary.outcome }}</dd>
        <dt>주문</dt>
        <dd>{{ summary.order ?? '주문 입력 기록 없음' }}</dd>
        <dt>실행 시각</dt>
        <dd>{{ stampOf(trace.started_at, { seconds: true }) }} <span class="muted">· {{ trace.elapsed_ms }} ms 걸림</span></dd>
        <dt>Skill 세트</dt>
        <dd>
          <SnapshotLabel v-if="trace.snapshot_id" :id="trace.snapshot_id" />
          <span v-else class="muted">파일 고정 전에 실패해 세트가 없어요.</span>
        </dd>
        <dt>실행 방식</dt>
        <dd>{{ trace.mode }} · {{ trace.model_mode }} ({{ trace.model }}) · 선택 {{ trace.raw_selection }} → {{ trace.resolved_skill_id ?? '—' }}</dd>
        <dt>비용</dt>
        <dd>API {{ trace.api_calls }}회 · 토큰 {{ trace.input_tokens ?? '미관측' }}/{{ trace.output_tokens ?? '미관측' }}</dd>
      </dl>
    </section>

    <section v-if="failure" class="card failure">
      <h2><span class="sec-no">!</span> 실행 오류 · {{ failureLabel(failure) }} <code class="cat">{{ failure.category }}</code></h2>
      <dl>
        <template v-if="failure.skill_id">
          <dt>실패 Skill</dt>
          <dd><code>{{ failure.skill_id }}</code></dd>
        </template>
        <template v-if="failure.phase">
          <dt>검사 단계</dt>
          <dd>
            {{ failure.phase === 'static_validation' ? '실행 전 정적 검사' : '실행 중 입력 검사' }}
            <span class="muted">{{ failure.phase === 'static_validation' ? '— 업무 도구 실행 전에 막혔어요' : '' }}</span>
          </dd>
        </template>
        <template v-if="failure.missing_inputs?.length">
          <dt>못 받은 입력</dt>
          <dd><span v-for="k in failure.missing_inputs" :key="k" class="chip miss">{{ k }}</span></dd>
        </template>
        <template v-if="failure.missing_dependencies?.length">
          <dt>끝나지 않은 앞 단계</dt>
          <dd><span v-for="k in failure.missing_dependencies" :key="k" class="chip">{{ k }}</span></dd>
        </template>
        <template v-if="failure.message || failure.reason">
          <dt>메시지</dt>
          <dd>{{ failure.message ?? failure.reason }}</dd>
        </template>
      </dl>
      <p v-if="failure.skill_id" class="muted small hint">
        오류에 표시된 Skill 과 고쳐야 할 파일은 다를 수 있어요. 입력을 공급해야 하는 앞 단계의 출력 선언도 확인해 주세요 →
        <RouterLink to="/compare">버전 비교</RouterLink>
      </p>
    </section>

    <section v-if="steps.length" class="card">
      <h2><span class="sec-no">02</span> 단계별 결과</h2>
      <ol class="steps">
        <li v-for="(id, i) in steps" :key="id">
          <button
            class="step"
            :class="[statusOf(id), { on: selected === id }]"
            :aria-pressed="selected === id"
            @click="selected = selected === id ? null : id"
          >
            <span class="step-top">
              <component :is="STEP_ICON[statusOf(id)]" :size="16" />
              <span class="idx">{{ String(i + 1).padStart(2, '0') }}</span>
            </span>
            <span class="name">{{ id }}</span>
            <small>
              {{ RUN_LABEL[statusOf(id)] }}
              <template v-if="run.bySkill[id]?.rule"> · 규칙 {{ run.bySkill[id].rule.reason }}</template>
              <template v-if="run.bySkill[id]?.duration_ms != null"> · {{ run.bySkill[id].duration_ms }} ms</template>
            </small>
          </button>
          <ChevronRight v-if="i < steps.length - 1" class="arrow" :size="16" />
        </li>
      </ol>
      <p class="muted small">단계를 누르면 아래 이벤트를 그 단계만 골라 보여 줘요.</p>
    </section>

    <section class="card">
      <div class="head">
        <h2><span class="sec-no">03</span> 이벤트 {{ trace.events.length }}개</h2>
        <span v-if="selected" class="muted small filter">
          {{ selected }} 만 보는 중 <button class="sm" @click="selected = null">전체 보기</button>
        </span>
      </div>
      <EventTimeline :events="trace.events" :skill="selected" />
    </section>
  </template>

  <DevInfo :rows="devRows" />
</template>

<style scoped>
.card + .card {
  margin-top: 16px;
}

h2 {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.head h2 {
  margin-bottom: 14px;
}

dl {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  margin: 0;
  border-top: 1px solid var(--border);
}

dt,
dd {
  padding: 9px 0;
  border-bottom: 1px solid var(--border);
}

dt {
  padding-right: 28px;
  color: var(--muted);
}

dd {
  margin: 0;
  min-width: 0;
  color: var(--text-2);
  overflow-wrap: anywhere;
}

dd.strong {
  font-family: var(--display);
  font-size: 16px;
  font-weight: 700;
  color: var(--text);
}

dd.ok {
  color: var(--ok);
}

dd.warn {
  color: var(--warn);
}

dd.err {
  color: var(--err);
}

.cat {
  font-size: var(--fs-xs);
  font-weight: 500;
}

.failure {
  border-color: rgb(255 149 132 / 30%);
  background: linear-gradient(180deg, var(--del-bg), transparent 70%), var(--surface);
}

.failure .sec-no {
  color: var(--err);
}

.hint {
  margin: 14px 0 0;
}

.steps {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 6px;
  margin: 0 0 12px;
  padding: 0;
  list-style: none;
}

.steps li {
  display: flex;
  align-items: center;
  gap: 6px;
}

.step {
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  min-width: 148px;
  padding: 12px 14px;
  border-radius: var(--radius);
  border-color: var(--border-strong);
  font-weight: 400;
  text-align: left;
}

.step-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.idx {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--faint);
}

.step .name {
  font-weight: 700;
  color: var(--text);
}

.step small {
  color: var(--muted);
}

.step.ok svg {
  color: var(--ok);
}

.step.stopped {
  border-color: rgb(246 207 122 / 50%);
  background: var(--warn-soft);
}

.step.stopped svg,
.step.stopped small {
  color: var(--warn);
}

.step.error {
  border-color: rgb(255 149 132 / 55%);
  background: var(--err-soft);
}

.step.error svg,
.step.error small {
  color: var(--err);
}

.step.blocked,
.step.skipped {
  opacity: 0.55;
}

.step.on {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-soft);
  opacity: 1;
}

.arrow {
  color: var(--faint);
  flex: none;
}

.filter {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

@media (max-width: 760px) {
  dl {
    grid-template-columns: minmax(0, 1fr);
  }

  dt {
    padding: 10px 0 0;
    border-bottom: none;
    font-size: var(--fs-xs);
  }

  dd {
    padding-top: 2px;
  }

  .step {
    min-width: 0;
  }

  .arrow {
    display: none;
  }
}
</style>
