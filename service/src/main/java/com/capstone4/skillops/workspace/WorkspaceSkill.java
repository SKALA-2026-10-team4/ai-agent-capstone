package com.capstone4.skillops.workspace;

/**
 * 연습 공간 안의 skill 하나.
 *
 * @param baseVersion 연습 공간을 만들 때 복사해 온 버전. apply_patch 가 수정 내용의 기준 버전과 비교한다
 * @param fullText    SKILL.md 원문 전체. 수정이 들어가면 바뀐다
 * @param revision    이 연습 공간에서 이 skill 에 수정이 들어간 횟수. apply_patch 가 돌려주는 버전은 baseVersion + revision 이다.
 *                    이 칸이 생기기 전에 저장한 행(JSON 에 revision 이 없다)은 0 으로 읽힌다
 */
public record WorkspaceSkill(int baseVersion, String fullText, int revision) {

    /** 수정이 들어간 적 없는 skill (revision 0) */
    public WorkspaceSkill(int baseVersion, String fullText) {
        this(baseVersion, fullText, 0);
    }
}
