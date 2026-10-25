<script setup>
// 검증·진단 기록 화면이다. frontend/src/router/index.js 가 /diagnoses/:threadId? 경로에 연결한다.
// Skill 수정본을 저장한 뒤 자동 검증과 진단이 어디까지 왔는지 보여 주며,
// 주소에 따라 세 가지로 동작한다.
// 1. /diagnoses?change_id=… 는 저장 직후의 전달 대기다. frontend/src/views/SkillDraftView.vue 가
//    저장 응답의 change_id 로 이 주소의 링크를 보여 준다. frontend/src/api/diagnoses.js 의
//    getChangeDelivery 로 전달 상태를 읽고, thread_id 가 생기면 상세 주소로 바꾼다.
// 2. /diagnoses/{threadId} 는 진단 한 건의 상세다. diagnoses.js 의 getDiagnosis 로 읽는다.
// 3. /diagnoses 는 진단 기록 목록이다. diagnoses.js 의 listDiagnoses 로 읽는다.
// 전달 상태 요청(GET /api/skill-changes/{id})은
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
// changeDelivery() 가 받는다. 상세와 목록 요청은
// service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 의 status() 와
// list() 가 받아 skillops-agent 로 중계하고, skillops-agent/skillops_agent/main.py 의
// get_run_events(GET /runs/{thread_id}/events)와 list_runs(GET /runs)가 답한다.
// 목록과 전달 대기는 화면을 떠나거나 상세로 넘어갈 때까지 계속 다시 읽고,
// 상세는 진단이 진행 중이거나 직전 조회가 실패했을 때만 다시 읽는다.
// 간격은 frontend/src/config.js 의 DIAGNOSIS_POLL_MS 이다.
// 다시 읽을지, 상태 제목, 후보와 원인을 고르는 규칙은 frontend/src/utils/diagnosisView.js 에 있고,
// 진단 상태가 늘면 그 파일의 DIAGNOSIS_LABEL 과 IN_PROGRESS_STATUSES 를 고친다.
// 진단 결과(diagnosis.finding)가 INTENT_NOT_REALIZED 면 상태 제목 아래에 '의도가 아직 실행되지 않음'
// 안내를, 원인 종류(root_cause.kind)가 incomplete_edit 면 원인 칸 제목을 바꿔 보여 준다. 담당자 의도와
// 기대 결과, 후보 종류(encode_rule 등)와 경고(is_revert, weakens_contract), service 가 다시 계산한
// 판정(server_overrides)도 있으면 보여 준다. 모양은 docs/plans/2026-10-26-behavior-edit-root-fix.md
// 2.3·2.5~2.7 절이고 이름표는 frontend/src/utils/diagnosisView.js 와 frontend/src/utils/proposalView.js 에 있다.
// 진단 결과가 NEEDS_TOOL('도구 개발 필요')이고 제안서에 개발 요청서(development_request, 2.11 절)가 있으면
// frontend/src/components/DevelopmentRequestCard.vue 로 요청서 카드를 함께 보여 준다.
// mock 모드(frontend/src/api/client.js 의 USE_MOCK)에는 진단 경로가 없으므로 아무것도 읽지 않고
// 안내만 보여 준다.
// frontend/tests/diagnosis.spec.js 가 상태 제목, '진행 로그' 제목, '전체 기록' 링크를 확인한다.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity } from 'lucide-vue-next'
import { USE_MOCK } from '@/api/client'
import { getChangeDelivery, getDiagnosis, listDiagnoses } from '@/api/diagnoses'
import CandidateTags from '@/components/CandidateTags.vue'
import DevelopmentRequestCard from '@/components/DevelopmentRequestCard.vue'
import IntentExpectations from '@/components/IntentExpectations.vue'
import PageHeader from '@/components/PageHeader.vue'
import ServerOverrides from '@/components/ServerOverrides.vue'
import { DIAGNOSIS_POLL_MS } from '@/config'
import {
  baselineSnapshotOf,
  candidateSnapshotOf,
  candidatesOf,
  candidateVerdict,
  diagnosisLabel,
  findingLabel,
  findingNote,
  intentOf,
  rootCauseOf,
  shouldPollAgain,
} from '@/utils/diagnosisView'
import { developmentRequestOf } from '@/utils/developmentRequest'
import { overrideRows, rootCauseKind, rootCauseKindLabel } from '@/utils/proposalView'
import { executionPath } from '@/utils/traceRun'

