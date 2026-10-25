<script setup>
// 제안서 한 건의 상세 화면이다. frontend/src/router/index.js 가 /proposals/:id 경로에 연결하고,
// 받은 제안서 목록(frontend/src/views/ProposalListView.vue)과 검증·진단 기록 화면
// (frontend/src/views/DiagnosisView.vue)의 '제안서 보기' 링크가 이 화면을 연다.
// service 가 저장한 본문(document)을 frontend/src/components/ProposalDocument.vue 로 목차대로
// 보여 주고, 후보를 골라 승인하거나 거절한다. 승인은 모든 확인을 통과한 후보(valid_candidate_nos)만
// 할 수 있고, 결정(승인과 거절)은 OPERATOR 역할만 할 수 있다. 역할 규칙은
// service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 와, 후보 규칙은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 checkDecidable 과
// 같아야 한다.
// 상세는 frontend/src/api/proposals.js 의 getProposal 로 읽고 결정은 approveProposal 과
// rejectProposal 로 보낸다. 상세 요청은
// service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 detail() 이, 승인과
// 거절은 service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 의 approve() 와
// reject() 가 받는다.
// mock 모드에서는 frontend/src/mocks/index.js 가 같은 규칙으로 흉내 낸다.
// 고른 후보의 patch 는 '초안으로 가져와 고치기' 로 frontend/src/stores/workspace.js 의 초안에
// 적용해 Skill 편집 화면에서 이어서 다듬을 수 있다. 이때 서버에는 저장하지 않는다.
// 결정 권한은 frontend/src/stores/auth.js 의 hasRole 로 보고, 결정과 초안 적용의 결과는
// frontend/src/stores/toast.js 의 show 로 알린다.
// 처음 고르는 후보 규칙은 load, 결정 권한은 canDecide, 승인 버튼 조건은 canApprove 에서 고친다.
// 본문 밖에 Agent 원본 제안서(detail.proposal)에서 읽어 덧붙이는 것이 넷 있다. 원인이 '의도를 실행하지
// 못하는 수정'(root_cause.kind incomplete_edit)이면 맨 위 안내, 변경 의도 절 아래의 기대 결과와 그
// 출처(intent.expectations, intent.source), 후보 카드의 종류·경고 꼬리표(kind, is_revert,
// weakens_contract), 그리고 service 가 다시 계산해 덮은 판정(server_overrides)이다. 모양은
// docs/plans/2026-10-26-behavior-edit-root-fix.md 2.5~2.7 절이고 이름표는
// frontend/src/utils/proposalView.js 에 있다.
// 제안서에 개발 요청서(development_request, 2.11 절)가 있으면 맨 위에 '도구 개발 필요' 안내를 띄우고,
// 본문 아래에 frontend/src/components/DevelopmentRequestCard.vue 로 요청서 카드('마크다운으로 복사')를 그린다.
// 제안서·진단·변경 번호와 Skill 세트 id, 실패한 실행 번호 같은 원래 id 는 본문에서 빼고 맨 아래
// frontend/src/components/DevInfo.vue 의 '개발자용 정보' 에 접어 둔다(복사 가능).
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CircleAlert, CircleCheck, FilePen, Scale, ShieldCheck } from 'lucide-vue-next'
import { approveProposal, getProposal, rejectProposal } from '@/api/proposals'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { useWorkspaceStore } from '@/stores/workspace'
import { developmentRequestOf, markdownContextOf } from '@/utils/developmentRequest'
import { findingLabel, findingNote } from '@/utils/diagnosisView'
import { idFactsOf, overrideRows, rootCauseKind } from '@/utils/proposalView'
import CandidateTags from '@/components/CandidateTags.vue'
import DevelopmentRequestCard from '@/components/DevelopmentRequestCard.vue'
import DevInfo from '@/components/DevInfo.vue'
import DiffView from '@/components/DiffView.vue'
import IntentExpectations from '@/components/IntentExpectations.vue'
import PageHeader from '@/components/PageHeader.vue'
import ProposalDocument from '@/components/ProposalDocument.vue'
import ProposalStatusBadge from '@/components/ProposalStatusBadge.vue'
import ServerOverrides from '@/components/ServerOverrides.vue'

