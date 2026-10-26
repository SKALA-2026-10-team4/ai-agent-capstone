<script setup>
// 주문 Agent 실행 한 건의 증거 화면이다.
// frontend/src/router/index.js 가 /executions/:id 경로에 연결한다.
// 식별자, 오류 상세, 실행 당시 snapshot 으로 그린 단계 흐름, 시간 순서의 이벤트를 보여 준다.
// order-agent/docs/team-scenarios.md 의 "담당자 간 전달할 증거"(execution_id, order_id, snapshot,
// 오류 상세, 최종 상태)를 모두 보여 주는 것이 목적이다.
// 이 화면을 여는 곳은 frontend/src/views/OrderView.vue 의 결과와 실행 기록 링크, 그리고
// frontend/src/views/DiagnosisView.vue 와 frontend/src/components/ProposalFacts.vue 의 실행 번호
// 링크다. 실행 번호 링크의 주소는 frontend/src/utils/traceRun.js 의 executionPath 가 만든다.
// trace 는 frontend/src/api/orderAgent.js 의 getTrace 로 읽는다. GET /api/executions/{id}/trace 는
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
// trace() 가 받는다. 실행에 쓴 Skill 세트는 frontend/src/stores/workspace.js 의 ensureSet 으로
// 읽는다.
// 단계 상태 계산은 frontend/src/utils/traceRun.js 의 traceRun, 상태 이름은 같은 파일의 RUN_LABEL,
// 상태 아이콘은 이 파일의 STEP_ICON 에서 고친다. 이벤트 목록은
// frontend/src/components/EventTimeline.vue 가 그린다.
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getTrace } from '@/api/orderAgent'
import { useWorkspaceStore } from '@/stores/workspace'
import { shortId } from '@/utils/format'
import { flowSteps } from '@/utils/skillSet'
import { RUN_LABEL, traceRun } from '@/utils/traceRun'
import EventTimeline from '@/components/EventTimeline.vue'
import {
  ChevronRight,
  CircleCheck,
  CircleDashed,
  CirclePause,
  CircleX,
  LoaderCircle,
  ScrollText,
} from 'lucide-vue-next'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const route = useRoute()
const ws = useWorkspaceStore()

const trace = ref(null)
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

