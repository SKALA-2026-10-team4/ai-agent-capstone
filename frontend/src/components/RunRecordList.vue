<script setup>
// 실행 번호 목록(run_id 나 execution_id)을 사람이 읽는 실행 기록 타임라인으로 그린다. 가는 세로선과 점 위에,
// 왼쪽에는 시각, 오른쪽에는 결과('규칙 PREPAID_ONLY 로 보류' 등, 결과 색), 주문 요약, 실행에 쓴 Skill 세트를
// 쓴다. 가장 늦은 실행은 더 밝게 강조한다. 처음에는 limit 개만 보이고 'N건 더 보기' 로 나머지를 편다.
// 줄을 누르면 실행 화면(/executions/{uuid})으로 간다. 원래 실행 번호는 글로 찍지 않고 title 과 작은 'ID 복사'
// 버튼에만 둔다. 기록을 읽는 동안이나 읽지 못하면 '실행 기록 N' 과 흐린 안내를 보여 준다.
// 기록은 frontend/src/api/runRecords.js 의 loadRunRecord 가 읽고(캐시, 동시 요청 제한), 요약 규칙은
// frontend/src/utils/runRecord.js 에 있다. frontend/src/components/ProposalFacts.vue(실패한 실행, 원인 확인 실행,
// 근거 실행), frontend/src/components/DevelopmentRequestCard.vue(증명·근거 실행), frontend/src/views/DiagnosisView.vue
// (원인 실험, 후보 증거, 저장 직후 검증), frontend/src/views/OrderView.vue(최근 실행 기록)가 쓴다.
import { computed, ref, watch } from 'vue'
import { Copy } from 'lucide-vue-next'
import { loadRunRecord } from '@/api/runRecords'
import { useToastStore } from '@/stores/toast'
import { copyText } from '@/utils/clipboard'
import { executionPath } from '@/utils/traceRun'
import SnapshotLabel from './SnapshotLabel.vue'

// refs 는 실행 번호 목록, limit 는 접기 전 보일 개수다. stopClick 이 참이면 줄의 클릭을 위로 올리지 않는다
// (누르면 후보를 고르는 제안서 후보 카드 안에서 쓴다). showSnapshot 이 거짓이면 세트 이름을 뺀다.
// highlight 는 강조할 줄을 고르는 방법이다. 'latest' 는 시작 시각이 가장 늦은 줄, 'first' 는 첫 줄이다. 서버가 이미
// 최근 것부터 준 목록(주문 화면의 최근 실행)은 'first' 로 그 순서를 따른다.
const props = defineProps({
  refs: { type: Array, required: true },
  limit: { type: Number, default: 4 },
  stopClick: { type: Boolean, default: false },
  showSnapshot: { type: Boolean, default: true },
  highlight: { type: String, default: 'latest' },
})

const toast = useToastStore()
// 실행 번호마다 { state: 'loading' | 'ok' | 'error', record } 다.
const records = ref({})
const expanded = ref(false)

// 실행 번호가 바뀌면 아직 읽지 않은 번호만 읽는다. 같은 번호는 runRecords.js 가 한 번만 요청한다.
watch(
  () => props.refs,
  (list) => {
    for (const runRef of list) {
      if (records.value[runRef]) continue
      records.value[runRef] = { state: 'loading', record: null }
      loadRunRecord(runRef)
        .then((record) => (records.value[runRef] = { state: 'ok', record }))
        .catch(() => (records.value[runRef] = { state: 'error', record: null }))
    }
  },
  { immediate: true },
)

// 화면 줄 목록이다. 줄마다 실행 번호, 순번, 읽은 상태, 요약, 실행 화면 주소를 담는다.
const rows = computed(() =>
  props.refs.map((runRef, i) => {
    const entry = records.value[runRef] ?? { state: 'loading', record: null }
    return { ref: runRef, no: i + 1, ...entry, path: entry.record?.path ?? executionPath(runRef) }
  }),
)
// 강조할 실행의 번호다. 'first' 면 첫 줄, 아니면 가장 늦게 시작한 실행이다. 시각을 모르면 강조하지 않고,
// 같으면 뒤의 것을 고른다.
const latestRef = computed(() => {
  if (props.highlight === 'first') return rows.value[0]?.ref ?? null
  let best = null
  for (const r of rows.value) {
    if (r.record?.at && (!best || r.record.at >= best.record.at)) best = r
  }
  return best?.ref ?? null
})
const shown = computed(() => (expanded.value ? rows.value : rows.value.slice(0, props.limit)))
const hiddenCount = computed(() => rows.value.length - shown.value.length)

// 줄 안 링크와 버튼의 클릭을 후보 카드까지 올리지 않는다. stopClick 일 때만 쓴다.
const guard = (event) => props.stopClick && event.stopPropagation()

// 실행 번호를 클립보드에 넣고 토스트로 알린다. 'ID 복사' 버튼이 부른다.
async function copyId(event, runRef) {
  event.stopPropagation()
  event.preventDefault()
  try {
    await copyText(runRef)
    toast.show('실행 ID 를 복사했어요.', 'info', 2000)
  } catch {
    toast.show('복사하지 못했어요. 브라우저의 클립보드 권한을 확인해 주세요.', 'err')
  }
}
</script>

