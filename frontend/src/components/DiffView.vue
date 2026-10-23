<script setup>
// 두 글의 차이를 줄 단위로 보여 주는 비교 상자다. 입력은 두 가지다.
// before 와 after 텍스트를 주면 두 글을 직접 비교하고, patch 에 unified diff 문자열을 주면
// 그 diff 를 그대로 펼친다. patch 가 null 이 아니면 patch 를 쓴다.
// frontend/src/views/SkillDraftView.vue 의 '원본과 비교' 탭과
// frontend/src/views/SnapshotCompareView.vue 의 파일별 비교는 텍스트를 넘기고,
// frontend/src/views/ProposalView.vue 는 고른 후보의 patch 를 넘긴다.
// 줄 계산은 frontend/src/utils/diffLines.js 의 toDiffLines, pairRows, countChanges 가 하고,
// 이 컴포넌트는 보기 방식만 고른다. 넓은 화면은 "나란히"(왼쪽이 변경 전, 오른쪽이 변경 후),
// 좁은 화면은 "한 줄로" 를 기본으로 보여 준다.
import { computed, ref } from 'vue'
import { Columns2, Rows2 } from 'lucide-vue-next'
import { countChanges, pairRows, toDiffLines } from '@/utils/diffLines'

// beforeLabel 과 afterLabel 은 두 칸의 머리글이다(예: 원본과 내 초안, 지금과 후보 적용 후).
const props = defineProps({
  before: { type: String, default: null },
  after: { type: String, default: null },
  patch: { type: String, default: null },
  beforeLabel: { type: String, default: '변경 전' },
  afterLabel: { type: String, default: '변경 후' },
})

// 기본 보기를 정하는 폭 기준 900px 는 frontend/src/App.vue 와
// frontend/src/components/AppSidebar.vue 의 media query 와 같은 값이다.
// 처음 그릴 때 한 번만 보므로 창 크기를 바꿔도 보기 방식은 그대로다.
// window 가 없는 환경에서는 넓은 화면으로 본다.
const wide = typeof window === 'undefined' || window.innerWidth > 900
// 'split' 은 나란히 보기, 'unified' 는 한 줄로 보기다. 위쪽 버튼으로 바꾼다.
const mode = ref(wide ? 'split' : 'unified')

// lines 는 한 줄로 보기에, rows 는 삭제와 추가를 한 줄씩 짝지은 나란히 보기에,
// stats 는 위쪽의 추가·삭제 줄 수에 쓴다.
const lines = computed(() => toDiffLines(props))
const rows = computed(() => pairRows(lines.value))
const stats = computed(() => countChanges(lines.value))
</script>

<template>
  <div class="diff">
    <div class="bar">
      <span class="stat add">+{{ stats.add }}</span>
      <span class="stat del">−{{ stats.del }}</span>
      <span v-if="!stats.add && !stats.del" class="muted small">바뀐 줄이 없다</span>
      <span class="spacer" />
      <div class="toggle" role="group" aria-label="보기 방식">
        <button class="ghost sm" :class="{ on: mode === 'split' }" @click="mode = 'split'"><Columns2 :size="14" /> 나란히</button>
        <button class="ghost sm" :class="{ on: mode === 'unified' }" @click="mode = 'unified'"><Rows2 :size="14" /> 한 줄로</button>
      </div>
    </div>

    <div v-if="mode === 'split'" class="body split">
      <div class="head">{{ beforeLabel }}</div>
      <div class="head">{{ afterLabel }}</div>
      <template v-for="(r, i) in rows" :key="i">
        <div v-if="r.hunk" class="hunk">{{ r.hunk }}</div>
        <template v-else>
          <div class="cell" :class="r.left ? (r.left.kind === 'del' ? 'del' : '') : 'blank'">
            <span class="no">{{ r.left?.oldNo ?? '' }}</span><span class="text">{{ r.left?.text ?? '' }}</span>
          </div>
          <div class="cell" :class="r.right ? (r.right.kind === 'add' ? 'add' : '') : 'blank'">
            <span class="no">{{ r.right?.newNo ?? '' }}</span><span class="text">{{ r.right?.text ?? '' }}</span>
          </div>
        </template>
      </template>
    </div>

    <div v-else class="body unified">
      <template v-for="(l, i) in lines" :key="i">
        <div v-if="l.kind === 'hunk'" class="hunk">{{ l.text }}</div>
        <div v-else class="cell" :class="l.kind">
          <span class="no">{{ l.oldNo ?? '' }}</span>
          <span class="no">{{ l.newNo ?? '' }}</span>
          <span class="sign">{{ l.kind === 'add' ? '+' : l.kind === 'del' ? '−' : '' }}</span>
          <span class="text">{{ l.text }}</span>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.diff {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--surface);
}

.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
}

.stat {
  font-family: var(--mono);
  font-size: var(--fs-xs);
  font-weight: 700;
}

.stat.add {
  color: var(--ok);
}

.stat.del {
  color: var(--err);
}

.toggle {
  display: flex;
  gap: 2px;
}

.toggle .on {
  background: var(--surface);
  color: var(--accent);
  box-shadow: var(--shadow-sm);
}

.body {
  font-family: var(--mono);
  font-size: var(--fs-sm);
  line-height: 1.6;
  overflow-x: auto;
}

.split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

.head {
  padding: 4px 12px;
  font-family: var(--font);
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--muted);
  border-bottom: 1px solid var(--border);
}

.head + .head,
.split .cell:nth-child(even) {
  border-left: 1px solid var(--border);
}

.hunk {
  grid-column: 1 / -1;
  padding: 2px 12px;
  background: var(--accent-soft);
  color: var(--accent);
  font-family: var(--font);
  font-size: var(--fs-xs);
}

.cell {
  display: flex;
  min-width: 0;
}

.cell.add {
  background: var(--add-bg);
}

.cell.del {
  background: var(--del-bg);
}

.cell.blank {
  background: repeating-linear-gradient(135deg, transparent 0 6px, var(--surface-2) 6px 7px);
}

.no {
  flex: none;
  width: 40px;
  padding-right: 8px;
  text-align: right;
  color: var(--muted);
  user-select: none;
  font-size: var(--fs-xs);
  line-height: 1.85;
}

.sign {
  flex: none;
  width: 16px;
  color: var(--muted);
  user-select: none;
}

.add .sign {
  color: var(--ok);
}

.del .sign {
  color: var(--err);
}

.text {
  flex: 1;
  padding-right: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
