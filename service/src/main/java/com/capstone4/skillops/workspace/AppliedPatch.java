package com.capstone4.skillops.workspace;

/**
 * 연습 공간에 실제로 넣은 수정 하나다. service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java 의
 * applied_patches JSON 칸에 넣은 순서대로 camelCase 키(skill, baseVersion, patch)로 쌓인다.
 * WorkspaceService.PatchInput 은 Jackson 3 의 @JsonNaming 을 달고 있어 Hibernate 의 JSON 변환기(Jackson 2 또는 3)에 따라 키가
 * 달라질 수 있으므로, 저장용으로는 어노테이션 없는 이 record 를 따로 둔다.
 * save_proposal 재판정(service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java)이 제안서 후보의 patches 와
 * 칸마다 같은지 비교한다.
 *
 * @param skill       고친 skill 이름이다
 * @param baseVersion apply_patch 에 넣은 기준 버전이다
 * @param patch       apply_patch 에 넣은 unified diff 원문이다
 */
public record AppliedPatch(String skill, int baseVersion, String patch) {
}
