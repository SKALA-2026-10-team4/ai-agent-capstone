// frontend/src/utils/snapshotDiff.js 의 버전 비교 규칙을 보호한다. 파일
// 상태(fileStatus), 경로 짝맞춤(pairFiles), frontmatter 필드 변경(fieldChanges)을
// 고정하고 예전 frontend/src/views/SnapshotCompareView.vue 안의 식과도 비교한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { FRONTMATTER_FIELDS, fieldChanges, fileStatus, pairFiles } from '../src/utils/snapshotDiff.js'
import { isSkillFile, toSkill } from '../src/utils/skillSet.js'
import { PUBLISHED_FILES, fixtureFiles } from '../src/mocks/skillFiles.js'

// 예전 frontend/src/views/SnapshotCompareView.vue 의 식을 그대로 복사한 비교 기준이다.
const FIELDS = ['inputs', 'outputs', 'depends_on', 'steps', 'description', 'owner', 'version']
function oldFieldChanges(l, r) {
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
// 같은 화면의 경로 짝맞춤과 파일 상태 식이다.
function oldPairs(aFiles, bFiles) {
  const left = Object.fromEntries(aFiles.map((f) => [f.path, f]))
  const right = Object.fromEntries(bFiles.map((f) => [f.path, f]))
  const paths = [...new Set([...Object.keys(left), ...Object.keys(right)])].sort()
  return paths.map((path) => {
    const l = left[path]
    const r = right[path]
    return { path, l, r, status: !l ? 'added' : !r ? 'removed' : l.hash === r.hash ? 'unchanged' : 'modified' }
  })
}

// 파일 묶음에서 SKILL.md 만 골라 toSkill 로 읽는다.
const skillsOf = (files) => Object.entries(files).filter(([p]) => isSkillFile(p)).map(([p, t]) => toSkill(p, t))

test('비교할 frontmatter 필드', () => {
  assert.deepStrictEqual(FRONTMATTER_FIELDS, FIELDS)
})

test('fileStatus: added · removed · unchanged · modified', () => {
  assert.equal(fileStatus(undefined, { hash: 'a' }), 'added')
  assert.equal(fileStatus({ hash: 'a' }, undefined), 'removed')
  assert.equal(fileStatus({ hash: 'a' }, { hash: 'a' }), 'unchanged')
  assert.equal(fileStatus({ hash: 'a' }, { hash: 'b' }), 'modified')
  assert.equal(fileStatus(undefined, undefined), 'added')
})

test('pairFiles: 두 목록의 경로 합집합을 정렬하고 양쪽 파일과 상태를 붙인다', () => {
  const a = [
    { path: 'skills/b/SKILL.md', hash: '1' },
    { path: 'skills/a/SKILL.md', hash: '2' },
    { path: 'gone.md', hash: '3' },
  ]
  const b = [
    { path: 'skills/a/SKILL.md', hash: '2' },
    { path: 'skills/b/SKILL.md', hash: '9' },
    { path: 'Zeta.md', hash: '4' },
  ]
  assert.deepStrictEqual(pairFiles(a, b), [
    { path: 'Zeta.md', left: undefined, right: b[2], status: 'added' },
    { path: 'gone.md', left: a[2], right: undefined, status: 'removed' },
    { path: 'skills/a/SKILL.md', left: a[1], right: b[0], status: 'unchanged' },
    { path: 'skills/b/SKILL.md', left: a[0], right: b[1], status: 'modified' },
  ])
  assert.deepStrictEqual(
    pairFiles(a, b).map(({ path, left, right, status }) => ({ path, l: left, r: right, status })),
    oldPairs(a, b),
  )
  assert.deepStrictEqual(pairFiles([], []), [])
})

test('fieldChanges: 목록은 추가·삭제 키로, 값은 전후로, 같은 값은 빼고, 한쪽이 없으면 빈 목록', () => {
  const before = skillsOf(PUBLISHED_FILES)
  const broken = skillsOf(fixtureFiles('broken-contract'))
  // 세트에서 skill_id 로 Skill 을 찾는다.
  const at = (skills, id) => skills.find((s) => s.skill_id === id)
  assert.deepStrictEqual(fieldChanges(at(before, 'inventory-check'), at(broken, 'inventory-check')), [
    { field: 'outputs', added: ['inventory.reservable_quantity'], removed: ['inventory.available', 'inventory.reserved'] },
  ])
  assert.deepStrictEqual(fieldChanges(at(before, 'payment'), at(broken, 'payment')), [])
  assert.deepStrictEqual(
    fieldChanges({ inputs: ['a'], description: 'x', version: 1, owner: 'o' }, { inputs: ['a'], description: 'y', version: 2, owner: 'o' }),
    [
      { field: 'description', before: 'x', after: 'y' },
      { field: 'version', before: 1, after: 2 },
    ],
  )
  assert.deepStrictEqual(fieldChanges({ steps: ['a'] }, { steps: null }), [{ field: 'steps', before: ['a'], after: null }])
  assert.deepStrictEqual(fieldChanges(undefined, { inputs: [] }), [])
  assert.deepStrictEqual(fieldChanges({ inputs: [] }, null), [])
})

test('예전 식과 같은 결과(fixture 전 조합)', () => {
  const sets = [PUBLISHED_FILES, fixtureFiles('baseline'), fixtureFiles('broken-contract'), fixtureFiles('repaired')].map(skillsOf)
  for (const a of sets) {
    for (const b of sets) {
      for (const l of a) {
        for (const r of b) assert.deepStrictEqual(fieldChanges(l, r), oldFieldChanges(l, r))
      }
    }
  }
})
