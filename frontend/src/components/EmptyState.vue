<script setup>
// 보여 줄 항목이 없을 때 띄우는 안내 상자다. 아이콘, 제목(title), 그리고 기본 slot 에 넣은 설명이나
// 다음에 할 일을 가운데 정렬로 쌓아 그린다. icon 에는 lucide-vue-next 아이콘 컴포넌트를 넘긴다.
// frontend/src/views/ProposalListView.vue 가 고른 탭에 보일 제안서가 없을 때 쓴다.
defineProps({
  title: { type: String, required: true },
  icon: { type: [Object, Function], default: null },
})
</script>

<template>
  <div class="empty">
    <span v-if="icon" class="icon"><component :is="icon" :size="28" /></span>
    <strong>{{ title }}</strong>
    <div class="body"><slot /></div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 8px;
  padding: 40px 20px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--surface);
}

.icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--surface-2);
  color: var(--muted);
}

.body {
  color: var(--text-2);
  font-size: var(--fs-sm);
  max-width: 460px;
}
</style>
