<script setup>
// Skill 편집 화면이다. frontend/src/router/index.js 가 /skills/:skill? 경로에 연결한다.
// 왼쪽은 Skill 목록, 가운데는 편집기와 원본 비교, 오른쪽은 저장 전 점검(입력·출력 계약 검사)이다.
// 편집 중인 초안은 브라우저(frontend/src/stores/workspace.js 의 drafts, localStorage)에만 남고,
// '전체 수정본 저장' 을 누르면 별도의 불변 snapshot 이 된다. 원본과 초안의 차이, 그리고 초안을
// 반영한 전체 세트의 계약 검사 결과를 바로 보여 준다.
// 저장은 workspace store 의 saveDrafts 가 frontend/src/api/orderAgent.js 의 saveSkillEdits 로
// POST /api/snapshots/edits 를 보내고,
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
// saveEdits() 가 받는다. order-agent 는 저장한 변경을 service 를 거쳐 skillops-agent 의 자동
// 검증·진단으로 넘기고, 이 화면은 응답의 change_id 로 검증·진단 기록 화면
// (/diagnoses?change_id=…, frontend/src/views/DiagnosisView.vue)으로 가는 링크를 보여 준다.
// 계약 검사는 frontend/src/utils/flowGraph.js 의 flowGraphOf 와 problemCount 가 하고,
// 새 Skill 의 이름 규칙과 틀은 frontend/src/utils/skillSet.js 의 skillNameProblem 과
// newSkillTemplate 이 정한다. 제안서 상세(frontend/src/views/ProposalView.vue)의
// '초안으로 가져와 고치기' 도 patch 를 초안에 적용한 뒤 이 화면으로 온다.
// mock 모드(frontend/src/api/client.js 의 USE_MOCK)에는 저장 경로가 없어 저장 버튼을 끈다.
// 저장, 되돌리기, 새 Skill 초안 만들기와 지우기의 결과는 frontend/src/stores/toast.js 의 show 로
// 알린다.
// frontend/tests/diagnosis.spec.js 가 편집기(textarea), '전체 수정본 저장' 버튼,
// '자동 검증·진단 진행 확인' 링크로 저장부터 진단 화면 이동까지 확인한다.
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CircleAlert, CircleCheck, FilePen, GitCompareArrows, Plus, RotateCcw, Trash2 } from 'lucide-vue-next'
import { useToastStore } from '@/stores/toast'
import { useWorkspaceStore } from '@/stores/workspace'
import { flowGraphOf, problemCount } from '@/utils/flowGraph'
import { ENTRY_SKILL, newSkillTemplate, skillNameProblem, skillPath } from '@/utils/skillSet'
import DiffView from '@/components/DiffView.vue'
import PageHeader from '@/components/PageHeader.vue'
import SnapshotPicker from '@/components/SnapshotPicker.vue'
import { USE_MOCK } from '@/api/client'

const route = useRoute()
const router = useRouter()
const ws = useWorkspaceStore()
const toast = useToastStore()
// 가운데 칸의 탭이다. 'edit' 는 편집기, 'diff' 는 원본과 비교다.
const tab = ref('edit')
const saveError = ref(null)
// 마지막 저장 응답(snapshot_id, change_id, changed_paths 등)이다. 결과 줄과 진단 화면 링크에 쓴다.
const saved = ref(null)
/**
 * 지금 세트의 초안 전체를 새 수정본 snapshot 으로 저장한다. '전체 수정본 저장' 버튼이 부른다.
 * 저장할 수 없는 경우(mock 모드, 저장 중, 바뀐 초안 없음)에는 saveDrafts 가 오류를 던지고
 * 그 문구를 보여 준다. saveDrafts 는 저장한 내용과 같은 초안만 지우므로 요청 중에 더 고친 내용은
 * 초안으로 남는다.
 */
async function save() {
  saveError.value = null
  try {
    saved.value = await ws.saveDrafts()
    toast.show('수정본을 저장했어요. 자동 검증 요청이 전달됩니다.', 'info')
  } catch (e) {
    saveError.value = e.message
  }
}

