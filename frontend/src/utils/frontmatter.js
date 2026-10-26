// SKILL.md 를 '---' 사이의 YAML frontmatter 와 본문으로 나누고 읽는다. 경계 규칙은
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 와 같다.
// frontend/src/utils/skillSet.js 의 toSkill 이 parseSkillMd 를, rulesProblem 이 parseFrontmatterYaml11 을,
// frontend/src/api/skillSets.js 가 splitSkillMd 를 쓴다. frontend/unit/frontmatter.test.js 가 지금 동작을
// 고정한다.
import yaml from 'js-yaml'

/**
 * SKILL.md 원문을 { yaml, body } 로 나눈다. CRLF 를 LF 로 바꾼 뒤, 첫 줄이 '---' 이고 그
 * 뒤에 '---' 만 있는 줄이 있어야 한다. 닫는 구분선은 다섯 번째 글자(index 4)부터
 * 찾으므로 여는 구분선 바로 다음 줄의 '---' 는 닫는 구분선이 아니다. 형식이 틀리면
 * { error: 'INVALID_SKILL_FORMAT' } 을 돌려주고, 본문은 앞뒤 공백을 다듬는다.
 */
export function splitSkillMd(text) {
  const normalized = text.replace(/\r\n/g, '\n')
  if (!normalized.startsWith('---\n')) return { error: 'INVALID_SKILL_FORMAT' }
  const end = normalized.indexOf('\n---\n', 4)
  if (end < 0) return { error: 'INVALID_SKILL_FORMAT' }
  return { yaml: normalized.slice(4, end), body: normalized.slice(end + 5).trim() }
}

// YAML 맨 위 칸의 크기와 중첩 상한이다. shared/skill-parser 의 SkillParser 의
// MAX_YAML_CODE_POINTS 와 MAX_NESTING_DEPTH 와 같은 값이어야 한다.
const MAX_YAML_CODE_POINTS = 100000
const MAX_NESTING_DEPTH = 50

/**
 * 목록 칸을 읽는다. 누락과 null 은 빈 목록이고, 목록이 아니거나 비어 있지 않은 글이 아닌
 * 항목(숫자, null, 빈 글, 공백만 있는 글, 중첩 목록 등)이 섞이면 null 을 돌려준다. null 은
 * 형식 오류를 뜻하고 parseSkillMd 가 invalid_lists 에 그 칸 이름을 담는다. 규칙은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의
 * strings 와 같다. 예전에는 목록이 아니면 빈 목록으로, 항목은 글로 바꿔 읽었지만 그러면
 * 서버가 거절하는 초안을 화면이 통과시켰다.
 */
const list = (v) => {
  if (v == null) return []
  if (!Array.isArray(v)) return null
  return v.every((x) => typeof x === 'string' && x.trim()) ? v : null
}

// 값의 중첩 깊이를 센다. 상한을 넘으면 더 세지 않으므로 깊은 값에도 끝난다.
function depthOf(value, depth = 1) {
  if (depth > MAX_NESTING_DEPTH || value === null || typeof value !== 'object') return depth
  return Object.values(value).reduce((max, child) => Math.max(max, depthOf(child, depth + 1)), depth)
}

// 값이 { } 객체인지 본다. 목록과 null 은 아니다.
const isMap = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

// 실행 규칙 한 항목의 칸 이름이다. 칸 이름 then 을 객체 글자로 쓰면 lint(unicorn/no-thenable)가
// 막으므로 이 목록으로 객체를 만든다.
const RULE_FIELDS = ['when', 'then', 'reason']
// 단계 Skill 의 실행 규칙 rules 를 화면용 { when, then, reason } 목록으로 꺼낸다. 목록이 아니면 빈
// 목록이고 객체가 아닌 항목은 뺀다. 없는 칸은 null 이다. 형식 검사는 하지 않으며, 원래 값은
// raw.rules 에 그대로 남아 frontend/src/utils/skillSet.js 의 rulesProblem 이 그것으로 검사한다.
// 규칙 모양은 docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2 절이다.
const ruleList = (v) =>
  Array.isArray(v) ? v.filter(isMap).map((r) => Object.fromEntries(RULE_FIELDS.map((k) => [k, r[k] ?? null]))) : []

// 주문 Agent 와 service 는 frontmatter 를 SnakeYAML(YAML 1.1)로 읽어 따옴표 없는 yes · no · on · off ·
// true · false 를 참·거짓 값으로 읽는다. js-yaml CORE_SCHEMA 는 true · false 만 그렇게 읽으므로 같은 글을
// 화면과 서버가 다르게 읽는다. 그 차이를 찾으려고 공용 사례(docs/contracts/examples/skill-rules-cases.json
// 의 notes)와 SnakeYAML 의 bool 규칙이 정한 낱말(yes · no · on · off · true · false 를 소문자, 첫 글자만 대문자,
// 모두 대문자로 쓴 것)을 참·거짓으로 읽는
// schema 를 하나 더 둔다. 같은 tag 의 bool 형을 갈아 끼우므로 나머지 해석은 CORE_SCHEMA 와 같다.
// 따옴표를 친 글자는 이 해석을 거치지 않아 글자로 남는다.
const YAML11_BOOL = /^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$/
const YAML11_TRUE = /^(?:yes|Yes|YES|true|True|TRUE|on|On|ON)$/
const YAML11_SCHEMA = yaml.CORE_SCHEMA.extend({
  implicit: [
    new yaml.Type('tag:yaml.org,2002:bool', {
      kind: 'scalar',
      resolve: (data) => typeof data === 'string' && YAML11_BOOL.test(data),
      construct: (data) => YAML11_TRUE.test(data),
      predicate: (v) => typeof v === 'boolean',
    }),
  ],
})