const route = useRoute()
const router = useRouter()
// 목록 모드의 진단 기록들이다.
const items = ref([])
// 상세 모드의 진단 한 건이다. 다른 모드에서는 null 로 남는다.
const run = ref(null)
// 전달 대기 모드의 전달 상태다(thread_id, last_error 등).
const delivery = ref(null)
const error = ref(null)
// 예약한 다음 조회의 setTimeout 핸들이다.
let timer
// 주소가 바뀌거나 화면을 떠날 때마다 1씩 올리는 세대 번호다. 조회를 시작할 때의 세대(epoch)와
// 지금 세대가 다르면 그 응답은 이전 주소의 것이므로 버린다.
// 느린 응답이 새 주소의 화면을 덮어쓰지 않게 하려는 장치다.
let generation = 0
// change_id 로 들어왔고 아직 진단 상세를 읽지 못했으면 전달 대기 상자를 보여 준다.
const pending = computed(() => route.query.change_id && !run.value)
// 후보와 원인은 저장된 제안서에 있으면 그것을, 없으면 진단 결과의 값을 쓴다.
const candidates = computed(() => candidatesOf(run.value))
const root = computed(() => rootCauseOf(run.value))
// 진단 결과 이름(diagnosis.finding), 담당자 의도, service 가 다시 계산한 판정 줄이다. 없으면 null 이나
// 빈 목록이다.
const finding = computed(() => run.value?.diagnosis?.finding ?? null)
const intent = computed(() => intentOf(run.value))
const overrides = computed(() => overrideRows(run.value?.proposal))
// 개발 요청서와 마크다운 배경 절의 맥락이다. 요청서는 진단 결과의 칸을 먼저, 없으면 저장된 제안서 칸을 본다.
const devRequest = computed(() => developmentRequestOf(run.value))
const devContext = computed(() => ({
  proposalId: run.value?.proposal?.id ?? null,
  intentText: intent.value?.text ?? null,
  rootCause: root.value ?? null,
  baselineSnapshotId: run.value ? baselineSnapshotOf(run.value) : null,
  snapshotId: run.value ? candidateSnapshotOf(run.value) : null,
}))
/**
 * 실행 번호를 실행 화면 주소로 바꾼다. frontend/src/utils/traceRun.js 의 executionPath 가
 * 알아보지 못하는 모양이면 받은 값을 그대로 /executions/ 뒤에 붙인다.
 * template 의 실행 번호 링크(원인 실험, 의도 검증 실패, 후보 증거, 저장 직후 검증)가 모두 쓴다.
 */
const executionLink = (id) => executionPath(id) ?? `/executions/${id}`

/**
 * 진단 한 건(thread)을 읽어 run 에 넣는다. 주소에 threadId 가 있으면 loadForRoute 가 부른다.
 * 응답을 기다리는 사이 주소가 바뀌었으면 오래된 응답이므로 버리고 false 를 돌려준다.
 * 받은 run 의 diagnosis_status 가 다음 조회 여부를 정한다.
 */
async function loadThread(epoch, threadId) {
  const result = await getDiagnosis(threadId)
  if (epoch !== generation) return false
  run.value = result
  return true
}

/**
 * 저장한 변경(change_id)의 전달 상태를 읽어 delivery 에 넣는다. 주소에 threadId 가 없고
 * change_id 가 있으면 loadForRoute 가 부른다. 진단 요청 전달은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java
 * 가 맡는다. 그 dispatcher 가 service 로 진단 요청을 넘기고 thread_id 를 받으면
 * 응답에 thread_id 가 생긴다. 그러면 /diagnoses/{thread_id} 로 주소를 바꾸고 false 를 돌려준다.
 * 주소가 바뀌면 watch 가 새 세대로 다시 읽으므로 여기서는 다음 조회를 예약하지 않는다.
 * 오래된 응답이어도 false 를 돌려준다.
 * 전달이 실패하는 동안에는 template 이 delivery.last_error 를 보여 주고 계속 기다린다.
 */
async function followChange(epoch, changeId) {
  const result = await getChangeDelivery(changeId)
  if (epoch !== generation) return false
  delivery.value = result
  if (result.thread_id) {
    await router.replace(`/diagnoses/${result.thread_id}`)
    return false
  }
  return true
}

/**
 * 진단 기록 목록을 읽어 items 에 넣는다. 주소에 threadId 도 change_id 도 없으면 loadForRoute 가
 * 부른다. 오래된 응답이면 버리고 false 를 돌려준다.
 */
async function loadList(epoch) {
  const result = await listDiagnoses()
  if (epoch !== generation) return false
  items.value = result.items
  return true
}

/**
 * 주소에 맞는 것을 하나 읽는다. threadId 가 있으면 상세를, 없고 change_id 가 있으면 전달 상태를,
 * 둘 다 없으면 목록을 읽는다. 이 우선순위가 화면 모드를 정한다.
 * 읽은 값을 화면에 넣었고 폴링을 이어 가도 될 때만 true 를 돌려준다.
 */
function loadForRoute(epoch) {
  const change = route.query.change_id
  if (route.params.threadId) return loadThread(epoch, route.params.threadId)
  if (change) return followChange(epoch, change)
  return loadList(epoch)
}

