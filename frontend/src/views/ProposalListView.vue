<script setup>
import { computed, onMounted, ref } from 'vue'
import { ChevronRight, Inbox, Star } from 'lucide-vue-next'
import { listProposals } from '@/api/proposals'
import { STATUS } from '@/utils/proposalView'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'

// 받은 제안서 목록. service 가 저장한 제안서의 제목과 개요를 보여 주고, 검토 대기(proposed)를 먼저 고른다.
const proposals = ref([])
const error = ref(null)
const filter = ref('pending')

const pendingOnly = (items) => items.filter((p) => p.status === 'proposed')
const shown = computed(() => (filter.value === 'pending' ? pendingOnly(proposals.value) : proposals.value))
const pendingCount = computed(() => pendingOnly(proposals.value).length)

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
    :icon="Inbox"
  >
    <template #actions>
      <div class="tabs" role="tablist">
        <button class="ghost sm" :class="{ on: filter === 'pending' }" @click="filter = 'pending'">
          검토 대기 <span class="n">{{ pendingCount }}</span>
        </button>
        <button class="ghost sm" :class="{ on: filter === 'all' }" @click="filter = 'all'">
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
    <RouterLink v-for="p in shown" :key="p.proposal_id" :to="`/proposals/${p.proposal_id}`" class="item card">
      <div class="head">
        <span class="status" :class="STATUS[p.status]?.tone">{{ STATUS[p.status]?.label ?? p.status }}</span>
        <strong class="title">{{ p.title }}</strong>
      </div>
      <p class="summary">{{ p.summary }}</p>
      <div class="foot">
        <span v-if="p.recommended_candidate_no" class="rec">
          <Star :size="14" /> 추천 후보 {{ p.recommended_candidate_no }}번
        </span>
        <span class="muted small">합격 {{ p.valid_candidate_nos.length }} / 후보 {{ p.candidate_count }}</span>
        <span class="spacer" />
        <span class="go">검토하기 <ChevronRight :size="16" /></span>
      </div>
    </RouterLink>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 4px;
  padding: 3px;
  border-radius: var(--radius);
  background: var(--surface-2);
}

.tabs .on {
  background: var(--surface);
  color: var(--accent);
  box-shadow: var(--shadow-sm);
}

.n {
  min-width: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--border);
  font-size: 11px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 8px;
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

.status {
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--text-2);
}

.status.info {
  background: var(--accent-soft);
  color: var(--accent);
}

.status.ok {
  background: var(--ok-soft);
  color: var(--ok);
}

.status.err {
  background: var(--err-soft);
  color: var(--err);
}

.summary {
  margin: 0;
  color: var(--text-2);
}

.foot {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.rec {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--accent);
}

.go {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-weight: 700;
  color: var(--accent);
}
</style>
