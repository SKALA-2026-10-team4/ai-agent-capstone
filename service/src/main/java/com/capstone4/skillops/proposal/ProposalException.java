package com.capstone4.skillops.proposal;

/**
 * 제안서 REST API 가 조회나 결정(승인·거절) 요청을 받을 수 없을 때 던지는 예외다. 오류 코드, 화면에 보여 줄 문구, HTTP 상태를 함께 담는다.
 * service/src/main/java/com/capstone4/skillops/api/ProposalApiExceptionHandler.java 가 이 예외를 {code, message} 본문과 httpStatus 응답으로 바꾼다.
 * frontend/src/api/client.js 가 응답의 message 를 오류 문구로 옮기고, 화면(frontend/src/views/ProposalView.vue)이 그 문구를 그대로 보여 준다.
 * 오류 코드와 문구와 상태를 정적 생성 메서드에 모아, 같은 거부가 여러 곳에서 다른 모양으로 나가지 않게 했다. 새 거부 사유는 생성 메서드로 더한다.
 * code 와 httpStatus 는 담은 값을 그대로 돌려주는 accessor 다.
 */
public class ProposalException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    /** 코드, 문구, HTTP 상태를 정해 예외를 만든다. 서비스 코드는 아래 생성 메서드를 쓰고, 테스트는 같은 모양의 예외를 이 생성자로 직접 만든다. */
    public ProposalException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * 없는 제안서 번호를 받았을 때의 404 PROPOSAL_NOT_FOUND 다. service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의
     * markDecided 와 service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 detail 이 쓴다.
     */
    public static ProposalException notFound() {
        return new ProposalException("PROPOSAL_NOT_FOUND", "제안서를 찾을 수 없습니다.", 404);
    }

    /**
     * 이미 결정한 제안서를 다시 결정하려 할 때의 409 PROPOSAL_ALREADY_DECIDED 다. ProposalService 의 checkDecidable 이 던지고,
     * 동시 결정으로 커밋이 실패하면 ProposalApiExceptionHandler 의 decidedConcurrently 도 같은 응답을 만든다.
     */
    public static ProposalException alreadyDecided() {
        return new ProposalException("PROPOSAL_ALREADY_DECIDED", "이미 결정한 제안서입니다.", 409);
    }

    /**
     * 합격 후보가 없는 제안서(no_valid_fix)를 승인하려 할 때의 409 NO_VALID_FIX 다. 이런 제안서는 거절로만 닫는다.
     * ProposalService 의 checkDecidable 이 던진다.
     */
    public static ProposalException noValidFix() {
        return new ProposalException("NO_VALID_FIX", "승인할 수 있는 수정안이 없는 제안서입니다.", 409);
    }

    /** 합격 후보 목록에 없는 번호로 승인하려 할 때의 400 CANDIDATE_NOT_VALID 다. ProposalService 의 checkDecidable 이 던진다. */
    public static ProposalException candidateNotValid() {
        return new ProposalException("CANDIDATE_NOT_VALID", "모든 확인을 통과한 후보만 승인할 수 있습니다.", 400);
    }

    /**
     * 담당자가 의도 해석을 확인하지 않은 승인 요청의 400 INTENT_NOT_CONFIRMED 다.
     * service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 의 approve 가 서비스를 부르기 전에 던진다.
     */
    public static ProposalException intentNotConfirmed() {
        return new ProposalException("INTENT_NOT_CONFIRMED", "의도 확인 없이 승인할 수 없습니다.", 400);
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
