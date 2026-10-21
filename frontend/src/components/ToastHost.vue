<script setup>
import { CircleAlert, CircleCheck, Info, X } from 'lucide-vue-next'
import { useToastStore } from '@/stores/toast'

const toast = useToastStore()
const ICON = { ok: CircleCheck, err: CircleAlert, info: Info }
</script>

<template>
  <div class="host" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in toast.items" :key="t.id" class="toast" :class="t.tone">
        <component :is="ICON[t.tone] ?? Info" :size="18" />
        <span>{{ t.message }}</span>
        <button class="ghost sm" aria-label="닫기" @click="toast.dismiss(t.id)"><X :size="14" /></button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.host {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 50;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: min(420px, calc(100vw - 32px));
}

.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 8px 10px 14px;
  border-radius: var(--radius);
  background: var(--surface);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
  font-size: var(--fs-sm);
}

.toast span {
  flex: 1;
}

.toast.ok svg:first-child {
  color: var(--ok);
}

.toast.err svg:first-child {
  color: var(--err);
}

.toast.info svg:first-child {
  color: var(--accent);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
