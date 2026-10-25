<script setup>
// 버전 비교 화면이다. frontend/src/router/index.js 가 /compare 경로에 연결하고,
// 실행 상세 화면(frontend/src/views/ExecutionView.vue)의 실행 오류 상자도
// 'snapshot 비교' 링크로 이 화면을 연다.
// 두 snapshot(기준, 비교)의 파일 차이와 양쪽의 계약 위반을 나란히 보여 준다.
// order-agent/docs/team-scenarios.md 의 추천 팀 시연 순서 가운데
// "snapshot 파일 차이 확인" 단계에 쓴다.
// service 의 diff_skill(service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java)은
// MCP 전용 도구라서 화면에서는 manifest 의 파일 hash 와 파일 본문으로 직접 비교한다.
// 세트는 frontend/src/stores/workspace.js 의 ensureSet 으로 읽고, Skill 이 아닌 파일 본문은
// frontend/src/api/orderAgent.js 의 getSnapshotFile(GET /api/snapshots/{id}/files)로 읽는다.
// 파일 상태와 frontmatter 필드 변경 규칙은 frontend/src/utils/snapshotDiff.js 의 pairFiles,
// fieldChanges, 비교 필드 목록 FRONTMATTER_FIELDS 에 있고, 계약 위반 계산은
// frontend/src/utils/flowGraph.js 의 flowGraphOf 가 한다. 줄 단위 비교는
// frontend/src/components/DiffView.vue 가 그린다.
import { computed, onMounted, ref, watch } from 'vue'
import { getSnapshotFile } from '@/api/orderAgent'
import { useWorkspaceStore } from '@/stores/workspace'
import { flowGraphOf } from '@/utils/flowGraph'
import { fieldChanges, pairFiles } from '@/utils/snapshotDiff'
import { ArrowRight, GitCompareArrows } from 'lucide-vue-next'
import DiffView from '@/components/DiffView.vue'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'

const ws = useWorkspaceStore()
// 기준과 비교로 고른 snapshot id 다. 둘 다 있어야 비교한다.
const before = ref('')
const after = ref('')
// 비교한 파일 목록이다. [{ path, status, before, after, changes }] 모양이며,
// unchanged 파일은 본문을 읽지 않아 path 와 status 만 있다.
const files = ref([])
const busy = ref(false)
const error = ref(null)

// 기준과 비교 세트의 계약 그래프다. 세트를 읽지 못한 쪽은 null 이다.
const graphs = ref([null, null])

/**
 * 바뀐 파일 하나의 원문을 돌려준다. compare 가 바뀐 파일마다 그 파일이 있는 쪽에서 부른다.
 * Skill 파일이면 이미 불러온 세트의 글을 쓰고, 아니면(resources 같은 관련 파일) 파일 API 로 읽는다.
 */
async function loadFileText(id, set, path) {
  return set.skills.find((s) => s.path === path)?.text ?? (await getSnapshotFile(id, path)).content
}

/**
 * 두 snapshot 을 비교한다. 두 세트를 함께 불러 계약 그래프를 계산하고, manifest 의 파일 목록을
 * 경로별로 짝지어 상태(added, removed, modified, unchanged)를 정한다. 바뀐 파일만 양쪽 원문을 읽고,
 * 양쪽 세트에 모두 있는 Skill 파일이면 frontmatter 필드별 변경(changes)도 붙인다.
 * 한쪽에만 있는 파일은 없는 쪽을 빈 글로 둔다.
 * 아래 watch 가 before 나 after 가 바뀔 때마다 부른다.
 */
