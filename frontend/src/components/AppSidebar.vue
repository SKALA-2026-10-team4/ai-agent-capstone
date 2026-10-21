<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FileDiff, FilePen, GitCompareArrows, Inbox, LogOut, PlayCircle, Sparkles } from 'lucide-vue-next'
import { USE_MOCK } from '@/api/client'
import { listProposals, PROPOSALS_AVAILABLE } from '@/api/proposals'
import { useAuthStore } from '@/stores/auth'

// 왼쪽 메뉴. 담당자의 일 순서대로 묶었다: 개선안 받기 → Skill 손보기 → 실행해 보기.
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const pending = ref(0)

const GROUPS = [
  { title: '개선안', items: [{ to: '/proposals', label: '받은 개선안', icon: Inbox, badge: true }] },
  {
    title: 'Skill',
    items: [
      { to: '/skills', label: 'Skill 편집', icon: FilePen },
      { to: '/compare', label: '버전 비교', icon: GitCompareArrows },
    ],
  },
  { title: '실행', items: [{ to: '/orders', label: '주문 실행 · 기록', icon: PlayCircle }] },
]

const ROLE = { OWNER: '업무 담당자', OPERATOR: '운영자' }

function logout() {
  auth.logout()
  router.push('/login')
}

// 승인·거절 뒤 다른 화면으로 가면 숫자가 바로 맞도록 이동할 때마다 다시 센다.
async function countPending() {
  if (!PROPOSALS_AVAILABLE || !auth.loggedIn) return
  try {
    pending.value = (await listProposals()).filter((p) => p.approval_status === 'pending').length
  } catch {
    pending.value = 0
  }
}
watch(() => route.fullPath, countPending, { immediate: true })
</script>

<template>
  <aside class="sidebar">
    <RouterLink to="/proposals" class="brand">
      <span class="logo"><Sparkles :size="18" /></span>
      <span>SkillOps</span>
    </RouterLink>

    <nav>
      <div v-for="g in GROUPS" :key="g.title" class="group">
        <span class="group-title">{{ g.title }}</span>
        <RouterLink v-for="item in g.items" :key="item.to" :to="item.to" class="item">
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
  color: #fff;
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

.item.router-link-active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 700;
}

.count {
  margin-left: auto;
  min-width: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--err);
  color: #fff;
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