// 실행 번호가 바뀔 때마다(처음 포함) 화면 값을 비우고 trace 를 새로 읽는다. trace 에 snapshot_id 가
// 있으면 그 세트도 읽어 단계 순서와 세트 이름(workspace store 의 kindLabelOf)에 쓴다.
watch(
  () => route.params.id,
  async (id) => {
    trace.value = null
    set.value = null
    error.value = null
    try {
      trace.value = await getTrace(id)
      if (trace.value.snapshot_id) set.value = await ws.ensureSet(trace.value.snapshot_id)
    } catch (e) {
      error.value = e.message
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
    :title="trace ? `실행 ${shortId(trace.execution_id)}` : '실행 기록'"
    description="주문 Agent 가 어떤 Skill 파일로 무엇을 했는지, 어디서 왜 멈췄는지 남긴 기록이에요."
    :icon="ScrollText"
    :back="{ to: '/orders#history', label: '테스트 목록' }"
  />
  <p v-if="error" class="error-text">{{ error }}</p>

  <template v-if="trace">
    <section class="card">
      <div class="head">
        <h2>실행 정보</h2>
        <StatusBadge :status="trace.final_status ?? trace.completion" />
        <StatusBadge v-if="trace.final_status && trace.final_status !== trace.completion" :status="trace.completion" />
        <code v-if="trace.error_category" class="error-text">{{ trace.error_category }}</code>
      </div>
      <dl>
        <dt>execution_id</dt>
        <dd><code>{{ trace.execution_id }}</code></dd>
        <dt>run_id</dt>
        <dd><code>{{ trace.run_id }}</code> <span class="muted">(trace 조회 별칭 · 진단 run 과 다름)</span></dd>
        <dt>order_id</dt>
        <dd><code>{{ trace.order_id }}</code></dd>
        <dt>snapshot</dt>
        <dd>
          <template v-if="trace.snapshot_id">
            <code>{{ trace.snapshot_id }}</code>
            <span class="muted"> · {{ ws.kindLabelOf(trace.snapshot_id) }}</span>
            <br />
            <code class="muted">SHA-256 {{ trace.snapshot_hash }}</code>
          </template>
          <span v-else class="muted">파일 고정 전에 실패해 snapshot 이 없다.</span>
        </dd>
        <dt>실행 방식</dt>
        <dd>{{ trace.mode }} · {{ trace.model_mode }} ({{ trace.model }}) · 선택 {{ trace.raw_selection }} → {{ trace.resolved_skill_id ?? '—' }}</dd>
        <dt>비용·시간</dt>
        <dd>
          API {{ trace.api_calls }}회 · 토큰 {{ trace.input_tokens ?? '미관측' }}/{{ trace.output_tokens ?? '미관측' }} ·
          {{ trace.elapsed_ms }} ms · {{ new Date(trace.started_at).toLocaleString() }}
        </dd>
      </dl>
    </section>

    <section v-if="failure" class="card failure">
      <h2>실행 오류 · {{ failure.category }}</h2>
      <dl>
        <template v-if="failure.skill_id">
          <dt>실패 Skill</dt>
          <dd><code>{{ failure.skill_id }}</code></dd>
        </template>
        <template v-if="failure.phase">
          <dt>단계</dt>
          <dd>
            {{ failure.phase }}
            <span class="muted">{{ failure.phase === 'static_validation' ? '— 업무 도구 실행 전에 차단됨' : '— 실행 중 입력 검사' }}</span>
          </dd>
        </template>
        <template v-if="failure.missing_inputs?.length">
          <dt>누락 입력</dt>
          <dd><code v-for="k in failure.missing_inputs" :key="k">{{ k }}</code></dd>
        </template>
        <template v-if="failure.missing_dependencies?.length">
          <dt>누락 선행 단계</dt>
          <dd><code v-for="k in failure.missing_dependencies" :key="k">{{ k }}</code></dd>
        </template>
        <template v-if="failure.message || failure.reason">
          <dt>메시지</dt>
          <dd>{{ failure.message ?? failure.reason }}</dd>
        </template>
      </dl>
      <p v-if="failure.skill_id" class="muted small">
        오류에 표시된 Skill 과 고쳐야 할 파일은 다를 수 있다. 입력을 공급해야 하는 앞 단계의 출력 선언도 확인한다 →
        <RouterLink to="/compare">snapshot 비교</RouterLink>
      </p>
    </section>

    <section v-if="steps.length" class="card">
      <h2>단계별 결과</h2>
      <ol class="steps">
        <li v-for="(id, i) in steps" :key="id">
          <button
            class="step"
            :class="[statusOf(id), { on: selected === id }]"
            @click="selected = selected === id ? null : id"
          >
            <component :is="STEP_ICON[statusOf(id)]" :size="18" />
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
        <h2>이벤트 {{ trace.events.length }}개</h2>
        <span v-if="selected" class="muted">
          {{ selected }} 만 보는 중 <button @click="selected = null">전체 보기</button>
        </span>
      </div>
      <EventTimeline :events="trace.events" :skill="selected" />
    </section>
  </template>
</template>

<style scoped>
.card + .card {
  margin-top: 16px;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.head h1,
.head h2 {
  margin: 0;
}

dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 4px 16px;
  margin: 0;
}

dt {
  color: var(--muted);
}

dd {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
}

dd code + code {
  margin-left: 6px;
}

.steps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin: 0 0 8px;
  padding: 0;
  list-style: none;
}

.steps li {
  display: flex;
  align-items: center;
  gap: 4px;
}

.step {
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 130px;
  padding: 10px 12px;
  font-weight: 400;
  text-align: left;
  border-radius: var(--radius);
}

.step .name {
  font-weight: 700;
}

.step small {
  color: var(--muted);
}

.step.ok svg {
  color: var(--ok);
}

.step.stopped {
  border-color: var(--warn);
  background: var(--warn-soft);
}

.step.stopped svg {
  color: var(--warn);
}

.step.error {
  border-color: var(--err);
  background: var(--err-soft);
}

.step.error svg,
.step.error small {
  color: var(--err);
}

.step.blocked,
.step.skipped {
  opacity: 0.6;
}

.step.on {
  box-shadow: var(--focus);
  border-color: var(--accent);
}

.arrow {
  color: var(--muted);
  flex: none;
}

.failure {
  border-left: 3px solid var(--err);
  background: var(--del-bg);
}

.small {
  font-size: 12px;
  margin: 8px 0 0;
}
</style>
