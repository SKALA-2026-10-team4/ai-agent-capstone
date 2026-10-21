package com.capstone4.skillops.workspace;

import java.util.Map;
import java.util.TreeMap;

import com.capstone4.skillops.scenario.TestFixtures;

/**
 * 장애 F1 의 inventory-check SKILL.md 글. 주문 Agent 의 SnapshotService.demo 와 같은 방법으로 만든다.
 * 운영 글의 inventory.available 을 inventory.reservable_quantity 로 바꾸고(repaired), 거기서 ", inventory.reserved" 를 지우면(broken)
 * payment 가 받는 inventory.reserved 를 내놓는 skill 이 없어진다.
 */
final class F1Texts {

    static final String PATH = "skills/inventory-check/SKILL.md";
    /** broken 의 outputs 줄. SKILL.md 의 5번째 줄이다 */
    static final String BROKEN_OUTPUTS = "outputs:    [inventory.reservable_quantity]";
    static final String REPAIRED_OUTPUTS = "outputs:    [inventory.reservable_quantity, inventory.reserved]";

    private F1Texts() {
    }

    static String live() {
        return TestFixtures.liveSkills().get("inventory-check").fullText();
    }

    static String repaired() {
        return live().replace("inventory.available", "inventory.reservable_quantity");
    }

    static String broken() {
        return repaired().replace(", inventory.reserved", "");
    }

    /** 운영 skill 6개(버전 1) 가운데 inventory-check 만 broken 으로 바꾼 연습 공간 원문 */
    static Map<String, WorkspaceSkill> brokenSkills() {
        Map<String, WorkspaceSkill> skills = new TreeMap<>(TestFixtures.liveSkills());
        skills.put("inventory-check", new WorkspaceSkill(1, broken()));
        return skills;
    }

    /** broken 의 outputs 줄을 repaired 줄로 바꾸는 수정 내용(--- +++ 머리줄 포함) */
    static String repairOutputsPatch() {
        return "--- a/" + PATH + "\n+++ b/" + PATH + "\n@@ -5,1 +5,1 @@\n-" + BROKEN_OUTPUTS + "\n+" + REPAIRED_OUTPUTS + "\n";
    }

    /** 운영 글의 outputs 줄을 broken 줄로 바꾸는 수정 내용 */
    static String breakOutputsPatch() {
        return "--- a/" + PATH + "\n+++ b/" + PATH + "\n@@ -5,1 +5,1 @@\n-outputs:    [inventory.available, inventory.reserved]\n+"
                + BROKEN_OUTPUTS + "\n";
    }
}
