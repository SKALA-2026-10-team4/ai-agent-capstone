<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  CircleAlert,
  CircleCheck,
  FilePen,
  Info,
  Lightbulb,
  PlugZap,
  ShieldCheck,
  Sparkles,
} from 'lucide-vue-next'
import { approveProposal, getProposal, PROPOSALS_AVAILABLE, rejectProposal } from '@/api/proposals'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { useWorkspaceStore } from '@/stores/workspace'
import { APPROVAL, KIND, TRIGGER } from '@/utils/proposalView'
import CandidateCard from '@/components/CandidateCard.vue'
import DiffView from '@/components/DiffView.vue'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'

// 받은 개선안 한 건: 무엇이 깨졌나 → AI 가 이해한 의도 → 후보 비교 → 바뀌는 내용 → 초안으로 가져오기 / 승인.
// 형식은 skillops_agent/state.py. 승인 대상은 에이전트가 정한 recommended 후보이고 promote·smoke 는 에이전트가 한다.
const route = useRoute()
const router = useRouter()
const ws = useWorkspaceStore()
const auth = useAuthStore()
const toast = useToastStore()

const proposal = ref(null)
const selected = ref(null)
const intentOk = ref(false)
const decided = ref(null)
const busy = ref(false)
const error = ref(null)
const applyError = ref(null)

const candidate = computed(() => proposal.value?.candidates[selected.value] ?? null)
const recommended = computed(() => proposal.value?.candidates[proposal.value.recommended] ?? null)
const pending = computed(() => proposal.value?.approval_status === 'pending')
// 승인·거절은 운영자만. service 도 같은 규칙으로 막는다(OWNER 는 403).
const canDecide = computed(() => auth.hasRole('OPERATOR'))
const patched = computed(() => candidate.value?.patches.map((p) => p.skill) ?? [])
const kindOf = (c) => KIND[c?.kind]?.label ?? c?.kind

const setLabel = computed(() => ws.labelOf(ws.snapshotId))

watch(
  () => route.params.id,
  async (id) => {
    proposal.value = null
    decided.value = null
    error.value = null
    applyError.value = null
    if (!PROPOSALS_AVAILABLE) return
    try {
      const p = await getProposal(id)
      proposal.value = p
      selected.value = p.recommended ?? 0
      intentOk.value = p.intent.confirmed === true
    } catch (e) {
      error.value = e.message
    }
  },
  { immediate: true },
)

onMounted(() => ws.init())

async function decide(status) {
  busy.value = true
  error.value = null
  try {
    decided.value =
      status === 'approved'
        ? await approveProposal(proposal.value.id, proposal.value.recommended, intentOk.value)
        : await rejectProposal(proposal.value.id)
    proposal.value.approval_status = decided.value.decision
    toast.show(status === 'approved' ? '승인했어요. 에이전트가 운영 반영을 시작해요.' : '거절했어요.')
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
      `${result.failed.join(', ')} 의 현재 내용이 개선안의 기준과 달라 자동으로 적용하지 못했어요. ` +
      '개선안이 만들어진 세트(예: 결함본)를 골랐는지 확인해 주세요.'
    return
  }
  toast.show(`${patched.value.length}개 Skill 에 개선안을 초안으로 적용했어요. 이어서 다듬어 보세요.`)
  router.push(`/skills/${patched.value[0]}`)
}
</script>

