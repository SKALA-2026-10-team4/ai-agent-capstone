<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { listExecutions, getTrace, getSnapshot, getSnapshotFile, publishSnapshot } from '../api/evidence.js'
import { timestamp } from '../utils/format.js'
const props = defineProps({ executionId: String, locked: Boolean, selectedSnapshotId: String })
const emit = defineEmits(['snapshot'])
const recent = ref([]), trace = ref(null), snapshot = ref(null), file = ref(null), path = ref('')
const tab = ref('trace'), error = ref(''), loading = ref(false), publishing = ref(false), published = ref(null)
const controller = new AbortController()
let generation = 0, fileGeneration = 0
const options = () => ({ signal: controller.signal })
const kindLabels = { SNAPSHOT_FIXED: '실행 파일 고정', CANDIDATES_OFFERED: '실제 제공한 후보', SKILL_SELECTED: 'Skill 선택',
  SKILL_RESOLVED: 'Skill 이름 해석', SKILL_LOADED: '실제 파일 로드', FLOW_VALIDATED: '흐름 계약 검사', DECISION_INPUT: '실행기에 제공한 입력', MODEL_RESPONSE: '원래 응답',
  MODEL_REQUEST: 'API 호출', TOOL_REQUEST: '도구 요청', TOOL_RESULT: '도구 실행 결과', TOOL_ERROR: '도구 오류', TOOL_REPLAY: '멱등 결과 재사용',
  SKILL_COMPLETED: '단계 결과 전달', EXECUTION_ERROR: '실행 오류', BUSINESS_STOP: '정상 업무 종료', EXECUTION_FINISHED: '실행 종료',
  EXECUTION_STARTED: '실행 시작', DECISION_ERROR: '응답 오류', SKILL_LOAD_REJECTED: '파일 로드 거절', EXECUTION_INTERRUPTED: '중단 실행 복구' }