async function compare() {
  if (!before.value || !after.value) return
  busy.value = true
  error.value = null
  try {
    const [a, b] = await Promise.all([ws.ensureSet(before.value), ws.ensureSet(after.value)])
    graphs.value = [a ? flowGraphOf(a.skills) : null, b ? flowGraphOf(b.skills) : null]
    files.value = await Promise.all(
      pairFiles(a.snapshot.files, b.snapshot.files).map(async ({ path, left, right, status }) => {
        if (status === 'unchanged') return { path, status }
        return {
          path,
          status,
          before: left ? await loadFileText(before.value, a, path) : '',
          after: right ? await loadFileText(after.value, b, path) : '',
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

/**
 * 시연용 비교 쌍을 준비한다. 위쪽 도구 막대의 준비 버튼이 부른다. 정상본(baseline)과
 * 결함본(broken-contract) fixture 를 차례로 새 snapshot 으로 고정해(workspace store 의 pin)
 * 기준과 비교에 넣으면 watch 가 비교를 시작한다. 데모 쌍을 바꾸려면 여기의 fixture 이름을 고친다.
 */
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

// 기준이나 비교가 바뀌면 다시 비교한다.
watch([before, after], compare)
// 선택지(workspace store 의 knownSnapshots)에 최근 실행이 쓴 snapshot 도 보이도록
// 실행 목록을 읽는다.
onMounted(() => ws.refreshExecutions())

// 본문 비교를 보여 줄 바뀐 파일들이다.
const changed = computed(() => files.value.filter((f) => f.status !== 'unchanged'))
/**
 * 선택지에 있는 snapshot 이면 그 이름을, 없으면 workspace store 의 labelOf 이름(종류와 시각)을 돌려준다.
 * id 는 쓰지 않는다. 기준과 비교의 계약 위반 칸 제목이 쓴다.
 */
const label = (id) => ws.knownSnapshots.find((s) => s.id === id)?.label ?? ws.labelOf(id)
</script>

<template>
  <PageHeader
    title="버전 비교"
    description="두 Skill 세트에서 어떤 파일이 어떻게 달라졌는지, 그 때문에 어디 연결이 끊겼는지 나란히 봐요."
  />
  <div class="card toolbar">
    <label>
      기준
      <select v-model="before">
        <option value="" disabled>세트 고르기</option>
        <option v-for="s in ws.knownSnapshots" :key="s.id" :value="s.id">{{ s.label }}</option>
      </select>
    </label>
    <ArrowRight class="arrow" :size="16" aria-hidden="true" />
    <label>
      비교
      <select v-model="after">
        <option value="" disabled>세트 고르기</option>
        <option v-for="s in ws.knownSnapshots" :key="s.id" :value="s.id">{{ s.label }}</option>
      </select>
    </label>
    <button class="sm" :disabled="busy" @click="prepareDemo">정상본 ↔ 결함본 준비</button>
    <span v-if="error" class="error-text">{{ error }}</span>
  </div>

  <template v-if="files.length">
    <div class="sides">
      <section v-for="(g, i) in graphs" :key="i" class="card" :class="g?.missing.length ? 'bad' : 'good'">
        <p class="side-tag">{{ i === 0 ? '기준' : '비교' }}</p>
        <h2>{{ label(i === 0 ? before : after) }}</h2>
        <p v-if="!g?.missing.length" class="ok-text">계약 위반 없음</p>
        <ul v-else>
          <li v-for="m in g.missing" :key="m.skill_id" class="error-text">
            <code>{{ m.skill_id }}</code> 입력 누락 {{ m.missing_inputs.join(', ') || '—' }}
            <template v-if="m.missing_dependencies.length"> · 선행 {{ m.missing_dependencies.join(', ') }}</template>
          </li>
        </ul>
      </section>
    </div>

    <p class="muted summary">
      파일 {{ files.length }}개 중 {{ changed.length }}개가 달라요. 오류가 난 Skill 과 바뀐 파일이 다를 수 있어요
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
  <EmptyState v-else title="비교할 두 세트를 골라 주세요" :icon="GitCompareArrows">
    위에서 기준과 비교 세트를 고르거나 '정상본 ↔ 결함본 준비' 를 누르면 파일 차이와 끊긴 연결을 나란히 보여 줘요.
  </EmptyState>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  margin-bottom: 16px;
}

label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-2);
}

select {
  max-width: 320px;
  font-size: var(--fs-sm);
}

.arrow {
  color: var(--faint);
}

.sides {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.side-tag {
  margin: 0 0 4px;
  font-family: var(--mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--faint);
}

.sides h2 {
  font-size: 16px;
}

.sides ul {
  margin: 0;
  padding-left: 18px;
}

.bad {
  border-color: rgb(255 149 132 / 35%);
  box-shadow: inset 3px 0 0 var(--err);
}

.good {
  border-color: rgb(142 227 181 / 30%);
  box-shadow: inset 3px 0 0 var(--ok);
}

.summary {
  margin: 4px 0 0;
}

.file {
  margin-top: 14px;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.tag {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 9px;
  border-radius: var(--radius-pill);
  background: rgb(255 255 255 / 6%);
}

.modified {
  color: var(--warn);
  background: var(--warn-soft);
}

.added {
  color: var(--ok);
  background: var(--ok-soft);
}

.removed {
  color: var(--err);
  background: var(--err-soft);
}

.changes {
  margin: 0 0 10px;
  padding-left: 18px;
  font-size: var(--fs-sm);
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

@media (max-width: 760px) {
  label {
    flex-direction: column;
    align-items: stretch;
    width: 100%;
  }

  select {
    max-width: none;
  }

  .arrow {
    display: none;
  }
}
</style>
