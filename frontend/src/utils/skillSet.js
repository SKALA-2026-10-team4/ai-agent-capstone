// Skill 파일 세트 규칙이다. snapshot 하나가 Skill 파일 세트이고, skills/<id>/SKILL.md 만
// Skill 로 보며 나머지(resources 등)는 관련 파일이다. 원본 Skill 은 order-agent 가 실행 로더와
// 같은 규칙으로 파싱한 결과(GET /api/snapshots/{id}/skills, frontend/src/api/skillSets.js)를
// 쓰고, 브라우저 초안만 toSkill() 로 직접 파싱한다. 그래서 초안 검사 규칙도 서버 로더인
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 와
// 맞춘다. API 를 부르지 않고 상대 경로로만 import 하므로 frontend/unit/skillSet.test.js 가
// Node 에서 불러온다.
import { parseSkillMd } from './frontmatter.js'

// 진입 흐름 Skill 이름이다. 이 Skill 의 steps 가 단계 실행 순서를 정한다. 같은 이름을
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java 의
// ROOT_FLOW_ID, skillops-agent/skillops_agent/config.py 의 DEFAULT_ENTRY_SKILL_ID,
// service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 ENTRY_SKILL 이
// 따로 들고 있으므로 바꿀 때 함께 바꾼다. 화면에서는 frontend/src/utils/flowGraph.js,
// frontend/src/mocks/index.js, frontend/src/views/SkillDraftView.vue 가 이 값을 쓴다.
export const ENTRY_SKILL = 'order-flow'
// Skill 파일 경로 규칙이다. 소문자로 시작하고 소문자, 숫자,
// 하이픈으로 된 폴더 이름이 skill_id 가 된다.
const SKILL_PATH = /^skills\/([a-z][a-z0-9-]*)\/SKILL\.md$/

// skill_id 로 SKILL.md 경로를 만든다. skillops-agent/skillops_agent/contracts.py 의
// skill_file_path 와 같은 모양이다. frontend/src/stores/workspace.js 의
// applyPatchesToDraft 와 frontend/src/views/SkillDraftView.vue 의 새 Skill 만들기가 쓴다.
export const skillPath = (id) => `skills/${id}/SKILL.md`

// 경로가 skills/<id>/SKILL.md 모양인지 본다. 아니면 관련 파일이다.
// frontend/src/api/skillSets.js 가 manifest 에서 관련 파일을 고를 때 쓴다.
export const isSkillFile = (path) => SKILL_PATH.test(path)

/**
 * 파싱 결과 p 에서 SkillLoader.load 와 같은 필수 항목을 검사하고 첫 문제의 화면 문구를 돌려준다.
 * 문제가 없으면 null 이다. 여기서 걸리는 초안은 서버도 INVALID_SKILL_FORMAT 으로 거절한다. 검사
 * 순서는 구분선과 YAML 형식, name 과 폴더 이름, description, owner, version(1 이상의 정수),
 * 본문, 단계 Skill 의 inputs, outputs, depends_on 이다. 흐름 Skill(steps 가 있는 Skill)은 마지막
 * 검사를 건너뛴다. 초안 검사 규칙을 바꾸려면 이 함수를 고친다.
 */
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

/**
 * SKILL.md 경로와 원문으로 화면용 Skill 객체를 만든다. skill_id 는 경로의 폴더 이름이고,
 * 경로가 규칙에 맞지 않으면 null 이다. 목록 칸은 빈 목록을 기본값으로 두고 그 위에
 * frontend/src/utils/frontmatter.js 의 parseSkillMd 결과를 펼친다. 형식 문제가 있으면
 * error 를 INVALID_SKILL_FORMAT 으로, error_message 를 화면 문구로 채운다.
 * frontend/src/stores/workspace.js 의 draftSkills(초안), frontend/src/api/skillSets.js
 * (서버가 읽지 못한 파일), frontend/src/mocks/index.js(mock 서버 파싱과 실행)가 부른다.
 */
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

// 세트에서 진입 Skill 의 steps 를 돌려준다. 진입 Skill 이 없으면 빈 목록이다.
// frontend/src/utils/flowGraph.js 의 flowGraphOf, frontend/src/views/ExecutionView.vue,
// frontend/src/mocks/index.js 의 execute 가 쓴다.
export const flowSteps = (skills) => skills.find((s) => s.skill_id === ENTRY_SKILL)?.steps ?? []

// 새 Skill 이름 규칙이다. kebab-case 이고 64자 이하여야 한다. 경로
// 규칙(SKILL_PATH)보다 엄격해서 하이픈이 겹치거나 끝에 오는 이름은 새로 만들 수 없다.
const NAME_RE = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/
const NAME_MAX_LENGTH = 64

// 새 Skill 이름의 문제 문구를 돌려준다. 비어 있거나 문제가 없으면 null 이다. 이름 규칙을 먼저 보고
// 같은 이름이 있는지는 나중에 본다. frontend/src/views/SkillDraftView.vue 의 새 Skill 입력이 쓴다.
export function skillNameProblem(name, skills) {
  if (!name) return null
  if (!NAME_RE.test(name) || name.length > NAME_MAX_LENGTH) {
    return `kebab-case ${NAME_MAX_LENGTH}자 이하만 쓴다. 예: shipping-check`
  }
  if (skills.some((s) => s.skill_id === name)) return '같은 이름의 skill 이 이미 있다'
  return null
}

// 새 Skill 초안의 SKILL.md 틀을 만든다. description 과 owner 가 비어 있어
// 처음에는 형식 오류로 보이고, 담당자가 채워야 한다. 진입 Skill 의 steps 에
// 넣어야 실행된다. frontend/src/views/SkillDraftView.vue 의 create 가 쓴다.
export const newSkillTemplate = (id) => `---
name: ${id}
description:
inputs: []
outputs: []
depends_on: []
owner:
version: 1
---
# ${id}

`