const route = useRoute()
const router = useRouter()
const ws = useWorkspaceStore()
const auth = useAuthStore()
const toast = useToastStore()

// 상세 응답이다. proposal 칸에 Agent 가 보낸 원래 제안서가, document 칸에 화면용 본문이 있다.
const detail = ref(null)
// 지금 고른 후보 번호다.
const selectedNo = ref(null)
// '의도가 맞아요' 체크 여부다. 승인 요청의 intent_confirmed 로 보낸다.
const intentOk = ref(false)
const busy = ref(false)
const error = ref(null)
const applyError = ref(null)

// candidates 는 제안서의 후보 목록이고, candidate 는 지금 고른 후보다.
const candidates = computed(() => detail.value?.proposal.candidates ?? [])
const candidate = computed(() => candidates.value.find((c) => c.candidate_no === selectedNo.value) ?? null)
// 상태가 proposed 면 후보를 골라 승인하거나 거절한다. no_valid_fix 면 승인할 후보가 없으므로
// 확인하고 닫기(거절)만 한다. decision 은 결정 기록이고 아직 결정하지 않았으면 null 이다.
const status = computed(() => detail.value?.status)
const proposed = computed(() => status.value === 'proposed')
const noValidFix = computed(() => status.value === 'no_valid_fix')
const decision = computed(() => detail.value?.decision ?? null)
// 결정 권한이다. frontend/src/stores/auth.js 의 hasRole 로 OPERATOR 역할을 본다.
const canDecide = computed(() => auth.hasRole('OPERATOR'))
// 고른 후보가 모든 확인을 통과했는지 본다.
const selectedValid = computed(() => !!detail.value?.valid_candidate_nos.includes(selectedNo.value))
// 승인 버튼 조건이다. 권한, 의도 확인 체크, 통과한 후보, 요청 중이 아님을 모두 만족해야 한다.
// service 도 권한, 의도 확인, 통과한 후보 번호를 다시 검사하므로
// 화면 조건은 잘못된 요청을 미리 막는 역할이다.
const canApprove = computed(() => canDecide.value && intentOk.value && selectedValid.value && !busy.value)
// 고른 후보가 고치는 Skill 이름 목록이다. 초안에 적용한 뒤 첫 Skill 의 편집 화면으로 간다.
const patched = computed(() => candidate.value?.patches.map((p) => p.skill) ?? [])
// 초안을 적용할 Skill 세트(지금 고른 snapshot)의 이름이다.
const setLabel = computed(() => ws.labelOf(ws.snapshotId))
// 원인이 '의도를 실행하지 못하는 수정'(incomplete_edit)인지다. 참이면 본문 위에 안내를 띄운다.
// 이 원인은 진단 결과 INTENT_NOT_REALIZED 로 확정되므로 그 이름과 설명을 함께 쓴다.
const incompleteEdit = computed(() => rootCauseKind(detail.value?.proposal.root_cause) === 'incomplete_edit')
// service 가 다시 계산해 덮은 판정 줄이다. 없으면 빈 목록이다.
const overrides = computed(() => overrideRows(detail.value))
// 개발 요청서다. SKILL.md 수정만으로 고칠 수 없어 도구 개발이 필요할 때만 있고, 없으면 null 이다.
// 마크다운의 배경 절에 넣을 제안서 번호·의도·원인·세트 id 는 devContext 다.
const devRequest = computed(() => developmentRequestOf(detail.value))
const devContext = computed(() => markdownContextOf(detail.value))
// 개발자용 정보에 접어 둘 원래 id 들이다. 본문의 번호 칸(참고 정보 절)도 함께 모은다.
const devRows = computed(() => {
  const d = detail.value
  if (!d) return []
  const p = d.proposal ?? {}
  return [
    { label: '제안서 번호', value: d.proposal_id },
    { label: '진단 번호', value: d.thread_id ?? p.thread_id },
    { label: '변경 번호', value: p.trigger?.change_id },
    { label: '수정 전 Skill 세트', value: devContext.value.baselineSnapshotId },
    { label: '수정 후 Skill 세트', value: devContext.value.snapshotId },
    { label: '실패한 실행', value: p.failure?.execution_id },
    { label: '원인 확인 실행', value: p.root_cause?.proof_run_ids },
    ...idFactsOf(d.document).filter((f) => !['제안서 번호', '진단 번호', '변경 번호'].includes(f.label)),
  ]
})

