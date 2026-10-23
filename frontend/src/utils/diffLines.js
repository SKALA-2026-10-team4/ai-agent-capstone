// diff 를 줄 단위로 계산하는 순수 함수 모음이다. unified diff 문자열이나
// before 와 after 텍스트를 받아 줄 목록, 나란히 보기의 짝, 추가와 삭제 통계를
// 만든다. frontend/src/components/DiffView.vue 가 toDiffLines, pairRows,
// countChanges 를 쓰고, frontend/unit/diffLines.test.js 가 결과를 고정한다.
import { diffLines } from 'diff'

/**
 * unified diff(제안서 후보의 patch)를 줄 목록으로 바꾼다. '---' 와 '+++' 머리, 빈 줄은 건너뛴다.
 * hunk 머리(@@ 로 시작하는 줄)에서 원본과 새 파일의 시작 줄 번호를 읽어 'N번째 줄 근처' 라는
 * hunk 줄을 넣고, 이어지는 줄마다 해당 쪽 번호를 하나씩 올린다. 문맥 줄은 양쪽 번호를 모두 올린다.
 */
export function linesFromPatch(patch) {
  const out = []
  let o = 0
  let n = 0
  for (const l of patch.split('\n')) {
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

/**
 * before 와 after 텍스트(편집기 비교, 버전 비교)를 diff 패키지의 diffLines 로 비교해
 * 줄 목록으로 바꾼다. null 과 undefined 는 빈 글로 보고, 줄 번호는 1부터 센다.
 */
export function linesFromTexts(before, after) {
  const out = []
  let o = 1
  let n = 1
  for (const part of diffLines(before ?? '', after ?? '')) {
    const kind = part.added ? 'add' : part.removed ? 'del' : 'same'
    for (const text of part.value.replace(/\n$/, '').split('\n')) {
      if (kind === 'del') out.push({ kind, oldNo: o++, text })
      else if (kind === 'add') out.push({ kind, newNo: n++, text })
      else out.push({ kind, oldNo: o++, newNo: n++, text })
    }
  }
  return out
}

// DiffView 의 props 로 줄 목록을 만든다. patch 가 있으면 patch 를 쓰고, 없으면 before 와 after 를
// 비교한다. 줄은 { kind, oldNo, newNo, text } 이고 kind 는 same, del, add, hunk 가운데 하나다.
export const toDiffLines = ({ patch, before, after }) =>
  patch != null ? linesFromPatch(patch) : linesFromTexts(before, after)

/**
 * 나란히 보기의 행을 만든다. 연속된 삭제 묶음과 바로 뒤의 추가 묶음을 한 줄씩 짝짓고, 짝이 모자란
 * 쪽은 null 로 둔다. 같은 줄은 왼쪽과 오른쪽에 같은 줄을 두고, hunk 줄은 { hunk } 행이 된다.
 */
export function pairRows(lines) {
  const out = []
  for (let i = 0; i < lines.length; ) {
    const l = lines[i]
    if (l.kind === 'hunk' || l.kind === 'same') {
      out.push(l.kind === 'hunk' ? { hunk: l.text } : { left: l, right: l })
      i++
      continue
    }
    const dels = []
    const adds = []
    while (lines[i]?.kind === 'del') dels.push(lines[i++])
    while (lines[i]?.kind === 'add') adds.push(lines[i++])
    for (let k = 0; k < Math.max(dels.length, adds.length); k++) out.push({ left: dels[k] ?? null, right: adds[k] ?? null })
  }
  return out
}

// 추가된 줄과 삭제된 줄의 수를 센다. DiffView 머리의 더하기와 빼기 숫자로 보인다.
export const countChanges = (lines) => ({
  add: lines.filter((l) => l.kind === 'add').length,
  del: lines.filter((l) => l.kind === 'del').length,
})
