import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { applyPatch } from 'diff'
import { USE_MOCK } from '@/api/client'
import { listExecutions, pinFixture } from '@/api/orderAgent'
import { loadSkillSet, skillPath, toSkill } from '@/utils/skillSet'

// 화면들이 함께 보는 작업 상태: 지금 보고 있는 snapshot, 그 Skill 세트, 브라우저에만 있는 초안.
// 초안은 서버에 저장하지 않는다. TODO(C-1 연동): 초안을 snapshot 으로 저장하는 API 가 생기면 여기서 게시한다.
// mock 과 실서버는 snapshot id 가 서로 없으므로 따로 저장한다.
// snapshot metadata(fixture·source) → 담당자가 알아보는 이름
const SET_LABEL = {
  baseline: '정상본',
  'broken-contract': '결함본',
  repaired: '수정본',
  'published-bundle': '게시본',
}

const STORAGE_KEY = USE_MOCK ? 'skillops.workspace.mock' : 'skillops.workspace'

function restore() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY)) ?? {}
  } catch {
    return {}
  }
}

export const useWorkspaceStore = defineStore('workspace', () => {
  const saved = restore()
  const snapshotId = ref(saved.snapshotId ?? null)
  const pinned = ref(saved.pinned ?? []) // [{ snapshot_id, fixture, created_at }]
  const drafts = ref(saved.drafts ?? {}) // { [snapshotId]: { [path]: text } }
  const executions = ref([])

  const sets = ref({}) // snapshotId → { snapshot, skills, others }
  const loading = ref(false)
  const error = ref(null)

  watch(
    [snapshotId, pinned, drafts],
    () => {
      try {
        const state = { snapshotId: snapshotId.value, pinned: pinned.value, drafts: drafts.value }
        localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
      } catch {
        // 저장소를 못 쓰는 환경이면 새로고침 때 초안이 사라질 뿐이다.
      }
    },
    { deep: true },
  )

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

  async function select(id) {
    snapshotId.value = id
    await ensureSet(id)
  }

  async function refreshExecutions() {
    try {
      executions.value = (await listExecutions()).items
    } catch (e) {
      error.value = e.message
    }
  }

  async function pin(fixture) {
    const m = await pinFixture(fixture)
    const entry = { snapshot_id: m.snapshot_id, fixture, created_at: m.created_at }
    pinned.value = [entry, ...pinned.value].slice(0, 12)
    await select(m.snapshot_id)
    return m
  }

  // 선택지: 고정한 fixture + 최근 실행이 쓴 snapshot (snapshot 목록 API 는 없다)
  const knownSnapshots = computed(() => {
    const seen = new Map()
    for (const p of pinned.value) seen.set(p.snapshot_id, labelOf(p.snapshot_id))
    for (const x of executions.value) {
      if (x.snapshot_id && !seen.has(x.snapshot_id)) {
        const known = labelOf(x.snapshot_id)
        // 내용을 아직 안 읽은 세트는 그 세트로 돌린 실행 결과로 구분한다.
        const label = known.startsWith('Skill 세트')
          ? `${x.final_status ?? x.completion} 실행에 쓴 세트 · ${x.snapshot_id.slice(0, 8)}`
          : known
        seen.set(x.snapshot_id, label)
      }
    }
    return [...seen].map(([id, label]) => ({ id, label }))
  })

  const fixtureOf = (id) => pinned.value.find((p) => p.snapshot_id === id)?.fixture ?? null

  // "결함본 · f47e7062" 처럼. 불러온 적 있는 세트는 metadata 로, 아니면 고정 기록으로 알아낸다.
  function labelOf(id) {
    if (!id) return '선택 안 됨'
    const meta = sets.value[id]?.snapshot.metadata
    const key = meta?.fixture ?? meta?.source ?? fixtureOf(id)
    return `${SET_LABEL[key] ?? key ?? 'Skill 세트'} · ${id.slice(0, 8)}`
  }

  const current = computed(() => sets.value[snapshotId.value] ?? null)
  const currentDrafts = computed(() => drafts.value[snapshotId.value] ?? {})

  // snapshot 원본에 초안을 덮어쓴 Skill 목록. 초안으로 새 파일을 추가할 수도 있다.
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

  function setDraft(path, text) {
    const id = snapshotId.value
    const original = current.value?.skills.find((s) => s.path === path)?.text
    const next = { ...drafts.value[id] }
    if (text === original) delete next[path]
    else next[path] = text
    drafts.value = { ...drafts.value, [id]: next }
  }

  // 개선안의 unified diff 들을 현재 초안(없으면 원본)에 적용한다. 하나라도 기준 내용이 다르면
  // 아무것도 바꾸지 않고 실패한 skill 목록을 돌려준다(반쯤 적용된 초안을 만들지 않는다).
  function applyPatchesToDraft(patches) {
    const results = patches.map((p) => {
      const path = skillPath(p.skill)
      const base = draftSkills.value.find((s) => s.path === path)?.text
      const next = base == null ? false : applyPatch(base, p.diff, { fuzzFactor: 1 })
      return { skill: p.skill, path, next }
    })
    const failed = results.filter((r) => r.next === false).map((r) => r.skill)
    if (failed.length) return { ok: false, failed }
    for (const r of results) setDraft(r.path, r.next)
    return { ok: true, failed: [] }
  }

  // 화면을 처음 열 때: 저장해 둔 snapshot 을 다시 읽고, 없어졌으면 최근 것으로 바꾼다.
  async function init() {
    await refreshExecutions()
    if (snapshotId.value && (await ensureSet(snapshotId.value))) return
    if (knownSnapshots.value.length) {
      error.value = null
      await select(knownSnapshots.value[0].id)
    }
  }

  function removeDraft(path) {
    const id = snapshotId.value
    const next = { ...drafts.value[id] }
    delete next[path]
    drafts.value = { ...drafts.value, [id]: next }
  }

  function clearDrafts() {
    const next = { ...drafts.value }
    delete next[snapshotId.value]
    drafts.value = next
  }

  return {
    snapshotId,
    pinned,
    executions,
    loading,
    error,
    current,
    currentDrafts,
    draftSkills,
    knownSnapshots,
    ensureSet,
    select,
    refreshExecutions,
    pin,
    fixtureOf,
    labelOf,
    setDraft,
    applyPatchesToDraft,
    init,
    removeDraft,
    clearDrafts,
  }
})