// 새 Skill 이름 입력 칸의 값이다.
const newName = ref('')

// skillId 는 주소의 :skill 값이고 없으면 null 이다.
// skill 은 초안을 덮어쓴 목록(workspace store 의 draftSkills)에서 지금 편집 중인 Skill 이다.
// original 은 같은 Skill 의 원본(지금 snapshot 에 저장된 내용)이며 새 Skill 초안이면 null 이다.
// isDraft 는 지금 Skill 에 브라우저 초안이 있는지를 나타낸다.
const skillId = computed(() => route.params.skill || null)
const skill = computed(() => ws.draftSkills.find((s) => s.skill_id === skillId.value) ?? null)
const original = computed(() => ws.current?.skills.find((s) => s.skill_id === skillId.value) ?? null)
const isDraft = computed(() => !!skill.value && skill.value.path in ws.currentDrafts)

// 초안을 반영한 전체 세트의 계약 그래프와 문제 수다. 진입 Skill(order-flow)의 steps 순서로
// 앞 단계의 출력이 뒤 단계의 입력을 채우는지 본다. 규칙은 order-agent 의 실행 전 검사인
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의
// validateFlow 와 같다.
const graph = computed(() => flowGraphOf(ws.draftSkills))
const problems = computed(() => problemCount(graph.value))

// 계약 위반을 skill_id 로 찾는 표다.
const missingOf = computed(() => Object.fromEntries(graph.value.missing.map((m) => [m.skill_id, m])))
/**
 * 목록 항목 하나의 상태 점과 꼬리표를 정한다. 아래 listItems 가 항목마다 부른다.
 * 형식 오류, 연결 끊김(계약 위반), 초안, 원본 순서로 보고 처음 맞는 것을 쓴다.
 */
function stateOf(s) {
  if (s.error) return { tone: 'err', label: '형식 오류' }
  if (missingOf.value[s.skill_id]) return { tone: 'err', label: '연결 끊김' }
  if (s.path in ws.currentDrafts) return { tone: 'warn', label: '초안' }
  return { tone: 'none', label: '원본' }
}
// 목록 항목마다 상태를 한 번만 계산한다. skill_id 가 없는 초안도 있어서 맵이 아니라 배열로 둔다.
const listItems = computed(() => ws.draftSkills.map((s) => ({ skill: s, state: stateOf(s) })))
// 지금 Skill 의 계약 위반이다. 받는 값과 앞 단계 칩 가운데 끊긴 것을 빨갛게 칠하는 데 쓴다.
const myMissing = computed(() => missingOf.value[skillId.value] ?? null)
// '원본과 비교' 탭 옆 숫자다. 같은 줄 번호끼리 비교한 대략의 바뀐 줄 수라서, 줄 하나가 끼어들면
// 그 뒤 줄이 모두 바뀐 것으로 센다. 정확한 차이는 frontend/src/components/DiffView.vue 가
// 보여 준다.
const changedLines = computed(() => {
  if (!isDraft.value) return 0
  const a = (original.value?.text ?? '').split('\n')
  const b = skill.value.text.split('\n')
  return Math.max(a.length, b.length) - a.filter((l, i) => l === b[i]).length
})

/**
 * 지금 Skill 을 원본 내용으로 되돌린다. '원본으로' 버튼이 부른다. 원본과 같은 글로 setDraft 하면
 * workspace store 가 그 초안을 지우므로 초안 표시도 사라진다.
 */
function revert() {
  edit(original.value.text)
  toast.show(`${skillId.value} 를 원본으로 되돌렸어요.`, 'info')
}

/**
 * 원본이 없는 새 Skill 초안을 지우고 /skills 로 간다. '초안 삭제' 버튼이 부른다.
 * 그러면 아래 watch 가 세트의 첫 Skill 로 옮긴다.
 */
function removeNew() {
  ws.removeDraft(skill.value.path)
  toast.show('새 Skill 초안을 지웠어요.', 'info')
  router.push('/skills')
}

