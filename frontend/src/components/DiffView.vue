<script setup>
import { computed, ref } from 'vue'
import { diffLines } from 'diff'
import { Columns2, Rows2 } from 'lucide-vue-next'

// 두 가지 입력을 받는다: before/after 텍스트(편집기·버전 비교) 또는 unified diff 문자열(개선안 patch).
// 넓은 화면은 "나란히"(왼쪽 = 전, 오른쪽 = 후), 좁은 화면은 "한 줄로" 를 기본으로 보여 준다.
const props = defineProps({
  before: { type: String, default: null },
  after: { type: String, default: null },
  patch: { type: String, default: null },
  beforeLabel: { type: String, default: '변경 전' },
  afterLabel: { type: String, default: '변경 후' },
})

const wide = typeof window === 'undefined' || window.innerWidth > 900
const mode = ref(wide ? 'split' : 'unified')

// → [{ kind: 'same'|'del'|'add'|'hunk', oldNo, newNo, text }]
const lines = computed(() => {
  const out = []
  if (props.patch != null) {
    let o = 0
    let n = 0
    for (const l of props.patch.split('\n')) {
      if (!l || l.startsWith('---') || l.startsWith('+++')) continue
      const h = l.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
      if (h) {
        o = Number(h[1])
        n = Number(h[2])
        out.push({ kind: 'hunk', text: `${o}번째 줄 근처` })
      } else if (l[0] === '-') out.push({ kind: 'del', oldNo: o++, text: l.slice(1) })
      else if (l[0] === '+') out.push({ kind: 'add', newNo: n++, text: l.slice(1) })
      else out.push({ kind: 'same', oldNo: o++, newNo: n++, text: l.slice(1) })
    }
    return out
  }
  let o = 1
  let n = 1
  for (const part of diffLines(props.before ?? '', props.after ?? '')) {
    const kind = part.added ? 'add' : part.removed ? 'del' : 'same'
    for (const text of part.value.replace(/\n$/, '').split('\n')) {
      if (kind === 'del') out.push({ kind, oldNo: o++, text })
      else if (kind === 'add') out.push({ kind, newNo: n++, text })
      else out.push({ kind, oldNo: o++, newNo: n++, text })
    }
  }
  return out
})

// 나란히 보기: 연속된 삭제·추가 묶음을 한 줄씩 짝지운다.
const rows = computed(() => {
  const out = []
  const src = lines.value
  for (let i = 0; i < src.length; ) {
    const l = src[i]
    if (l.kind === 'hunk' || l.kind === 'same') {
      out.push(l.kind === 'hunk' ? { hunk: l.text } : { left: l, right: l })
      i++
      continue
    }
    const dels = []
    const adds = []
    while (src[i]?.kind === 'del') dels.push(src[i++])
    while (src[i]?.kind === 'add') adds.push(src[i++])
    for (let k = 0; k < Math.max(dels.length, adds.length); k++) out.push({ left: dels[k] ?? null, right: adds[k] ?? null })
  }
  return out
})

const stats = computed(() => ({
  add: lines.value.filter((l) => l.kind === 'add').length,
  del: lines.value.filter((l) => l.kind === 'del').length,
}))
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
