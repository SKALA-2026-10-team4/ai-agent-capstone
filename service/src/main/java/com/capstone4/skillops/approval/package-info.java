/**
 * A · 운영자 승인 중계: 화면의 승인/거절을 받아 승인자와 approval_code 를 기록하고,
 * SkillOps Agent(POST /approve)에 서비스 토큰으로 전달한다. 브라우저는 Agent 토큰을 갖지 않는다.
 */
package com.capstone4.skillops.approval;