/**
 * 한 번 읽고 필요하면 다음 조회를 예약하는 폴링 한 바퀴다. 첫 바퀴는 restartForRoute 가 부른다.
 * mock 모드면 아무것도 하지 않는다.
 * loadForRoute 가 false 를 돌려주면(오래된 응답, 상세 주소로 이동) 예약 없이 끝낸다.
 * 읽기에 성공하면 오류 문구를 지우고, 실패하면 같은 세대일 때만 오류 문구를 남긴다.
 * 다음 조회 여부는 frontend/src/utils/diagnosisView.js 의 shouldPollAgain 이 정한다.
 * 진단 결과가 아직 없거나(목록, 전달 대기), 진단이 진행 중이거나, 직전 조회가 실패했으면
 * DIAGNOSIS_POLL_MS 뒤에 같은 세대로 다시 부른다.
 */
async function refresh(epoch) {
  if (USE_MOCK) return
  try {
    if (!(await loadForRoute(epoch))) return
    error.value = null
  } catch (e) {
    if (epoch === generation) error.value = e.message
  }
  // 거절된 전달(FAILED)은 주문 Agent 가 다시 보내지 않으므로 더 조회해도 바뀌지 않는다.
  const rejected = delivery.value?.delivery_status === 'FAILED'
  if (epoch === generation && !rejected && shouldPollAgain(run.value, error.value)) {
    timer = setTimeout(() => refresh(epoch), DIAGNOSIS_POLL_MS)
  }
}

/**
 * 주소가 바뀌면 예약한 조회를 지우고, 이전 응답을 버리도록 세대를 올린 뒤 화면 값을 비우고
 * 처음부터 읽는다. 아래 watch 가 처음 그릴 때와 route.fullPath 가 바뀔 때마다 부른다.
 */
function restartForRoute() {
  clearTimeout(timer)
  generation++
  run.value = null
  delivery.value = null
  error.value = null
  refresh(generation)
}

/** 화면을 떠나면 진행 중인 응답을 버리고 예약한 조회를 지운다. onBeforeUnmount 가 부른다. */
function stopPolling() {
  generation++
  clearTimeout(timer)
}

watch(() => route.fullPath, restartForRoute, { immediate: true })
onBeforeUnmount(stopPolling)
</script>