<template>
  <div class="runs">
    <ol class="timeline">
      <li v-for="r in shown" :key="r.ref" class="rec" :class="[r.record?.tone ?? 'pending', { latest: r.ref === latestRef }]" :title="`실행 ID ${r.ref}`">
        <time class="when" :datetime="r.record?.at ?? undefined">
          <template v-if="r.record?.time">
            <span v-if="r.record.date" class="date">{{ r.record.date }}</span>
            <span class="clock">{{ r.record.time }}</span>
          </template>
          <span v-else class="clock faint">—</span>
        </time>
        <span class="dot" aria-hidden="true" />
        <div class="body">
          <RouterLink v-if="r.path" :to="r.path" class="outcome" @click="guard">
            <template v-if="r.state === 'ok'">{{ r.record.outcome }}</template>
            <template v-else>실행 기록 {{ r.no }}</template>
          </RouterLink>
          <span v-else class="outcome">실행 기록 {{ r.no }}</span>
          <span v-if="r.state === 'ok'" class="meta">
            <span v-if="r.record.order">{{ r.record.order }}</span>
            <SnapshotLabel v-if="showSnapshot && r.record.snapshotId" :id="r.record.snapshotId" plain short class="set" />
          </span>
          <span v-else-if="r.state === 'loading'" class="meta muted">(불러오는 중…)</span>
          <span v-else class="meta muted">(기록을 불러오지 못했어요)</span>
        </div>
        <button type="button" class="icon-btn copy" :aria-label="`실행 ${r.no} ID 복사`" title="ID 복사" @click="copyId($event, r.ref)">
          <Copy :size="13" />
        </button>
      </li>
    </ol>
    <button v-if="hiddenCount > 0" type="button" class="ghost sm more" @click.stop="expanded = true">{{ hiddenCount }}건 더 보기</button>
    <button v-else-if="expanded && rows.length > limit" type="button" class="ghost sm more" @click.stop="expanded = false">접기</button>
  </div>
</template>

<style scoped>
.runs {
  min-width: 0;
}

.timeline {
  position: relative;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 세로선: 점 칸 가운데를 지난다 */
.timeline::before {
  content: '';
  position: absolute;
  top: 14px;
  bottom: 14px;
  left: calc(76px + 7px);
  width: 1px;
  background: var(--border-strong);
}

.rec {
  position: relative;
  display: grid;
  grid-template-columns: 76px 15px minmax(0, 1fr) auto;
  align-items: start;
  column-gap: 12px;
  padding: 7px 4px 7px 0;
  border-radius: var(--radius-sm);
  transition: background 0.15s var(--ease);
}

.rec:hover {
  background: rgb(255 255 255 / 3%);
}

.when {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  padding-top: 1px;
  font-family: var(--mono);
  font-size: 12px;
  line-height: 1.5;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.date {
  font-family: var(--font);
  font-size: 11px;
  color: var(--faint);
}

.dot {
  position: relative;
  z-index: 1;
  justify-self: center;
  width: 9px;
  height: 9px;
  margin-top: 6px;
  border: 1.5px solid var(--muted);
  border-radius: 50%;
  background: var(--surface);
}

.ok .dot {
  border-color: var(--ok);
}

.warn .dot {
  border-color: var(--warn);
}

.err .dot {
  border-color: var(--err);
}

.info .dot {
  border-color: var(--info);
}

.body {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.outcome {
  color: var(--text-2);
  font-weight: 600;
  font-size: var(--fs-sm);
  text-decoration: none;
}

/* 링크가 줄 전체를 덮어 어디를 눌러도 실행 화면으로 간다(복사 버튼은 위에 있다). */
.outcome::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: var(--radius-sm);
}

.outcome:hover {
  text-decoration: none;
  color: var(--text);
}

.ok .outcome {
  color: color-mix(in srgb, var(--ok) 80%, var(--text-2));
}

.warn .outcome {
  color: color-mix(in srgb, var(--warn) 80%, var(--text-2));
}

.err .outcome {
  color: color-mix(in srgb, var(--err) 85%, var(--text-2));
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 10px;
  font-size: 12px;
  color: var(--muted);
}

.set {
  color: var(--faint);
}

.set::before {
  content: '· ';
}

.meta > span:first-child.set::before {
  content: '';
}

/* 가장 늦은 실행: 점을 채우고 글을 밝게 */
.latest .when,
.latest .meta {
  color: var(--text-2);
}

.latest .outcome {
  color: var(--text);
}

.latest .dot {
  background: var(--accent);
  border-color: var(--accent);
  box-shadow: 0 0 0 4px rgb(250 255 220 / 10%);
}

.latest.err .dot {
  background: var(--err);
  border-color: var(--err);
  box-shadow: 0 0 0 4px var(--err-soft);
}

.latest.warn .dot {
  background: var(--warn);
  border-color: var(--warn);
  box-shadow: 0 0 0 4px var(--warn-soft);
}

.copy {
  position: relative;
  z-index: 2;
  opacity: 0;
  transition: opacity 0.15s var(--ease);
}

.rec:hover .copy,
.copy:focus-visible {
  opacity: 1;
}

@media (hover: none) {
  .copy {
    opacity: 0.6;
  }
}

.more {
  margin: 4px 0 0 calc(76px + 15px + 12px - 12px);
  color: var(--muted);
}

@media (max-width: 760px) {
  .timeline::before {
    left: calc(60px + 7px);
  }

  .rec {
    grid-template-columns: 60px 15px minmax(0, 1fr) auto;
    column-gap: 10px;
  }

  .more {
    margin-left: calc(60px + 15px);
  }
}
</style>