/**
 * 제안서 상세를 읽고 처음 보여 줄 후보를 고른다. 추천 후보(recommended_candidate_no)가 있으면
 * 그것을, 없으면 첫 후보를 고르고, 후보가 없으면 null 이다. 처음 고르는 후보 규칙은 여기서 고친다.
 * 아래 watch 가 화면을 열 때와 제안서 번호가 바뀔 때 부르고,
 * decide 가 결정한 뒤 상태와 결정 기록을 새로 읽으려고 다시 부른다.
 */
async function load(id) {
  detail.value = await getProposal(id)
  selectedNo.value = detail.value.recommended_candidate_no ?? candidates.value[0]?.candidate_no ?? null
}

// 제안서 번호가 바뀔 때마다(처음 포함) 화면 값을 비우고 다시 읽는다. 의도 확인 체크도 풀어 둔다.
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

// 초안 적용에 쓸 Skill 세트를 준비하려고 workspace store 의 init 으로
// 저장해 둔 snapshot 을 다시 읽는다.
onMounted(() => ws.init())

/**
 * 고른 후보를 승인하거나 제안서를 거절한다. 결정 칸의 승인, 거절, '확인하고 닫기' 버튼이 부른다.
 * 승인은 후보 번호와 의도 확인 여부를 함께 보낸다.
 * no_valid_fix 제안서의 '확인하고 닫기' 도 거절(reject)로 보낸다.
 * 성공하면 상세를 다시 읽어 상태와 결정 기록을 보여 주고 토스트를 띄우며,
 * 실패하면 오류 문구와 오류 토스트를 띄운다. 다시 읽으면 selectedNo 가 바뀔 수 있으므로
 * 보낸 후보 번호를 no 에 담아 두고 토스트 문구에 쓴다.
 */
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

