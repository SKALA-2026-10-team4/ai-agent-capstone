package com.capstone4.skillops.workspace;

/**
 * 연습 공간의 마지막 apply_patch 결과에 들어 있던 오류 수다. service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java 의
 * last_apply JSON 칸에 camelCase 키로 저장된다. save_proposal 재판정
 * (service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java)이 no_broken_links, metadata_valid, flow_valid 를
 * Agent 의 값이 아니라 이 수로 다시 계산한다. 오류 목록이 아니라 수만 두는 것은 판정에 수만 쓰기 때문이다.
 *
 * @param brokenLinks          broken_links 의 항목 수다
 * @param dependencyViolations dependency_violations 의 항목 수다
 * @param metadataErrors       metadata_errors 의 항목 수다
 * @param flowErrors           flow_errors 의 항목 수다
 */
public record ApplyCheck(int brokenLinks, int dependencyViolations, int metadataErrors, int flowErrors) {

    /** 입력 공급과 선행 단계가 모두 맞으면 true 다. Agent 의 no_broken_links 와 같은 기준이다. */
    public boolean noBrokenLinks() {
        return brokenLinks == 0 && dependencyViolations == 0;
    }
}
