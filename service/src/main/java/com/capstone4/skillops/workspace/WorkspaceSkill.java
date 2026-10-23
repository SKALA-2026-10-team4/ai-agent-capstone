package com.capstone4.skillops.workspace;

/**
 * 연습 공간 안의 skill 하나다. service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java 의 skills JSON
 * 칸에 camelCase 키로 저장되고, service/src/main/java/com/capstone4/skillops/scenario/FlowRunner.java 의 DryRunRequest 에
 * 담겨 실행기로 넘어간다. service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 가 연습 공간을 만들
 * 때와 수정을 넣을 때 새로 만든다.
 * 옛 행을 읽을 수 있는지는 service/src/test/java/com/capstone4/skillops/workspace/WorkspaceSkillJsonTest.java 가 확인한다.
 *
 * @param baseVersion 연습 공간을 만들 때 복사해 온 버전이다. apply_patch 가 수정 내용의 기준 버전과 비교한다
 * @param fullText    SKILL.md 원문 전체다. 수정이 들어가면 바뀐다
 * @param revision    이 연습 공간에서 이 skill 에 수정이 들어간 횟수다. apply_patch 가 돌려주는 버전은 baseVersion 에
 *                    revision 을 더한 값이다. 이 칸이 생기기 전에 저장한 행(JSON 에 revision 이 없다)은 0 으로 읽힌다
 */
public record WorkspaceSkill(int baseVersion, String fullText, int revision) {

    /** 수정이 들어간 적 없는 skill 을 revision 0 으로 만든다. 연습 공간을 처음 만들 때 쓴다. */
    public WorkspaceSkill(int baseVersion, String fullText) {
        this(baseVersion, fullText, 0);
    }
}
