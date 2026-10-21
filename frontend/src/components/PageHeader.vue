<script setup>
// 화면 맨 위 제목 줄: 아이콘 · 제목 · 한 줄 설명 · 오른쪽 동작(slot).
defineProps({
  title: { type: String, required: true },
  description: { type: String, default: null },
  icon: { type: [Object, Function], default: null },
})
</script>

<template>
  <header class="page-header">
    <div class="titles">
      <slot name="crumbs" />
      <h1>
        <span v-if="icon" class="icon"><component :is="icon" :size="22" /></span>
        {{ title }}
        <slot name="badges" />
      </h1>
      <p v-if="description" class="desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.titles {
  min-width: 0;
}

h1 {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 4px 0 6px;
}

.icon {
  display: inline-grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius);
  background: var(--accent-soft);
  color: var(--accent);
}

.desc {
  margin: 0;
  color: var(--text-2);
  max-width: 720px;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
