// snapshot 하나의 Skill 세트를 order-agent 에서 읽어 화면에서 쓰는 Skill 목록으로
// 조립한다. frontend/src/stores/workspace.js 의 ensureSet 이 세트마다 한 번 부르고 결과를
// 캐시한다. API 를 부르는 이 로딩을 frontend/src/utils/skillSet.js 와 떼어 둔 덕분에
// utils 쪽은 Node 단위 테스트(frontend/unit/skillSet.test.js)가 바로 불러올 수 있다.
import { getSnapshot, getSnapshotFile, getSnapshotSkill, listSnapshotSkills } from './orderAgent'
import { parseSkillMd, splitSkillMd } from '@/utils/frontmatter'
import { isSkillFile, toSkill } from '@/utils/skillSet'

/**
 * 서버 메타데이터 한 줄과 SKILL.md 원문으로 화면용 Skill 객체를 만든다. 화면이 읽는
 * 칸 이름은 frontend/src/utils/skillSet.js 의 toSkill 결과와 같고, 파싱 원본인
 * raw 칸만 없다. 서버가 이미 실행 로더와 같은 규칙으로 읽은 Skill 이므로 error 는
 * null 이고, 본문은 splitSkillMd 로 잘라 둔다. file_hash 는 서버의 hash 칸에서 온다.
 * 실행 규칙 rules 는 서버 메타데이터에 있으면 그 값을, 없으면 원문 frontmatter 에서 읽는다.
 * 주문 Agent 의 SkillQueryService metadata 는 rules 를 늘 담으므로(없으면 빈 목록) 원문 대체는
 * 그 칸이 없던 이전 서버와 mock 을 위한 것이다.
 */
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
    rules: item.rules ?? parseSkillMd(content).rules ?? [],
    body: splitSkillMd(content).body ?? '',
    error: null,
    error_message: null,
  }
}

/**
 * snapshot 하나의 Skill 세트를 읽어 { snapshot, skills, others, partial,
 * parse_errors } 를 돌려준다. manifest(getSnapshot)와 Skill 목록(listSnapshotSkills)을
 * 함께 읽고, 서버가 읽은 Skill 마다 원문(getSnapshotSkill)을 받는다. skills 는 서버가
 * 읽은 Skill 뒤에 읽지 못한 파일을 오류 Skill 로 붙인 목록이고, others 는 SKILL.md 가
 * 아닌 관련 파일(resources 등)의 manifest 항목이다. partial 은 읽지 못한 파일이 있으면
 * 참이다. 네 API 는 모두 frontend/src/api/orderAgent.js 에 있다. 그 GET /api/snapshots/{id} 아래 경로는
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 가
 * 받고, mock 모드에서는 frontend/src/mocks/index.js 의 orderAgentRoutes 가 같은 경로를 흉내 낸다.
 */
export async function loadSkillSet(snapshotId) {
  const [snapshot, list] = await Promise.all([getSnapshot(snapshotId), listSnapshotSkills(snapshotId)])
  const skills = await Promise.all(
    list.items.map(async (item) => fromServer(item, (await getSnapshotSkill(snapshotId, item.skill_id)).content)),
  )
  // 서버가 읽지 못한 파일은 화면이 추측해서 채우지 않고 오류 Skill 로 보여 준다.
  // 원문은 파일 API(getSnapshotFile)로 읽고, 오류 코드와 문구는 서버가 준 값을 쓴다.
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
    others: snapshot.files.filter((f) => !isSkillFile(f.path)),
    partial: list.partial,
    parse_errors: list.parse_errors,
  }
}
