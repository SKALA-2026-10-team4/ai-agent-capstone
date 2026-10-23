/**
 * 운영자의 승인과 거절을 기록하는 패키지다. 담당은 A 다.
 * 화면의 승인·거절 요청은 service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 가 받아
 * service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 로 넘긴다.
 * ApprovalService 는 저장된 제안서에 결정을 표시하고, 결정한 운영자와 승인 번호(approval_token)를 Approval 기록으로 남긴다.
 * 결정은 SkillOps Agent 로 보내지 않는다. Agent 의 진단 흐름(skillops-agent/skillops_agent/graph.py)은 제안서를 저장한 뒤
 * 바로 끝나므로, 예전에 service 가 Agent 의 POST /approve(skillops-agent/skillops_agent/main.py 의 approve)로 결정을 전달하던
 * 코드를 없앴다. 그래서 이 패키지는 Agent 서비스 토큰도 쓰지 않는다. Agent 쪽 approve 경로는 남아 있지만 부르는 production 코드는 없다.
 * 승인 번호는 계약 5 의 approval_code 에 해당하며, 운영 반영(promote_workspace)이 연결되면 그 입력으로 쓸 번호다.
 */
package com.capstone4.skillops.approval;
