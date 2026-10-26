<script setup>
// 화면 맨 위의 제목 줄이다. 아이콘, 제목, 한 줄 설명을 그리고 오른쪽에 actions slot 을 둔다.
// 제목 위에는 back 링크와 crumbs slot 을, 제목 옆에는 badges slot 을 넣을 수 있다.
// frontend/src/views 아래 화면 7개(DiagnosisView, ExecutionView, OrderView, ProposalListView,
// ProposalView, SkillDraftView, SnapshotCompareView)가 모두 이 컴포넌트로 머리를 그린다.
// badges 는 ProposalView 의 상태 배지가 쓰고, actions 와 crumbs 를 쓰는 화면은 지금 없다.
// descriptionTip 을 켜면 설명을 제목 아래 글 대신 제목 옆 ● 의 툴팁으로 보여 준다.
// 지금은 ProposalListView, SkillDraftView, DiagnosisView, OrderView 가 켠다. 나머지 화면
// (ProposalView, ExecutionView, SnapshotCompareView)은 설명 줄을 그대로 보여 준다.
import { ArrowLeft } from 'lucide-vue-next'

defineProps({
  title: { type: String, required: true },
  description: { type: String, default: null },
  // lucide-vue-next 아이콘 컴포넌트다.
  icon: { type: [Object, Function], default: null },
  // 제목 위에 왼쪽 화살표와 함께 보이는 이전 화면 링크다. { to, label } 모양이고
  // ExecutionView(테스트 목록으로)와 ProposalView(수정안 목록으로)가 넘긴다.
  back: { type: Object, default: null },
  // true 면 description 을 마우스를 올리거나 키보드로 고를 때만 보이는 툴팁으로 그린다.
  descriptionTip: { type: Boolean, default: false },
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
        <!-- 화면 설명 툴팁이다. 탭으로 고를 수 있게 tabindex 를 두고, 화면 읽기 프로그램은 aria-label 로 읽는다. -->
        <span v-if="description && descriptionTip" class="tip" tabindex="0" :aria-label="description">
          <!-- 정보 표시 글자다. 뜻은 aria-label 이 전하므로 화면 읽기 프로그램에서는 숨긴다. -->
          <span class="tip-mark" aria-hidden="true">i</span>
          <span class="tip-text" role="tooltip">{{ description }}</span>
        </span>
        <slot name="badges" />
      </h1>
      <p v-if="description && !descriptionTip" class="desc">{{ description }}</p>
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

/* 제목 옆 ● 이다. 가운데에 i 를 두고, 마우스를 올리거나 포커스하면 아래로 설명 상자를 띄운다 */
.tip {
  position: relative;
  display: inline-grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--accent);
  cursor: help;
}

/* ● 안의 i 다. 강조색(라임) 면 위 글자라 --on-fill(남색)을 쓴다 */
.tip-mark {
  color: var(--on-fill);
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 12px;
  font-style: italic;
  font-weight: 700;
  line-height: 1;
}

.tip:focus-visible {
  outline: none;
  box-shadow: var(--focus);
}

.tip-text {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  z-index: 30;
  width: max-content;
  max-width: 320px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  box-shadow: var(--shadow);
  color: var(--text-2);
  font-size: var(--fs-sm);
  font-weight: 400;
  line-height: 1.5;
  visibility: hidden;
  opacity: 0;
  transition: opacity 0.12s;
}

.tip:hover .tip-text,
.tip:focus .tip-text {
  visibility: visible;
  opacity: 1;
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
