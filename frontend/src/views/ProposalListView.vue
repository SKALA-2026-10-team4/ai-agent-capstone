<script setup>
import { computed, onMounted, ref } from 'vue'
import { ChevronRight, Inbox, PlugZap, Star } from 'lucide-vue-next'
import { listProposals, PROPOSALS_AVAILABLE } from '@/api/proposals'
import { APPROVAL, KIND, TRIGGER } from '@/utils/proposalView'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'

// 받은 개선안 목록. 담당자가 가장 먼저 보는 화면이라 "무엇이 깨졌고 AI 가 무엇을 추천하는지" 를 한 장에 보여 준다.
const proposals = ref([])
const error = ref(null)
const filter = ref('pending')

const shown = computed(() =>
  filter.value === 'pending' ? proposals.value.filter((p) => p.approval_status === 'pending') : proposals.value,
)
const pendingCount = computed(() => proposals.value.filter((p) => p.approval_status === 'pending').length)
const recommendedOf = (p) => p.candidates[p.recommended] ?? null
const validCount = (p) => p.candidates.filter((c) => c.valid).length

onMounted(async () => {
  if (!PROPOSALS_AVAILABLE) return
  try {
    proposals.value = await listProposals()
  } catch (e) {
    error.value = e.message
  }
})
</script>

<template>
  <PageHeader
    title="받은 개선안"
    description="Skill 을 고친 뒤 주문이 깨지면 AI 가 원인을 찾아 실제로 검증한 개선안을 보내요. 검토하고 직접 다듬어 보세요."
    :icon="Inbox"
  >
    <template v-if="PROPOSALS_AVAILABLE" #actions>
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

  <EmptyState v-if="!PROPOSALS_AVAILABLE" title="아직 개선안을 불러올 수 없어요" :icon="PlugZap">
    진단 에이전트에 개선안 조회 기능이 생기면 여기에 나타나요. 지금은 <code>npm run dev:mock</code> 으로 예시를 볼 수
    있어요.
  </EmptyState>
  <p v-if="error" class="error-text">{{ error }}</p>
  <EmptyState v-else-if="PROPOSALS_AVAILABLE && !shown.length" title="검토할 개선안이 없어요" :icon="Inbox">
    주문이 실패하거나 Skill 을 저장하면 AI 가 점검해서 이곳으로 보내요.
  </EmptyState>

  <div class="list">
    <RouterLink v-for="p in shown" :key="p.id" :to="`/proposals/${p.id}`" class="item card">
      <div class="head">
        <span class="status" :class="APPROVAL[p.approval_status]?.tone">
          {{ APPROVAL[p.approval_status]?.label ?? p.status }}
        </span>
        <strong class="title">{{ p.root_cause.skill }} 개선안</strong>
        <span class="muted small">{{ TRIGGER[p.trigger.type] ?? p.trigger.type }}</span>
      </div>
      <p class="summary">{{ p.root_cause.summary }}</p>
      <p v-if="p.intent?.text" class="intent">AI 가 이해한 의도: “{{ p.intent.text }}”</p>
      <div class="foot">
        <span v-if="recommendedOf(p)" class="rec">
          <Star :size="14" /> 추천: {{ KIND[recommendedOf(p).kind]?.label ?? recommendedOf(p).kind }}
        </span>
        <span class="muted small">후보 {{ p.candidates.length }}개 중 {{ validCount(p) }}개 적용 가능</span>
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

.intent {
  margin: 0;
  padding: 8px 12px;
  border-left: 3px solid var(--accent);
  background: var(--accent-soft);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  font-size: var(--fs-sm);
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
