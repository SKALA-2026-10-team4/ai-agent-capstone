import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// 화면: 받은 개선안 · Skill 편집 · 버전 비교 · 주문 실행 · 실행 상세 · 수정안. 로그인 화면 말고는 로그인이 필요하다.
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/proposals' },
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/skills/:skill?', name: 'skills', component: () => import('@/views/SkillDraftView.vue') },
    { path: '/compare', name: 'compare', component: () => import('@/views/SnapshotCompareView.vue') },
    { path: '/orders', name: 'orders', component: () => import('@/views/OrderView.vue') },
    { path: '/executions/:id', name: 'execution', component: () => import('@/views/ExecutionView.vue') },
    { path: '/proposals', name: 'proposals', component: () => import('@/views/ProposalListView.vue') },
    { path: '/proposals/:id', name: 'proposal', component: () => import('@/views/ProposalView.vue') },
    // 없어진 화면(예: 예전 /flow)이나 잘못된 주소는 첫 화면으로
    { path: '/:pathMatch(.*)*', redirect: '/proposals' },
  ],
})

router.beforeEach((to) => {
  if (to.meta.public || useAuthStore().loggedIn) return true
  return { path: '/login', query: { next: to.fullPath } }
})

export default router