<template>
  <PageHeader title="검증·진단 기록" description="저장한 수정본이 실제 주문 흐름에서 어떻게 동작하는지 확인해요." :icon="Activity" />
  <p class="callout">수정본은 새 파일 세트로 저장돼요. 이 세트를 지정한 검증·주문에만 적용되며, 5174의 기본 주문에는 자동 반영되지 않아요.</p>
  <p v-if="USE_MOCK" class="callout">실제 검증·진단 기록은 서버 모드에서 확인할 수 있어요.</p>
  <p v-if="error" class="callout err" role="alert">{{ error }} 잠시 후 다시 조회합니다.</p>
  <section v-if="pending" class="card" role="status">
    <h2>{{ delivery?.delivery_status === 'FAILED' ? '저장 완료 · 자동 검증 요청 거절됨' : '저장 완료 · 자동 검증 요청 전달 대기' }}</h2>
    <p>변경 번호: <code>{{ route.query.change_id }}</code></p>
    <p>주문 Agent가 요청을 전달하면 이 화면에 진행 상태가 나타나요.</p>
    <p v-if="delivery?.delivery_status === 'FAILED'">{{ delivery.last_error }} 진단 요청이 거절되어 다시 보내지 않습니다. 수정본을 고쳐 다시 저장해 주세요.</p>
    <p v-else-if="delivery?.last_error">{{ delivery.last_error }} 서버가 자동으로 재전송합니다.</p>
  </section>
  <template v-if="run">
    <RouterLink to="/diagnoses">전체 기록</RouterLink>
    <section class="card stack" role="status">
      <h2>{{ diagnosisLabel(run.diagnosis_status) }}</h2>
      <p v-if="finding" class="callout warn">
        <strong>{{ findingLabel(finding) }}</strong><template v-if="findingNote(finding)"> — {{ findingNote(finding) }}</template>
      </p>
      <p>진단 번호: <code>{{ run.thread_id }}</code></p>
      <p>기준 세트: <code>{{ baselineSnapshotOf(run) }}</code></p>
      <p>수정 세트: <code>{{ candidateSnapshotOf(run) }}</code></p>
      <p v-if="run.error" class="error-text">{{ run.error }}</p>
      <p v-if="run.diagnosis_status === 'VALIDATED'">등록된 기본 주문 검증을 통과했어요. 자연어 정책 전체의 검증이나 운영 반영을 의미하지 않아요.</p>
      <p v-if="run.proposal?.storage_status === 'NOT_SAVED'">평가 결과를 확인할 수 있어요. 제안 승인·운영 반영은 아직 연결되지 않았어요.</p>
      <p v-if="run.proposal?.storage_status === 'SAVED'">제안서를 저장했어요. 운영자가 검토하면 승인 여부가 정해져요.</p>
      <RouterLink v-if="run.proposal?.id" :to="`/proposals/${run.proposal.id}`">제안서 보기</RouterLink>
    </section>
    <section class="card">
      <h2>진행 로그</h2>
      <ol>
        <li v-for="(event, i) in run.events" :key="i"><time>{{ event.at }}</time> · {{ event.msg }}</li>
      </ol>
    </section>
    <section v-if="intent" class="card">
      <h2>바꾸려는 업무 규칙(의도)</h2>
      <blockquote>{{ intent.text }}</blockquote>
      <IntentExpectations :intent="intent" />
      <p v-if="!intent.expectations?.length" class="muted">기대 결과를 적지 않았어요. SkillOps 가 의도 글과 바뀐 내용으로 기대 결과를 만들어 시험해요.</p>
    </section>
    <section v-if="root?.skill" class="card">
      <h2>{{ rootCauseKind(root) === 'incomplete_edit' ? '원인 · 의도를 실행하지 못하는 수정' : '원인 실험 결과' }}</h2>
      <p class="muted">{{ rootCauseKindLabel(root) }}</p>
      <p>{{ root.skill }}: {{ root.summary }}</p>
      <p v-if="root.repro_scenario_ids?.length">재현 주문: <code v-for="id in root.repro_scenario_ids" :key="id">{{ id }} </code></p>
      <RouterLink v-for="id in root.proof_run_ids" :key="id" :to="executionLink(id)">{{ id }} </RouterLink>
    </section>
    <section v-if="run.diagnosis" class="card">
      <h2>변경 의도 검증</h2>
      <p>{{ run.diagnosis.explanation }}</p>
      <p v-for="(failure, i) in run.diagnosis.failures" :key="i">{{ failure.reason }} <RouterLink :to="executionLink(failure.run_id)">실행 기록</RouterLink></p>
    </section>
    <DevelopmentRequestCard v-if="devRequest" :request="devRequest" :context="devContext" />
    <section v-for="candidate in candidates" :key="candidate.candidate_no" class="card">
      <h2>후보 {{ candidate.candidate_no }} · {{ candidateVerdict(candidate.valid) }}</h2>
      <CandidateTags :candidate="candidate" />
      <p v-if="candidate.valid == null">생성된 수정안입니다. 검증이 완료되지 않아 적용 가능한 수정안으로 확정되지 않았어요.</p>
      <p>{{ candidate.explanation }}</p>
      <p v-if="candidate.validation_error" class="error-text">{{ candidate.validation_error }}</p>
      <p v-if="candidate.results">실패 해결: {{ candidate.results.fixes_failure }} · 회귀 {{ candidate.results.keeps_old_orders.passed }}/{{ candidate.results.keeps_old_orders.total }} · 의도 보존: {{ candidate.results.keeps_intent }} · 계약: {{ candidate.results.no_broken_links }}</p>
      <details v-for="patch in candidate.patches" :key="patch.skill">
        <summary>{{ patch.skill }} 수정 내용</summary>
        <pre>{{ patch.patch }}</pre>
      </details>
      <details>
        <summary>실행 증거</summary>
        <p v-for="id in candidate.evidence_run_ids" :key="id"><RouterLink :to="executionLink(id)">{{ id }}</RouterLink></p>
      </details>
    </section>
    <section v-if="overrides.length" class="card">
      <h2>서버가 다시 계산한 판정</h2>
      <p>SkillOps Agent 가 보낸 판정과 service 가 실행 장부로 다시 계산한 판정이 달라 서버 값으로 바꿨어요.</p>
      <ServerOverrides :rows="overrides" />
    </section>
    <section v-if="run.preflight_run_ids?.length" class="card">
      <h2>저장 직후 검증 기록</h2>
      <p v-for="id in run.preflight_run_ids" :key="id"><RouterLink :to="executionLink(id)">{{ id }}</RouterLink></p>
    </section>
  </template>
  <section v-else-if="!pending && !USE_MOCK" class="stack">
    <p v-if="!items.length">아직 진단 기록이 없어요. Skill 편집에서 수정본을 저장하면 자동으로 시작돼요.</p>
    <RouterLink v-for="item in items" :key="item.thread_id" class="card" :to="`/diagnoses/${item.thread_id}`">
      {{ diagnosisLabel(item.diagnosis_status) }} · {{ item.created_at }}
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
blockquote { margin: 8px 0 0; padding: 10px 14px; border-radius: var(--radius); background: var(--accent-soft); }
</style>
