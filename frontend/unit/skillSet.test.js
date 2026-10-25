// frontend/src/utils/skillSet.js 의 Skill 세트 규칙을 보호한다. 서버 로더
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 와
// 같은 초안 검사(toSkill), 진입 Skill, 경로, 새 Skill 이름 규칙과 틀의 지금 동작을
// 고정한다. 초안 검사 문구는 frontend/src/views/SkillDraftView.vue 에 그대로 보인다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ENTRY_SKILL,
  flowSteps,
  isSkillFile,
  newSkillTemplate,
  skillNameProblem,
  skillPath,
  toSkill,
} from '../src/utils/skillSet.js'
import { PUBLISHED_FILES } from '../src/mocks/skillFiles.js'

// 예시로 쓰는 단계 Skill 경로다. md 는 frontmatter 줄 목록과 본문으로 SKILL.md 원문을
// 만들고, VALID 는 형식 검사를 모두 통과하는 단계 Skill frontmatter 다.
const PAYMENT = 'skills/payment/SKILL.md'
const md = (lines, body = '# 결제\n\n본문') => `---\n${lines.join('\n')}\n---\n${body}\n`
const VALID = [
  'name: payment',
  'description: 결제한다',
  'inputs: [order.id]',
  'outputs: [payment.id]',
  'depends_on: []',
  'owner: 주문운영팀',
  'version: 1',
]
// 검사 문구를 꺼내는 도구와, VALID 에서 한 줄을 빼거나 바꾸는 도구다.
const problemOf = (text, path = PAYMENT) => toSkill(path, text).error_message
const without = (key) => VALID.filter((l) => !l.startsWith(`${key}:`))
const replace = (key, line) => VALID.map((l) => (l.startsWith(`${key}:`) ? line : l))

test('진입 Skill 과 Skill 파일 경로', () => {
  assert.equal(ENTRY_SKILL, 'order-flow')
  assert.equal(skillPath('payment'), PAYMENT)
  assert.equal(isSkillFile(PAYMENT), true)
  assert.equal(isSkillFile('skills/a/SKILL.md'), true)
  assert.equal(isSkillFile('skills/shipping-check2/SKILL.md'), true)
  assert.equal(isSkillFile('skills/inventory-check/resources/policy.md'), false)
  assert.equal(isSkillFile('skills/Payment/SKILL.md'), false)
  assert.equal(isSkillFile('skills/1abc/SKILL.md'), false)
  assert.equal(isSkillFile('x/skills/a/SKILL.md'), false)
  assert.equal(isSkillFile('skills/a/skill.md'), false)
})

test('toSkill: 게시본 Skill 은 오류 없이 frontmatter 필드를 펼친다', () => {
  const text = PUBLISHED_FILES[PAYMENT]
  assert.deepStrictEqual(toSkill(PAYMENT, text, 'h1'), {
    skill_id: 'payment',
    path: PAYMENT,
    file_hash: 'h1',
    text,
    inputs: ['order.id', 'order.total', 'order.payment_method', 'inventory.reserved'],
    outputs: ['payment.id', 'payment.status'],
    depends_on: ['inventory-check'],
    steps: [],
    invalid_lists: [],
    name: 'payment',
    description: '예약된 주문 금액을 결제한다',
    version: 1,
    owner: '주문운영팀',
    body: text.slice(text.indexOf('\n---\n') + 5).trim(),
    raw: {
      name: 'payment',
      description: '예약된 주문 금액을 결제한다',
      inputs: ['order.id', 'order.total', 'order.payment_method', 'inventory.reserved'],
      outputs: ['payment.id', 'payment.status'],
      depends_on: ['inventory-check'],
      owner: '주문운영팀',
      version: 1,
    },
    error: null,
    error_message: null,
  })
  assert.equal(toSkill(PAYMENT, text).file_hash, null)
})

test('toSkill: 흐름 Skill(steps) 은 inputs·outputs·depends_on 이 없어도 된다', () => {
  const flow = toSkill('skills/order-flow/SKILL.md', PUBLISHED_FILES['skills/order-flow/SKILL.md'])
  assert.equal(flow.error, null)
  assert.deepStrictEqual(flow.steps, ['order-intake', 'inventory-check', 'payment', 'shipping-reserve', 'notify'])
  assert.deepStrictEqual([flow.inputs, flow.outputs, flow.depends_on], [[], [], []])
})

