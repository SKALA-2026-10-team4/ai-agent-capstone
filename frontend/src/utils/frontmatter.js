// SKILL.md 를 '---' 사이의 YAML frontmatter 와 본문으로 나누고 읽는다. 경계 규칙은
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 와 같다.
// frontend/src/utils/skillSet.js 의 toSkill 이 parseSkillMd 를, frontend/src/api/skillSets.js 가
// splitSkillMd 를 쓴다. frontend/unit/frontmatter.test.js 가 지금 동작을 고정한다.
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

// 목록 칸의 항목을 모두 글자로 바꾼다. 목록이 아니면 빈 목록이다.
const list = (v) => (Array.isArray(v) ? v.map(String) : [])

/**
 * SKILL.md 원문을 읽어 { name, description, version, owner, inputs, outputs, depends_on,
 * steps, body, raw, error } 를 돌려준다. YAML 은 js-yaml CORE_SCHEMA 로 읽으므로 날짜 같은
 * 값이 글자로 남는다. 구분선이 틀리거나 최상위가 객체가 아니면 error 만 있는 객체를 돌려주고,
 * YAML 문법 오류면 detail 에 js-yaml 이 알려 준 이유를 담는다. 없는 칸은 기본값으로 채우고
 * 필수 항목 검사는 하지 않는다. 그 검사는 frontend/src/utils/skillSet.js 의 toSkill 이 한다.
 */
export function parseSkillMd(text) {
  const parts = splitSkillMd(text)
  if (parts.error) return { error: parts.error }
  let fm
  try {
    fm = yaml.load(parts.yaml, { schema: yaml.CORE_SCHEMA })
  } catch (e) {
    return { error: 'INVALID_SKILL_FORMAT', detail: e.reason ?? e.message }
  }
  if (!fm || typeof fm !== 'object' || Array.isArray(fm)) return { error: 'INVALID_SKILL_FORMAT' }
  return {
    name: fm.name ?? null,
    description: fm.description ?? '',
    version: fm.version ?? null,
    owner: fm.owner ?? '',
    inputs: list(fm.inputs),
    outputs: list(fm.outputs),
    depends_on: list(fm.depends_on),
    steps: list(fm.steps),
    body: parts.body,
    // 필수 항목 검사(frontend/src/utils/skillSet.js 의 toSkill)가
    // 칸이 실제로 적혀 있었는지 볼 수 있게 원래 값도 둔다.
    raw: fm,
    error: null,
  }
}
