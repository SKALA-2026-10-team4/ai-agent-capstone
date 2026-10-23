<script setup>
// 화면 맨 위의 제목 줄이다. 아이콘, 제목, 한 줄 설명을 그리고 오른쪽에 actions slot 을 둔다.
// 제목 위에는 back 링크와 crumbs slot 을, 제목 옆에는 badges slot 을 넣을 수 있다.
// frontend/src/views 아래 화면 7개(DiagnosisView, ExecutionView, OrderView, ProposalListView,
// ProposalView, SkillDraftView, SnapshotCompareView)가 모두 이 컴포넌트로 머리를 그린다.
// actions 는 ProposalListView 의 탭이, badges 는 ProposalView 의 상태 배지가 쓰고,
// crumbs 를 쓰는 화면은 지금 없다.
import { ArrowLeft } from 'lucide-vue-next'

defineProps({
  title: { type: String, required: true },
  description: { type: String, default: null },
  // lucide-vue-next 아이콘 컴포넌트다.
  icon: { type: [Object, Function], default: null },
  // 제목 위에 왼쪽 화살표와 함께 보이는 이전 화면 링크다. { to, label } 모양이고
  // ExecutionView(주문 실행 · 기록으로)와 ProposalView(받은 제안서로)가 넘긴다.
  back: { type: Object, default: null },
})
</script>

<template>
  <header class="page-header">
    <div class="titles">
      <RouterLink v-if="back" :to="back.to" class="crumb"><ArrowLeft :size="14" /> {{ back.label }}</RouterLink>
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

.crumb {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-sm);
  color: var(--muted);
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
