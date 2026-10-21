<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import ToastHost from '@/components/ToastHost.vue'

// 로그인 화면은 메뉴 없이 가운데에만 그린다.
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
  min-height: 100vh;
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
