package com.capstone4.skillops.snapshot;

import java.util.regex.Pattern;

/**
 * 주문 Agent snapshot 의 skill 파일 배치 규칙(skills/&lt;skill ID&gt;/SKILL.md)과 skill ID(폴더 이름) 형식을 한곳에 둔다.
 * 주문 Agent 도 같은 배치를 쓴다(order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의
 * skillPath). 같은 패키지(service/src/main/java/com/capstone4/skillops/snapshot/)에 있는 C-1 의 SkillDiffService,
 * DependencyGraphService, DependencyAnalyzer 와 C-2 의
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java,
 * service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java 가 함께 쓴다.
 */
public final class SkillPaths {

    /** skill 폴더들이 들어 있는 최상위 폴더다. 끝 '/' 를 포함한다. */
    public static final String SKILLS_DIR = "skills/";
    /** skill 폴더 안의 정의 파일 이름이다. */
    public static final String SKILL_FILE_NAME = "SKILL.md";

    /**
     * skill ID 형식이다. 소문자나 숫자로 시작하고 소문자, 숫자, 하이픈만 쓴다. '/' 와 '.' 을 받지 않으므로 이 ID 로 만든 경로는
     * skills 폴더 밖으로 나가지 않는다. 주문 Agent 의 이름 검사(SkillLoader.validateName)보다 느슨해서 숫자로 시작하거나,
     * 하이픈이 연달아 오거나 끝에 오거나, 64자를 넘는 이름도 받는다.
     */
    private static final Pattern SKILL_ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private SkillPaths() {
    }

    /**
     * value 가 SKILL_ID 형식의 skill ID 인지 판단한다. null 이면 false 다. DependencyGraphService 가 steps 의 단계 이름을
     * 거를 때와 LiveFolderSkillVersionStore 가 skill 이름을 확인할 때 쓴다.
     */
    public static boolean isSkillId(String value) {
        return value != null && SKILL_ID.matcher(value).matches();
    }

    /**
     * skill 폴더 경로 skills/&lt;id&gt;/ 를 만든다. 끝 '/' 가 있어야 skills/&lt;id&gt;-v2/ 같은 이웃 폴더가 섞이지 않는다.
     * SkillDiffService 가 manifest 에서 같은 폴더의 파일을 고를 때 쓴다.
     */
    public static String skillDir(String skillId) {
        return SKILLS_DIR + skillId + "/";
    }

    /** skill 정의 파일 경로 skills/&lt;id&gt;/SKILL.md 를 만든다. */
    public static String skillMd(String skillId) {
        return skillDir(skillId) + SKILL_FILE_NAME;
    }

    /**
     * skill ID 형식이 틀리면 INVALID_INPUT 을 던진다. param 은 문구 앞에 붙는 도구 입력 이름이고 snapshotId 는 오류에 실을
     * 값이다. SkillDiffService 는 snapshot_id 없이, DependencyGraphService 는 snapshot_id 를 실어 주문 Agent 를 부르기 전에 부른다.
     */
    static void requireSkillId(String param, String value, String snapshotId) {
        if (!isSkillId(value)) {
            throw new OrderAgentException("INVALID_INPUT", param + " 는 소문자·숫자·하이픈만 씁니다: " + value,
                    false, null, snapshotId);
        }
    }

    /**
     * snapshot 에 그 skill 의 SKILL.md 가 없다는 SKILL_NOT_FOUND 예외를 만든다. snapshotId 는 호출처가 오류에 실을 값이며
     * null 일 수 있다. DependencyGraphService 는 snapshot_id 를 싣고 DependencyAnalyzer.flowSteps 는 null 을 싣는다.
     */
    static OrderAgentException notInSnapshot(String skillId, String snapshotId) {
        return new OrderAgentException("SKILL_NOT_FOUND", "snapshot 에 " + skillMd(skillId) + " 가 없습니다.",
                false, null, snapshotId);
    }
}
