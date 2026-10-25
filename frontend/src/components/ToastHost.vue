<script setup>
// 화면 오른쪽 아래에 짧은 알림(토스트)을 쌓아 보여 준다.
// frontend/src/App.vue 가 셸 밖에 하나만 둔다.
// 알림 목록은 frontend/src/stores/toast.js 가 가지고 있고, 화면들은 그 store 의 show 로
// 알림을 띄운다. 지금은 frontend/src/views/ProposalView.vue(승인, 거절, 초안 적용)와
// frontend/src/views/SkillDraftView.vue(저장, 되돌리기, 새 Skill 초안 만들기와 지우기)가 띄운다.
// 알림은 show 에 넘긴 시간(기본 3500 ms)이 지나면 저절로 사라지고,
// 닫기 버튼은 store 의 dismiss 를 부른다.
import { CircleAlert, CircleCheck, Info, X } from 'lucide-vue-next'
import { useToastStore } from '@/stores/toast'

const toast = useToastStore()
// 알림 톤(ok, err, info)별 아이콘이다. 표에 없는 톤은 Info 아이콘으로 그린다.
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
  padding: 10px 8px 10px 16px;
  border-radius: var(--radius);
  background: var(--surface-3);
  border: 1px solid var(--border-strong);
  box-shadow: var(--shadow-lg);
  color: var(--text);
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
  transition:
    opacity 0.2s var(--ease),
    transform 0.2s var(--ease);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
