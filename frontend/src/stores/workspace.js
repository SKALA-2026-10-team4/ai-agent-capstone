// 화면들이 함께 보는 작업 상태를 담는 Pinia 스토어다. 지금 보고 있는 snapshot, 그 Skill 세트,
// 브라우저에만 있는 초안과 변경 의도 입력, 고정 이력, 최근 실행 목록을 둔다. 초안은 브라우저에
// 보관하고 담당자가 명시적으로 저장할 때 새 snapshot 으로 발행한다. snapshotId, pinned, drafts,
// intents 는 localStorage 에 저장하는데, mock 과 실서버는 서로의 snapshot id 를 모르므로 저장소
// 키를 나눈다. 화면 쪽
// 사용처는 frontend/src/components/SnapshotPicker.vue(선택과 고정),
// frontend/src/views/SkillDraftView.vue(초안 편집과 저장),
// frontend/src/views/OrderView.vue(실행 목록), frontend/src/views/ExecutionView.vue(실행 당시
// 세트), frontend/src/views/SnapshotCompareView.vue(비교할 두 세트),
// frontend/src/views/ProposalView.vue(제안서 patch 를 초안에 적용)다. 튜닝 값인 고정 이력
// 개수와 patch 허용 오차는 frontend/src/config.js 에 있다.
import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { applyPatch } from 'diff'
import { USE_MOCK } from '@/api/client'
import { listExecutions, pinFixture, saveSkillEdits } from '@/api/orderAgent'
import { loadSkillSet } from '@/api/skillSets'
import { PATCH_FUZZ_FACTOR, PINNED_SNAPSHOT_LIMIT } from '@/config'
import { shortId } from '@/utils/format'
import { skillPath, toSkill } from '@/utils/skillSet'

// snapshot metadata 의 fixture 나 source 값을 담당자가 알아보는 세트 이름으로 바꾸는
// 표다. frontend/src/components/SnapshotPicker.vue 가 fixture 고정 버튼의 글자로도 쓴다.
export const SET_LABEL = {
  baseline: '정상본',
  'broken-contract': '결함본',
  repaired: '수정본',
  'published-bundle': '게시본',
  'user-edit': '내 수정본',
}
// 종류를 알 수 없는 세트의 이름이다. 내용을 아직 읽지 않았고 고정 기록에도 없는 세트가 여기에
// 든다. knownSnapshots 는 이 접두어로 그런 세트를 알아보고 실행 결과로 이름을 대신 붙인다.
const UNKNOWN_SET_LABEL = 'Skill 세트'
// 고른 세트가 없을 때의 이름이다.
const NO_SELECTION_LABEL = '선택 안 됨'

// localStorage 키다. mock 모드는 다른 키를 써서 실서버의 snapshot id 와 섞이지 않게 한다.
const STORAGE_KEY = USE_MOCK ? 'skillops.workspace.mock' : 'skillops.workspace'

// localStorage 에 저장해 둔 작업 상태를 읽는다. 없거나 읽을 수 없으면 빈 객체다.
function restore() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY)) ?? {}
  } catch {
    return {}
  }
}

