<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CircleAlert, CircleCheck, FilePen, GitCompareArrows, Plus, RotateCcw, Trash2 } from 'lucide-vue-next'
import { useToastStore } from '@/stores/toast'
import { useWorkspaceStore } from '@/stores/workspace'
import { buildGraph, problemCount } from '@/utils/flowGraph'
import { ENTRY_SKILL, flowSteps, skillPath } from '@/utils/skillSet'
import DiffView from '@/components/DiffView.vue'
import PageHeader from '@/components/PageHeader.vue'
import SnapshotPicker from '@/components/SnapshotPicker.vue'
import { USE_MOCK } from '@/api/client'

// 편집 중인 초안은 브라우저에 남고 저장 시 별도 불변 snapshot이 된다.
// 원본과 초안의 차이, 그리고 초안을 반영한 전체 세트의 계약 검사 결과를 바로 보여 준다.
const route = useRoute()
const router = useRouter()
const ws = useWorkspaceStore()
const toast = useToastStore()
const tab = ref('edit')
const saveError = ref(null)
const saved = ref(null)
async function save() {
  saveError.value = null
  try {
    saved.value = await ws.saveDrafts()
    toast.show('수정본을 저장했어요. 자동 검증 요청이 전달됩니다.', 'info')
  } catch (e) {
    saveError.value = e.message
  }
}

const newName = ref('')
const NAME_RE = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/

const skillId = computed(() => route.params.skill || null)
const skill = computed(() => ws.draftSkills.find((s) => s.skill_id === skillId.value) ?? null)
const original = computed(() => ws.current?.skills.find((s) => s.skill_id === skillId.value) ?? null)
const isDraft = computed(() => !!skill.value && skill.value.path in ws.currentDrafts)

const stepSkills = computed(() => ws.draftSkills.filter((s) => s.skill_id !== ENTRY_SKILL))
const steps = computed(() => flowSteps(ws.draftSkills))
const graph = computed(() => buildGraph(stepSkills.value, steps.value))
const problems = computed(() => problemCount(graph.value))

// 목록의 상태 점: 형식 오류 · 연결 끊김 · 초안 · 원본
const missingOf = computed(() => Object.fromEntries(graph.value.missing.map((m) => [m.skill_id, m])))
function stateOf(s) {
  if (s.error) return { tone: 'err', label: '형식 오류' }
  if (missingOf.value[s.skill_id]) return { tone: 'err', label: '연결 끊김' }
  if (s.path in ws.currentDrafts) return { tone: 'warn', label: '초안' }
  return { tone: 'none', label: '원본' }
}
const myMissing = computed(() => missingOf.value[skillId.value] ?? null)
const changedLines = computed(() => {
  if (!isDraft.value) return 0
  const a = (original.value?.text ?? '').split('\n')
  const b = skill.value.text.split('\n')
  return Math.max(a.length, b.length) - a.filter((l, i) => l === b[i]).length
})

function revert() {
  edit(original.value.text)
  toast.show(`${skillId.value} 를 원본으로 되돌렸어요.`, 'info')
}

function removeNew() {
  ws.removeDraft(skill.value.path)
  toast.show('새 Skill 초안을 지웠어요.', 'info')
  router.push('/skills')
}

const nameError = computed(() => {
  if (!newName.value) return null
  if (!NAME_RE.test(newName.value) || newName.value.length > 64) return 'kebab-case 64자 이하만 쓴다. 예: shipping-check'
  if (ws.draftSkills.some((s) => s.skill_id === newName.value)) return '같은 이름의 skill 이 이미 있다'
  return null
})

watch(
  () => [ws.current, skillId.value],
  () => {
    if (ws.current && !skillId.value && route.query.new !== '1') {
      router.replace(`/skills/${ws.current.skills[0]?.skill_id ?? ''}`)
    }
  },
  { immediate: true },
)

function edit(text) {
  ws.setDraft(skill.value.path, text)
}

function create() {
  const id = newName.value
  const text = `---
name: ${id}
description:
inputs: []
outputs: []
depends_on: []
owner:
version: 1
---
# ${id}

`
  ws.setDraft(skillPath(id), text)
  newName.value = ''
  toast.show(`새 Skill ${id} 초안을 만들었어요. order-flow 의 steps 에 넣어야 실행돼요.`, 'info', 5000)
  router.push(`/skills/${id}`)
}
</script>

<template>
  <PageHeader
    title="Skill 편집"
    description="초안을 새 Skill 세트로 저장하고 주문 실행에서 확인해요. 오른쪽에서 입력·출력 연결을 점검할 수 있어요."
    :icon="FilePen"
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
        v-for="s in ws.draftSkills"
        :key="s.skill_id"
        :to="`/skills/${s.skill_id}`"
        class="item"
        :class="{ active: s.skill_id === skillId }"
      >
        <span class="dot" :class="stateOf(s).tone" :title="stateOf(s).label" />
        <span class="name">
          {{ s.skill_id }}
          <small>{{ s.owner || '담당 없음' }} · v{{ s.version ?? '?' }}</small>
        </span>
        <span v-if="stateOf(s).tone !== 'none'" class="tag" :class="stateOf(s).tone">{{ stateOf(s).label }}</span>
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
                <small><RouterLink to="/skills/order-flow">order-flow</RouterLink> 의 steps 순서를 확인해 주세요.</small>
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
