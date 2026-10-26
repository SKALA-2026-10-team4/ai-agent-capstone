<script setup>
// 운영 화면 전체의 틀(shell)이다.
// frontend/src/main.js 가 createApp(App) 으로 만들어 #app 에 mount 한다.
// 왼쪽에 frontend/src/components/AppSidebar.vue 메뉴를 두고, 오른쪽 main 칸에는 RouterView 로
// frontend/src/router/index.js 가 고른 화면을 그린다.
// 알림을 그리는 frontend/src/components/ToastHost.vue 는 어느 화면에서든 보이도록
// 셸 밖에 항상 둔다.
// style 의 좁은 화면 기준 900px 는 AppSidebar.vue 의 media query 와
// frontend/src/components/DiffView.vue 의 기본 보기 기준과 같은 값이므로 함께 고친다.
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import ToastHost from '@/components/ToastHost.vue'

// 로그인 화면은 메뉴 없이 가운데에만 그린다. 라우트 표가 meta.public 을 붙인 화면이면
// bare 가 true 가 되어 사이드바를 빼고 한 칸짜리 grid 로 그린다.
const route = useRoute()
const bare = computed(() => !!route.meta.public)
</script>

<template>
  <div class="shell" :class="{ bare }">
    <AppSidebar v-if="!bare" />
    <main class="page">
      <RouterView />
    </main>
  </div>
  <ToastHost />
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: var(--sidebar-w) minmax(0, 1fr);
  min-height: var(--view-h);
}

.shell.bare {
  grid-template-columns: minmax(0, 1fr);
}

.shell > .page {
  width: 100%;
}

@media (max-width: 900px) {
  .shell {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
