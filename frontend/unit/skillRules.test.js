// 단계 Skill 의 실행 규칙 rules 를 화면이 버리지 않고 읽는지(frontend/src/utils/frontmatter.js),
// 초안 형식 검사와 조건 키 검사가 주문 Agent·service 와 같은 판정을 내는지
// (frontend/src/utils/skillSet.js 의 toSkill, ruleKeyProblems, flowRuleProblems) 공용 사례
// docs/contracts/examples/skill-rules-cases.json 으로 확인한다. mock 의 규칙 예시 세트
// (frontend/src/mocks/skillFiles.js 의 prepaidPolicyFiles)도 여기서 확인한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parseFrontmatterYaml11, parseSkillMd } from '../src/utils/frontmatter.js'
import { RULES_MAX, RULE_THEN_LABEL, flowRuleProblems, isSkillFile, ruleKeyProblems, skillPath, toSkill } from '../src/utils/skillSet.js'
import { PREPAID_POLICY, PUBLISHED_FILES, prepaidPolicyFiles } from '../src/mocks/skillFiles.js'

const cases = JSON.parse(readFileSync(new URL('../../docs/contracts/examples/skill-rules-cases.json', import.meta.url), 'utf8'))
// 사례 하나의 파일들을 화면용 Skill 목록으로 만든다.
const skillsOf = (c) => Object.entries(c.skills).map(([id, text]) => toSkill(skillPath(id), text))

// 실행 규칙 항목(when · then · reason)을 만든다. 칸 이름 then 을 객체 글자로 쓰면 lint
// (unicorn/no-thenable)가 막으므로 이름 목록으로 만든다. extra 는 앞에 덧붙일 칸이다.
const RULE_FIELDS = ['when', 'then', 'reason']
const ruleOf = (when, then, reason, extra = {}) => {
  const values = [when, then, reason]
  return { ...extra, ...Object.fromEntries(RULE_FIELDS.map((k, i) => [k, values[i]])) }
}

test('공용 사례: 형식 오류(metadata_errors)가 난 Skill 만 INVALID_SKILL_FORMAT 이다', () => {
  assert.ok(cases.cases.length > 0)
  for (const c of cases.cases) {
    const invalid = new Set(c.metadata_errors.map((e) => e.skill))
    for (const s of skillsOf(c)) {
      assert.equal(s.error, invalid.has(s.skill_id) ? 'INVALID_SKILL_FORMAT' : null, `${c.id} ${s.skill_id}: ${s.error_message}`)
    }
  }
})

test('공용 사례: 조건 키가 선언 input 이 아닌 규칙(flow_errors RULE_KEY_NOT_INPUT)을 같은 Skill 에서 찾는다', () => {
  for (const c of cases.cases) {
    const expected = c.flow_errors.filter((e) => e.reason === 'RULE_KEY_NOT_INPUT').map((e) => e.skill)
    const found = flowRuleProblems(skillsOf(c)).map((p) => p.skill_id)
    assert.deepStrictEqual([...new Set(found)], expected, c.id)
  }
})

test('parseSkillMd: rules 를 { when, then, reason } 목록으로 꺼내고 원래 값은 raw 에 둔다', () => {
  const text = PUBLISHED_FILES['skills/payment/SKILL.md'].replace(
    'version: 1\n---',
    "version: 1\nrules:\n  - when: \"order.total >= 100000\"\n    then: hold\n    reason: HIGH_AMOUNT\n  - when: order.payment_method == 'card'\n    then: reject\n    reason: NO_CARD\n  - just text\n---",
  )
  const parsed = parseSkillMd(text)
  assert.deepStrictEqual(parsed.rules, [
    ruleOf('order.total >= 100000', 'hold', 'HIGH_AMOUNT'),
    ruleOf("order.payment_method == 'card'", 'reject', 'NO_CARD'),
  ])
  assert.equal(parsed.raw.rules.length, 3)
  // 객체가 아닌 항목은 목록에서 빠지지만 형식 검사는 raw 로 하므로 오류로 잡힌다.
  assert.equal(toSkill('skills/payment/SKILL.md', text).error_message, 'rules 의 3번째 규칙은 when · then · reason 을 가진 항목이어야 해요.')
  assert.deepStrictEqual(parseSkillMd('---\nname: a\nrules: hold\n---\nb').rules, [])
  assert.deepStrictEqual(parseSkillMd('---\nname: a\n---\nb').rules, [])
})

