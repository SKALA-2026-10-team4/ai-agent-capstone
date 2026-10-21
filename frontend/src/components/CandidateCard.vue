<script setup>
import { computed } from 'vue'
import { Check, Minus, Star, X } from 'lucide-vue-next'
import { KIND } from '@/utils/proposalView'

// 개선안 후보 하나. 유효성 기준 5개를 담당자가 읽는 문장으로 보여 준다(설계도 3절).
// 1·2·3 을 모두 통과해야 "적용 가능", 순위는 4·5(연결 정상 → 고치는 범위가 작은 순).
const props = defineProps({
  candidate: { type: Object, required: true },
  recommended: { type: Boolean, default: false },
  selected: { type: Boolean, default: false },
})
defineEmits(['select'])

const r = computed(() => props.candidate.results)
const criteria = computed(() => {
  if (!r.value) return []
  const keeps = r.value.regression.passed === r.value.regression.total
  return [
    { ok: r.value.functional, text: r.value.functional ? '실패한 주문이 다시 처리돼요' : '실패한 주문이 여전히 실패해요' },
    { ok: keeps, text: `기존 주문 ${r.value.regression.passed}/${r.value.regression.total}건이 그대로 처리돼요` },
    { ok: r.value.intent, text: r.value.intent ? '내가 바꾸려던 규칙이 유지돼요' : '내가 바꾼 내용을 되돌려요' },
    { ok: r.value.contract, text: r.value.contract ? 'Skill 사이 연결이 모두 맞아요' : 'Skill 사이 연결이 끊긴 곳이 남아요' },
    { ok: null, text: `고치는 Skill ${r.value.footprint}개` },
  ]
})
const kind = computed(() => KIND[props.candidate.kind] ?? { label: props.candidate.kind, icon: null })
</script>

<template>
  <button class="cand" :class="{ selected, invalid: candidate.valid === false }" @click="$emit('select')">
    <span v-if="recommended" class="ribbon"><Star :size="12" /> AI 추천</span>
    <span class="top">
      <span class="kind-icon"><component :is="kind.icon" v-if="kind.icon" :size="18" /></span>
      <span class="titles">
        <strong>{{ kind.label }}</strong>
        <small>{{ kind.hint }}</small>
      </span>
    </span>

    <span class="verdict" :class="candidate.valid ? 'ok' : 'no'">
      {{ candidate.valid ? '적용 가능' : '적용 불가' }}
      <small v-if="candidate.rank">· {{ candidate.rank }}순위</small>
    </span>

    <ul v-if="criteria.length" class="criteria">
      <li v-for="(c, i) in criteria" :key="i" :class="c.ok === true ? 'ok' : c.ok === false ? 'no' : 'neutral'">
        <Check v-if="c.ok === true" :size="15" />
        <X v-else-if="c.ok === false" :size="15" />
        <Minus v-else :size="15" />
        <span>{{ c.text }}</span>
      </li>
    </ul>
    <span v-else class="muted small">아직 검증 결과가 없어요</span>

    <span class="skills">
      <span v-for="p in candidate.patches" :key="p.skill" class="chip">{{ p.skill }}</span>
    </span>
  </button>
</template>

<style scoped>
.cand {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  width: 100%;
  padding: 16px;
  text-align: left;
  font-weight: 400;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.cand:hover:not(:disabled) {
  background: var(--surface);
  border-color: var(--border-strong);
  box-shadow: var(--shadow);
}

.cand.selected {
  border-color: var(--accent);
  box-shadow: var(--focus);
}

.cand.invalid .titles,
.cand.invalid .criteria {
  opacity: 0.75;
}

.ribbon {
  position: absolute;
  top: -10px;
  right: 14px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: var(--fs-xs);
  font-weight: 700;
}

.top {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.kind-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  color: var(--text-2);
  flex: none;
}

.selected .kind-icon {
  background: var(--accent-soft);
  color: var(--accent);
}

.titles {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.titles small {
  color: var(--muted);
}

.verdict {
  align-self: flex-start;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: var(--fs-xs);
  font-weight: 700;
}

.verdict.ok {
  background: var(--ok-soft);
  color: var(--ok);
}

.verdict.no {
  background: var(--err-soft);
  color: var(--err);
}

.criteria {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--fs-sm);
}

.criteria li {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  color: var(--text-2);
}

.criteria svg {
  flex: none;
  margin-top: 3px;
}

.criteria .ok svg {
  color: var(--ok);
}

.criteria .no {
  color: var(--err);
  font-weight: 600;
}

.criteria .neutral svg {
  color: var(--muted);
}

.skills {
  margin-top: auto;
}
</style>
