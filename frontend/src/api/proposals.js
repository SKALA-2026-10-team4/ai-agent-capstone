// 수정 제안서. SkillOps Agent 가 save_proposal 로 저장한 제안서를 service 가 보관하고, 화면은 service 에서 읽는다.
// 목록·상세는 로그인한 사람 누구나, 승인·거절은 OPERATOR 만 할 수 있다(service SecurityConfig 와 같은 규칙).
import { get, post } from './client'

// → { items: [{ proposal_id, status, created_at, title, summary, root_cause_skill, recommended_candidate_no,
//               valid_candidate_nos, candidate_count }] }
export const listProposals = () => get('/service/api/proposals')

// → { proposal_id, status, created_at, decided_at, thread_id, valid_candidate_nos, recommended_candidate_no,
//     document: { title, summary, sections, tone_warnings }, proposal: <Agent 가 보낸 제안서>, decision: 승인 기록 | null }
export const getProposal = (id) => get(`/service/api/proposals/${encodeURIComponent(id)}`)

// candidateNo 는 제안서의 후보 번호(1부터)이고, 모든 확인을 통과한 후보만 승인할 수 있다.
// → { proposal_id, decision, candidate_no, intent_confirmed, approval_token, approved_by, approver_name, decided_at, used_at }
export const approveProposal = (proposalId, candidateNo, intentConfirmed) =>
  post(`/service/api/proposals/${encodeURIComponent(proposalId)}/approve`, {
    candidate_no: candidateNo,
    intent_confirmed: intentConfirmed,
  })

export const rejectProposal = (proposalId) =>
  post(`/service/api/proposals/${encodeURIComponent(proposalId)}/reject`)

export const listApprovals = () => get('/service/api/approvals')