test('toSkill: rules 형식 문구(앞에서부터 하나만)', () => {
  const base = PUBLISHED_FILES['skills/payment/SKILL.md']
  const withRules = (yaml) => toSkill('skills/payment/SKILL.md', base.replace('version: 1\n---', `version: 1\n${yaml}\n---`)).error_message
  const rule = (lines) => `rules:\n  - ${lines.join('\n    ')}`
  assert.equal(withRules('rules: []'), null)
  assert.equal(withRules('rules:'), null)
  assert.equal(withRules('rules: hold'), 'rules 는 목록이어야 해요(없으면 []).')
  assert.equal(withRules(rule(['when: "order.total >= 1"', 'then: hold'])), 'rules 의 1번째 규칙에는 when · then · reason 세 칸만 적어 주세요.')
  assert.equal(
    withRules(rule(['when: "order.total => 1"', 'then: hold', 'reason: A'])),
    "rules 의 1번째 규칙의 when 을 읽을 수 없어요 (예: order.payment_method != 'prepaid').",
  )
  assert.equal(withRules(rule(['when: "order.total >= 1"', 'then: ship', 'reason: A'])), 'rules 의 1번째 규칙의 then 은 hold 나 reject 여야 해요.')
  assert.equal(
    withRules(rule(['when: "order.total >= 1"', 'then: hold', 'reason: prepaid'])),
    'rules 의 1번째 규칙의 reason 은 대문자로 시작하는 대문자·숫자·밑줄 40자 이하여야 해요 (예: PREPAID_ONLY).',
  )
  assert.equal(withRules(rule(['when: "order.total >= 1"', 'then: hold', `reason: A${'B'.repeat(39)}`])), null)
  assert.notEqual(withRules(rule(['when: "order.total >= 1"', 'then: hold', `reason: A${'B'.repeat(40)}`])), null)
  const eleven = `rules:\n${Array.from({ length: RULES_MAX + 1 }, (_, i) => `  - when: "order.total >= ${i}"\n    then: hold\n    reason: A`).join('\n')}`
  assert.equal(withRules(eleven), 'rules 는 10개까지 적을 수 있어요(지금 11개).')
  const flow = PUBLISHED_FILES['skills/order-flow/SKILL.md'].replace('version: 1\n---', 'version: 1\nrules:\n  - when: "order.total >= 1"\n    then: hold\n    reason: A\n---')
  assert.equal(toSkill('skills/order-flow/SKILL.md', flow).error_message, '흐름 Skill(steps 가 있는 Skill)에는 rules 를 적을 수 없어요.')
  assert.deepStrictEqual(RULE_THEN_LABEL, { hold: '보류(HOLD)', reject: '거절(REJECTED)' })
})

test('toSkill: 따옴표 없는 YAML 1.1 불리언 낱말 reason 은 서버(SnakeYAML)가 참·거짓으로 읽으므로 형식 오류다', () => {
  const base = PUBLISHED_FILES['skills/payment/SKILL.md']
  const withReason = (reason) =>
    toSkill('skills/payment/SKILL.md', base.replace('version: 1\n---', `version: 1\nrules:\n  - when: "order.total >= 1"\n    then: hold\n    reason: ${reason}\n---`))
  const boolMessage =
    'rules 의 1번째 규칙의 reason 을 주문 Agent 가 참·거짓 값으로 읽어요. YES · NO · ON · OFF · TRUE · FALSE 대신 다른 코드(예: NO_CARD)를 쓰거나 따옴표로 감싸 주세요.'
  for (const word of ['YES', 'NO', 'ON', 'OFF', 'TRUE', 'FALSE', 'yes', 'Off', 'true']) {
    const skill = withReason(word)
    assert.equal(skill.error, 'INVALID_SKILL_FORMAT', word)
    assert.equal(skill.error_message, boolMessage, word)
  }
  // SnakeYAML 은 Y · N 을 글자로 읽으므로 reason 으로 받는다.
  for (const word of ['Y', 'N']) assert.equal(withReason(word).error_message, null, word)
  // 화면 목록은 js-yaml 이 읽은 글자 그대로 보여 준다(TRUE · FALSE 만 참·거짓 값).
  assert.equal(withReason('NO').rules[0].reason, 'NO')
  // 따옴표로 감싸면 서버도 글자로 읽으므로 받는다. 낱말을 품은 코드도 받는다.
  for (const reason of ['"NO"', "'ON'", '"TRUE"', 'NO_CARD', 'YESTERDAY', 'ONLY', 'OFF1']) {
    assert.equal(withReason(reason).error_message, null, reason)
  }
  // 따옴표로 감싸도 소문자는 reason 모양 규칙에서 걸린다.
  assert.equal(
    withReason('"no"').error_message,
    'rules 의 1번째 규칙의 reason 은 대문자로 시작하는 대문자·숫자·밑줄 40자 이하여야 해요 (예: PREPAID_ONLY).',
  )
  // 두 번째 규칙만 걸리면 그 번호를 알린다.
  const two = base.replace(
    'version: 1\n---',
    'version: 1\nrules:\n  - when: "order.total >= 1"\n    then: hold\n    reason: "OFF"\n  - when: "order.total >= 2"\n    then: reject\n    reason: OFF\n---',
  )
  assert.match(toSkill('skills/payment/SKILL.md', two).error_message, /^rules 의 2번째 규칙의 reason 을 주문 Agent 가 참·거짓 값으로/)
})

