<script setup>
// order-agent trace 의 events 를 받은 순서(시간 순서) 그대로 두고, 연속된 같은 단계(skill)끼리 묶어
// 문장으로 보여 준다. 원본 data 는 각 이벤트의 "원본" 을 눌러야 보인다.
// frontend/src/views/ExecutionView.vue 의 이벤트 칸이 쓰고,
// skill 을 주면 그 단계의 이벤트만 보여 준다.
// 이벤트를 문장으로 바꾸는 규칙은 frontend/src/utils/eventView.js 에 있다. groupBySkill 이 묶으면서
// 이벤트마다 describe 로 제목·설명·항목을 만들고, eventLabel 이 종류 이름을 정한다.
// isNoisy 는 모델 입력과 응답처럼 길어서 기본으로 숨길 종류를 정한다.
// 이벤트 종류의 라벨, 문장, 숨김 여부는 그 파일의 EVENT_KINDS 한 곳에서 고친다.
// 항목 목록은 frontend/src/components/FieldList.vue 가 그린다.
import { computed, ref } from 'vue'
import { eventLabel, groupBySkill, isNoisy } from '@/utils/eventView'
import { clockOf } from '@/utils/format'
import FieldList from './FieldList.vue'

const props = defineProps({
  events: { type: Array, required: true },
  skill: { type: String, default: null },
})

// 숨기는 종류까지 모두 보일지 정하는 체크박스 값이다.
const showAll = ref(false)
// 원본 data 를 펼친 이벤트의 sequence 모음이다.
const raw = ref(new Set())

// skill 이 있으면 그 단계 이벤트만 남기고, 모두 보기를 켜지 않았으면 숨기는 종류를 뺀다.
// hidden 은 숨긴 수로 체크박스 옆에 보여 주고, groups 는 화면에 그릴 묶음 목록이다.
// 묶음의 이벤트마다 view(문장)와 error(오류 이벤트 표시)가 붙는다.
const matching = computed(() => props.events.filter((e) => !props.skill || e.skill_id === props.skill))
const shown = computed(() => matching.value.filter((e) => showAll.value || !isNoisy(e.kind)))
const hidden = computed(() => matching.value.length - shown.value.length)
const groups = computed(() => groupBySkill(shown.value))

/**
 * 이벤트 하나의 원본 data 펼침을 켜고 끈다. 이벤트마다 있는 '원본' 버튼(펼친 뒤에는 '닫기')이
 * 부른다. 이벤트는 sequence 로 구분하고, 복사한 Set 을 고친 뒤 raw 에 통째로 넣는다.
 */
function toggleRaw(seq) {
  const next = new Set(raw.value)
  if (next.has(seq)) next.delete(seq)
  else next.add(seq)
  raw.value = next
}
/** 원본 data 를 두 칸 들여쓴 JSON 글로 바꾼다. */
const pretty = (v) => JSON.stringify(v, null, 2)
</script>

<template>
  <div class="timeline">
    <label class="toggle muted">
      <input v-model="showAll" type="checkbox" />
      모델 입력·응답 이벤트도 보기 <span v-if="!showAll && hidden">({{ hidden }}개 숨김)</span>
    </label>

    <section v-for="(g, i) in groups" :key="i" class="group">
      <h3><span class="g-dot" aria-hidden="true" />{{ g.skill ? `${g.skill} 단계` : '실행 준비 · 마무리' }}</h3>
      <div v-for="e in g.events" :key="e.sequence" class="event" :class="{ err: e.error }">
        <div class="line">
          <span class="seq">{{ e.sequence }}</span>
          <span class="kind">{{ eventLabel(e.kind) }}</span>
          <span class="title">{{ e.view.title }}</span>
          <small class="time">{{ clockOf(e.at) }}</small>
          <button class="raw-btn" @click="toggleRaw(e.sequence)">{{ raw.has(e.sequence) ? '닫기' : '원본' }}</button>
        </div>
        <p v-if="e.view.note" class="note">{{ e.view.note }}</p>
        <FieldList v-if="e.view.fields.length" :fields="e.view.fields" />
        <pre v-if="raw.has(e.sequence)">{{ pretty(e.data) }}</pre>
      </div>
    </section>
    <p v-if="!shown.length" class="muted">이벤트가 없어요.</p>
  </div>
</template>

<style scoped>
.timeline {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-xs);
}

.group {
  position: relative;
  padding-left: 18px;
}

/* 묶음마다 가는 세로선 */
.group::before {
  content: '';
  position: absolute;
  top: 18px;
  bottom: 4px;
  left: 4px;
  width: 1px;
  background: var(--border-strong);
}

.group h3 {
  position: relative;
  display: flex;
  align-items: center;
  margin: 0 0 8px;
  font-size: var(--fs-xs);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--muted);
}

.g-dot {
  position: absolute;
  left: -18px;
  width: 9px;
  height: 9px;
  border: 1.5px solid var(--muted);
  border-radius: 50%;
  background: var(--surface);
}

.event {
  padding: 9px 12px;
  margin-bottom: 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: rgb(255 255 255 / 2%);
}

.event.err {
  border-color: rgb(255 149 132 / 45%);
  background: var(--del-bg);
}

.line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.seq {
  min-width: 22px;
  font-family: var(--mono);
  font-size: 11px;
  color: var(--faint);
  font-variant-numeric: tabular-nums;
}

.kind {
  font-size: var(--fs-xs);
  color: var(--muted);
  white-space: nowrap;
}

.title {
  min-width: 0;
  font-weight: 600;
  color: var(--text);
  overflow-wrap: anywhere;
}

.time {
  margin-left: auto;
  font-family: var(--mono);
  font-size: 11px;
  color: var(--muted);
}

.raw-btn {
  padding: 1px 9px;
  font-size: 11px;
  font-weight: 500;
  color: var(--muted);
}

.note {
  margin: 4px 0 0 32px;
  font-size: var(--fs-sm);
  color: var(--muted);
}

.event :deep(.fields) {
  margin: 6px 0 0 32px;
}

pre {
  margin: 8px 0 0;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-deep);
  overflow-x: auto;
  font-size: 12px;
}

@media (max-width: 760px) {
  .note,
  .event :deep(.fields) {
    margin-left: 0;
  }
}
</style>
