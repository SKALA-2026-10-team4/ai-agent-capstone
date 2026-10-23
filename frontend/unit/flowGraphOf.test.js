// frontend/src/utils/flowGraph.js 의 flowGraphOf 를 보호한다.
// frontend/src/views/SkillDraftView.vue 와 frontend/src/views/SnapshotCompareView.vue 가
// 예전에 직접 쓰던 식과 같은 계약 그래프를 내는지 확인한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildGraph, flowGraphOf, problemCount } from '../src/utils/flowGraph.js'
import { ENTRY_SKILL, flowSteps, isSkillFile, skillPath, toSkill } from '../src/utils/skillSet.js'
import { PUBLISHED_FILES, fixtureFiles } from '../src/mocks/skillFiles.js'

// 파일 묶음에서 SKILL.md 만 골라 toSkill 로 읽는다.
const skillsOf = (files) =>
  Object.entries(files)
    .filter(([path]) => isSkillFile(path))
    .map(([path, text]) => toSkill(path, text))

// 화면에 있던 식이다. 진입 Skill 을 빼고 그 steps 순서로 buildGraph 를 부른다.
const viewGraph = (skills) => buildGraph(skills.filter((s) => s.skill_id !== ENTRY_SKILL), flowSteps(skills))

test('fixture 세트마다 화면 식과 같은 그래프', () => {
  for (const files of [PUBLISHED_FILES, fixtureFiles('baseline'), fixtureFiles('broken-contract'), fixtureFiles('repaired')]) {
    const skills = skillsOf(files)
    assert.deepStrictEqual(flowGraphOf(skills), viewGraph(skills))
  }
  assert.equal(problemCount(flowGraphOf(skillsOf(fixtureFiles('broken-contract')))), 2)
})

test('진입 Skill 이 없거나 steps 에 이상한 이름이 있어도 화면 식과 같다', () => {
  const skills = skillsOf(PUBLISHED_FILES)
  assert.deepStrictEqual(flowGraphOf([]), viewGraph([]))
  assert.deepStrictEqual(flowGraphOf(skills.filter((s) => s.skill_id !== ENTRY_SKILL)), viewGraph(skills.filter((s) => s.skill_id !== ENTRY_SKILL)))
  const flowPath = skillPath(ENTRY_SKILL)
  const looped = skillsOf({
    ...PUBLISHED_FILES,
    [flowPath]: PUBLISHED_FILES[flowPath].replace('steps: [order-intake,', 'steps: [order-flow, ghost, order-intake,'),
  })
  assert.deepStrictEqual(flowGraphOf(looped), viewGraph(looped))
  assert.deepStrictEqual(
    flowGraphOf(looped).nodes.slice(0, 2).map((n) => n.parse_error),
    ['UNKNOWN_SKILL', 'UNKNOWN_SKILL'],
  )
  assert.equal(flowGraphOf(looped).entry_skill_id, ENTRY_SKILL)
})