/**
 * 고른 후보의 patch 를 지금 고른 Skill 세트의 초안에 적용하고 그 Skill 의 편집 화면으로 간다.
 * '초안으로 가져와 고치기' 버튼이 부른다.
 * 서버에는 저장하지 않는다. 세트를 아직 고르지 않았으면 적용하지 않고 안내 문구를 보여 준다.
 * 적용은 frontend/src/stores/workspace.js 의 applyPatchesToDraft 가 한다.
 * patch 하나라도 기준 내용과 맞지 않으면 아무것도 바꾸지 않고 실패한 Skill 목록을 돌려주므로,
 * 그때는 제안서를 만든 세트를 골랐는지 확인하라는 문구를 보여 준다.
 * 기준 줄 어긋남 허용치는 frontend/src/config.js 의 PATCH_FUZZ_FACTOR 다.
 */
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
    :back="{ to: '/proposals', label: '받은 개선안' }"
  >
    <template #badges>
      <ProposalStatusBadge :status="status" />
    </template>
  </PageHeader>

  <div v-if="error" class="callout err"><CircleAlert :size="18" /><p>{{ error }}</p></div>

  <div v-if="detail" class="layout">
    <div class="main stack">
      <div v-if="incompleteEdit" class="callout warn">
        <CircleAlert :size="18" />
        <p><strong>{{ findingLabel('INTENT_NOT_REALIZED') }}</strong> — {{ findingNote('INTENT_NOT_REALIZED') }}</p>
      </div>
      <div v-if="devRequest" class="callout warn">
        <CircleAlert :size="18" />
        <p><strong>{{ findingLabel('NEEDS_TOOL') }}</strong> — {{ findingNote('NEEDS_TOOL') }} 본문 아래 개발 요청서를 확인해 주세요.</p>
      </div>
      <ProposalDocument
        :document="detail.document"
        :selected-no="selectedNo"
        :candidates="candidates"
        @select="(no) => (selectedNo = no)"
      >
        <template #intent>
          <IntentExpectations :intent="detail.proposal.intent" />
          <label v-if="proposed" class="confirm" :class="{ on: intentOk }">
            <input v-model="intentOk" type="checkbox" />
            <span>
              <strong>네, SkillOps가 이해한 의도가 맞아요</strong>
              <small>의도가 다르면 체크하지 말고 거절하거나, 후보를 초안으로 가져와 직접 고쳐 주세요.</small>
            </span>
          </label>
        </template>
        <template #candidates>
          <p v-if="candidate" class="picked">
            <strong>후보 {{ selectedNo }}번</strong>
            <CandidateTags :candidate="candidate" />
          </p>
          <div v-for="p in candidate?.patches ?? []" :key="p.skill" class="patch">
            <h3><FilePen :size="16" /> 후보 {{ selectedNo }}번 · {{ p.skill }} <span class="muted small">(v{{ p.base_version }} 기준)</span></h3>
            <DiffView :patch="p.patch" before-label="지금" after-label="후보 적용 후" />
          </div>
        </template>
      </ProposalDocument>
      <DevelopmentRequestCard v-if="devRequest" :request="devRequest" :context="devContext" />
      <section v-if="overrides.length" class="card">
        <h2 class="sec"><Scale :size="18" /> 서버가 다시 계산한 판정</h2>
        <p class="small muted">
          SkillOps Agent 가 보낸 판정과 service 가 실행 장부로 다시 계산한 판정이 달라 서버 값으로 바꿨어요. 승인할 수 있는 후보도 서버 값을 따라요.
        </p>
        <ServerOverrides :rows="overrides" />
      </section>
      <DevInfo :rows="devRows" />
    </div>

    <!-- 결정 패널 -->
    <aside class="side">
      <div class="card decide">
        <h3><ShieldCheck :size="16" /> 결정</h3>
        <template v-if="proposed">
          <span class="muted small">고른 후보</span>
          <strong class="pick">후보 {{ selectedNo }}번</strong>
          <p v-if="!selectedValid" class="small error-text">이 후보는 모든 확인을 통과하지 못해 승인할 수 없어요.</p>
          <CandidateTags :candidate="candidate" />
          <button class="primary block" :disabled="!canApprove" @click="decide('approve')">
            <CircleCheck :size="16" /> 후보 {{ selectedNo }}번 승인
          </button>
          <button class="danger block" :disabled="!canDecide || busy" @click="decide('reject')">거절</button>
          <p v-if="!canDecide" class="small muted">승인과 거절은 운영자만 할 수 있어요.</p>
          <p v-else-if="!intentOk" class="small warn-text">변경 의도를 확인해야 승인할 수 있어요.</p>
        </template>
        <template v-else-if="noValidFix">
          <p v-if="devRequest" class="small">
            SKILL.md 수정만으로는 고칠 수 없어요. 개발 요청서를 마크다운으로 복사해 개발팀에 전달한 뒤 제안서를 닫아 주세요.
          </p>
          <p v-else class="small">승인할 수 있는 후보가 없어요. 내용을 확인했으면 제안서를 닫아 주세요.</p>
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
        <p class="small set-line"><span class="muted">적용할 Skill 세트</span> <strong>{{ setLabel }}</strong></p>
        <button class="primary block" :disabled="!candidate?.patches.length" @click="applyToDraft">
          <FilePen :size="16" /> 초안으로 가져와 고치기
        </button>
        <p v-if="applyError" class="small error-text">{{ applyError }}</p>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 24px;
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
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  cursor: pointer;
  transition:
    border-color 0.15s var(--ease),
    background 0.15s var(--ease);
}

.confirm:hover {
  border-color: rgb(255 255 255 / 28%);
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

.picked {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 16px 0 0;
}

.sec {
  display: flex;
  align-items: center;
  gap: 8px;
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
  top: calc(var(--topbar-h) + 20px);
}

.decide {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 22px;
}

.decide h3 {
  font-family: var(--display);
  font-size: 15px;
}

.decide p {
  margin: 0;
}

.pick {
  font-family: var(--display);
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.set-line {
  display: flex;
  flex-direction: column;
  gap: 2px;
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
  padding: 12px 14px;
  border-radius: var(--radius);
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
