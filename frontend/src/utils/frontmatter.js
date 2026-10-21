// SKILL.md = '---' YAML frontmatter '---' + 본문. order-agent SkillLoader 와 같은 경계로 자른다.
import yaml from 'js-yaml'

export function splitSkillMd(text) {
  const normalized = text.replace(/\r\n/g, '\n')
  if (!normalized.startsWith('---\n')) return { error: 'INVALID_SKILL_FORMAT' }
  const end = normalized.indexOf('\n---\n', 4)
  if (end < 0) return { error: 'INVALID_SKILL_FORMAT' }
  return { yaml: normalized.slice(4, end), body: normalized.slice(end + 5).trim() }
}

const list = (v) => (Array.isArray(v) ? v.map(String) : [])

// → { name, description, version, owner, inputs, outputs, depends_on, steps, body, error }
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
    // 필수 항목 검사(skillSet.toSkill)가 "적혀 있었는지" 를 볼 수 있게 원래 값도 둔다.
    raw: fm,
    error: null,
  }
}

// order-flow 처럼 steps 줄 하나만 바꾼다. 나머지 바이트는 그대로 둔다.
export const replaceSteps = (text, steps) =>
  text.replace(/^steps:.*$/m, `steps: [${steps.join(', ')}]`)
