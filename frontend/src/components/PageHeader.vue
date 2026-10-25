<script setup>
// 화면 맨 위의 제목 줄이다. 구역 번호와 묶음 이름을 쓴 작은 줄(eyebrow), 큰 디스플레이 제목, 한 줄 설명을
// 그리고 오른쪽에 actions slot 을 둔다. 제목 위에는 back 링크와 crumbs slot 을, 제목 옆에는 badges slot 을
// 넣을 수 있다. 오른쪽 뒤에는 frontend/src/components/OrbitArt.vue 의 옅은 궤도 선을 깔아 둔다.
// 구역 번호('01 · 개선안')는 지금 주소로 frontend/src/router/nav.js 의 sectionOf 가 정하고, eyebrow 를
// 넘기면 그 글을 대신 쓴다.
// frontend/src/views 아래 화면 7개(DiagnosisView, ExecutionView, OrderView, ProposalListView,
// ProposalView, SkillDraftView, SnapshotCompareView)가 모두 이 컴포넌트로 머리를 그린다.
// actions 는 ProposalListView 의 탭이, badges 는 ProposalView 와 ExecutionView 의 상태 배지가 쓰고,
// crumbs 를 쓰는 화면은 지금 없다.
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft } from 'lucide-vue-next'
import { sectionOf } from '@/router/nav'
import OrbitArt from './OrbitArt.vue'

const props = defineProps({
  title: { type: String, required: true },
  description: { type: String, default: null },
  // 머리 위 작은 줄의 글이다. 없으면 주소로 구역 번호와 묶음 이름을 찾는다.
  eyebrow: { type: String, default: null },
  // 제목 위에 왼쪽 화살표와 함께 보이는 이전 화면 링크다. { to, label } 모양이고
  // ExecutionView(주문 실행 · 기록으로)와 ProposalView(받은 개선안으로)가 넘긴다.
  back: { type: Object, default: null },
})

const route = useRoute()
const section = computed(() => sectionOf(route.path))
</script>

<template>
  <header class="page-header">
    <OrbitArt class="orbit" />
    <div class="titles">
      <RouterLink v-if="back" :to="back.to" class="crumb"><ArrowLeft :size="14" /> {{ back.label }}</RouterLink>
      <slot name="crumbs" />
      <p v-if="props.eyebrow || section" class="eyebrow">
        <template v-if="props.eyebrow">{{ props.eyebrow }}</template>
        <template v-else>
          <span class="no">{{ section.no }}</span>
          <span class="rule" aria-hidden="true" />
          <span>{{ section.group }}</span>
        </template>
      </p>
      <h1>
        <span class="title-text">{{ title }}</span>
        <slot name="badges" />
      </h1>
      <p v-if="description" class="desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.page-header {
  position: relative;
  isolation: isolate;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  min-height: 150px;
  margin-bottom: 28px;
  padding-top: 8px;
}

.orbit {
  position: absolute;
  z-index: -1;
  top: -150px;
  right: -60px;
  width: 440px;
  height: 440px;
}

.titles {
  min-width: 0;
  max-width: 860px;
}

.crumb {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 14px;
  font-size: var(--fs-sm);
  color: var(--muted);
}

.crumb:hover {
  color: var(--text);
  text-decoration: none;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 14px;
  font-size: var(--fs-xs);
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--muted);
}

.no {
  font-family: var(--mono);
  font-weight: 500;
  color: var(--accent);
}

.rule {
  width: 28px;
  height: 1px;
  background: var(--border-strong);
}

h1 {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin: 0 0 12px;
  font-family: var(--display);
  font-size: var(--fs-display);
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -0.035em;
}

.title-text {
  min-width: 0;
  overflow-wrap: anywhere;
}

.desc {
  margin: 0;
  max-width: 680px;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.65;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

@media (max-width: 760px) {
  .page-header {
    min-height: 0;
    margin-bottom: 20px;
  }

  .orbit {
    top: -110px;
    right: -120px;
    width: 300px;
    height: 300px;
  }

  .desc {
    font-size: var(--fs-md);
  }
}
</style>
