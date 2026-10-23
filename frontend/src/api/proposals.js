// 수정 제안서 API 를 부르는 모듈이다. SkillOps Agent 가 MCP 도구 save_proposal
// (service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java)로 저장한 제안서를
// service 가 보관하고, 화면은 /service/api 경로로 service 에서 읽는다. 목록과 상세는
// service/src/main/java/com/capstone4/skillops/api/ProposalController.java 가, 승인과 거절은
// service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 가 받는다. 목록과
// 상세는 로그인한 사람 누구나, 승인과 거절은 OPERATOR 만 할 수 있다. 이 규칙은
// service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 와 같다. mock
// 모드에서는 frontend/src/mocks/index.js 의 serviceRoutes 가 같은 경로와 승인 규칙을 흉내 낸다.
import { apiPath, get, post } from './client'

// 제안서 목록을 읽는다. GET /service/api/proposals 는 ProposalController 의 list() 가
// 받는다. 응답은 { items: [{ proposal_id, status, created_at, title, summary,
// root_cause_skill, recommended_candidate_no, valid_candidate_nos,
// candidate_count }] } 다. frontend/src/views/ProposalListView.vue 와
// frontend/src/components/AppSidebar.vue(검토 대기 수)가 부른다.
export const listProposals = () => get('/service/api/proposals')

// 제안서 한 건의 상세를 읽는다. GET /service/api/proposals/{id} 는
// ProposalController 의 detail() 이 받는다. 응답은 { proposal_id, status, created_at,
// decided_at, thread_id, valid_candidate_nos, recommended_candidate_no, document,
// proposal, decision } 이다. document 는 { title, summary, sections, tone_warnings } 인
// 본문이고, proposal 은 Agent 가 보낸 제안서 원본, decision 은 승인 기록이며 결정
// 전에는 null 이다. frontend/src/views/ProposalView.vue 의 load 가 부른다.
export const getProposal = (id) => get(apiPath`/service/api/proposals/${id}`)

// 제안서 후보 하나를 승인한다. candidateNo 는 제안서의 후보 번호(1부터)이고, 모든 확인을 통과한
// 후보만 승인할 수 있다. intentConfirmed 가 false 면 서버가 400 INTENT_NOT_CONFIRMED 로 거절한다.
// POST /service/api/proposals/{id}/approve 는 ApprovalController 의 approve() 가 받는다. 응답은
// { proposal_id, decision, candidate_no, intent_confirmed, approval_token, approved_by,
// approver_name, decided_at, used_at } 다. frontend/src/views/ProposalView.vue 의 decide 가 부른다.
export const approveProposal = (proposalId, candidateNo, intentConfirmed) =>
  post(apiPath`/service/api/proposals/${proposalId}/approve`, {
    candidate_no: candidateNo,
    intent_confirmed: intentConfirmed,
  })

// 제안서를 거절한다. 보낼 본문이 없으므로 frontend/src/api/client.js 의
// post 가 빈 객체를 보낸다. 승인할 안이 없는 no_valid_fix 제안서도 이 경로로
// 닫는다. POST /service/api/proposals/{id}/reject 는 ApprovalController 의
// reject() 가 받는다. frontend/src/views/ProposalView.vue 의 decide 가 부른다.
export const rejectProposal = (proposalId) =>
  post(apiPath`/service/api/proposals/${proposalId}/reject`)

// 최근 승인과 거절 기록 { items: [...] } 를 읽는다. GET /service/api/approvals 는
// ApprovalController 의 recent() 가 받는다. 지금 이 함수를 부르는 화면 코드는 없다.
export const listApprovals = () => get('/service/api/approvals')
