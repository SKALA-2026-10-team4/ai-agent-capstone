<script setup>
// 왼쪽 메뉴(사이드바)다. frontend/src/App.vue 가 meta.public 이 아닌 모든 화면에 붙인다.
// 메뉴는 skill 관리(skill 목록), 수정안 관리(수정안 목록, 그 아래 수정안 검증 진단),
// 테스트 관리(테스트 실행, 테스트 목록) 세 묶음이다. 버전 비교(/compare)는 메뉴에 두지 않고
// frontend/src/views/ExecutionView.vue 의 'snapshot 비교' 링크로 들어간다.
// '수정안 목록' 옆 숫자는 검토 대기 제안서 수다. frontend/src/api/proposals.js 의 listProposals 로
// 목록을 읽고 frontend/src/utils/proposalView.js 의 awaitsDecision 에 맞는 것만 센다. 목록 요청은
// service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 list() 가 받는다.
// 아래쪽에는 mock 모드 표시(frontend/src/api/client.js 의 USE_MOCK), 로그인한 사용자,
// 로그아웃 버튼이 있다. frontend/src/stores/auth.js 의 user 로 사용자를 보여 주고, loggedIn 으로
// 검토 대기 수를 셀지 정하고, logout 으로 로그아웃한다.
// 화면 폭이 900px 이하이면 style 의 media query 가 위쪽 가로 막대로 바꾼다. 이 900px 는
// frontend/src/App.vue 와 frontend/src/components/DiffView.vue 의 기준과 같은 값이다.
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FileDiff, FilePen, Inbox, ListChecks, LogOut, PlayCircle, Sparkles } from 'lucide-vue-next'
import { USE_MOCK } from '@/api/client'
import { listProposals } from '@/api/proposals'
import { HOME_PATH, LOGIN_PATH } from '@/router/paths'
import { awaitsDecision } from '@/utils/proposalView'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
// 검토 대기 제안서 수다. 0 이면 배지를 숨긴다.
const pending = ref(0)

// 메뉴 묶음과 항목이다. 메뉴를 더하거나 순서를 바꿀 때 여기를 고치고,
// to 는 frontend/src/router/index.js 의 경로와 맞춘다.
// badge 가 true 인 항목 옆에 검토 대기 수를 붙이고, sub 가 true 인 항목은 바로 위 항목의
// 하위 메뉴로 들여 쓴다. active 는 지금 주소에서 이 메뉴를 선택된 것으로 볼지 정한다.
// 상세 화면(/proposals/:id, /executions/:id)에서도 상위 메뉴에 배경이 남도록 경로 앞부분으로 본다.
// 테스트 실행과 테스트 목록은 같은 /orders 화면이라 #history(실행 기록 위치) 유무로 나눈다.
const GROUPS = [
  {
    title: 'skill 관리',
    items: [{ to: '/skills', label: 'skill 목록', icon: FilePen, active: (r) => r.path.startsWith('/skills') }],
  },
  {
    title: '수정안 관리',
    items: [
      { to: '/proposals', label: '수정안 목록', icon: Inbox, badge: true, active: (r) => r.path.startsWith('/proposals') },
      { to: '/diagnoses', label: '수정안 검증 진단', icon: Sparkles, sub: true, active: (r) => r.path.startsWith('/diagnoses') },
    ],
  },
  {
    title: '테스트 관리',
    items: [
      { to: '/orders', label: '테스트 실행', icon: PlayCircle, active: (r) => r.path === '/orders' && r.hash !== '#history' },
      {
        to: '/orders#history',
        label: '테스트 목록',
        icon: ListChecks,
        // 실행 상세는 실행 기록에서 들어가므로 테스트 목록으로 본다.
        active: (r) => (r.path === '/orders' && r.hash === '#history') || r.path.startsWith('/executions'),
      },
    ],
  },
]

// 로그인 응답의 역할 이름을 화면 이름으로 바꾸는 표다. 역할 이름은
// service/src/main/resources/application.yml 의 데모 계정 roles 와 같다.
// 표에 없는 역할은 이름을 그대로 보여 준다.
const ROLE = { OWNER: '업무 담당자', OPERATOR: '운영자' }

/**
 * auth store 의 세션을 지우고 로그인 화면으로 보낸다. 아래쪽 로그아웃 버튼이 부른다.
 * next 를 붙이지 않으므로 다시 로그인하면 첫 화면으로 간다.
 */
function logout() {
  auth.logout()
  router.push(LOGIN_PATH)
}

