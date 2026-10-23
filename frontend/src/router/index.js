// 운영 화면의 라우트 표와 로그인 가드다. frontend/src/main.js 가 app.use(router) 로 설치하고,
// 401 처리기에서 router.currentRoute 와 router.push 를 쓴다.
// 모든 화면을 lazy import 로 불러오므로 화면 코드는 그 화면을 처음 열 때 내려받는다.
// 첫 화면과 로그인 경로는 frontend/src/router/paths.js 에서 고친다.
// 화면 사이의 이동은 route name 이 아니라 경로 문자열로 한다. RouterLink, router.push,
// router.replace 에 경로가 직접 적혀 있고 frontend/src/utils/traceRun.js 의 executionPath 도
// /executions/ 주소를 만들므로, 경로를 바꾸면 그런 링크도 함께 고친다.
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { HOME_PATH, LOGIN_PATH, loginRedirect } from './paths'

// 화면은 받은 제안서 목록(첫 화면), 제안서 상세, Skill 편집, 버전 비교, 검증·진단 기록, 주문 실행,
// 실행 상세, 로그인이다. 로그인 화면 말고는 로그인이 필요하다.
// /skills/:skill? 는 :skill 이 없으면 화면이 세트의 첫 Skill 로 옮긴다.
// /diagnoses/:threadId? 는 threadId 가 없으면 목록이고, ?change_id= 가 붙으면
// 저장 직후의 전달 대기다.
// meta.public 은 로그인 없이 열리는 화면 표시이며 frontend/src/App.vue 도 이 값으로 메뉴를 뺀다.
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: HOME_PATH },
    { path: LOGIN_PATH, name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/skills/:skill?', name: 'skills', component: () => import('@/views/SkillDraftView.vue') },
    { path: '/compare', name: 'compare', component: () => import('@/views/SnapshotCompareView.vue') },
    { path: '/diagnoses/:threadId?', name: 'diagnoses', component: () => import('@/views/DiagnosisView.vue') },
    { path: '/orders', name: 'orders', component: () => import('@/views/OrderView.vue') },
    { path: '/executions/:id', name: 'execution', component: () => import('@/views/ExecutionView.vue') },
    { path: '/proposals', name: 'proposals', component: () => import('@/views/ProposalListView.vue') },
    { path: '/proposals/:id', name: 'proposal', component: () => import('@/views/ProposalView.vue') },
    // 없어진 화면(예: 예전 /flow)이나 잘못된 주소는 첫 화면으로 보낸다.
    { path: '/:pathMatch(.*)*', redirect: HOME_PATH },
  ],
})

// 로그인 가드다. meta.public 화면이거나 로그인 상태(frontend/src/stores/auth.js 의 loggedIn)면
// 그대로 보낸다. 아니면 가려던 주소(fullPath)를 next 로 담아 로그인 화면으로 보내고,
// 로그인에 성공하면 frontend/src/views/LoginView.vue 가 그 주소로 돌아간다.
// loggedIn 은 만료 시각도 보지만 session 이 바뀔 때만 다시 계산하는 computed 다. 그래서 토큰이
// 만료된 직후의 이동은 이 가드를 지날 수 있고, 만료된 세션은 다음 /service 요청에서 auth store 의
// token() 이 지운다.
router.beforeEach((to) => {
  if (to.meta.public || useAuthStore().loggedIn) return true
  return loginRedirect(to.fullPath)
})

export default router
