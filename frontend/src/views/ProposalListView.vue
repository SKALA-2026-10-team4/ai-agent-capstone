<script setup>
// 받은 제안서 목록 화면이며, 로그인 뒤 첫 화면이다(frontend/src/router/paths.js 의 HOME_PATH).
// frontend/src/router/index.js 가 /proposals 경로에 연결한다.
// 제안서는 skillops-agent 가 service 의 save_proposal MCP 도구
// (service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java)로 저장한 것이다.
// service 가 저장한 제안서의 제목과 개요를 보여 주고, 처음에는 아직 결정하지 않은 제안서만 고른
// '검토 대기' 탭을 연다. 목록은 frontend/src/api/proposals.js 의 listProposals 로 읽는다.
// GET /service/api/proposals 는
// service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 list() 가 받는다.
// 검토 대기 판정은 frontend/src/utils/proposalView.js 의 awaitsDecision 이며,
// 위쪽 막대(frontend/src/components/AppTopBar.vue)의 숫자도 같은 규칙으로 센다.
// 항목을 누르면 제안서 상세 화면(/proposals/:id, frontend/src/views/ProposalView.vue)으로 간다.
import { computed, onMounted, ref } from 'vue'
import { ArrowUpRight, Inbox, Star } from 'lucide-vue-next'
import { listProposals } from '@/api/proposals'
import { stampOf } from '@/utils/format'
import { awaitsDecision } from '@/utils/proposalView'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import ProposalStatusBadge from '@/components/ProposalStatusBadge.vue'

const proposals = ref([])
const error = ref(null)
// 고른 탭이다. 'pending' 은 검토 대기, 'all' 은 전체다.
const filter = ref('pending')

/** 검토 대기 제안서만 남긴다. */
const pendingOnly = (items) => items.filter(awaitsDecision)
// shown 은 고른 탭에 맞는 목록이고, pendingCount 는 검토 대기 탭 옆 숫자다.
const shown = computed(() => (filter.value === 'pending' ? pendingOnly(proposals.value) : proposals.value))
const pendingCount = computed(() => pendingOnly(proposals.value).length)

// 화면을 열 때 한 번만 목록을 읽는다. 읽지 못하면 오류 문구를 보여 준다.
onMounted(async () => {
  try {
    proposals.value = (await listProposals()).items
  } catch (e) {
    error.value = e.message
  }
})
</script>

<template>
  <PageHeader
    title="받은 제안서"
    description="SkillOps가 실제로 시험한 수정 제안서예요. 내용을 읽고 승인하거나 거절해 주세요."
  >
    <template #actions>
      <div class="seg" role="tablist">
        <button role="tab" :aria-selected="filter === 'pending'" :class="{ on: filter === 'pending' }" @click="filter = 'pending'">
          검토 대기 <span class="n">{{ pendingCount }}</span>
        </button>
        <button role="tab" :aria-selected="filter === 'all'" :class="{ on: filter === 'all' }" @click="filter = 'all'">
          전체 <span class="n">{{ proposals.length }}</span>
        </button>
      </div>
    </template>
  </PageHeader>

  <p v-if="error" class="error-text">{{ error }}</p>
  <EmptyState v-else-if="!shown.length" title="검토할 제안서가 없어요" :icon="Inbox">
    Skill 수정본을 저장하면 SkillOps가 시험한 뒤 제안서를 이곳으로 보내요.
  </EmptyState>

  <div class="list">
    <RouterLink v-for="(p, i) in shown" :key="p.proposal_id" :to="`/proposals/${p.proposal_id}`" class="item card">
      <span class="no" aria-hidden="true">{{ String(i + 1).padStart(2, '0') }}</span>
      <div class="main">
        <div class="head">
          <ProposalStatusBadge :status="p.status" />
          <span class="when">{{ stampOf(p.created_at) }}</span>
        </div>
        <strong class="title">{{ p.title }}</strong>
        <p class="summary">{{ p.summary }}</p>
        <div class="foot">
          <span v-if="p.recommended_candidate_no" class="rec">
            <Star :size="13" /> 추천 후보 {{ p.recommended_candidate_no }}번
          </span>
          <span class="muted small">합격 {{ p.valid_candidate_nos.length }} / 후보 {{ p.candidate_count }}</span>
          <span class="spacer" />
          <span class="go">검토하기 <ArrowUpRight :size="16" /></span>
        </div>
      </div>
    </RouterLink>
  </div>
</template>

<style scoped>
.n {
  display: inline-grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background: rgb(255 255 255 / 10%);
  font-size: 11px;
}

.seg button.on .n {
  background: rgb(23 37 50 / 15%);
}

.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.item {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px;
  padding: 24px 26px 22px 18px;
  color: var(--text);
  text-decoration: none;
  transition:
    border-color 0.2s var(--ease),
    background 0.2s var(--ease),
    transform 0.2s var(--ease);
}

.item:hover {
  border-color: rgb(250 255 220 / 35%);
  background: var(--surface-2);
  text-decoration: none;
}

.no {
  padding-top: 3px;
  font-family: var(--mono);
  font-size: var(--fs-xs);
  color: var(--faint);
  text-align: center;
}

.main {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.when {
  font-size: var(--fs-xs);
  color: var(--muted);
}

.title {
  font-family: var(--display);
  font-size: 21px;
  font-weight: 800;
  letter-spacing: -0.025em;
  line-height: 1.3;
}

.summary {
  margin: 0;
  max-width: 900px;
  color: var(--muted);
  line-height: 1.7;
}

.foot {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.rec {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--accent);
}

.go {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-pill);
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--text);
  transition:
    background 0.2s var(--ease),
    color 0.2s var(--ease);
}

.item:hover .go {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-ink);
}

@media (max-width: 760px) {
  .item {
    grid-template-columns: minmax(0, 1fr);
    padding: 18px 16px;
  }

  .no {
    display: none;
  }

  .title {
    font-size: 18px;
  }
}
</style>