const pretty = (value) => JSON.stringify(value, null, 2)
async function inspect(id) {
  if (!id) return
  const current = ++generation
  fileGeneration++; loading.value = true; error.value = ''; file.value = null; snapshot.value = null; path.value = ''
  try {
    const record = await getTrace(id, options())
    if (current !== generation) return
    trace.value = record
    if (record.snapshot_id) {
      const bundle = await getSnapshot(record.snapshot_id, options())
      if (current !== generation) return
      snapshot.value = bundle; path.value = bundle.files[0]?.path || ''
      if (path.value) await readFile()
    }
  } catch (e) { if (current === generation) error.value = e.message }
  finally { if (current === generation) loading.value = false }
}
async function refresh() {
  try {
    const result = await listExecutions(options()); recent.value = result.items
    if (!trace.value && !props.executionId && recent.value.length) await inspect(recent.value[0].execution_id)
  } catch (e) { error.value = e.message }
}
async function readFile() {
  if (!snapshot.value || !path.value) return
  const current = ++fileGeneration
  try {
    const result = await getSnapshotFile(snapshot.value.snapshot_id, path.value, options())
    if (current === fileGeneration) file.value = result
  } catch (e) { if (current === fileGeneration) error.value = e.message }
}
async function publish(fixture) {
  if (props.locked || publishing.value) return
  publishing.value = true; error.value = ''
  try { published.value = await publishSnapshot(fixture, options()); emit('snapshot', published.value.snapshot_id) }
  catch (e) { error.value = e.message }
  finally { publishing.value = false }
}
watch(() => props.executionId, async (id) => { if (id) { await inspect(id); await refresh() } }, { immediate: true })
onMounted(refresh)
onUnmounted(() => { generation++; fileGeneration++; controller.abort() })
</script>
<template>
  <section class="panel evidence-panel" aria-labelledby="evidence-title">
    <div class="section-heading"><div><span class="eyebrow">03 / EXECUTION EVIDENCE</span><h2 id="evidence-title">Trace · Snapshot</h2></div><button class="button ghost" type="button" @click="refresh">실행 목록 새로고침</button></div>
    <p class="muted evidence-intro">Trace는 실제 실행 기록, snapshot은 그 실행에 사용한 Skill 파일 원본입니다. 이전 실행도 조회할 수 있어요.</p>
    <div class="evidence-toolbar"><label for="execution-select">최근 실행<select id="execution-select" :value="trace?.execution_id || ''" @change="inspect($event.target.value)"><option value="" disabled>실행을 선택하세요</option><option v-for="item in recent" :key="item.execution_id" :value="item.execution_id">{{ timestamp(item.started_at) }} · {{ item.final_status || item.completion }} · {{ item.execution_id.slice(0, 8) }}</option></select></label><div class="button-row" aria-label="실행 증거 보기"><button class="button" :class="tab === 'trace' ? 'primary' : 'ghost'" type="button" :aria-pressed="tab === 'trace'" @click="tab='trace'">Trace 보기</button><button class="button" :class="tab === 'snapshot' ? 'primary' : 'ghost'" type="button" :aria-pressed="tab === 'snapshot'" @click="tab='snapshot'">Snapshot 보기</button></div></div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p><p v-if="loading" class="notice" role="status">실행 기록을 읽고 있습니다.</p>
    <template v-if="trace">
      <dl class="evidence-identifiers"><div><dt>실행 ID</dt><dd>{{ trace.execution_id }}</dd></div><div><dt>원래 선택 → 해석</dt><dd>{{ trace.raw_selection ?? '응답 없음' }} → {{ trace.resolved_skill_id ?? '해석 없음' }}</dd></div><div><dt>실행 모드</dt><dd>{{ trace.model_mode === 'fixed' ? '고정 응답 · 실제 모델 호출 없음' : trace.model }} · {{ trace.completion }} <span v-if="trace.error_category">· {{ trace.error_category }}</span></dd></div><div><dt>관측 비용</dt><dd>API {{ trace.api_calls }}회 · 입력 {{ trace.input_tokens ?? '미관측' }} / 출력 {{ trace.output_tokens ?? '미관측' }} 토큰 · {{ trace.elapsed_ms ?? '진행 중' }}ms</dd></div></dl>
      <div v-if="tab === 'trace'" class="trace-events" aria-label="실행 trace 이벤트"><details v-for="event in trace.events" :key="event.sequence" :class="{ 'event-error': event.kind.includes('ERROR') || event.kind.includes('REJECTED') }"><summary><span class="event-number">{{ event.sequence }}</span>{{ kindLabels[event.kind] || event.kind }} <strong v-if="event.skill_id">{{ event.skill_id }}</strong><small>{{ event.at.slice(11, 23) }} UTC</small></summary><pre>{{ pretty(event.data) }}</pre></details></div>
      <div v-else-if="snapshot" class="snapshot-view"><dl class="evidence-identifiers"><div><dt>Snapshot ID</dt><dd>{{ snapshot.snapshot_id }}</dd></div><div><dt>전체 SHA-256</dt><dd>{{ snapshot.hash }}</dd></div><div><dt>후보 출처</dt><dd>{{ pretty(snapshot.metadata) }}</dd></div></dl><label for="snapshot-file">고정된 파일 · 관련 자원 포함<select id="snapshot-file" v-model="path" @change="readFile"><option v-for="item in snapshot.files" :key="item.path" :value="item.path">{{ item.path }} · {{ item.size }} bytes</option></select></label><template v-if="file"><p class="file-hash mono">SHA-256 {{ file.hash }} · {{ file.encoding }}</p><pre class="snapshot-content">{{ file.content }}</pre></template></div>
      <p v-else class="notice">파일 고정 전에 실행이 실패해 snapshot이 없습니다. Trace의 오류를 확인해주세요.</p>
    </template>
    <p v-else-if="!loading" class="notice">아직 Agent 실행 기록이 없습니다. 새 주문을 실행하면 여기에 표시됩니다. 기존 MVP 주문에는 소급해 기록을 만들지 않습니다.</p>
    <details class="skill-demo"><summary>개발·시연: 미리 준비한 Skill 후보</summary><p>아래는 미리 준비한 파일 세트입니다. SkillOps가 생성하거나 자동 수정한 후보가 아닙니다. 주문 입력을 채우고 후보를 고정한 다음 실행하세요. 원본과 이전 snapshot은 유지됩니다.</p><div class="button-row"><button class="button ghost" :disabled="locked || publishing" @click="publish('baseline')">준비된 정상본 고정</button><button class="button ghost" :disabled="locked || publishing" @click="publish('broken-contract')">준비된 결함본 고정</button><button class="button ghost" :disabled="locked || publishing" @click="publish('repaired')">준비된 수정본 고정</button></div><p class="mono">다음 주문 snapshot: {{ selectedSnapshotId || '실행 시 현재 게시된 Skill 세트 고정' }}</p><p v-if="published && selectedSnapshotId === published.snapshot_id" class="notice">{{ published.metadata.fixture }} 후보를 선택했습니다. 위 주문 실행 버튼으로 실행해주세요.</p><small>결함 후보는 inventory.reserved 선언을 제거해 계약 검사에서 실패합니다. 고정 모드는 자연어 본문 해석의 유효성을 보증하지 않습니다.</small></details>
  </section>
</template>
