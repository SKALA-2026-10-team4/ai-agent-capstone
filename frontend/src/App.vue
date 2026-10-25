<script setup>
// 운영 화면 전체의 틀(shell)이다.
// frontend/src/main.js 가 createApp(App) 으로 만들어 #app 에 mount 한다.
// 위쪽에 frontend/src/components/AppTopBar.vue 막대를 두고, 그 아래 main 칸에는 RouterView 로
// frontend/src/router/index.js 가 고른 화면을 그린다.
// 알림을 그리는 frontend/src/components/ToastHost.vue 는 어느 화면에서든 보이도록
// 셸 밖에 항상 둔다.
// 좁은 화면 기준 760px 는 AppTopBar.vue 와 frontend/src/assets/base.css 의 media query 와 같은 값이다.
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppTopBar from '@/components/AppTopBar.vue'
import ToastHost from '@/components/ToastHost.vue'

// 로그인 화면은 메뉴 없이 화면 전체에 그린다. 라우트 표가 meta.public 을 붙인 화면이면
// bare 가 true 가 되어 위쪽 막대를 빼고 main 의 폭 제한과 여백도 없앤다.
const route = useRoute()
const bare = computed(() => !!route.meta.public)
</script>

<template>
  <div class="shell" :class="{ bare }">
    <AppTopBar v-if="!bare" />
    <main :class="bare ? 'bare-page' : 'page'">
      <RouterView />
    </main>
  </div>
  <ToastHost />
</template>

<style scoped>
.shell {
  min-height: 100vh;
}

.shell > .page {
  width: 100%;
}

.bare-page {
  min-height: 100vh;
}
</style>
