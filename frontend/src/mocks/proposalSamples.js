// mock 모드의 수정안 목록 확인용 표본이다. frontend/src/mocks/index.js 가 proposal-f1.json 과 함께
// proposals 저장소에 넣는다. 상태(검토 대기, 통과한 후보 없음, 승인됨, 거절됨), 추천 후보 유무,
// 원인 Skill, 만든 시각을 섞어 frontend/src/views/ProposalListView.vue 의 필터·정렬·스크롤을 볼 수 있게 했다.
// 목록에 보이는 값(제목, 요약, 원인 Skill, 후보 수, 통과 후보, 추천 후보, 상태, 시각)만 바꾸고,
// 상세 화면의 본문 섹션은 proposal-f1.json 것을 그대로 쓴다. 그래서 표본의 상세 본문은 목록과 다를 수 있다.

// 표본마다 바꿀 값이다. valid 는 통과한 후보 번호, count 는 후보 수, rec 는 추천 후보 번호다.
const SAMPLES = [
  { id: 'p_mock_s1', status: 'proposed', at: '2026-10-20T08:30:00Z', skill: 'shipping-reserve', count: 2, valid: [1, 2], rec: 2 },
  { id: 'p_mock_s2', status: 'no_valid_fix', at: '2026-10-19T02:15:00Z', skill: 'payment', count: 2, valid: [], rec: null },
  { id: 'p_mock_s3', status: 'approved', at: '2026-10-18T06:40:00Z', skill: 'inventory-check', count: 2, valid: [1], rec: 1 },
  { id: 'p_mock_s4', status: 'rejected', at: '2026-10-17T11:05:00Z', skill: 'order-intake', count: 3, valid: [2], rec: 2 },
  { id: 'p_mock_s5', status: 'proposed', at: '2026-10-16T04:50:00Z', skill: 'notify', count: 1, valid: [1], rec: 1 },
  { id: 'p_mock_s6', status: 'approved', at: '2026-10-15T09:20:00Z', skill: 'payment', count: 3, valid: [1, 3], rec: 3 },
  { id: 'p_mock_s7', status: 'no_valid_fix', at: '2026-10-14T01:00:00Z', skill: 'shipping-reserve', count: 2, valid: [], rec: null },
]

// 결정이 끝난 표본에 붙일 승인 기록이다. 모양은 index.js 의 approve/reject 처리기가 만드는 record 와 같다.
const decisionOf = (s) => ({
  proposal_id: s.id,
  decision: s.status,
  candidate_no: s.status === 'approved' ? s.rec : null,
  intent_confirmed: s.status === 'approved',
  approval_token: s.status === 'approved' ? `a_${s.id.slice(-2).toUpperCase()}MOCK00` : null,
  approved_by: 'operator',
  approver_name: '운영자',
  decided_at: s.at,
  used_at: null,
})

/**
 * base(proposal-f1.json)를 복사해 표본 수정안 목록을 만든다. clone 은 index.js 의 structuredClone 도우미다.
 */
export function sampleProposals(base, clone) {
  return SAMPLES.map((s) => {
    const p = clone(base)
    const decided = s.status === 'approved' || s.status === 'rejected'
    p.proposal_id = s.id
    p.status = s.status
    p.created_at = s.at
    p.decided_at = decided ? s.at : null
    p.decision = decided ? decisionOf(s) : null
    p.valid_candidate_nos = s.valid
    p.recommended_candidate_no = s.rec
    p.document.title = `${s.skill} 수정 제안서`
    p.document.summary = `${s.skill} Skill 변경 뒤 기준 주문이 멈춰 수정안 ${s.count}개를 시험했습니다.`
    p.proposal.root_cause = { ...p.proposal.root_cause, skill: s.skill }
    p.proposal.recommended_candidate_no = s.rec
    // 후보 수를 맞추고, 통과 여부를 표본 값대로 바꾼다. 모자라면 마지막 후보를 복사해 번호만 바꾼다.
    const candidates = []
    for (let no = 1; no <= s.count; no++) {
      const src = p.proposal.candidates[Math.min(no, p.proposal.candidates.length) - 1]
      candidates.push({ ...clone(src), candidate_no: no, valid: s.valid.includes(no), rank: no === s.rec ? 1 : null })
    }
    p.proposal.candidates = candidates
    return p
  })
}
