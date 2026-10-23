// 두 snapshot 의 파일 비교 규칙이다. manifest hash 로 파일 상태를, Skill frontmatter 로 필드
// 변경을 계산한다. frontend/src/views/SnapshotCompareView.vue 의 compare 가 pairFiles 와
// fieldChanges 를 쓰고 frontend/unit/snapshotDiff.test.js 가 결과를 고정한다. service 의
// MCP 도구 diff_skill 은 화면에서 부를 수 없으므로 같은 규칙을 브라우저에서 따로 계산한다.

// 비교할 frontmatter 필드 목록이다. 필드를 더하면 버전 비교 화면의 필드 변경 목록에도 나온다.
export const FRONTMATTER_FIELDS = ['inputs', 'outputs', 'depends_on', 'steps', 'description', 'owner', 'version']

// 파일 하나의 상태를 정한다. 왼쪽에 없으면 added, 오른쪽에 없으면 removed, hash 가 같으면
// unchanged, 다르면 modified 다.
// service/src/main/java/com/capstone4/skillops/snapshot/SkillFileDiff.java 와 같은 규칙이다.
export const fileStatus = (l, r) => (!l ? 'added' : !r ? 'removed' : l.hash === r.hash ? 'unchanged' : 'modified')

/**
 * 두 manifest 의 파일 목록을 경로로 짝지어 경로 순의 [{ path, left, right,
 * status }] 목록을 만든다. 한쪽에만 있는 파일은 다른 쪽이 undefined 다.
 */
export function pairFiles(leftFiles, rightFiles) {
  const left = Object.fromEntries(leftFiles.map((f) => [f.path, f]))
  const right = Object.fromEntries(rightFiles.map((f) => [f.path, f]))
  const paths = [...new Set([...Object.keys(left), ...Object.keys(right)])].sort()
  return paths.map((path) => ({ path, left: left[path], right: right[path], status: fileStatus(left[path], right[path]) }))
}

/**
 * 같은 Skill 의 두 판에서 frontmatter 필드별 변경을 만든다. 목록 필드는 추가된 키(added)와 삭제된
 * 키(removed)로 나누고, 그 밖의 필드는 전후 값(before, after)을 담는다. 값이 같은 필드는 빼고,
 * 한쪽 Skill 이 없으면 빈 목록이다. 뜻은 service 의 SkillFileDiff.FrontmatterChange 와 같다.
 */
export function fieldChanges(l, r) {
  if (!l || !r) return []
  return FRONTMATTER_FIELDS.flatMap((field) => {
    const x = l[field]
    const y = r[field]
    if (JSON.stringify(x) === JSON.stringify(y)) return []
    if (Array.isArray(x) && Array.isArray(y)) {
      return [{ field, added: y.filter((k) => !x.includes(k)), removed: x.filter((k) => !y.includes(k)) }]
    }
    return [{ field, before: x, after: y }]
  })
}
