package com.capstone4.skillops.workspace;

/**
 * 연습 공간 안의 skill 하나.
 *
 * @param baseVersion 연습 공간을 만들 때 복사해 온 버전. apply_patch 가 수정 내용의 기준 버전과 비교한다
 * @param fullText    SKILL.md 원문 전체. 수정이 들어가면 바뀐다
 */
public record WorkspaceSkill(int baseVersion, String fullText) {
}
