<script setup>
// 제안서 상태 배지다(검토 대기, 통과한 후보 없음, 승인됨, 거절됨).
// 라벨과 색 톤은 frontend/src/utils/proposalView.js 의 STATUS 를 따르고,
// 표에 없는 상태는 값을 그대로 보여 준다.
// 상태 값(proposed, no_valid_fix, approved, rejected)은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalStatus.java 의 상수와 같다.
// frontend/src/views/ProposalListView.vue 의 목록 항목과 frontend/src/views/ProposalView.vue 의
// 제목 옆(PageHeader 의 badges slot)에 쓴다.
import { STATUS } from '@/utils/proposalView'

defineProps({ status: { type: String, default: null } })
</script>

<template>
  <span class="status" :class="STATUS[status]?.tone">{{ STATUS[status]?.label ?? status }}</span>
</template>

<style scoped>
.status {
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--text-2);
}

/* 화면 제목(PageHeader 의 h1) 안에 넣은 배지는 조금 더 크게 */
h1 .status {
  padding: 3px 10px;
}

.status.info {
  background: var(--accent-soft);
  color: var(--accent);
}

.status.ok {
  background: var(--ok-soft);
  color: var(--ok);
}

.status.err {
  background: var(--err-soft);
  color: var(--err);
}
</style>
