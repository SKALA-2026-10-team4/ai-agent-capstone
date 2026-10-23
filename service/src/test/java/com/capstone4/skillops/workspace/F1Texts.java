package com.capstone4.skillops.workspace;

import java.util.Map;
import java.util.TreeMap;

import com.capstone4.skillops.scenario.TestFixtures;

/**
 * 장애 F1 의 inventory-check SKILL.md 글을 만드는 테스트 도우미다.
 * service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java 와
 * service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java 가 쓴다.
 * 운영 글의 inventory.available 을 inventory.reservable_quantity 로 바꾼 글이 repaired 이고, 거기서 ", inventory.reserved" 를
 * 지운 글이 broken 이다. broken 에서는 payment 가 받는 inventory.reserved 를 내놓는 skill 이 없어진다.
 * 주문 Agent 의 order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 에 있는 demo 와
 * 같은 치환이다. broken 은 demo 의 broken-contract fixture 와 같고, demo 의 repaired fixture 는 설명 한 줄을 더 붙이므로
 * 여기의 repaired 와 글이 다르다.
 */
final class F1Texts {

    /** inventory-check 정의 파일의 snapshot 안 경로다. */
    static final String PATH = "skills/inventory-check/SKILL.md";
    /** broken 글의 outputs 줄이다. SKILL.md 의 5번째 줄이라 수정 내용의 덩어리가 5번째 줄에서 시작한다. */
    static final String BROKEN_OUTPUTS = "outputs:    [inventory.reservable_quantity]";
    /** repaired 글의 outputs 줄이다. */
    static final String REPAIRED_OUTPUTS = "outputs:    [inventory.reservable_quantity, inventory.reserved]";

    /** 정적 메서드만 쓰는 도우미라서 객체를 만들지 못하게 막는다. */
    private F1Texts() {
    }

    /** 저장소 skills/inventory-check/SKILL.md 의 운영 글이다. */
    static String live() {
        return TestFixtures.liveSkills().get("inventory-check").fullText();
    }

    /** 운영 글의 inventory.available 을 inventory.reservable_quantity 로 바꾼 글이다. */
    static String repaired() {
        return live().replace("inventory.available", "inventory.reservable_quantity");
    }

    /** repaired 글에서 ", inventory.reserved" 를 지워 payment 와의 연결이 끊긴 글이다. */
    static String broken() {
        return repaired().replace(", inventory.reserved", "");
    }

    /** 운영 skill 6개(버전 1) 가운데 inventory-check 만 broken 으로 바꾼 연습 공간 원문이다. */
    static Map<String, WorkspaceSkill> brokenSkills() {
        Map<String, WorkspaceSkill> skills = new TreeMap<>(TestFixtures.liveSkills());
        skills.put("inventory-check", new WorkspaceSkill(1, broken()));
        return skills;
    }

    /** broken 의 outputs 줄을 repaired 줄로 바꾸는 수정 내용이다. '---', '+++' 머리줄이 붙어 있다. */
    static String repairOutputsPatch() {
        return "--- a/" + PATH + "\n+++ b/" + PATH + "\n@@ -5,1 +5,1 @@\n-" + BROKEN_OUTPUTS + "\n+" + REPAIRED_OUTPUTS + "\n";
    }

    /** 운영 글의 outputs 줄을 broken 줄로 바꾸는 수정 내용이다. '---', '+++' 머리줄이 붙어 있다. */
    static String breakOutputsPatch() {
        return "--- a/" + PATH + "\n+++ b/" + PATH + "\n@@ -5,1 +5,1 @@\n-outputs:    [inventory.available, inventory.reserved]\n+"
                + BROKEN_OUTPUTS + "\n";
    }
}
