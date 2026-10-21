<script setup>
// 비어 있는 화면 안내: 아이콘 · 제목 · 설명 · 다음에 할 일(slot).
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
