// frontend/src/utils/diffLines.js 의 줄 계산을 보호한다.
// frontend/src/components/DiffView.vue 에서 옮긴 순수 함수가 제안서 patch 와 before 와 after
// 텍스트에서 같은 줄 번호, 나란히 보기 짝, 추가와 삭제 수를 내는지 고정하고, 예전 컴포넌트 안의
// 식과 같은 결과인지도 함께 본다. patch 예시는 frontend/src/mocks/proposal-f1.json 에서 읽는다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { diffLines } from 'diff'
import { countChanges, linesFromPatch, linesFromTexts, markLines, pairRows, toDiffLines } from '../src/utils/diffLines.js'

// 예시 patch 를 담은 mock 제안서다.
const proposal = JSON.parse(readFileSync(new URL('../src/mocks/proposal-f1.json', import.meta.url), 'utf8'))
// 제안서 후보 번호와 Skill 이름으로 patch 원문을 꺼낸다.
const patchOf = (no, skill) =>
  proposal.proposal.candidates.find((c) => c.candidate_no === no).patches.find((p) => p.skill === skill).patch

// 예전 frontend/src/components/DiffView.vue 의 computed 본문을 props 대신 인자를 받도록만 바꿔
// 그대로 복사한 비교 기준이다. oldLines 는 줄 목록을, oldRows 는 나란히 보기 행을 만든다.
function oldLines(props) {
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
}
// 같은 컴포넌트의 나란히 보기 행 계산이다.
function oldRows(src) {
  const out = []
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
}

test('제안서 후보 patch: hunk 문구와 삭제·추가 줄 번호', () => {
  const lines = linesFromPatch(patchOf(1, 'inventory-check'))
  assert.deepStrictEqual(lines, [
    { kind: 'hunk', text: '5번째 줄 근처' },
    { kind: 'del', oldNo: 5, text: 'outputs: [inventory.available]' },
    { kind: 'add', newNo: 5, text: 'outputs: [inventory.available, inventory.reserved]' },
  ])
  assert.deepStrictEqual(countChanges(lines), { add: 1, del: 1 })
  assert.deepStrictEqual(pairRows(lines), [{ hunk: '5번째 줄 근처' }, { left: lines[1], right: lines[2] }])
})

test('patch: --- ·+++ 머리와 빈 줄은 건너뛰고 문맥 줄은 양쪽 번호를 올린다', () => {
  const patch = '--- a/x\n+++ b/x\n@@ -3,4 +3,3 @@\n keep\n-gone1\n-gone2\n+new1\n\n same\n@@ -10 +9 @@\n+tail\n'
  assert.deepStrictEqual(linesFromPatch(patch), [
    { kind: 'hunk', text: '3번째 줄 근처' },
    { kind: 'same', oldNo: 3, newNo: 3, text: 'keep' },
    { kind: 'del', oldNo: 4, text: 'gone1' },
    { kind: 'del', oldNo: 5, text: 'gone2' },
    { kind: 'add', newNo: 4, text: 'new1' },
    { kind: 'same', oldNo: 6, newNo: 5, text: 'same' },
    { kind: 'hunk', text: '10번째 줄 근처' },
    { kind: 'add', newNo: 9, text: 'tail' },
  ])
  const rows = pairRows(linesFromPatch(patch))
  assert.deepStrictEqual(
    rows.map((r) => (r.hunk ? r.hunk : [r.left?.text ?? null, r.right?.text ?? null])),
    ['3번째 줄 근처', ['keep', 'keep'], ['gone1', 'new1'], ['gone2', null], ['same', 'same'], '10번째 줄 근처', [null, 'tail']],
  )
})

test('before/after 텍스트: 같은 줄·삭제·추가 번호', () => {
  const lines = linesFromTexts('a\nb\nc\n', 'a\nB\nc\nd\n')
  assert.deepStrictEqual(lines, [
    { kind: 'same', oldNo: 1, newNo: 1, text: 'a' },
    { kind: 'del', oldNo: 2, text: 'b' },
    { kind: 'add', newNo: 2, text: 'B' },
    { kind: 'same', oldNo: 3, newNo: 3, text: 'c' },
    { kind: 'add', newNo: 4, text: 'd' },
  ])
  assert.deepStrictEqual(countChanges(lines), { add: 2, del: 1 })
  assert.deepStrictEqual(linesFromTexts(null, undefined), [])
})

test('toDiffLines: patch 가 있으면 patch, 없으면 before/after', () => {
  assert.deepStrictEqual(toDiffLines({ patch: '@@ -1 +1 @@\n-x\n+y', before: 'q', after: 'r' }), linesFromPatch('@@ -1 +1 @@\n-x\n+y'))
  assert.deepStrictEqual(toDiffLines({ patch: null, before: 'q', after: 'r' }), linesFromTexts('q', 'r'))
  assert.deepStrictEqual(toDiffLines({ patch: undefined, before: 'q', after: 'q' }), linesFromTexts('q', 'q'))
  assert.deepStrictEqual(toDiffLines({ patch: '' }), [])
})

test('예전 DiffView 식과 같은 결과', () => {
  const texts = ['', 'a', 'a\n', 'a\nb\nc', 'a\r\nb\r\n', 'x\n\n\ny\n', 'only\nnew\nlines\n']
  const cases = []
  for (const before of [...texts, null]) for (const after of [...texts, undefined]) cases.push({ patch: null, before, after })
  for (const c of proposal.proposal.candidates) for (const p of c.patches) cases.push({ patch: p.patch, before: null, after: null })
  cases.push({ patch: '@@ -1,2 +1,2 @@\n-a\n-b\n+c\n x\n+d\n-e\n', before: 'ignored', after: 'ignored' })
  for (const props of cases) {
    const lines = toDiffLines(props)
    assert.deepStrictEqual(lines, oldLines(props))
    assert.deepStrictEqual(pairRows(lines), oldRows(oldLines(props)))
  }
})

test('markLines 는 바뀐 줄, 끼어든 줄, 지워진 자리를 after 줄마다 표시한다', () => {
  const before = 'a\nb\nc\nd\n'
  const after = 'a\nB\nc\nnew\n'
  const { lines, stats } = markLines(before, after)
  assert.deepEqual(lines.map((l) => [l.text, l.mark, l.cut]), [
    ['a', null, false],
    ['B', 'change', false],
    ['c', null, false],
    ['new', 'change', false],
  ])
  assert.deepEqual(stats, { add: 0, change: 2, cut: 0 })
})

test('markLines 는 순수 추가를 add 로, 대신 들어온 줄 없는 삭제를 다음 줄의 cut 으로 본다', () => {
  const { lines, stats } = markLines('a\nx\nb\n', 'a\nb\nrules:\n  - when: "k == 1"\n')
  assert.deepEqual(lines.map((l) => [l.text, l.mark, l.cut]), [
    ['a', null, false],
    ['b', null, true],
    ['rules:', 'add', false],
    ['  - when: "k == 1"', 'add', false],
  ])
  assert.deepEqual(stats, { add: 2, change: 0, cut: 1 })
})

test('markLines 는 파일 끝에서 지운 줄을 마지막 줄의 cut 으로 남기고 같으면 표시가 없다', () => {
  assert.deepEqual(markLines('a\nb\n', 'a\n').lines, [{ text: 'a', mark: null, cut: true }])
  assert.deepEqual(markLines('a\n', 'a\n').stats, { add: 0, change: 0, cut: 0 })
})
