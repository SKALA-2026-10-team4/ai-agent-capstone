package com.capstone4.skillops.skill;

/** 메타데이터와 flow 검사가 공유하는 주문 Agent의 Skill 이름 규칙이다. */
public final class SkillNames {

    /** 정적 이름 검사만 제공하므로 객체 생성을 막는다. */
    private SkillNames() {
    }

    /** 64자 이하의 소문자 kebab-case 이름인지 검사한다. */
    public static boolean isValid(String name) {
        return name != null && name.length() <= 64 && name.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    }
}
