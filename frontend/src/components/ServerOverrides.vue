<script setup>
// service 가 제안서를 저장할 때 실행 장부로 다시 계산해 Agent 판정을 덮은 칸(server_overrides)을 표로
// 그린다. 줄 모양은 frontend/src/utils/proposalView.js 의 overrideRows 가 만든
// [{ candidate_no, field, label, agent, server }] 이다. 재판정 규칙은
// docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7 절이고 승인 가능 여부는 서버 값을 따른다.
// 줄이 없으면 아무것도 그리지 않는다. frontend/src/views/ProposalView.vue 와
// frontend/src/views/DiagnosisView.vue 가 쓴다.
defineProps({ rows: { type: Array, required: true } })
</script>

<template>
  <table v-if="rows.length" class="overrides">
    <thead>
      <tr>
        <th>후보</th>
        <th>판정 칸</th>
        <th>Agent 값</th>
        <th>서버 값</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="(r, i) in rows" :key="i">
        <td>{{ r.candidate_no != null ? `${r.candidate_no}번` : '제안서' }}</td>
        <td :title="r.field">{{ r.label }}</td>
        <td class="agent">{{ r.agent }}</td>
        <td class="server">{{ r.server }}</td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.overrides {
  width: 100%;
  margin-top: 10px;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

th,
td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  text-align: left;
}

th {
  color: var(--muted);
  font-weight: 600;
}

.agent {
  color: var(--muted);
  text-decoration: line-through;
}

.server {
  font-weight: 700;
}
</style>
