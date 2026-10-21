// 수정안(근거 리포트). skillops-agent 에는 아직 조회 API 가 없어 목록·상세는 mock 으로만 동작한다.
// TODO(B·C-2 연동): 수정안 목록·상세 GET 이 생기면 경로만 바꾼다. 응답 형식은 skillops_agent/state.py 와 같다.
import { get, post, USE_MOCK } from './client'

// 조회 API 가 생기기 전까지 실서버 모드에서는 화면이 호출하지 않고 연결 대기를 표시한다.
export const PROPOSALS_AVAILABLE = USE_MOCK

export const listProposals = () => get('/agent/proposals')

export const getProposal = (id) => get(`/agent/proposals/${encodeURIComponent(id)}`)

// 승인/거절은 service 가 받는다(로그인 + OPERATOR). 계약 5 REST: POST /api/proposals/{id}/approve
// {candidate, intent_confirmed} → approval_token. service 가 승인 기록(approvals)을 남기고
// skillops-agent POST /approve 로 넘긴다. 지금 Agent 는 recommended 후보만 반영한다.
// → { proposal_id, decision, candidate, intent_confirmed, approval_token, approved_by, approver_name, decided_at,
//     used_at, agent_result, agent_message }
export const approveProposal = (proposalId, candidate, intentConfirmed) =>
  post(`/service/api/proposals/${encodeURIComponent(proposalId)}/approve`, {
    candidate,
    intent_confirmed: intentConfirmed,
  })

export const rejectProposal = (proposalId) =>
  post(`/service/api/proposals/${encodeURIComponent(proposalId)}/reject`)

export const listApprovals = () => get('/service/api/approvals')
