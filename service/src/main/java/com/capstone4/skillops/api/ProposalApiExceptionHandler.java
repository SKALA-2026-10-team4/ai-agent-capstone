package com.capstone4.skillops.api;

import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.capstone4.skillops.common.ApiError;
import com.capstone4.skillops.proposal.ProposalException;

/**
 * 제안서 REST API(조회와 결정)에서 난 거부를 {code, message} 응답으로 바꾼다. assignableTypes 로 같은 패키지
 * (service/src/main/java/com/capstone4/skillops/api/)의 ApprovalController 와 ProposalController 에만 적용된다.
 * 제안서 컨트롤러를 더하면 assignableTypes 에도 더해야 한다.
 * 오류 코드, 문구, HTTP 상태는 service/src/main/java/com/capstone4/skillops/proposal/ProposalException.java 의 생성 메서드에서
 * 정하고, 이 클래스는 그 값을 응답으로 옮기기만 한다. 로그인과 권한 오류(401, 403)는 컨트롤러에 닿기 전에
 * service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 가 만든다.
 */
@RestControllerAdvice(assignableTypes = { ApprovalController.class, ProposalController.class })
class ProposalApiExceptionHandler {

    /**
     * 서비스가 던진 ProposalException 을 응답으로 옮긴다. 이미 결정한 제안서(409), 수정안이 없는 제안서의 승인(409),
     * 합격하지 않은 후보의 승인(400), 서비스가 찾지 못한 제안서(404)가 여기로 온다.
     * 컨트롤러가 직접 판단하는 두 거부(상세 조회의 없는 제안서, 의도 확인 없는 승인)는 예외를 던지지 않고 toResponse 로 만든 응답을
     * 반환값으로 돌려준다. 그래야 JSON 을 받지 않는 클라이언트에게 리팩토링 전처럼 406 이 간다.
     */
    @ExceptionHandler(ProposalException.class)
    ResponseEntity<ApiError> proposalRefused(ProposalException e) {
        return toResponse(e);
    }

    /**
     * ProposalException 의 code, message, httpStatus 를 그대로 {code, message} 응답으로 옮긴다.
     * 이 클래스의 처리기와, 같은 패키지의 ProposalController.detail, ApprovalController.approve 가 함께 쓴다.
     */
    static ResponseEntity<ApiError> toResponse(ProposalException e) {
        return ApiError.response(e.httpStatus(), e.code(), e.getMessage());
    }

    /**
     * 두 운영자가 같은 제안서를 동시에 결정하면, 늦게 커밋한 쪽이
     * service/src/main/java/com/capstone4/skillops/proposal/Proposal.java 의 @Version 낙관적 잠금에 걸려 여기로 온다.
     * 이미 결정한 제안서와 같은 409 PROPOSAL_ALREADY_DECIDED 로 답한다.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> decidedConcurrently() {
        return proposalRefused(ProposalException.alreadyDecided());
    }
}