// 새 Skill 이름의 문제 문구다. 비어 있거나 문제가 없으면 null 이고,
// 규칙(kebab-case, 길이, 중복)은 frontend/src/utils/skillSet.js 의 skillNameProblem 에 있다.
const nameError = computed(() => skillNameProblem(newName.value, ws.draftSkills))

// 세트가 준비됐는데 주소에 Skill 이 없으면 세트의 첫 Skill 화면으로 바꾼다.
// 주소에 new=1 쿼리가 있으면 옮기지 않지만, 지금 이 쿼리를 붙이는 코드는 없다.
watch(
  () => [ws.current, skillId.value],
  () => {
    if (ws.current && !skillId.value && route.query.new !== '1') {
      router.replace(`/skills/${ws.current.skills[0]?.skill_id ?? ''}`)
    }
  },
  { immediate: true },
)

/**
 * 편집기의 글을 지금 Skill 의 초안으로 넣는다. 원본과 같아지면 workspace store 가 초안을 지운다.
 * textarea 의 input 이벤트와 revert 가 부른다.
 */
function edit(text) {
  ws.setDraft(skill.value.path, text)
}

/**
 * 입력한 이름으로 새 Skill 초안을 만들고 그 편집 화면으로 간다. '추가' 버튼과 이름 칸의 Enter 가
 * 부른다. 이름 검사는 그 버튼과 Enter 처리에서 nameError 로 먼저 막으므로 여기서는 하지 않는다.
 * 내용은 frontend/src/utils/skillSet.js 의 newSkillTemplate 틀이다.
 * 새 Skill 은 진입 Skill(ENTRY_SKILL)의 steps 에 넣어야 실행되므로
 * 그 안내를 5초 동안 토스트로 띄운다.
 */
function create() {
  const id = newName.value
  ws.setDraft(skillPath(id), newSkillTemplate(id))
  newName.value = ''
  toast.show(`새 Skill ${id} 초안을 만들었어요. ${ENTRY_SKILL} 의 steps 에 넣어야 실행돼요.`, 'info', 5000)
  router.push(`/skills/${id}`)
}
</script>