test('toSkill: 형식 검사는 앞에서부터 하나만 걸리고 문구가 정해져 있다', () => {
  assert.equal(problemOf(md(VALID)), null)
  assert.equal(problemOf('name: payment'), '맨 위 --- 구분선과 YAML 형식을 확인해 주세요.')
  assert.equal(problemOf(md(['- a'])), '맨 위 --- 구분선과 YAML 형식을 확인해 주세요.')
  assert.equal(problemOf(md(replace('name', 'name: pay'))), 'name 이 폴더 이름(payment)과 달라요.')
  assert.equal(problemOf(md(VALID), 'skills/Payment/SKILL.md'), 'name 이 폴더 이름(null)과 달라요.')
  const textRule = (key) => `${key} 은 비어 있지 않은 글이어야 해요.`
  assert.equal(problemOf(md(without('description'))), textRule('description'))
  assert.equal(problemOf(md(replace('description', "description: '  '"))), textRule('description'))
  // 글이 아닌 값도 같은 문구다. 예전에는 trim 을 불러 TypeError 로 화면이 깨졌다.
  assert.equal(problemOf(md(replace('description', 'description: 123'))), textRule('description'))
  assert.equal(problemOf(md(replace('description', 'description: [a]'))), textRule('description'))
  assert.equal(problemOf(md(without('owner'))), 'owner 는 비어 있지 않은 글이어야 해요.')
  assert.equal(problemOf(md(replace('owner', 'owner: 123'))), 'owner 는 비어 있지 않은 글이어야 해요.')
  const versionRule = 'version 은 1 이상 2147483647 이하의 정수여야 해요.'
  assert.equal(problemOf(md(without('version'))), versionRule)
  assert.equal(problemOf(md(replace('version', 'version: 0'))), versionRule)
  assert.equal(problemOf(md(replace('version', 'version: 1.5'))), versionRule)
  assert.equal(problemOf(md(replace('version', 'version: "2"'))), versionRule)
  // 서버는 Java Integer 만 받으므로 그 상한을 넘으면 거절한다.
  assert.equal(problemOf(md(replace('version', 'version: 2147483648'))), versionRule)
  assert.equal(problemOf(md(replace('version', 'version: 2147483647'))), null)
  assert.equal(problemOf(md(VALID, '')), '본문이 비어 있어요.')
  // 적지 않은 필수 목록만 문구에 담는다. 누락과 null 을 함께 본다.
  assert.equal(problemOf(md(without('depends_on'))), '단계 Skill 은 depends_on 을 적어야 해요(없으면 []).')
  assert.equal(problemOf(md(replace('inputs', 'inputs:'))), '단계 Skill 은 inputs 을 적어야 해요(없으면 []).')
  assert.equal(problemOf(md(replace('inputs', 'inputs: ~'))), '단계 Skill 은 inputs 을 적어야 해요(없으면 []).')
  assert.equal(
    problemOf(md(without('inputs').filter((l) => !l.startsWith('outputs:')))),
    '단계 Skill 은 inputs · outputs 을 적어야 해요(없으면 []).',
  )
  // 여러 규칙을 어겨도 첫 문구 하나만 나온다.
  assert.equal(problemOf(md(['name: other', 'version: 0'], '')), 'name 이 폴더 이름(payment)과 달라요.')
})

test('toSkill: 목록 칸은 비어 있지 않은 글의 목록만 받는다(SkillLoader.strings 와 같은 규칙)', () => {
  const listRule = (key) => `${key} 은 비어 있지 않은 글의 목록이어야 해요(없으면 []).`
  // 목록이 아닌 값이다. 예전에는 조용히 빈 목록으로 읽어 서버만 거절했다.
  assert.equal(problemOf(md(replace('inputs', 'inputs: order.id'))), listRule('inputs'))
  assert.equal(problemOf(md(replace('inputs', 'inputs: {a: 1}'))), listRule('inputs'))
  // 글이 아닌 항목이다. 예전에는 String() 으로 바꿔 '1' 이나 'null' 로 읽었다.
  assert.equal(problemOf(md(replace('inputs', 'inputs: [1]'))), listRule('inputs'))
  assert.equal(problemOf(md(replace('inputs', 'inputs: [true]'))), listRule('inputs'))
  assert.equal(problemOf(md(replace('inputs', 'inputs: [null]'))), listRule('inputs'))
  assert.equal(problemOf(md(replace('inputs', 'inputs: [[a]]'))), listRule('inputs'))
  // 비어 있거나 공백뿐인 글도 거절한다.
  assert.equal(problemOf(md(replace('inputs', "inputs: ['']"))), listRule('inputs'))
  assert.equal(problemOf(md(replace('inputs', "inputs: ['  ']"))), listRule('inputs'))
  // 빈 목록은 적은 것으로 본다.
  assert.equal(problemOf(md(replace('inputs', 'inputs: []'))), null)
  // steps 의 형식 오류는 흐름 Skill 이어도 거절한다.
  assert.equal(problemOf(md([...VALID, 'steps: [1]'])), listRule('steps'))
  assert.equal(problemOf(md([...VALID, 'steps: payment'])), listRule('steps'))
  // 여러 칸이 틀리면 칸 이름을 모두 담는다.
  assert.equal(
    problemOf(md(replace('inputs', 'inputs: 1').map((l) => (l.startsWith('outputs:') ? 'outputs: 2' : l)))),
    listRule('inputs · outputs'),
  )
})