test('parseFrontmatterYaml11: 따옴표 없는 YAML 1.1 불리언 낱말만 참·거짓으로 읽고 나머지는 parseSkillMd 와 같다', () => {
  const text = '---\nname: a\nversion: 1\nflags: [Y, n, YES, no, On, OFF, true, FALSE, "NO", \'on\', NOPE]\nrules:\n  - reason: NO\n---\nbody'
  const fm = parseFrontmatterYaml11(text)
  assert.deepStrictEqual(fm.flags, ['Y', 'n', true, false, true, false, true, false, 'NO', 'on', 'NOPE'])
  assert.equal(fm.rules[0].reason, false)
  assert.equal(fm.version, 1)
  // 화면 읽기(js-yaml CORE_SCHEMA)는 그대로다.
  assert.deepStrictEqual(parseSkillMd(text).raw.flags, ['Y', 'n', 'YES', 'no', 'On', 'OFF', true, false, 'NO', 'on', 'NOPE'])
  assert.equal(parseFrontmatterYaml11('name: a'), null)
  assert.equal(parseFrontmatterYaml11('---\n- a\n---\nbody'), null)
  assert.equal(parseFrontmatterYaml11('---\nname: [a\n---\nbody'), null)
})

test('ruleKeyProblems: input 과 같거나 input + . 로 시작하는 값 이름만 통과한다', () => {
  const skill = (inputs, whens) => ({ skill_id: 's', error: null, steps: [], inputs, rules: whens.map((when) => ruleOf(when, 'hold', 'A')) })
  assert.deepStrictEqual(ruleKeyProblems(skill(['order.shipping'], ["order.shipping.country != 'KR'", 'order.shipping == null'])), [])
  assert.deepStrictEqual(ruleKeyProblems(skill(['order.id'], ["order.identity == 'x'", 'order.final_status == null'])), [
    { skill_id: 's', index: 0, when: "order.identity == 'x'", key: 'order.identity' },
    { skill_id: 's', index: 1, when: 'order.final_status == null', key: 'order.final_status' },
  ])
  assert.deepStrictEqual(ruleKeyProblems({ ...skill([], ['x == 1']), error: 'INVALID_SKILL_FORMAT' }), [])
  assert.deepStrictEqual(ruleKeyProblems({ ...skill([], ['x == 1']), steps: ['a'] }), [])
  assert.deepStrictEqual(ruleKeyProblems({ skill_id: 's', error: null, steps: [], inputs: [] }), [])
})

test('mock 규칙 예시 세트: 정책 문장만 넣은 세트와 규칙까지 옮긴 세트', () => {
  const load = (files) => Object.entries(files).filter(([p]) => isSkillFile(p)).map(([p, t]) => toSkill(p, t))
  const textOnly = load(prepaidPolicyFiles({ withRule: false }))
  const ruled = load(prepaidPolicyFiles({ withRule: true }))
  const payment = (skills) => skills.find((s) => s.skill_id === 'payment')
  assert.ok(payment(textOnly).body.endsWith(PREPAID_POLICY))
  assert.deepStrictEqual(payment(textOnly).rules, [])
  assert.deepStrictEqual(payment(ruled).rules, [ruleOf("order.payment_method != 'prepaid'", 'hold', 'PREPAID_ONLY')])
  assert.ok(payment(ruled).body.endsWith(PREPAID_POLICY))
  for (const skills of [textOnly, ruled]) {
    assert.ok(skills.every((s) => s.error === null))
    assert.deepStrictEqual(flowRuleProblems(skills), [])
  }
})
