<script setup>
import { computed, ref } from 'vue'
import { EVENT_LABELS, isErrorEvent } from '@/utils/traceRun'
import { describe } from '@/utils/eventView'
import FieldList from './FieldList.vue'

// order-agent trace events 를 시간 순서로, 단계(skill)별로 묶어 문장으로 보여 준다.
// 원본 data 는 각 이벤트의 "원본" 을 눌러야 보인다.
const props = defineProps({
  events: { type: Array, required: true },
  skill: { type: String, default: null },
})

// 모델 입력·원래 응답은 길어서 기본으로 숨긴다.
const NOISY = new Set(['DECISION_INPUT', 'MODEL_RESPONSE', 'MODEL_REQUEST', 'CANDIDATES_OFFERED'])
const showAll = ref(false)
const raw = ref(new Set())

const matching = computed(() => props.events.filter((e) => !props.skill || e.skill_id === props.skill))
const shown = computed(() => matching.value.filter((e) => showAll.value || !NOISY.has(e.kind)))
const hidden = computed(() => matching.value.length - shown.value.length)

// 연속된 같은 skill 의 이벤트를 한 묶음으로. skill 이 없는 이벤트는 "실행 준비·마무리".
const groups = computed(() => {
  const out = []
  for (const e of shown.value) {
    const key = e.skill_id ?? null
    if (!out.length || out.at(-1).skill !== key) out.push({ skill: key, events: [] })
    out.at(-1).events.push({ ...e, view: describe(e), error: isErrorEvent(e) })
  }
  return out
})

function toggleRaw(seq) {
  const next = new Set(raw.value)
  if (next.has(seq)) next.delete(seq)
  else next.add(seq)
  raw.value = next
}
const pretty = (v) => JSON.stringify(v, null, 2)
</script>

<template>
  <div class="timeline">
    <label class="toggle muted">
      <input v-model="showAll" type="checkbox" />
      모델 입력·응답 이벤트도 보기 <span v-if="!showAll && hidden">({{ hidden }}개 숨김)</span>
    </label>

    <section v-for="(g, i) in groups" :key="i" class="group">
      <h3>{{ g.skill ? `${g.skill} 단계` : '실행 준비 · 마무리' }}</h3>
      <div v-for="e in g.events" :key="e.sequence" class="event" :class="{ err: e.error }">
        <div class="line">
          <span class="seq">{{ e.sequence }}</span>
          <span class="kind">{{ EVENT_LABELS[e.kind] ?? e.kind }}</span>
          <span class="title">{{ e.view.title }}</span>
          <small class="muted time">{{ e.at.slice(11, 19) }}</small>
          <button class="raw-btn" @click="toggleRaw(e.sequence)">{{ raw.has(e.sequence) ? '닫기' : '원본' }}</button>
        </div>
        <p v-if="e.view.note" class="note">{{ e.view.note }}</p>
        <FieldList v-if="e.view.fields.length" :fields="e.view.fields" />
        <pre v-if="raw.has(e.sequence)">{{ pretty(e.data) }}</pre>
      </div>
    </section>
    <p v-if="!shown.length" class="muted">이벤트가 없다.</p>
  </div>
</template>

<style scoped>
.timeline {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.group h3 {
  font-size: 13px;
  margin: 0 0 6px;
  color: var(--muted);
}

.event {
  padding: 8px 10px;
  margin-bottom: 4px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
}

.event.err {
  border-color: var(--err);
  background: var(--del-bg);
}

.line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.seq {
  min-width: 22px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.kind {
  font-size: 12px;
  color: var(--muted);
  white-space: nowrap;
}

.title {
  font-weight: 600;
}

.time {
  margin-left: auto;
}

.raw-btn {
  padding: 0 8px;
  font-size: 11px;
}

.note {
  margin: 4px 0 0 30px;
  font-size: 13px;
  color: var(--muted);
}

.event :deep(.fields) {
  margin: 6px 0 0 30px;
}

pre {
  margin: 8px 0 0;
  padding: 8px 12px;
  background: var(--bg);
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
}
</style>