// 작업 상태 스토어다. 상태와 함수의 설명은 각 항목 위에 있다.
export const useWorkspaceStore = defineStore('workspace', () => {
  const saved = restore()
  // 지금 고른 snapshot id 다.
  const snapshotId = ref(saved.snapshotId ?? null)
  // 고정 이력 [{ snapshot_id, fixture, created_at }] 이다. 최근 것이 앞에 온다.
  const pinned = ref(saved.pinned ?? [])
  // snapshot id 마다 { 경로: 초안 원문 } 이다.
  const drafts = ref(saved.drafts ?? {})
  // snapshot id 마다 변경 의도 입력 { text, expectations } 이다. text 는 의도 한 줄, expectations 는
  // 고급 입력 칸의 글 그대로다. frontend/src/views/SkillDraftView.vue 가 고치고, 저장하면 지운다.
  const intents = ref(saved.intents ?? {})
  // 최근 실행 목록(GET /api/executions 의 items)이다.
  const executions = ref([])

  // snapshot id 마다 frontend/src/api/skillSets.js 의 loadSkillSet 결과
  // { snapshot, skills, others, partial, parse_errors } 를 캐시한다.
  const sets = ref({})
  // loading 은 ensureSet 이 세트를 읽는 동안, saving 은 saveDrafts 가 저장하는 동안 참이다.
  // error 는 세트나 최근 실행 목록을 읽지 못했을 때의 문구다.
  const loading = ref(false)
  const error = ref(null)
  const saving = ref(false)

  /**
   * 저장할 수 있는지 mock 모드, 저장 중, 변경 없음 순서로 확인하고 { id, base, changes } 를
   * 돌려준다. 조건에 걸리면 화면에 그대로 보일 문구로 Error 를 던진다. changes 는 지금
   * snapshot 의 초안을 복사한 것이라, 요청 중에 편집이 생겨도 보낸 내용과 섞이지 않는다.
   */
  function savableChanges() {
    if (USE_MOCK) throw new Error('수정본 저장은 실제 서버 모드에서 사용할 수 있어요.')
    if (saving.value) throw new Error('수정본을 저장하고 있어요.')
    const id = snapshotId.value
    const base = sets.value[id]
    const changes = { ...drafts.value[id] }
    if (!base || !Object.keys(changes).length) throw new Error('저장할 수정 내용이 없어요.')
    return { id, base, changes }
  }

  // 저장한 내용과 글자까지 같은 초안만 지우고 남은 초안을 돌려준다.
  // 요청 중에 다시 고친 파일은 남는다.
  function dropSavedDrafts(id, changes) {
    return updateDrafts(id, (next) => {
      for (const [path, text] of Object.entries(changes)) {
        if (next[path] === text) delete next[path]
      }
    })
  }

  /**
   * 지금 snapshot 의 초안을 서버에 저장한다. frontend/src/api/orderAgent.js 의 saveSkillEdits 가
   * POST /api/snapshots/edits 로 기준 snapshot id 와 hash, 바뀐 원문, 그리고 intent 가 있으면 담당자
   * 변경 의도를 보낸다. intent 는 frontend/src/utils/intentInput.js 의 buildIntent 결과이고 null 이면
   * 보내지 않는다. 저장된 snapshot 은 고정 이력에 내 수정본(user-edit)으로 넣고, 그 세트의 의도
   * 입력을 지운다. 같은 세트를 계속 보고 있으며 남은 초안이 없으면 새 snapshot 을 고른다. 돌려준
   * 응답의 change_id 로 frontend/src/views/SkillDraftView.vue 가 진단 화면 링크를 만든다. 실패하면
   * 초안과 의도 입력을 그대로 두고 예외를 던진다.
   */
  async function saveDrafts(intent = null) {
    const { id, base, changes } = savableChanges()
    saving.value = true
    try {
      const savedSnapshot = await saveSkillEdits(id, base.snapshot.hash, changes, intent)
      // 요청이 오가는 동안 생긴 편집은 다른 snapshot 의 편집까지 모두 남긴다.
      const remaining = dropSavedDrafts(id, changes)
      if (intent) clearIntentDraft(id)
      remember({ snapshot_id: savedSnapshot.snapshot_id, fixture: 'user-edit', created_at: savedSnapshot.created_at })
      if (snapshotId.value === id && !Object.keys(remaining).length) await select(savedSnapshot.snapshot_id)
      return savedSnapshot
    } finally {
      saving.value = false
    }
  }

  // 지금 snapshot 의 변경 의도 입력 { text, expectations } 이다. 적은 적이 없으면 빈 입력이다.
  const currentIntent = computed(() => intents.value[snapshotId.value] ?? { text: '', expectations: '' })

  // 지금 snapshot 의 변경 의도 입력에서 field('text' 나 'expectations')를 value 로 바꾼다. 두 칸이 모두
  // 비면 기록을 지운다. frontend/src/views/SkillDraftView.vue 의 의도 입력 칸이 부른다.
  function setIntentDraft(field, value) {
    const id = snapshotId.value
    const next = { ...currentIntent.value, [field]: value }
    const rest = { ...intents.value }
    if (next.text || next.expectations) rest[id] = next
    else delete rest[id]
    intents.value = rest
  }

  // snapshot id 의 변경 의도 입력을 지운다. 저장에 성공한 뒤 saveDrafts 가 부른다.
  function clearIntentDraft(id) {
    const rest = { ...intents.value }
    delete rest[id]
    intents.value = rest
  }

  // snapshotId, pinned, drafts, intents 가 바뀔 때마다 localStorage 에 쓴다.
  watch(
    [snapshotId, pinned, drafts, intents],
    () => {
      try {
        const state = { snapshotId: snapshotId.value, pinned: pinned.value, drafts: drafts.value, intents: intents.value }
        localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
      } catch {
        // 저장소를 못 쓰는 환경이면 새로고침 때 초안이 사라질 뿐이다.
      }
    },
    { deep: true },
  )

  /**
   * snapshot id 의 Skill 세트를 한 번만 읽어 sets 에 캐시하고 돌려준다. 읽지 못하면 error 에 문구를
   * 남기고 null 을 돌려준다. frontend/src/views/ExecutionView.vue(실행 당시 세트),
   * frontend/src/views/SnapshotCompareView.vue(비교할 두 세트), select, init 이 부른다.
   */
  async function ensureSet(id) {
    if (!id || sets.value[id]) return sets.value[id] ?? null
    loading.value = true
    error.value = null
    try {
      sets.value[id] = await loadSkillSet(id)
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
    return sets.value[id] ?? null
  }

  // snapshot 을 고르고 그 세트를 읽는다. frontend/src/components/SnapshotPicker.vue 의
  // 선택, pin, saveDrafts, init 이 부른다.
  async function select(id) {
    snapshotId.value = id
    await ensureSet(id)
  }

  // 최근 실행 목록을 다시 읽는다. 실패하면 error 에 문구만 남긴다. init,
  // frontend/src/views/OrderView.vue(화면을 열 때와 주문을 실행한 뒤),
  // frontend/src/views/SnapshotCompareView.vue(화면을 열 때)가 부른다.
  async function refreshExecutions() {
    try {
      executions.value = (await listExecutions()).items
    } catch (e) {
      error.value = e.message
    }
  }

  // 고정 이력 맨 앞에 넣고 상한 개수까지만 남긴다. 상한은
  // frontend/src/config.js 의 PINNED_SNAPSHOT_LIMIT 이다. saveDrafts 와 pin 이 부른다.
  function remember(entry) {
    pinned.value = [entry, ...pinned.value].slice(0, PINNED_SNAPSHOT_LIMIT)
  }

  /**
   * 준비된 fixture 로 새 snapshot 을 만들고(POST /api/snapshots) 고정 이력에 넣은 뒤 그 snapshot 을
   * 고른다. 만든 snapshot 의 manifest 를 돌려준다. frontend/src/components/SnapshotPicker.vue 의
   * 고정 버튼과 frontend/src/views/SnapshotCompareView.vue 의 시연 준비가 부른다.
   */
  async function pin(fixture) {
    const m = await pinFixture(fixture)
    remember({ snapshot_id: m.snapshot_id, fixture, created_at: m.created_at })
    await select(m.snapshot_id)
    return m
  }

  // snapshot 선택지다. snapshot 목록 API 가 없으므로 고정 이력과 최근 실행이 쓴 snapshot 을 합친다.
  // 항목은 { id, label } 이고 frontend/src/components/SnapshotPicker.vue 와
  // frontend/src/views/SnapshotCompareView.vue 가 쓴다.
  const knownSnapshots = computed(() => {
    const seen = new Map()
    for (const p of pinned.value) seen.set(p.snapshot_id, labelOf(p.snapshot_id))
    for (const x of executions.value) {
      if (x.snapshot_id && !seen.has(x.snapshot_id)) {
        const known = labelOf(x.snapshot_id)
        // 내용을 아직 안 읽은 세트는 그 세트로 돌린 실행 결과로 구분한다.
        const label = known.startsWith(UNKNOWN_SET_LABEL)
          ? `${x.final_status ?? x.completion} 실행에 쓴 세트 · ${shortId(x.snapshot_id)}`
          : known
        seen.set(x.snapshot_id, label)
      }
    }
    return [...seen].map(([id, label]) => ({ id, label }))
  })

  // 고정 이력에서 snapshot 의 fixture 이름을 찾는다. 없으면 null 이다.
  const fixtureOf = (id) => pinned.value.find((p) => p.snapshot_id === id)?.fixture ?? null

  // 세트 종류 값을 구한다. 불러온 적 있는 세트는 metadata 로, 아니면 고정 기록으로 알아낸다.
  // 표에 있으면 그 라벨을, 표에 없으면 metadata 의 원래 값을, 아무것도 모르면
  // UNKNOWN_SET_LABEL 을 돌려준다. labelOf 가 이 값을 이름 글에 넣는다.
  function kindOf(id) {
    const meta = sets.value[id]?.snapshot.metadata
    const key = meta?.fixture ?? meta?.source ?? fixtureOf(id)
    return SET_LABEL[key] ?? key ?? UNKNOWN_SET_LABEL
  }

  // 세트 종류 이름(예: 결함본)을 돌려준다. labelOf 가 만든 이름에서 첫 ' · ' 앞부분을 잘라 쓴다.
  // 리팩토링 전 화면이 쓰던 식(labelOf(id).split(' · ')[0])과 같은 결과를 내려고 이렇게 계산한다.
  // metadata 값에 ' · ' 가 들어 있거나 글이 아닌 값이어도 결과가 같다. id 가 없으면
  // NO_SELECTION_LABEL 이다. frontend/src/components/SnapshotPicker.vue 와
  // frontend/src/views/ExecutionView.vue 가 쓴다.
  function kindLabelOf(id) {
    return labelOf(id).split(' · ')[0]
  }

  // '결함본 · f47e7062' 처럼 종류 이름과 id 앞 8자를 이은 이름을
  // 돌려준다. id 가 없으면 NO_SELECTION_LABEL 이다. knownSnapshots, kindLabelOf,
  // frontend/src/views/OrderView.vue, frontend/src/views/ProposalView.vue 가 쓴다.
  function labelOf(id) {
    return id ? `${kindOf(id)} · ${shortId(id)}` : NO_SELECTION_LABEL
  }

  // 지금 고른 세트(loadSkillSet 결과)다. 아직 읽지 않았으면 null 이다.
  const current = computed(() => sets.value[snapshotId.value] ?? null)
  // 지금 고른 snapshot 의 초안 { 경로: 원문 } 이다.
  const currentDrafts = computed(() => drafts.value[snapshotId.value] ?? {})

  // snapshot 원본에 초안을 덮어쓴 Skill 목록이다. 초안 파일은
  // frontend/src/utils/skillSet.js 의 toSkill 로 다시 파싱하고, 원본에 없는 경로의
  // 초안은 새 Skill 로 뒤에 붙인다. frontend/src/views/SkillDraftView.vue 의 목록과 계약 점검,
  // applyPatchesToDraft 가 이 목록을 쓴다.
  const draftSkills = computed(() => {
    if (!current.value) return []
    const overrides = currentDrafts.value
    const original = current.value.skills.map((s) =>
      s.path in overrides ? toSkill(s.path, overrides[s.path]) : s,
    )
    const added = Object.entries(overrides)
      .filter(([p]) => !current.value.skills.some((s) => s.path === p))
      .map(([p, text]) => toSkill(p, text))
    return [...original, ...added]
  })

  // drafts[id] 를 복사해 mutate 로 고친 뒤 drafts 전체를 새 객체로 바꾸고
  // 고친 복사본을 돌려준다. 전체를 새 객체로 바꾸어 반응성을 유지한다.
  function updateDrafts(id, mutate) {
    const next = { ...drafts.value[id] }
    mutate(next)
    drafts.value = { ...drafts.value, [id]: next }
    return next
  }

  // 지금 snapshot 의 path 초안을 text 로 둔다. 원본과 같아지면 초안을 지워
  // 바뀐 파일로 세지 않는다. frontend/src/views/SkillDraftView.vue 의
  // 편집, 되돌리기, 새 Skill 만들기와 applyPatchesToDraft 가 부른다.
  function setDraft(path, text) {
    const id = snapshotId.value
    const original = current.value?.skills.find((s) => s.path === path)?.text
    updateDrafts(id, (next) => {
      if (text === original) delete next[path]
      else next[path] = text
    })
  }

  /**
   * 제안서 후보의 unified diff(patches[].patch)들을 현재 초안(없으면 원본)에 적용한다.
   * 하나라도 대상 파일이 없거나 기준 내용이 다르면 아무것도 바꾸지 않고 실패한 skill 목록을
   * { ok: false, failed } 로 돌려준다. 반쯤 적용된 초안을 만들지 않기 위해서다. 모두 적용되면
   * { ok: true, failed: [] } 이다. 문맥 줄 허용 오차는 frontend/src/config.js 의
   * PATCH_FUZZ_FACTOR 다. frontend/src/views/ProposalView.vue 의 applyToDraft 가 부른다.
   */
  function applyPatchesToDraft(patches) {
    const results = patches.map((p) => {
      const path = skillPath(p.skill)
      const base = draftSkills.value.find((s) => s.path === path)?.text
      const next = base == null ? false : applyPatch(base, p.patch, { fuzzFactor: PATCH_FUZZ_FACTOR })
      return { skill: p.skill, path, next }
    })
    const failed = results.filter((r) => r.next === false).map((r) => r.skill)
    if (failed.length) return { ok: false, failed }
    for (const r of results) setDraft(r.path, r.next)
    return { ok: true, failed: [] }
  }

  /**
   * 화면을 처음 열 때 부른다. 최근 실행을 읽고 저장해 둔 snapshot 을 다시 읽는다. 저장해 둔
   * snapshot 이 없거나 읽지 못하면 선택지의 첫 세트로 바꾼다.
   * frontend/src/components/SnapshotPicker.vue 와 frontend/src/views/ProposalView.vue 가
   * onMounted 에서 부른다.
   */
  async function init() {
    await refreshExecutions()
    if (snapshotId.value && (await ensureSet(snapshotId.value))) return
    if (knownSnapshots.value.length) {
      error.value = null
      await select(knownSnapshots.value[0].id)
    }
  }

  // 지금 snapshot 의 path 초안을 지운다.
  // frontend/src/views/SkillDraftView.vue 가 새 Skill 초안을 지울 때 부른다.
  function removeDraft(path) {
    updateDrafts(snapshotId.value, (next) => {
      delete next[path]
    })
  }

  return {
    snapshotId,
    pinned,
    executions,
    loading,
    error,
    saving,
    saveDrafts,
    current,
    currentDrafts,
    currentIntent,
    setIntentDraft,
    draftSkills,
    knownSnapshots,
    ensureSet,
    select,
    refreshExecutions,
    pin,
    fixtureOf,
    kindLabelOf,
    labelOf,
    setDraft,
    applyPatchesToDraft,
    init,
    removeDraft,
  }
})