<template>
  <PageHeader
    title="skill 목록"
    description="초안을 새 Skill 세트로 저장하고 주문 실행에서 확인해요. 오른쪽에서 입력·출력 연결을 점검할 수 있어요."
    :icon="FilePen"
    description-tip
  />
  <div class="card toolbar">
    <SnapshotPicker />
    <button :disabled="USE_MOCK || ws.saving || !Object.keys(ws.currentDrafts).length" @click="save">
      {{ ws.saving ? '저장 중…' : '전체 수정본 저장' }}
    </button>
    <p v-if="USE_MOCK" class="muted small">수정본 저장은 실제 서버 모드에서 사용할 수 있어요.</p>
    <p v-if="saveError" role="alert" class="error-text">{{ saveError }}</p>
    <p v-if="saved" role="status" class="small">
      수정본 저장 완료 · {{ saved.snapshot_id }} · {{ saved.changed_paths.length }}개 파일 변경.
      <RouterLink :to="`/diagnoses?change_id=${saved.change_id}`">자동 검증·진단 진행 확인</RouterLink>
      · <RouterLink to="/orders">이 수정본으로 주문 실행</RouterLink>
    </p>
  </div>

  <p class="callout">편집 중인 초안은 아직 실행에 적용되지 않아요. 저장하면 새 수정 세트가 만들어지고, 자동 검증과 이 세트를 선택한 주문에 적용돼요. 5174의 기본 주문에는 자동 반영되지 않아요.</p>
  <div v-if="ws.current" class="layout">
    <!-- Skill 목록 -->
    <aside class="card list">
      <span class="list-title">Skill {{ ws.draftSkills.length }}개</span>
      <RouterLink
        v-for="{ skill: s, state } in listItems"
        :key="s.skill_id"
        :to="`/skills/${s.skill_id}`"
        class="item"
        :class="{ active: s.skill_id === skillId }"
      >
        <span class="dot" :class="state.tone" :title="state.label" />
        <span class="name">
          {{ s.skill_id }}
          <small>{{ s.owner || '담당 없음' }} · v{{ s.version ?? '?' }}</small>
        </span>
        <span v-if="state.tone !== 'none'" class="tag" :class="state.tone">{{ state.label }}</span>
      </RouterLink>
      <div class="new">
        <input v-model.trim="newName" placeholder="새 Skill 이름 (예: shipping-check)" @keyup.enter="!nameError && newName && create()" />
        <button class="sm" :disabled="!newName || !!nameError" @click="create"><Plus :size="14" /> 추가</button>
      </div>
      <small v-if="nameError" class="error-text">{{ nameError }}</small>
    </aside>

    <!-- 편집기 -->
    <section v-if="skill" class="card editor">
      <div class="ed-head">
        <div>
          <h2>
            {{ skill.skill_id }}
            <span class="tag" :class="isDraft ? 'warn' : 'none'">{{ isDraft ? (original ? '초안' : '새 Skill 초안') : '원본' }}</span>
          </h2>
          <p class="muted small">
            {{ skill.description || '설명 없음' }}
            <template v-if="original"> · 원본 <code>{{ original.file_hash?.slice(0, 10) }}</code></template>
          </p>
        </div>
        <div class="row">
          <button v-if="isDraft && original" class="ghost sm" @click="revert"><RotateCcw :size="14" /> 원본으로</button>
          <button v-if="!original" class="danger sm" @click="removeNew"><Trash2 :size="14" /> 초안 삭제</button>
        </div>
      </div>

      <dl v-if="!skill.error && !skill.steps.length" class="io">
        <dt>받는 값</dt>
        <dd>
          <span
            v-for="k in skill.inputs"
            :key="k"
            class="chip"
            :class="{ del: myMissing?.missing_inputs.includes(k) }"
            :title="myMissing?.missing_inputs.includes(k) ? '앞 단계가 이 값을 내보내지 않아요' : ''"
          >{{ k }}</span>
          <span v-if="!skill.inputs.length" class="muted small">없음</span>
        </dd>
        <dt>내보내는 값</dt>
        <dd>
          <span v-for="k in skill.outputs" :key="k" class="chip">{{ k }}</span>
          <span v-if="!skill.outputs.length" class="muted small">없음</span>
        </dd>
        <dt>앞 단계</dt>
        <dd>
          <span v-for="k in skill.depends_on" :key="k" class="chip" :class="{ del: myMissing?.missing_dependencies.includes(k) }">{{ k }}</span>
          <span v-if="!skill.depends_on.length" class="muted small">없음</span>
        </dd>
      </dl>
      <dl v-else-if="skill.steps.length" class="io">
        <dt>처리 순서</dt>
        <dd><span v-for="(k, i) in skill.steps" :key="k" class="chip">{{ i + 1 }}. {{ k }}</span></dd>
      </dl>
      <div v-if="skill.error" class="callout err">
        <CircleAlert :size="18" />
        <p>형식 오류({{ skill.error }}) — {{ skill.error_message ?? '맨 위 --- 구분선과 name 이 폴더 이름과 같은지 확인해 주세요.' }}</p>
      </div>

      <div class="tabs" role="tablist">
        <button class="ghost sm" :class="{ on: tab === 'edit' }" @click="tab = 'edit'"><FilePen :size="14" /> 편집</button>
        <button class="ghost sm" :class="{ on: tab === 'diff' }" :disabled="!isDraft" @click="tab = 'diff'">
          <GitCompareArrows :size="14" /> 원본과 비교 <span v-if="changedLines" class="n">{{ changedLines }}</span>
        </button>
      </div>
      <textarea
        v-if="tab === 'edit' || !isDraft"
        :value="skill.text"
        spellcheck="false"
        rows="22"
        @input="edit($event.target.value)"
      />
      <DiffView v-else :before="original?.text ?? ''" :after="skill.text" before-label="원본" after-label="내 초안" />
    </section>

    <!-- 점검 -->
    <aside class="check stack">
      <div class="card">
        <h3>저장 전 점검</h3>
        <div v-if="!problems" class="callout ok">
          <CircleCheck :size="18" />
          <p>선언된 입력·출력 연결에서 문제를 찾지 못했어요. 저장하면 기본 주문 검증이 자동으로 시작돼요.</p>
        </div>
        <template v-else>
          <div class="callout err">
            <CircleAlert :size="18" />
            <p>이대로면 주문이 <strong>{{ problems }}곳</strong>에서 멈춰요.</p>
          </div>
          <ul class="problems">
            <li v-for="m in graph.missing" :key="m.skill_id">
              <RouterLink :to="`/skills/${m.skill_id}`"><strong>{{ m.skill_id }}</strong></RouterLink>
              <template v-if="m.missing_inputs.length">
                이(가) <span v-for="k in m.missing_inputs" :key="k" class="chip del">{{ k }}</span> 를 받지 못해요.
                <small>앞 단계 Skill 의 내보내는 값(outputs)에 추가하거나, 이 Skill 의 받는 값(inputs)에서 빼 주세요.</small>
              </template>
              <template v-if="m.missing_dependencies.length">
                보다 <span v-for="k in m.missing_dependencies" :key="k" class="chip">{{ k }}</span> 가 먼저 끝나야 해요.
                <small><RouterLink :to="`/skills/${ENTRY_SKILL}`">{{ ENTRY_SKILL }}</RouterLink> 의 steps 순서를 확인해 주세요.</small>
              </template>
            </li>
            <li v-for="n in graph.nodes.filter((x) => x.parse_error)" :key="n.skill_id">
              <strong>{{ n.skill_id }}</strong> 를 읽을 수 없어요 ({{ n.parse_error }}).
            </li>
          </ul>
        </template>
        <p class="muted small">전체 수정본 저장을 누르면 새 세트로 저장돼요. 연결 오류가 있는 수정본도 저장할 수 있으며, 저장 후 자동 검증·진단이 시작돼요.</p>
      </div>
    </aside>
  </div>
  <p v-else-if="ws.loading" class="muted">불러오는 중…</p>
