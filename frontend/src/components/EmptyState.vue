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
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 10px;
  padding: 56px 24px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
  background: radial-gradient(420px 220px at 50% 0%, rgb(250 255 220 / 4%), transparent 70%), rgb(255 255 255 / 1.5%);
}

.icon {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  color: var(--muted);
  box-shadow: 0 0 0 10px rgb(255 255 255 / 2%), 0 0 0 22px rgb(255 255 255 / 1.5%);
  margin-bottom: 10px;
}

strong {
  font-family: var(--display);
  font-size: 18px;
  letter-spacing: -0.01em;
}

.body {
  color: var(--muted);
  font-size: var(--fs-sm);
  max-width: 460px;
}
</style>
