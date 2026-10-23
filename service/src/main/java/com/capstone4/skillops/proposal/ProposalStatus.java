package com.capstone4.skillops.proposal;

/**
 * 제안서 상태 문자열을 한곳에 모은다. proposals.status 칸과
 * 승인 기록(service/src/main/java/com/capstone4/skillops/approval/Approval.java)의 decision 칸이 같은 문자열을 쓴다.
 * 저장할 때는 proposed 나 no_valid_fix 이고, 운영자가 결정하면 approved 나 rejected 로 바뀐다.
 * SkillOps Agent(skillops-agent/skillops_agent/nodes.py 의 proposal_payload)와 화면(frontend/src/utils/proposalView.js)도 같은 문자열을
 * 쓰므로 값을 바꾸면 그쪽도 함께 고친다.
 */
public final class ProposalStatus {

    /** 합격 후보가 있어 운영자의 승인을 기다리는 상태다. */
    public static final String PROPOSED = "proposed";
    /** 합격 후보가 없어 승인할 수 없는 상태다. 운영자가 확인한 뒤 거절로 닫는다. */
    public static final String NO_VALID_FIX = "no_valid_fix";
    /** 운영자가 합격 후보 하나를 승인한 상태다. 승인 기록의 decision 값이기도 하다. */
    public static final String APPROVED = "approved";
    /** 운영자가 거절한 상태다. 승인 기록의 decision 값이기도 하다. */
    public static final String REJECTED = "rejected";

    /** 상수만 모은 클래스라서 인스턴스를 만들지 않는다. */
    private ProposalStatus() {
    }

    /**
     * save_proposal 로 저장할 수 있는 상태(proposed, no_valid_fix)인지 본다.
     * service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java 의 requireSavableStatus 가 저장 검사의 첫 단계에서 쓴다.
     */
    static boolean isSavable(String status) {
        return PROPOSED.equals(status) || NO_VALID_FIX.equals(status);
    }

    /**
     * 결정한 상태(approved, rejected)인지 본다. service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의
     * checkDecidable 이 이미 결정한 제안서를 거를 때 쓰고, requireKnownDecision 이 받은 결정 값이 올바른지 볼 때도 쓴다.
     */
    static boolean isDecided(String status) {
        return APPROVED.equals(status) || REJECTED.equals(status);
    }
}
