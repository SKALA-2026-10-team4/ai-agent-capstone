<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CircleAlert, CircleCheck, FilePen, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { approveProposal, getProposal, rejectProposal } from '@/api/proposals'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { useWorkspaceStore } from '@/stores/workspace'
import { STATUS } from '@/utils/proposalView'
import DiffView from '@/components/DiffView.vue'
import PageHeader from '@/components/PageHeader.vue'
import ProposalDocument from '@/components/ProposalDocument.vue'

// 제안서 한 건: service 가 저장한 본문(document)을 목차대로 읽고, 후보를 골라 승인하거나 거절한다.
// 승인은 모든 확인을 통과한 후보(valid_candidate_nos)만, 결정은 OPERATOR 만 할 수 있다.
const route = useRoute()
const router = useRouter()
const ws = useWorkspaceStore()
const auth = useAuthStore()
const toast = useToastStore()

const detail = ref(null)
const selectedNo = ref(null)
const intentOk = ref(false)
const busy = ref(false)
const error = ref(null)
const applyError = ref(null)

const candidates = computed(() => detail.value?.proposal.candidates ?? [])
const candidate = computed(() => candidates.value.find((c) => c.candidate_no === selectedNo.value) ?? null)
const status = computed(() => detail.value?.status)
const proposed = computed(() => status.value === 'proposed')
const noValidFix = computed(() => status.value === 'no_valid_fix')
const decision = computed(() => detail.value?.decision ?? null)
const canDecide = computed(() => auth.hasRole('OPERATOR'))
const selectedValid = computed(() => !!detail.value?.valid_candidate_nos.includes(selectedNo.value))
const canApprove = computed(() => canDecide.value && intentOk.value && selectedValid.value && !busy.value)
const patched = computed(() => candidate.value?.patches.map((p) => p.skill) ?? [])
const setLabel = computed(() => ws.labelOf(ws.snapshotId))

async function load(id) {
  detail.value = await getProposal(id)
  selectedNo.value = detail.value.recommended_candidate_no ?? candidates.value[0]?.candidate_no ?? null
}

watch(
  () => route.params.id,
  async (id) => {
    detail.value = null
    error.value = null
    applyError.value = null
    intentOk.value = false
    try {
      await load(id)
    } catch (e) {
      error.value = e.message
    }
  },
  { immediate: true },
)

onMounted(() => ws.init())

async function decide(action) {
  busy.value = true
  error.value = null
  const no = selectedNo.value
  try {
    if (action === 'approve') await approveProposal(detail.value.proposal_id, no, intentOk.value)
    else await rejectProposal(detail.value.proposal_id)
    await load(detail.value.proposal_id)
    toast.show(action === 'approve' ? `후보 ${no}번을 승인했어요.` : '제안서를 거절했어요.')
  } catch (e) {
    error.value = e.message
    toast.show(e.message, 'err')
  } finally {
    busy.value = false
  }
}

// 선택한 후보의 patch 를 지금 고른 Skill 세트의 초안에 적용하고 편집 화면으로 간다(서버 저장 없음).
function applyToDraft() {
  applyError.value = null
  if (!ws.current) {
    applyError.value = '먼저 Skill 파일 세트를 골라 주세요(Skill 편집 화면 위쪽).'
    return
  }
  const result = ws.applyPatchesToDraft(candidate.value.patches)
  if (!result.ok) {
    applyError.value =
      `${result.failed.join(', ')} 의 현재 내용이 제안서의 기준과 달라 자동으로 적용하지 못했어요. ` +
      '제안서를 만든 세트(수정본)를 골랐는지 확인해 주세요.'
    return
  }
  toast.show(`${patched.value.length}개 Skill 에 후보 내용을 초안으로 적용했어요. 이어서 다듬어 보세요.`)
  router.push(`/skills/${patched.value[0]}`)
}
</script>