<template>
  <PageHeader
    v-if="proposal"
    :title="`${proposal.root_cause.skill} 개선안`"
    description="AI 가 원인을 찾고 고치는 방법을 실제로 돌려 확인했어요. 아래 순서대로 확인하고, 직접 다듬거나 승인해 주세요."
    :icon="Sparkles"
  >
    <template #crumbs>
      <RouterLink to="/proposals" class="crumb"><ArrowLeft :size="14" /> 받은 개선안</RouterLink>
    </template>
    <template #badges>
      <span class="status" :class="APPROVAL[proposal.approval_status]?.tone">
        {{ APPROVAL[proposal.approval_status]?.label ?? proposal.status }}
      </span>
    </template>
  </PageHeader>

  <EmptyState v-if="!PROPOSALS_AVAILABLE" title="아직 개선안을 불러올 수 없어요" :icon="PlugZap">
    진단 에이전트에 개선안 조회 기능이 생기면 여기에 나타나요. 지금은 <code>npm run dev:mock</code> 으로 예시를 볼 수
    있어요.
  </EmptyState>
  <div v-if="error" class="callout err"><CircleAlert :size="18" /><p>{{ error }}</p></div>

  <div v-if="proposal" class="layout">
    <div class="main stack">
      <!-- ① 무엇이 문제였나 -->
      <section class="card">
        <h2 class="sec"><span class="step-no">1</span> 무엇이 문제였나요</h2>
        <div class="callout err">
          <CircleAlert :size="18" />
          <p>{{ proposal.root_cause.summary }}</p>
        </div>
        <dl class="facts">
          <dt>원인이 된 Skill</dt>
          <dd><span class="chip">{{ proposal.root_cause.skill }}</span></dd>
          <dt>바뀐 버전</dt>
          <dd>v{{ proposal.root_cause.from_version }} → v{{ proposal.root_cause.to_version }}</dd>
          <dt>시작된 계기</dt>
          <dd>
            {{ TRIGGER[proposal.trigger.type] ?? proposal.trigger.type }}
            <span v-if="proposal.trigger.run_id" class="chip">{{ proposal.trigger.run_id }}</span>
          </dd>
          <dt>원인을 확인한 실행</dt>
          <dd><span v-for="r in proposal.root_cause.confirmed_by" :key="r" class="chip">{{ r }}</span></dd>
        </dl>
      </section>

      <!-- ② 의도 -->
      <section class="card">
        <h2 class="sec"><span class="step-no">2</span> AI 가 이해한 내 변경 의도</h2>
        <blockquote>
          <Lightbulb :size="18" />
          <span>“{{ proposal.intent.text }}”</span>
        </blockquote>
        <p class="muted small">
          개선안은 아래 조건이 계속 지켜지는지 실제로 돌려서 확인했어요:
          <span v-for="a in proposal.intent.assertions" :key="a" class="chip">{{ a }}</span>
        </p>
        <label class="confirm" :class="{ on: intentOk, off: !pending }">
          <input v-model="intentOk" type="checkbox" :disabled="!pending" />
          <span>
            <strong>네, 제가 바꾸려던 규칙이 맞아요</strong>
            <small>의도가 다르면 체크하지 말고 개선안을 초안으로 가져와 직접 고쳐 주세요.</small>
          </span>
        </label>
      </section>

      <!-- ③ 후보 비교 -->
      <section>
        <h2 class="sec"><span class="step-no">3</span> 개선안 {{ proposal.candidates.length }}개 비교</h2>
        <p class="muted small sec-desc">
          AI 가 고친 방법마다 격리된 공간에서 실제 주문을 돌려 확인했어요. 카드를 누르면 아래에 바뀌는 내용이 보여요.
        </p>
        <div class="cands">
          <CandidateCard
            v-for="(c, i) in proposal.candidates"
            :key="c.id"
            :candidate="c"
            :recommended="i === proposal.recommended"
            :selected="i === selected"
            @select="selected = i"
          />
        </div>
      </section>

      <!-- ④ 바뀌는 내용 -->
      <section v-if="candidate" class="card">
        <h2 class="sec"><span class="step-no">4</span> {{ kindOf(candidate) }} — 바뀌는 내용</h2>
        <div class="callout info">
          <Info :size="18" />
          <div>
            <p>{{ candidate.explanation }}</p>
            <p class="small muted">
              근거가 된 실행:
              <span v-for="e in candidate.evidence" :key="e" class="chip">{{ e }}</span>
            </p>
          </div>
        </div>
        <div v-for="p in candidate.patches" :key="p.skill" class="patch">
          <h3><FilePen :size="16" /> {{ p.skill }} <span class="muted small">(v{{ p.base_version }} 기준)</span></h3>
          <DiffView :patch="p.diff" before-label="지금" after-label="개선안 적용 후" />
        </div>
      </section>
    </div>

    <!-- 결정 패널 -->
    <aside class="side">
      <div class="card decide">
        <span class="muted small">선택한 개선안</span>
        <strong class="pick">{{ kindOf(candidate) }}</strong>
        <span class="small" :class="candidate?.valid ? 'ok-text' : 'error-text'">
          {{ candidate?.valid ? '모든 확인을 통과했어요' : '확인을 통과하지 못했어요' }}
        </span>

        <hr />
        <h3><FilePen :size="16" /> 내가 직접 다듬기</h3>
        <p class="small muted">개선안을 Skill 편집 화면의 초안으로 가져와요. 서버에는 저장되지 않아요.</p>
        <p class="small">적용할 Skill 세트: <code>{{ setLabel }}</code></p>
        <button class="primary block" :disabled="!candidate" @click="applyToDraft">
          <FilePen :size="16" /> 초안으로 가져와 고치기
        </button>
        <p v-if="applyError" class="small error-text">{{ applyError }}</p>

        <hr />
        <h3><ShieldCheck :size="16" /> 운영 반영</h3>
        <template v-if="pending">
          <p class="small muted">
            승인하면 AI 추천안 <strong>{{ kindOf(recommended) }}</strong> 이 운영 Skill 에 반영되고 기존 주문으로 다시
            점검해요. 점검이 실패하면 자동으로 되돌려요.
          </p>
          <button class="primary block" :disabled="!canDecide || !intentOk || busy" @click="decide('approved')">
            <CircleCheck :size="16" /> 추천안 승인
          </button>
          <button class="danger block" :disabled="!canDecide || busy" @click="decide('rejected')">거절</button>
          <p v-if="!canDecide" class="small muted">운영자만 승인할 수 있어요. 담당자는 의도를 확인하고 초안으로 다듬어 주세요.</p>
          <p v-else-if="!intentOk" class="small warn-text">2단계에서 의도를 확인해야 승인할 수 있어요.</p>
          <p v-if="candidate && recommended && candidate.id !== recommended.id" class="small muted">
            지금 보고 있는 안은 추천안이 아니에요. 승인은 추천안에만 할 수 있어요.
          </p>
        </template>
        <p v-else class="small">
          이미 <strong>{{ APPROVAL[proposal.approval_status]?.label }}</strong> 개선안이에요.
        </p>

        <dl v-if="decided" class="result">
          <dt>결정</dt>
          <dd>{{ decided.decision === 'approved' ? '승인' : '거절' }} · {{ decided.approver_name }}</dd>
          <template v-if="decided.approval_token">
            <dt>승인 번호</dt>
            <dd><code>{{ decided.approval_token }}</code></dd>
          </template>
          <dt>에이전트</dt>
          <dd>{{ decided.agent_result }}</dd>
        </dl>
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

.sec {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sec-desc {
  margin: -6px 0 16px 34px;
}

.facts {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 8px 20px;
  margin: 14px 0 0;
  font-size: var(--fs-sm);
}

.facts dt {
  color: var(--muted);
}

.facts dd {
  margin: 0;
  min-width: 0;
}

blockquote {
  display: flex;
  gap: 10px;
  margin: 0 0 10px;
  padding: 14px 16px;
  border-radius: var(--radius);
  background: var(--accent-soft);
  color: var(--text);
  font-size: var(--fs-lg);
  line-height: 1.5;
}

blockquote svg {
  flex: none;
  margin-top: 4px;
  color: var(--accent);
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

.confirm.off {
  cursor: default;
  opacity: 0.7;
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

.cands {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
  padding-top: 6px;
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