</template>

<style scoped>
.toolbar {
  padding: 12px 16px;
  margin-bottom: 16px;
}

.layout {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr) 320px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 1200px) {
  .layout {
    grid-template-columns: 220px minmax(0, 1fr);
  }

  .check {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px;
}

.list-title {
  padding: 0 8px 6px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--muted);
}

.item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: var(--radius-sm);
  color: var(--text);
  text-decoration: none;
}

.item:hover {
  background: var(--surface-2);
  text-decoration: none;
}

.item.active {
  background: var(--accent-soft);
}

.name {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  font-weight: 600;
  line-height: 1.3;
}

.name small {
  font-weight: 400;
  color: var(--muted);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--border-strong);
  flex: none;
}

.dot.err {
  background: var(--err);
}

.dot.warn {
  background: var(--warn);
}

.tag {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--muted);
  white-space: nowrap;
}

.tag.warn {
  background: var(--warn-soft);
  color: var(--warn);
}

.tag.err {
  background: var(--err-soft);
  color: var(--err);
}

.new {
  display: flex;
  gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}

.new input {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-sm);
}

.editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.ed-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}

.ed-head h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 2px;
  font-size: var(--fs-xl);
}

.ed-head p {
  margin: 0;
}

.io {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 6px 14px;
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--radius);
  background: var(--surface-2);
  font-size: var(--fs-sm);
}

.io dt {
  color: var(--muted);
  font-weight: 600;
}

.io dd {
  margin: 0;
}

.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border);
}

.tabs button {
  border-radius: var(--radius-sm) var(--radius-sm) 0 0;
  border-bottom: 2px solid transparent;
}

.tabs .on {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.n {
  padding: 0 6px;
  border-radius: 999px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: 11px;
}

textarea {
  width: 100%;
  min-height: 420px;
  padding: 14px 16px;
  background: var(--surface-2);
  resize: vertical;
}

.check h3 {
  margin-bottom: 10px;
}

.problems {
  margin: 10px 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: var(--fs-sm);
}

.problems li {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
}

.problems small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
}
</style>
