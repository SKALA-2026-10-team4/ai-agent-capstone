// 제안서 상태를 담당자 말로 보여 주는 이름표와 검토 대기 판정이다. 상태 문자열은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalStatus.java 의 값과 같다.

// 상태마다 화면 라벨과 배지 색을 정하는 톤이다. frontend/src/components/ProposalStatusBadge.vue 가
// 그리고, 표에 없는 상태는 원래 이름으로 보인다.
export const STATUS = {
  proposed: { label: '검토 대기', tone: 'info' },
  no_valid_fix: { label: '쓸 수 있는 안 없음', tone: '' },
  approved: { label: '승인됨', tone: 'ok' },
  rejected: { label: '거절됨', tone: 'err' },
}

// 운영자가 아직 결정하지 않은 제안서인지 본다. 승인할 안이 없는 제안서(no_valid_fix)도
// 운영자가 확인하고 거절로 닫아야 하므로 넣는다. frontend/src/views/ProposalListView.vue 의
// 검토 대기 탭과 frontend/src/components/AppSidebar.vue 의 검토 대기 숫자가 함께 쓴다.
export const awaitsDecision = (p) => p.status === 'proposed' || p.status === 'no_valid_fix'

// 후보 종류(candidates[].kind)의 화면 이름이다. 앞의 셋은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 이름과 같고,
// encode_rule 은 본문·의도의 정책을 SKILL.md 의 실행 규칙 rules 로 옮기는 후보다
// (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.6 절).
export const CANDIDATE_KIND_LABEL = {
  same_skill: '바뀐 Skill만 고치기',
  linked_skills: '주고받는 값까지 함께 고치기',
  run_order: '실행 순서 다시 짜기',
  encode_rule: '실행 규칙으로 옮기기',
}
// 후보 종류의 화면 이름을 돌려준다. 표에 없으면 원래 값, 값이 없으면 null 이다.
export const candidateKindLabel = (kind) => (kind ? (CANDIDATE_KIND_LABEL[kind] ?? kind) : null)

// 원인 종류(root_cause.kind)의 화면 이름이다. 칸이 없으면 change 로 본다(proposal.schema.json).
// change 는 되돌리면 실패가 사라지는 변경이고, incomplete_edit 는 오류 없이 돌지만 담당자 의도를
// 실행하지 못하는 수정이다.
export const ROOT_CAUSE_KIND_LABEL = {
  change: '되돌리면 실패가 사라지는 변경',
  incomplete_edit: '의도를 실행하지 못하는 수정(덜 된 수정)',
}
export const rootCauseKind = (root) => root?.kind ?? 'change'
export const rootCauseKindLabel = (root) => ROOT_CAUSE_KIND_LABEL[rootCauseKind(root)] ?? rootCauseKind(root)

// 의도 기대 결과의 출처(intent.source) 이름이다. owner 는 담당자가 저장할 때 적은 것, inferred 는
// SkillOps 가 의도 글과 바뀐 내용으로 만든 것이다. 칸이 없으면 null 이다.
export const INTENT_SOURCE_LABEL = {
  owner: '담당자가 적은 기대 결과',
  inferred: 'SkillOps 가 추정한 기대 결과',
}
export const intentSourceLabel = (source) => (source ? (INTENT_SOURCE_LABEL[source] ?? source) : null)

/**
 * 후보의 경고 표시 목록 [{ key, label, title }] 을 돌려준다. is_revert 는 담당자가 바꾼 파일을
 * 기준본으로 되돌린 후보, weakens_contract 는 rules 나 그 조건이 쓰는 input 을 지운 후보다. 둘 다
 * 담당자 의도가 있는 요청에서는 불합격 사유다(2.6 절). 참인 것만 넣는다.
 */
export function candidateFlags(c) {
  const flags = []
  if (c?.is_revert) {
    flags.push({ key: 'is_revert', label: '담당자 수정 되돌림', title: '담당자가 바꾼 파일을 수정 전 내용으로 되돌리는 안이에요.' })
  }
  if (c?.weakens_contract) {
    flags.push({ key: 'weakens_contract', label: '규칙·입력 약화', title: '실행 규칙이나 규칙·의도가 쓰는 입력을 지우는 안이에요.' })
  }
  return flags
}

// server_overrides 의 field 화면 이름이다. 'results.' 를 앞에 붙여 와도 같은 이름으로 본다.
// 표에 없는 칸은 원래 이름으로 보인다.
export const OVERRIDE_FIELD_LABEL = {
  fixes_failure: '실패 해결',
  keeps_old_orders: '기준 주문 유지',
  keeps_intent: '의도 지킴',
  no_broken_links: '입력·출력 연결',
  metadata_valid: 'Skill 형식',
  flow_valid: '흐름 구조',
  valid: '합격 여부',
}

// 판정 값 하나를 짧은 글로 바꾼다. 참은 '예', 거짓은 '아니오', 없음은 '없음', { passed, total } 은
// 'passed/total', 그 밖의 객체와 목록은 JSON 글이다.
export function verdictText(v) {
  if (v === null || v === undefined) return '없음'
  if (typeof v === 'boolean') return v ? '예' : '아니오'
  if (typeof v === 'object' && 'passed' in v && 'total' in v) return `${v.passed}/${v.total}`
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}

/**
 * 제안서의 server_overrides 를 화면 줄 [{ candidate_no, field, label, agent, server }] 로 바꾼다.
 * service 가 저장할 때 장부로 다시 계산한 판정이 Agent 가 보낸 값과 다른 칸이다(2.7 절). service 상세
 * 응답의 맨 위 칸을 먼저 보고, 없으면 Agent 원본(proposal) 칸을 본다. 없으면 빈 목록이다.
 * frontend/src/views/ProposalView.vue 와 frontend/src/views/DiagnosisView.vue 가 쓴다.
 */
export function overrideRows(detail) {
  const list = detail?.server_overrides ?? detail?.proposal?.server_overrides ?? []
  return list.map((o) => {
    const field = String(o.field ?? '')
    const bare = field.replace(/^results\./, '')
    return {
      candidate_no: o.candidate_no ?? null,
      field,
      label: OVERRIDE_FIELD_LABEL[bare] ?? field,
      agent: verdictText(o.agent_value),
      server: verdictText(o.server_value),
    }
  })
}