/**
 * SKILL.md 원문의 frontmatter 를 서버(SnakeYAML, YAML 1.1)처럼 읽은 객체를 돌려준다. parseSkillMd 와
 * 다른 점은 따옴표 없는 yes · no · on · off(소문자, 첫 글자만 대문자, 모두 대문자)도 참·거짓 값이 된다는 것뿐이다.
 * Y 와 N 은 SnakeYAML 이 글자로 읽으므로 여기서도 글자다.
 * 구분선이나 YAML 형식이 틀리거나 최상위가 객체가 아니면 null 이다. frontend/src/utils/skillSet.js 의
 * rulesProblem 이 rules 의 reason 이 서버에서 글자가 아닌 값으로 읽히는지 볼 때 쓴다.
 */
export function parseFrontmatterYaml11(text) {
  const parts = splitSkillMd(text)
  if (parts.error) return null
  try {
    const fm = yaml.load(parts.yaml, { schema: YAML11_SCHEMA })
    return isMap(fm) ? fm : null
  } catch {
    return null
  }
}

/**
 * SKILL.md 원문을 읽어 { name, description, version, owner, inputs, outputs, depends_on,
 * steps, rules, invalid_lists, body, raw, error } 를 돌려준다. YAML 은 js-yaml CORE_SCHEMA 로 읽으므로
 * 날짜 같은 값이 글자로 남는다. 모르는 칸도 버리지 않고 raw 에 그대로 둔다. 구분선이 틀리거나 최상위가
 * 객체가 아니거나 크기·중첩 상한을 넘으면 error 만 있는 객체를 돌려주고, YAML 문법 오류면 detail 에
 * js-yaml 이 알려 준 이유를 담는다. 없는 칸은 기본값으로 채우고, 목록 칸의 형식만 보아 틀린 칸 이름을
 * invalid_lists 에 담는다. 필수 항목과 rules 형식 검사는 frontend/src/utils/skillSet.js 의 toSkill 이 한다.
 */
export function parseSkillMd(text) {
  const parts = splitSkillMd(text)
  if (parts.error) return { error: parts.error }
  // 크기와 중첩 상한은 서버가 YAML 을 읽기 전에 거절하는 값이라 읽기 전에 본다.
  if ([...parts.yaml].length > MAX_YAML_CODE_POINTS) {
    return { error: 'INVALID_SKILL_FORMAT', detail: `맨 위 칸이 ${MAX_YAML_CODE_POINTS}자를 넘어요.` }
  }
  let fm
  try {
    // 중복 키는 js-yaml 이 기본으로 거절하므로 서버의 allowDuplicateKeys(false)와 같다.
    // 다만 서버가 막는 컬렉션 별칭(&a / *a)은 js-yaml 에 끌 수 있는 설정이 없어 여기서는
    // 통과한다. 화면이 서버보다 느슨해지는 유일한 칸이라 저장할 때 서버가 거절한다.
    fm = yaml.load(parts.yaml, { schema: yaml.CORE_SCHEMA })
  } catch (e) {
    return { error: 'INVALID_SKILL_FORMAT', detail: e.reason ?? e.message }
  }
  if (!fm || typeof fm !== 'object' || Array.isArray(fm)) return { error: 'INVALID_SKILL_FORMAT' }
  if (depthOf(fm) > MAX_NESTING_DEPTH) {
    return { error: 'INVALID_SKILL_FORMAT', detail: `맨 위 칸의 중첩이 ${MAX_NESTING_DEPTH}단을 넘어요.` }
  }
  // 목록 칸은 한 번만 읽고, 형식이 틀린 칸 이름을 invalid_lists 에 모은다.
  const lists = {
    inputs: list(fm.inputs),
    outputs: list(fm.outputs),
    depends_on: list(fm.depends_on),
    steps: list(fm.steps),
  }
  return {
    name: fm.name ?? null,
    description: fm.description ?? '',
    version: fm.version ?? null,
    owner: fm.owner ?? '',
    inputs: lists.inputs ?? [],
    outputs: lists.outputs ?? [],
    depends_on: lists.depends_on ?? [],
    steps: lists.steps ?? [],
    rules: ruleList(fm.rules),
    // 형식이 틀린 목록 칸 이름이다. 값은 화면이 쓰기 좋게 빈 목록으로 두고,
    // 오류 문구는 frontend/src/utils/skillSet.js 의 formatProblem 이 만든다.
    invalid_lists: Object.keys(lists).filter((key) => lists[key] === null),
    body: parts.body,
    // 필수 항목 검사(frontend/src/utils/skillSet.js 의 toSkill)가
    // 칸이 실제로 적혀 있었는지 볼 수 있게 원래 값도 둔다.
    raw: fm,
    error: null,
  }
}
