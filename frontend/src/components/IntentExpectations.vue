<script setup>
// 담당자 의도(intent)의 기대 결과(expectations)를 범위와 확인 조건으로 나눠 그린다. 모양은
// docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3 절의 { scope, checks } 이고, scope 가 null 이면
// 모든 기준 주문에 붙는 조건이다. 출처(intent.source)가 있으면 담당자가 적은 것인지 SkillOps 가
// 추정한 것인지 함께 보여 준다(frontend/src/utils/proposalView.js 의 intentSourceLabel).
// 기대 결과가 없으면 아무것도 그리지 않는다. 의도 글(intent.text)은 부르는 화면이 따로 보여 준다.
// frontend/src/views/ProposalView.vue 의 변경 의도 절과 frontend/src/views/DiagnosisView.vue 가 쓴다.
import { computed } from 'vue'
import { intentSourceLabel } from '@/utils/proposalView'

const props = defineProps({ intent: { type: Object, default: null } })

const expectations = computed(() => props.intent?.expectations ?? [])
const source = computed(() => intentSourceLabel(props.intent?.source))
</script>

<template>
  <div v-if="expectations.length" class="expectations">
    <p class="head">
      <strong>기대 결과 {{ expectations.length }}개</strong>
      <span v-if="source" class="tag" :class="intent.source">{{ source }}</span>
    </p>
    <ul>
      <li v-for="(e, i) in expectations" :key="i">
        <span class="scope">
          <template v-if="e.scope">범위 <code>{{ e.scope }}</code></template>
          <template v-else>모든 기준 주문</template>
        </span>
        <span class="arrow">→</span>
        <span class="checks"><code v-for="c in e.checks" :key="c">{{ c }}</code></span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.expectations {
  margin-top: 12px;
  font-size: var(--fs-sm);
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 6px;
}

ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 8px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
}

.scope {
  color: var(--text-2);
}

.arrow {
  color: var(--muted);
}

.checks {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

code {
  overflow-wrap: anywhere;
}

.tag {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--muted);
}

.tag.owner {
  background: var(--ok-soft);
  color: var(--ok);
}

.tag.inferred {
  background: var(--warn-soft);
  color: var(--warn);
}
</style>
