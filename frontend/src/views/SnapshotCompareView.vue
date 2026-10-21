<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { getSnapshotFile } from '@/api/orderAgent'
import { useWorkspaceStore } from '@/stores/workspace'
import { buildGraph } from '@/utils/flowGraph'
import { ENTRY_SKILL, flowSteps } from '@/utils/skillSet'
import { GitCompareArrows } from 'lucide-vue-next'
import DiffView from '@/components/DiffView.vue'
import PageHeader from '@/components/PageHeader.vue'

// 두 snapshot 의 파일 차이. 팀 시연 순서의 "snapshot 파일 차이 확인" 단계.
// service 의 diff_skill 은 MCP 전용이라 화면에서는 manifest hash 와 파일 본문으로 직접 비교한다.
const ws = useWorkspaceStore()
const before = ref('')
const after = ref('')
const files = ref([]) // [{ path, status, before, after }]
const busy = ref(false)
const error = ref(null)

const FIELDS = ['inputs', 'outputs', 'depends_on', 'steps', 'description', 'owner', 'version']

function summary(set) {
  if (!set) return null
  const steps = flowSteps(set.skills)
  return buildGraph(set.skills.filter((s) => s.skill_id !== ENTRY_SKILL), steps)
}

const graphs = ref([null, null])

async function compare() {
  if (!before.value || !after.value) return
  busy.value = true
  error.value = null
  try {
    const [a, b] = await Promise.all([ws.ensureSet(before.value), ws.ensureSet(after.value)])
    graphs.value = [summary(a), summary(b)]
    const left = Object.fromEntries(a.snapshot.files.map((f) => [f.path, f]))
    const right = Object.fromEntries(b.snapshot.files.map((f) => [f.path, f]))
    const paths = [...new Set([...Object.keys(left), ...Object.keys(right)])].sort()
    const text = async (id, set, path) =>
      set.skills.find((s) => s.path === path)?.text ?? (await getSnapshotFile(id, path)).content
    files.value = await Promise.all(
      paths.map(async (path) => {
        const l = left[path]
        const r = right[path]
        const status = !l ? 'added' : !r ? 'removed' : l.hash === r.hash ? 'unchanged' : 'modified'
        if (status === 'unchanged') return { path, status }
        return {
          path,
          status,
          before: l ? await text(before.value, a, path) : '',
          after: r ? await text(after.value, b, path) : '',
          changes: fieldChanges(
            a.skills.find((s) => s.path === path),
            b.skills.find((s) => s.path === path),
          ),
        }
      }),
    )
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

// frontmatter 필드별 변경. 목록 필드는 추가·삭제 키로 나눈다(service SkillFileDiff.FrontmatterChange 와 같은 뜻).
function fieldChanges(l, r) {
  if (!l || !r) return []
  return FIELDS.flatMap((field) => {
    const x = l[field]
    const y = r[field]
    if (JSON.stringify(x) === JSON.stringify(y)) return []
    if (Array.isArray(x) && Array.isArray(y)) {
      return [{ field, added: y.filter((k) => !x.includes(k)), removed: x.filter((k) => !y.includes(k)) }]
    }
    return [{ field, before: x, after: y }]
  })
}

async function prepareDemo() {
  busy.value = true
  try {
    before.value = (await ws.pin('baseline')).snapshot_id
    after.value = (await ws.pin('broken-contract')).snapshot_id
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

watch([before, after], compare)
onMounted(() => ws.refreshExecutions())

const changed = computed(() => files.value.filter((f) => f.status !== 'unchanged'))
const label = (id) => ws.knownSnapshots.find((s) => s.id === id)?.label ?? id.slice(0, 8)
</script>

<template>
  <PageHeader
    title="버전 비교"
    description="두 Skill 세트에서 어떤 파일이 어떻게 달라졌는지, 그 때문에 어디 연결이 끊겼는지 나란히 봐요."
    :icon="GitCompareArrows"
  />
  <div class="card toolbar">
    <label>
      기준
      <select v-model="before">
        <option value="" disabled>snapshot</option>
        <option v-for="s in ws.knownSnapshots" :key="s.id" :value="s.id">{{ s.label }}</option>
      </select>
    </label>
    <span>→</span>
    <label>
      비교
      <select v-model="after">
        <option value="" disabled>snapshot</option>
        <option v-for="s in ws.knownSnapshots" :key="s.id" :value="s.id">{{ s.label }}</option>
      </select>
    </label>
    <button :disabled="busy" @click="prepareDemo">정상본 ↔ 결함본 준비</button>
    <span v-if="error" class="error-text">{{ error }}</span>
  </div>

  <template v-if="files.length">
    <div class="sides">
      <section v-for="(g, i) in graphs" :key="i" class="card" :class="g?.missing.length ? 'bad' : 'good'">
        <h2>{{ i === 0 ? '기준' : '비교' }} · {{ label(i === 0 ? before : after) }}</h2>
        <p v-if="!g?.missing.length" class="ok-text">계약 위반 없음</p>
        <ul v-else>
          <li v-for="m in g.missing" :key="m.skill_id" class="error-text">
            <code>{{ m.skill_id }}</code> 입력 누락 {{ m.missing_inputs.join(', ') || '—' }}
            <template v-if="m.missing_dependencies.length"> · 선행 {{ m.missing_dependencies.join(', ') }}</template>
          </li>
        </ul>
      </section>
    </div>

    <p class="muted">
      파일 {{ files.length }}개 중 {{ changed.length }}개가 다르다. 오류가 난 Skill 과 바뀐 파일이 다를 수 있다
      (예: <code>payment</code> 가 실패했지만 바뀐 건 <code>inventory-check</code>).
    </p>
    <section v-for="f in changed" :key="f.path" class="card file">
      <div class="head">
        <code>{{ f.path }}</code>
        <span class="tag" :class="f.status">{{ f.status }}</span>
      </div>
      <ul v-if="f.changes?.length" class="changes">
        <li v-for="c in f.changes" :key="c.field">
          <strong>{{ c.field }}</strong>
          <template v-if="c.added">
            <span v-for="k in c.added" :key="`+${k}`" class="add"> +{{ k }}</span>
            <span v-for="k in c.removed" :key="`-${k}`" class="del"> −{{ k }}</span>
          </template>
          <template v-else> {{ c.before }} → {{ c.after }}</template>
        </li>
      </ul>
      <DiffView :before="f.before" :after="f.after" before-label="기준" after-label="비교" />
    </section>
  </template>
  <p v-else-if="busy" class="muted">비교 중…</p>
  <p v-else class="muted">두 snapshot 을 고르거나 "정상본 ↔ 결함본 준비" 를 누른다.</p>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  margin-bottom: 12px;
}

label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

select {
  max-width: 300px;
}

.sides {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.sides h2 {
  font-size: 14px;
}

.sides ul {
  margin: 0;
  padding-left: 18px;
}

.bad {
  border-left: 3px solid var(--err);
}

.good {
  border-left: 3px solid var(--ok);
}

.file {
  margin-top: 12px;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.tag {
  font-size: 12px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid currentColor;
}

.modified {
  color: var(--warn);
}

.added {
  color: var(--ok);
}

.removed {
  color: var(--err);
}

.changes {
  margin: 0 0 8px;
  padding-left: 18px;
}

.add {
  color: var(--ok);
  font-family: var(--mono);
}

.del {
  color: var(--err);
  font-family: var(--mono);
}

.ok-text {
  color: var(--ok);
}
</style>
