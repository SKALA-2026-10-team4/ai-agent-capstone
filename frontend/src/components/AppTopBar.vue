<script setup>
// 위쪽 막대(top bar)다. frontend/src/App.vue 가 meta.public 이 아닌 모든 화면 위에 붙인다.
// 왼쪽에 소문자 'skillops' 글자 로고, 가운데에 알약 모양 메뉴, 오른쪽에 mock 모드 표시, 로그인한 사용자,
// 로그아웃 버튼을 둔다. 메뉴 묶음과 순서는 frontend/src/router/nav.js 의 NAV_GROUPS 에서 고치고,
// 켜진 메뉴는 같은 파일의 isNavActive 로 정한다(제안서 상세와 실행 상세도 부모 메뉴를 켠다).
// '받은 개선안' 옆 숫자는 검토 대기 제안서 수다. frontend/src/api/proposals.js 의 listProposals 로
// 목록을 읽고 frontend/src/utils/proposalView.js 의 awaitsDecision 에 맞는 것만 센다. 목록 요청은
// service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 list() 가 받는다.
// mock 모드 표시는 frontend/src/api/client.js 의 USE_MOCK 을 보고, 사용자는 frontend/src/stores/auth.js 의
// user 로 보여 준다. loggedIn 으로 검토 대기 수를 셀지 정하고, logout 으로 로그아웃한다.
// 화면 폭이 760px 이하이면 style 의 media query 가 메뉴를 둘째 줄로 내리고 가로로 밀어 보게 한다.
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LogOut } from 'lucide-vue-next'
import { USE_MOCK } from '@/api/client'
import { listProposals } from '@/api/proposals'
import { NAV_GROUPS, isNavActive } from '@/router/nav'
import { HOME_PATH, LOGIN_PATH } from '@/router/paths'
import { awaitsDecision } from '@/utils/proposalView'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
// 검토 대기 제안서 수다. 0 이면 배지를 숨긴다.
const pending = ref(0)

// 로그인 응답의 역할 이름을 화면 이름으로 바꾸는 표다. 역할 이름은
// service/src/main/resources/application.yml 의 데모 계정 roles 와 같다.
// 표에 없는 역할은 이름을 그대로 보여 준다.
const ROLE = { OWNER: '업무 담당자', OPERATOR: '운영자' }
const roleText = (user) => user.roles.map((r) => ROLE[r] ?? r).join(', ')

/**
 * auth store 의 세션을 지우고 로그인 화면으로 보낸다. 오른쪽 로그아웃 버튼이 부른다.
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
  <header class="topbar">
    <div class="inner">
      <RouterLink :to="HOME_PATH" class="brand" aria-label="SkillOps 첫 화면">
        <svg class="glyph" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="9.5" />
          <ellipse cx="12" cy="12" rx="4" ry="9.5" />
          <circle class="dot" cx="20.2" cy="7.2" r="2" />
        </svg>
        <span class="word">skillops</span>
      </RouterLink>

      <nav class="nav" aria-label="주 메뉴">
        <template v-for="(g, gi) in NAV_GROUPS" :key="g.title">
          <span v-if="gi" class="divider" aria-hidden="true" />
          <span class="group-title">{{ g.title }}</span>
          <RouterLink
            v-for="item in g.items"
            :key="item.to"
            :to="item.to"
            class="item"
            :class="{ on: isNavActive(item, route.path) }"
            :aria-current="isNavActive(item, route.path) ? 'page' : undefined"
          >
            {{ item.label }}
            <span v-if="item.badge && pending" class="count" :aria-label="`검토 대기 ${pending}건`">{{ pending }}</span>
          </RouterLink>
        </template>
      </nav>

      <div class="right">
        <span v-if="USE_MOCK" class="pill warn mock" title="서버 없이 mock 데이터로 보는 중이에요">mock 데이터</span>
        <span v-if="auth.user" class="user" :title="roleText(auth.user)">
          <span class="avatar" aria-hidden="true">{{ auth.user.display_name.slice(0, 1) }}</span>
          <span class="name">{{ auth.user.display_name }}</span>
        </span>
        <button class="ghost icon-btn out" title="로그아웃" aria-label="로그아웃" @click="logout"><LogOut :size="16" /></button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  background: rgb(13 23 33 / 92%);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--border);
}

.inner {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 24px;
  max-width: 1320px;
  height: var(--topbar-h);
  margin: 0 auto;
  padding: 0 32px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--text);
  text-decoration: none;
}

.brand:hover {
  text-decoration: none;
}

.glyph {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: rgb(255 255 255 / 55%);
  stroke-width: 1.2;
}

.glyph .dot {
  fill: var(--accent);
  stroke: var(--bg-deep);
  stroke-width: 1.5;
}

.word {
  font-family: var(--display);
  font-size: 19px;
  font-weight: 800;
  letter-spacing: -0.04em;
}

.nav {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 0;
}

.group-title {
  margin: 0 4px 0 2px;
  font-size: 11px;
  font-weight: 600;
  color: var(--faint);
  white-space: nowrap;
}

.divider {
  width: 1px;
  height: 16px;
  margin: 0 10px;
  background: var(--border-strong);
  flex: none;
}

.item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: var(--radius-pill);
  color: var(--text-2);
  font-size: var(--fs-sm);
  font-weight: 600;
  white-space: nowrap;
  text-decoration: none;
  transition:
    background 0.15s var(--ease),
    color 0.15s var(--ease);
}

.item:hover {
  background: rgb(255 255 255 / 6%);
  color: var(--text);
  text-decoration: none;
}

.item.on {
  background: var(--accent);
  color: var(--accent-ink);
}

/* 검토 대기 수: 남색 막대 위에서는 크림 원, 켜진 크림 알약 위에서는 남색 원 */
.count {
  display: inline-grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background: var(--accent);
  color: var(--accent-ink);
  font-size: 11px;
  font-weight: 800;
  line-height: 1;
}

.item.on .count {
  background: var(--accent-ink);
  color: var(--accent);
}

.right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mock {
  font-size: 11px;
}

.user {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  font-size: 12px;
  color: var(--text);
}

@media (max-width: 1240px) {
  .group-title {
    display: none;
  }
}

@media (max-width: 1080px) {
  .inner {
    gap: 14px;
    padding: 0 20px;
  }

  .divider {
    margin: 0 4px;
  }

  .item {
    padding: 7px 11px;
  }

  .user .name {
    display: none;
  }
}

/* 좁은 화면: 첫 줄에 로고와 사용자, 둘째 줄에 가로로 미는 메뉴 */
@media (max-width: 760px) {
  .topbar {
    position: relative;
  }

  .inner {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'brand right'
      'nav nav';
    height: auto;
    row-gap: 6px;
    padding: 10px 16px 8px;
  }

  .brand {
    grid-area: brand;
  }

  .right {
    grid-area: right;
    gap: 8px;
  }

  .nav {
    grid-area: nav;
    justify-content: flex-start;
    margin: 0 -16px;
    padding: 2px 16px 4px;
    overflow-x: auto;
    scrollbar-width: none;
  }

  .nav::-webkit-scrollbar {
    display: none;
  }
}
</style>
