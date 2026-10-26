package com.capstone4.skillops.workspace;

import java.util.List;
import java.util.Map;

/**
 * save_proposal 재판정이 연습 공간 하나에서 읽는 사실 묶음이다. 연습 공간 표(WorkspaceRecord)의 칸을 고칠 수 없는 값으로 옮긴 것이다.
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 evidenceOf 가 만들고,
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java 가 patch 일치, 고정 여부, 연결 판정, is_revert,
 * weakens_contract 를 다시 계산할 때 쓴다.
 *
 * @param workspaceId      연습 공간 번호다
 * @param sourceSnapshotId 시작한 주문 Agent snapshot 번호다. 운영 폴더에서 시작했으면 null 이다
 * @param frozen           run_scenarios 를 한 번이라도 돌려 고정됐으면 true 다
 * @param appliedPatches   넣은 수정을 넣은 순서대로 담는다. 이 칸이 생기기 전에 만든 연습 공간이면 null 이다
 * @param lastApply        마지막 apply_patch 결과의 오류 수다. 수정을 넣은 적이 없거나 옛 연습 공간이면 null 이다
 * @param startTexts       시작할 때의 skill 이름별 SKILL.md 원문이다. 옛 연습 공간이면 null 이다
 * @param currentTexts     지금의 skill 이름별 SKILL.md 원문이다
 */
public record WorkspaceEvidence(String workspaceId, String sourceSnapshotId, boolean frozen,
        List<AppliedPatch> appliedPatches, ApplyCheck lastApply, Map<String, String> startTexts,
        Map<String, String> currentTexts) {
}