/**
 * 검토 대기 제안서 수를 다시 센다. 승인하거나 거절한 뒤 다른 화면으로 가면 숫자가 바로 맞도록,
 * 아래 watch 가 처음 그릴 때와 주소가 바뀔 때마다 부른다.
 * 로그인 전이면 service 를 부르지 않고, 목록을 읽지 못하면 0 으로 두어 배지를 숨긴다.
 */
async function countPending() {
  if (!auth.loggedIn) return
  try {
    pending.value = (await listProposals()).items.filter(awaitsDecision).length
  } catch {
    pending.value = 0
  }
}
watch(() => route.fullPath, countPending, { immediate: true })
</script>

<template>
  <aside class="sidebar">
    <RouterLink :to="HOME_PATH" class="brand">
      <span class="logo"><Sparkles :size="18" /></span>
      <span>SkillOps</span>
    </RouterLink>

    <nav>
      <div v-for="g in GROUPS" :key="g.title" class="group">
        <span class="group-title">{{ g.title }}</span>
        <!-- 선택 표시는 RouterLink 의 기본 판정 대신 GROUPS 의 active 로 한다(.on 클래스). -->
        <RouterLink
          v-for="item in g.items"
          :key="item.to"
          :to="item.to"
          class="item"
          :class="{ on: item.active(route), sub: item.sub }"
          active-class=""
          exact-active-class=""
        >
          <component :is="item.icon" :size="18" />
          <span>{{ item.label }}</span>
          <span v-if="item.badge && pending" class="count">{{ pending }}</span>
        </RouterLink>
      </div>
    </nav>

    <div class="foot">
      <span v-if="USE_MOCK" class="mock"><FileDiff :size="14" /> mock 데이터</span>
      <div v-if="auth.user" class="user">
        <span class="avatar">{{ auth.user.display_name.slice(0, 1) }}</span>
        <span class="who">
          <strong>{{ auth.user.display_name }}</strong>
          <small>{{ auth.user.roles.map((r) => ROLE[r] ?? r).join(', ') }}</small>
        </span>
        <button class="ghost sm" title="로그아웃" aria-label="로그아웃" @click="logout"><LogOut :size="16" /></button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  width: var(--sidebar-w);
  height: 100vh;
  padding: 16px 12px;
  background: var(--surface);
  border-right: 1px solid var(--border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px 18px;
  font-size: var(--fs-lg);
  font-weight: 800;
  color: var(--text);
  text-decoration: none;
}

.logo {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--accent);
  color: var(--on-fill); /* 강조색(라임) 면 위 글자. 남색이다 */
}

nav {
  display: flex;
  flex-direction: column;
  gap: 18px;
  flex: 1;
  overflow-y: auto;
}

.group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.group-title {
  padding: 0 10px 4px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--muted);
}

.item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  color: var(--text-2);
  font-weight: 500;
  text-decoration: none;
}

.item:hover {
  background: var(--surface-2);
  text-decoration: none;
}

/* 선택된 메뉴는 배경색을 깐다. 옅은 라임 배경(--accent-soft) + 라임 굵은 글자(--accent)다 */
.item.on {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 700;
}

/* 하위 메뉴(수정안 검증 진단)는 위 항목보다 들여 쓴다 */
.item.sub {
  padding-left: 28px;
  font-size: var(--fs-sm);
}

.count {
  margin-left: auto;
  min-width: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--err);
  color: var(--on-fill); /* 오류색 면 위 글자 */
  font-size: 11px;
  font-weight: 700;
  text-align: center;
}

.foot {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.mock {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: var(--fs-xs);
  font-weight: 600;
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--surface-2);
  font-weight: 700;
  color: var(--text-2);
  flex: none;
}

.who {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
  line-height: 1.3;
}

.who small {
  color: var(--muted);
}

/* 좁은 화면: 위쪽 가로 막대로 */
@media (max-width: 900px) {
  .sidebar {
    position: sticky;
    z-index: 20;
    flex-direction: row;
    align-items: center;
    width: 100%;
    height: auto;
    padding: 8px 12px;
    gap: 12px;
    border-right: none;
    border-bottom: 1px solid var(--border);
  }

  .brand {
    padding: 0;
  }

  .brand span:last-child,
  .group-title,
  .mock,
  .who {
    display: none;
  }

  nav {
    flex-direction: row;
    gap: 2px;
    overflow-x: auto;
  }

  .group {
    flex-direction: row;
  }

  .item {
    white-space: nowrap;
    padding: 6px 10px;
  }

  .foot {
    padding: 0;
    border: none;
  }
}
</style>
