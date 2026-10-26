<script setup>
// 수정안 후보 하나의 종류와 경고 표시를 꼬리표로 그린다. 종류(kind)는 '실행 규칙으로 옮기기'
// (encode_rule) 같은 화면 이름으로, 경고는 담당자 수정을 되돌리는 안(is_revert)과 실행 규칙이나 그
// 입력을 지우는 안(weakens_contract)이다. 이름과 판단은 frontend/src/utils/proposalView.js 의
// candidateKindLabel 과 candidateFlags 가 정한다. 경고 둘은 담당자 의도가 있는 요청에서 불합격
// 사유다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.6 절).
// frontend/src/components/ProposalDocument.vue 의 후보 카드, frontend/src/views/ProposalView.vue 의
// 고른 후보, frontend/src/views/DiagnosisView.vue 의 후보 카드가 쓴다.
import { computed } from 'vue'
import { candidateFlags, candidateKindLabel } from '@/utils/proposalView'

// showKind 가 false 면 종류 꼬리표를 빼고 경고만 그린다. 후보 카드 제목에 종류 이름이 이미 있는
// ProposalDocument 가 끈다.
const props = defineProps({
  candidate: { type: Object, default: null },
  showKind: { type: Boolean, default: true },
})

const kind = computed(() => (props.showKind ? candidateKindLabel(props.candidate?.kind) : null))
const flags = computed(() => candidateFlags(props.candidate))
</script>

<template>
  <span v-if="kind || flags.length" class="tags">
    <span v-if="kind" class="tag" :class="{ rule: candidate.kind === 'encode_rule' }">{{ kind }}</span>
    <span v-for="f in flags" :key="f.key" class="tag err" :title="f.title">{{ f.label }}</span>
  </span>
</template>

<style scoped>
.tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

.tag {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--text-2);
  white-space: nowrap;
}

.tag.rule {
  background: var(--accent-soft);
  color: var(--accent);
}

.tag.err {
  background: var(--err-soft);
  color: var(--err);
}
</style>
