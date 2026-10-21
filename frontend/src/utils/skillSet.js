// snapshot 하나 = Skill 파일 세트. skills/<id>/SKILL.md 만 Skill 로 보고 나머지(resources/)는 관련 파일이다.
// 원본은 order-agent 가 실행 로더와 같은 규칙으로 파싱한 결과(GET /snapshots/{id}/skills)를 쓰고,
// 브라우저 초안만 toSkill() 로 직접 파싱한다. 그래서 초안 검사 규칙도 서버 로더(SkillLoader)와 맞춘다.
import { getSnapshot, getSnapshotFile, getSnapshotSkill, listSnapshotSkills } from '@/api/orderAgent'
import { parseSkillMd, splitSkillMd } from './frontmatter'

export const ENTRY_SKILL = 'order-flow'
const SKILL_PATH = /^skills\/([a-z][a-z0-9-]*)\/SKILL\.md$/

export const skillPath = (id) => `skills/${id}/SKILL.md`

// SkillLoader.load 와 같은 필수 항목 검사. 실패하면 서버도 INVALID_SKILL_FORMAT 으로 거절한다.
function formatProblem(p, skillId) {
  if (p.error) return p.detail ?? '맨 위 --- 구분선과 YAML 형식을 확인해 주세요.'
  if (p.name !== skillId) return `name 이 폴더 이름(${skillId})과 달라요.`
  if (!p.description?.trim()) return 'description 이 비어 있어요.'
  if (!p.owner?.trim()) return 'owner 가 비어 있어요.'
  if (!Number.isInteger(p.version) || p.version < 1) return 'version 은 1 이상의 정수여야 해요.'
  if (!p.body) return '본문이 비어 있어요.'
  if (!p.steps.length && !(p.raw.inputs && p.raw.outputs && p.raw.depends_on)) {
    return '단계 Skill 은 inputs · outputs · depends_on 을 모두 적어야 해요(없으면 []).'
  }
  return null
}

export function toSkill(path, text, fileHash = null) {
  const skillId = path.match(SKILL_PATH)?.[1] ?? null
  const parsed = parseSkillMd(text)
  const problem = formatProblem(parsed, skillId)
  return {
    skill_id: skillId,
    path,
    file_hash: fileHash,
    text,
    inputs: [],
    outputs: [],
    depends_on: [],
    steps: [],
    ...parsed,
    error: problem ? 'INVALID_SKILL_FORMAT' : null,
    error_message: problem,
  }
}

// 서버 메타데이터 + 원문 → 화면에서 쓰는 Skill 모양(toSkill 과 같은 필드)
function fromServer(item, content) {
  return {
    skill_id: item.skill_id,
    path: item.path,
    file_hash: item.hash,
    text: content,
    name: item.name,
    description: item.description,
    version: item.version,
    owner: item.owner,
    inputs: item.inputs,
    outputs: item.outputs,
    depends_on: item.depends_on,
    steps: item.steps,
    body: splitSkillMd(content).body ?? '',
    error: null,
    error_message: null,
  }
}

// → { snapshot, skills, others, partial, parse_errors }
export async function loadSkillSet(snapshotId) {
  const [snapshot, list] = await Promise.all([getSnapshot(snapshotId), listSnapshotSkills(snapshotId)])
  const skills = await Promise.all(
    list.items.map(async (item) => fromServer(item, (await getSnapshotSkill(snapshotId, item.skill_id)).content)),
  )
  // 서버가 읽지 못한 파일은 추측하지 않고 오류로 보여 준다. 원문은 파일 API 로 읽는다.
  const broken = await Promise.all(
    list.parse_errors.map(async (err) => {
      const file = await getSnapshotFile(snapshotId, err.path)
      return {
        ...toSkill(err.path, file.content, file.hash),
        error: err.code,
        error_message: err.message,
      }
    }),
  )
  return {
    snapshot,
    skills: [...skills, ...broken],
    others: snapshot.files.filter((f) => !SKILL_PATH.test(f.path)),
    partial: list.partial,
    parse_errors: list.parse_errors,
  }
}

export const flowSteps = (skills) => skills.find((s) => s.skill_id === ENTRY_SKILL)?.steps ?? []