test('toSkill: YAML 맨 위 칸의 크기·중첩 상한은 서버와 같다', () => {
  // 100000 코드 포인트를 넘으면 YAML 을 읽기 전에 거절한다.
  const long = `description: ${'가'.repeat(100001)}`
  assert.equal(problemOf(md([long])), '맨 위 칸이 100000자를 넘어요.')
  // 50단을 넘게 중첩하면 거절한다.
  const deep = `deep: ${'['.repeat(60)}1${']'.repeat(60)}`
  assert.equal(problemOf(md([...VALID, deep])), '맨 위 칸의 중첩이 50단을 넘어요.')
  assert.equal(problemOf(md([...VALID, `ok: ${'['.repeat(10)}1${']'.repeat(10)}`])), null)
})

test('toSkill: 서버와 맞추지 못한 칸 — YAML float 형과 컬렉션 별칭', () => {
  // version: 1.0 은 js-yaml 이 1 로 읽어 정수와 구분할 수 없다. 서버는 Double 이라 거절한다.
  assert.equal(problemOf(md(replace('version', 'version: 1.0'))), null)
  // 컬렉션 별칭을 끄는 설정이 js-yaml 에 없어 통과한다. 서버는 maxAliasesForCollections(0) 으로 거절한다.
  assert.equal(problemOf(md(['base: &b [order.id]', ...replace('inputs', 'inputs: *b')])), null)
})

test('toSkill: 구분선 오류면 기본 목록 필드를 두고 error·error_message 를 채운다', () => {
  assert.deepStrictEqual(toSkill(PAYMENT, 'no frontmatter', 'h2'), {
    skill_id: 'payment',
    path: PAYMENT,
    file_hash: 'h2',
    text: 'no frontmatter',
    inputs: [],
    outputs: [],
    depends_on: [],
    steps: [],
    invalid_lists: [],
    error: 'INVALID_SKILL_FORMAT',
    error_message: '맨 위 --- 구분선과 YAML 형식을 확인해 주세요.',
  })
})

test('toSkill: YAML 문법 오류는 detail 을 결과에 남기고 문구로도 쓴다', () => {
  const skill = toSkill(PAYMENT, '---\nname: [a\n---\nbody\n')
  assert.equal(skill.error, 'INVALID_SKILL_FORMAT')
  assert.equal(typeof skill.detail, 'string')
  assert.ok(skill.detail.length > 0)
  assert.equal(skill.error_message, skill.detail)
})

test('flowSteps: 진입 Skill 의 steps, 없으면 빈 배열', () => {
  const skills = Object.entries(PUBLISHED_FILES)
    .filter(([path]) => isSkillFile(path))
    .map(([path, text]) => toSkill(path, text))
  assert.deepStrictEqual(flowSteps(skills), ['order-intake', 'inventory-check', 'payment', 'shipping-reserve', 'notify'])
  assert.deepStrictEqual(flowSteps(skills.filter((s) => s.skill_id !== ENTRY_SKILL)), [])
  assert.deepStrictEqual(flowSteps([]), [])
})

test('skillNameProblem: 빈 값은 null, kebab-case·64자 규칙, 이름 중복 순서로 본다', () => {
  const skills = [{ skill_id: 'payment' }, { skill_id: null }]
  const kebab = 'kebab-case 64자 이하만 쓴다. 예: shipping-check'
  assert.equal(skillNameProblem('', skills), null)
  assert.equal(skillNameProblem('shipping-check', skills), null)
  assert.equal(skillNameProblem('a'.repeat(64), skills), null)
  assert.equal(skillNameProblem('a'.repeat(65), skills), kebab)
  for (const bad of ['Bad_Name', 'shipping_check', '-abc', 'abc-', 'a--b', '1abc', 'ab c']) {
    assert.equal(skillNameProblem(bad, skills), kebab, bad)
  }
  assert.equal(skillNameProblem('payment', skills), '같은 이름의 skill 이 이미 있다')
  assert.equal(skillNameProblem('Payment', skills), kebab)
})

test('newSkillTemplate: 새 Skill 초안 SKILL.md 틀(들여쓰기 없음, 끝은 빈 줄)', () => {
  assert.equal(
    newSkillTemplate('shipping-check'),
    [
      '---',
      'name: shipping-check',
      'description:',
      'inputs: []',
      'outputs: []',
      'depends_on: []',
      'owner:',
      'version: 1',
      '---',
      '# shipping-check',
      '',
      '',
    ].join('\n'),
  )
  // 틀 그대로는 description 이 비어 있어 형식 오류로 보인다.
  assert.equal(
    toSkill(skillPath('shipping-check'), newSkillTemplate('shipping-check')).error_message,
    'description 은 비어 있지 않은 글이어야 해요.',
  )
})