<template>
  <PageHeader
    v-if="detail"
    :title="detail.document.title"
    description="SkillOps가 실제 주문 흐름에서 시험한 결과예요. 개요부터 차례로 읽고 결정해 주세요."
    :icon="Sparkles"
  >
    <template #crumbs>
      <RouterLink to="/proposals" class="crumb"><ArrowLeft :size="14" /> 받은 제안서</RouterLink>
    </template>
    <template #badges>
      <span class="status" :class="STATUS[status]?.tone">{{ STATUS[status]?.label ?? status }}</span>
    </template>
  </PageHeader>

  <div v-if="error" class="callout err"><CircleAlert :size="18" /><p>{{ error }}</p></div>

  <div v-if="detail" class="layout">
    <div class="main">
      <ProposalDocument
        :document="detail.document"
        :selected-no="selectedNo"
        @select="(no) => (selectedNo = no)"
      >
        <template #intent>
          <label v-if="proposed" class="confirm" :class="{ on: intentOk }">
            <input v-model="intentOk" type="checkbox" />
            <span>
              <strong>네, SkillOps가 이해한 의도가 맞아요</strong>
              <small>의도가 다르면 체크하지 말고 거절하거나, 후보를 초안으로 가져와 직접 고쳐 주세요.</small>
            </span>
          </label>
        </template>
        <template #candidates>
          <div v-for="p in candidate?.patches ?? []" :key="p.skill" class="patch">
            <h3><FilePen :size="16" /> 후보 {{ selectedNo }}번 · {{ p.skill }} <span class="muted small">(v{{ p.base_version }} 기준)</span></h3>
            <DiffView :patch="p.patch" before-label="지금" after-label="후보 적용 후" />
          </div>
        </template>
      </ProposalDocument>
    </div>

    <!-- 결정 패널 -->
    <aside class="side">
      <div class="card decide">
        <h3><ShieldCheck :size="16" /> 결정</h3>
        <template v-if="proposed">
          <span class="muted small">고른 후보</span>
          <strong class="pick">후보 {{ selectedNo }}번</strong>
          <p v-if="!selectedValid" class="small error-text">이 후보는 모든 확인을 통과하지 못해 승인할 수 없어요.</p>
          <button class="primary block" :disabled="!canApprove" @click="decide('approve')">
            <CircleCheck :size="16" /> 후보 {{ selectedNo }}번 승인
          </button>
          <button class="danger block" :disabled="!canDecide || busy" @click="decide('reject')">거절</button>
          <p v-if="!canDecide" class="small muted">승인과 거절은 운영자만 할 수 있어요.</p>
          <p v-else-if="!intentOk" class="small warn-text">변경 의도를 확인해야 승인할 수 있어요.</p>
        </template>
        <template v-else-if="noValidFix">
          <p class="small">승인할 수 있는 후보가 없어요. 내용을 확인했으면 제안서를 닫아 주세요.</p>
          <button class="danger block" :disabled="!canDecide || busy" @click="decide('reject')">확인하고 닫기</button>
          <p v-if="!canDecide" class="small muted">승인과 거절은 운영자만 할 수 있어요.</p>
        </template>

        <template v-if="decision">
          <dl class="result">
            <dt>결정</dt>
            <dd>{{ decision.decision === 'approved' ? `후보 ${decision.candidate_no}번 승인` : '거절' }}</dd>
            <dt>결정한 사람</dt>
            <dd>{{ decision.approver_name }}</dd>
            <template v-if="decision.approval_token">
              <dt>승인 번호</dt>
              <dd><code>{{ decision.approval_token }}</code></dd>
            </template>
          </dl>
          <p class="small muted">운영 반영은 아직 연결되지 않았어요. 승인 기록과 승인 번호만 남아요.</p>
        </template>

        <hr />
        <h3><FilePen :size="16" /> 내가 직접 다듬기</h3>
        <p class="small muted">고른 후보의 내용을 Skill 편집 화면의 초안으로 가져와요. 서버에는 저장되지 않아요.</p>
        <p class="small">적용할 Skill 세트: <code>{{ setLabel }}</code></p>
        <button class="primary block" :disabled="!candidate?.patches.length" @click="applyToDraft">
          <FilePen :size="16" /> 초안으로 가져와 고치기
        </button>
        <p v-if="applyError" class="small error-text">{{ applyError }}</p>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.crumb {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-sm);
  color: var(--muted);
}

.status {
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 3px 10px;
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

.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

@media (max-width: 1100px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.confirm {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-top: 12px;
  padding: 12px 14px;
  border: 1.5px solid var(--border-strong);
  border-radius: var(--radius);
  cursor: pointer;
  transition: border-color 0.12s, background 0.12s;
}

.confirm.on {
  border-color: var(--ok);
  background: var(--ok-soft);
}

.confirm input {
  margin-top: 3px;
}

.confirm span {
  display: flex;
  flex-direction: column;
}

.confirm small {
  color: var(--muted);
}

.patch {
  margin-top: 16px;
}

.patch h3,
.decide h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.side {
  position: sticky;
  top: 20px;
}

.decide {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.decide p {
  margin: 0;
}

.pick {
  font-size: var(--fs-lg);
}

hr {
  width: 100%;
  margin: 8px 0;
  border: none;
  border-top: 1px solid var(--border);
}

.result {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 4px 12px;
  margin: 8px 0 0;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  font-size: var(--fs-sm);
}

.result dt {
  color: var(--muted);
}

.result dd {
  margin: 0;
}

@media (max-width: 1100px) {
  .side {
    position: static;
  }
}
</style>
