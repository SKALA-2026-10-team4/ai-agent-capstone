<script setup>
// 수정안 목록 화면이다. 사이드바(frontend/src/components/AppSidebar.vue)의 '수정안 목록' 메뉴이며
// frontend/src/router/index.js 가 /proposals 경로에 연결한다.
// 수정안(제안서)은 skillops-agent 가 service 의 save_proposal MCP 도구
// (service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java)로 저장한 것이다.
// 목록은 frontend/src/api/proposals.js 의 listProposals 로 읽는다. GET /service/api/proposals 는
// service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 list() 가 받는다.
// 위쪽에서 상태(전체 · 검토 대기 · 검토 완료)와 추천 후보만 보기로 거르고, 목록은 항상 최신순이다.
// 상태 묶음은 frontend/src/utils/proposalView.js 의 STATUS_FILTERS, matchesStatusFilter 이며,
// 검토 대기 규칙(awaitsDecision)은 사이드바 숫자와 같다.
// 카드 한 장은 수정안 한 건이다. 목록 응답에 후보별 정보가 없어 후보마다 카드를 나누지 않고,
// 핵심 요약 두 줄(원인, 시험 결과와 추천 후보 번호)을 같은 파일의 proposalHighlights 가 만든다.
// 화면 설명은 제목 옆 ● 에 마우스를 올릴 때만 보인다(PageHeader 의 descriptionTip).
// 카드를 누르면 상세 화면(/proposals/:id, frontend/src/views/ProposalView.vue)으로 간다.
import { computed, onMounted, ref } from 'vue'
import { Inbox } from 'lucide-vue-next'
import { listProposals } from '@/api/proposals'
import { dateTime } from '@/utils/format'
import { STATUS_FILTERS, matchesStatusFilter, proposalHighlights } from '@/utils/proposalView'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import ProposalStatusBadge from '@/components/ProposalStatusBadge.vue'

const proposals = ref([])
const error = ref(null)
// 고른 상태 필터다. 처음에는 전체를 연다.
const statusFilter = ref('all')
// 켜면 추천 후보가 있는 수정안만 남긴다.
const recommendedOnly = ref(false)

// 화면에 보일 목록이다. 상태와 추천 후보로 거른 뒤 생성일시가 늦은 것부터 놓는다.
// 서버도 최신순으로 주지만, 화면 규칙으로 고정하려고 여기서 한 번 더 정렬한다.
const shown = computed(() =>
  proposals.value
    .filter((p) => matchesStatusFilter(p, statusFilter.value))
    .filter((p) => !recommendedOnly.value || p.recommended_candidate_no)
    .sort((a, b) => (b.created_at ?? '').localeCompare(a.created_at ?? '')),
)

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
    title="수정안 목록"
    description="SkillOps가 실제로 시험한 수정 제안서예요. 내용을 읽고 승인하거나 거절해 주세요."
    description-tip
  />

  <section class="board">
    <!-- 필터 줄이다. 목록만 스크롤되고 이 줄은 위에 남는다. -->
    <div class="filters">
      <select v-model="statusFilter" aria-label="상태 필터">
        <option v-for="f in STATUS_FILTERS" :key="f.value" :value="f.value">{{ f.label }}</option>
      </select>
      <label class="check">
        <input v-model="recommendedOnly" type="checkbox" />
        추천 후보만 보기
      </label>
    </div>

    <p v-if="error" class="error-text">{{ error }}</p>
    <EmptyState v-else-if="!shown.length" title="조건에 맞는 수정안이 없어요" :icon="Inbox">
      Skill 수정본을 저장하면 SkillOps가 시험한 뒤 수정안을 이곳으로 보내요.
    </EmptyState>

    <div v-else class="list">
      <RouterLink v-for="p in shown" :key="p.proposal_id" :to="`/proposals/${p.proposal_id}`" class="item card">
        <div class="head">
          <ProposalStatusBadge :status="p.status" />
          <strong class="title">{{ p.title }}</strong>
          <span class="created">생성일시 {{ dateTime(p.created_at) }}</span>
        </div>
        <!-- 핵심 요약이다. 긴 요약 글 대신 항목별로 한 줄씩 보여 주고, 전체 요약 글은 마우스를 올리면 보인다. -->
        <dl class="highlights" :title="p.summary">
          <div v-for="h in proposalHighlights(p)" :key="h.label">
            <dt>{{ h.label }}</dt>
            <dd>{{ h.value }}</dd>
          </div>
        </dl>
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
/* 필터 줄과 목록을 담는 회색 판이다 */
.board {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border-radius: var(--radius);
  background: var(--surface-2);
}

.filters {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.filters select {
  min-width: 220px;
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  cursor: pointer;
}

/* 목록이 길어지면 이 칸만 스크롤한다. 높이는 화면에서 머리와 필터 줄을 뺀 만큼이다 */
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: calc(100vh - 240px);
  overflow-y: auto;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: none;
  border-color: var(--border-strong);
  color: var(--text);
  text-decoration: none;
  transition: border-color 0.12s, box-shadow 0.12s;
}

.item:hover {
  border-color: var(--accent);
  box-shadow: var(--shadow);
  text-decoration: none;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.title {
  font-size: var(--fs-lg);
}

/* 생성일시는 첫 줄 오른쪽 끝에 둔다 */
.created {
  margin-left: auto;
  color: var(--muted);
  font-size: var(--fs-xs);
}

/* 핵심 요약 두 줄이다. 항목 이름 칸 폭을 맞춰 줄마다 값이 같은 위치에서 시작하고, 길면 한 줄에서 말줄임한다 */
.highlights {
  display: grid;
  gap: 2px;
  margin: 0;
}

.highlights > div {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 8px;
}

.highlights dt {
  color: var(--muted);
  font-size: var(--fs-sm);
}

.highlights dd {
  margin: 0;
  overflow: hidden;
  color: var(--text-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
