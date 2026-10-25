// 제안서 상태를 담당자 말로 보여 주는 이름표와 검토 대기 판정이다. 상태 문자열은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalStatus.java 의 값과 같다.

// 상태마다 화면 라벨과 배지 색을 정하는 톤이다. frontend/src/components/ProposalStatusBadge.vue 가
// 그리고, 표에 없는 상태는 원래 이름으로 보인다.
export const STATUS = {
  proposed: { label: '검토 대기', tone: 'info' },
  // 시험한 후보가 모두 확인을 통과하지 못해 승인할 수 없는 상태다. 운영자가 확인한 뒤 거절로 닫는다.
  no_valid_fix: { label: '통과한 후보 없음', tone: '' },
  approved: { label: '승인됨', tone: 'ok' },
  rejected: { label: '거절됨', tone: 'err' },
}

// 운영자가 아직 결정하지 않은 제안서인지 본다. 승인할 안이 없는 제안서(no_valid_fix)도
// 운영자가 확인하고 거절로 닫아야 하므로 넣는다. frontend/src/views/ProposalListView.vue 의
// 검토 대기 탭과 frontend/src/components/AppSidebar.vue 의 검토 대기 숫자가 함께 쓴다.
export const awaitsDecision = (p) => p.status === 'proposed' || p.status === 'no_valid_fix'

// 수정안 목록 화면의 상태 필터다. 검토 대기는 awaitsDecision 과 같은 규칙이고,
// 검토 완료는 그 나머지(승인됨, 거절됨)다. frontend/src/views/ProposalListView.vue 의
// 드롭다운이 이 순서대로 그린다.
export const STATUS_FILTERS = [
  { value: 'all', label: '전체' },
  { value: 'pending', label: '검토 대기' },
  { value: 'done', label: '검토 완료' },
]

// 수정안이 고른 상태 필터에 들어가는지 본다. 표에 없는 값은 전체로 본다.
export const matchesStatusFilter = (p, filter) => {
  if (filter === 'pending') return awaitsDecision(p)
  if (filter === 'done') return !awaitsDecision(p)
  return true
}

// 목록 카드의 핵심 요약 두 줄이다. 카드 한 장은 수정안 한 건이고, 긴 요약 글(summary) 대신 원인과
// 시험 결과를 항목으로 나눠 보여 준다. 추천 후보는 시험 결과 줄 끝에 붙인다. 후보를 부르는 말은 상세 본문
// (service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 '후보 1번')과 맞춘다.
// 값은 목록 응답(ProposalController 의 ProposalSummary)에 있는 root_cause_skill, candidate_count,
// valid_candidate_nos, recommended_candidate_no 만 쓴다. 원인 Skill 을 모르면 확인 중으로 쓴다.
export const proposalHighlights = (p) => {
  const passed = p.valid_candidate_nos?.length ?? 0
  const recommended = p.recommended_candidate_no ? ` · 추천 후보 ${p.recommended_candidate_no}번` : ''
  return [
    { label: '원인', value: p.root_cause_skill ? `${p.root_cause_skill} Skill 변경` : '확인 중' },
    {
      label: '시험 결과',
      value: passed
        ? `후보 ${p.candidate_count}개 중 ${passed}개 통과${recommended}`
        : `후보 ${p.candidate_count}개 모두 통과하지 못함`,
    },
  ]
}
