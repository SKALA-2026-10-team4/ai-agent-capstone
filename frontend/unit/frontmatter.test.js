// 특성 테스트다. frontend/src/utils/frontmatter.js 가 SKILL.md 를 '---' frontmatter 와 본문으로
// 나누고(splitSkillMd) 읽는(parseSkillMd) 지금 동작을 고정한다. 경계 규칙은
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 와 같아야 하고,
// 이 결과를 frontend/src/utils/skillSet.js 의 toSkill 이 다시 검사한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import yaml from 'js-yaml'
import { parseSkillMd, splitSkillMd } from '../src/utils/frontmatter.js'

// 형식 오류 결과의 모양이다.
const INVALID = { error: 'INVALID_SKILL_FORMAT' }

test('splitSkillMd: CRLF 를 LF 로 바꾸고 frontmatter 와 다듬은 본문으로 나눈다', () => {
  assert.deepStrictEqual(splitSkillMd('---\r\nname: a\r\n---\r\n\r\n# 제목\r\n본문\r\n\r\n'), {
    yaml: 'name: a',
    body: '# 제목\n본문',
  })
})

test('splitSkillMd: 첫 줄이 --- 가 아니면 형식 오류다', () => {
  assert.deepStrictEqual(splitSkillMd('name: a\n---\nbody'), INVALID)
  assert.deepStrictEqual(splitSkillMd(' ---\nname: a\n---\nbody'), INVALID)
  assert.deepStrictEqual(splitSkillMd(''), INVALID)
})

test('splitSkillMd: 닫는 \\n---\\n 이 없으면 형식 오류다', () => {
  assert.deepStrictEqual(splitSkillMd('---\nname: a\n'), INVALID)
  assert.deepStrictEqual(splitSkillMd('---\nname: a\n---'), INVALID)
  // 여는 구분선 바로 뒤의 구분선은 닫는 구분선으로 보지 않는다. index 4 부터 찾기 때문이다.
  assert.deepStrictEqual(splitSkillMd('---\n---\nbody'), INVALID)
})

test('splitSkillMd: 빈 frontmatter 와 빈 본문', () => {
  assert.deepStrictEqual(splitSkillMd('---\n\n---\nbody'), { yaml: '', body: 'body' })
  assert.deepStrictEqual(splitSkillMd('---\nname: a\n---\n'), { yaml: 'name: a', body: '' })
})

test('parseSkillMd: 필드를 읽고 목록 요소는 글자로 바꾸며 원래 값(raw)도 둔다', () => {
  const text = [
    '---',
    'name: payment',
    'description: 결제한다',
    'inputs: [order.id, order.total]',
    'outputs: [payment.id]',
    'depends_on: [inventory-check]',
    'steps: [1, true, null]',
    'owner: 주문운영팀',
    'version: 2',
    'date: 2026-10-22',
    '---',
    '',
    '# 결제',
    '',
  ].join('\n')
  assert.deepStrictEqual(parseSkillMd(text), {
    name: 'payment',
    description: '결제한다',
    version: 2,
    owner: '주문운영팀',
    inputs: ['order.id', 'order.total'],
    outputs: ['payment.id'],
    depends_on: ['inventory-check'],
    steps: ['1', 'true', 'null'],
    body: '# 결제',
    raw: {
      name: 'payment',
      description: '결제한다',
      inputs: ['order.id', 'order.total'],
      outputs: ['payment.id'],
      depends_on: ['inventory-check'],
      steps: [1, true, null],
      owner: '주문운영팀',
      version: 2,
      date: '2026-10-22',
    },
    error: null,
  })
})

test('parseSkillMd: 없는 필드는 기본값, 목록이 아닌 목록 필드는 빈 배열', () => {
  assert.deepStrictEqual(parseSkillMd('---\ninputs: order.id\nversion: "1"\n---\nbody'), {
    name: null,
    description: '',
    version: '1',
    owner: '',
    inputs: [],
    outputs: [],
    depends_on: [],
    steps: [],
    body: 'body',
    raw: { inputs: 'order.id', version: '1' },
    error: null,
  })
})

test('parseSkillMd: null 로 적은 값은 기본값이 된다', () => {
  const parsed = parseSkillMd('---\nname:\ndescription:\nowner:\nversion:\n---\n')
  assert.equal(parsed.name, null)
  assert.equal(parsed.description, '')
  assert.equal(parsed.owner, '')
  assert.equal(parsed.version, null)
  assert.deepStrictEqual(parsed.raw, { name: null, description: null, owner: null, version: null })
})

test('parseSkillMd: 구분선 오류는 error 하나만 돌려준다', () => {
  assert.deepStrictEqual(parseSkillMd('name: a'), INVALID)
  assert.deepStrictEqual(parseSkillMd('---\nname: a\n'), INVALID)
})

test('parseSkillMd: YAML 문법 오류는 js-yaml 의 reason 을 detail 로 담는다', () => {
  const src = 'name: [a'
  let expected
  try {
    yaml.load(src, { schema: yaml.CORE_SCHEMA })
  } catch (e) {
    expected = e.reason ?? e.message
  }
  assert.ok(expected)
  assert.deepStrictEqual(parseSkillMd(`---\n${src}\n---\nbody`), { error: 'INVALID_SKILL_FORMAT', detail: expected })
})

test('parseSkillMd: 최상위가 객체가 아니면 형식 오류다', () => {
  assert.deepStrictEqual(parseSkillMd('---\n- a\n- b\n---\nbody'), INVALID)
  assert.deepStrictEqual(parseSkillMd('---\njust text\n---\nbody'), INVALID)
  assert.deepStrictEqual(parseSkillMd('---\n\n---\nbody'), INVALID)
})
