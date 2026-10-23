package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * flow 하나의 정적 연결 관계를 담는 결과 record 다. 선언(frontmatter)만 보고 계산하므로 실제 실행 여부는 trace 로 확인해야 한다.
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 가 만들고,
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphResult.java 의 of 가 snapshot 칸을 붙여
 * get_dependency_graph 결과로 내보낸다. apply_patch 에서는
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 rejectUnreadableHeaders 가 nodes 의
 * parse_error 로 맨 위 칸을 깨는 수정을 거부하고, brokenLinksOf 가 missing 으로 결과의 broken_links 를 만든다.
 * 칸 이름은 JsonNaming 으로 snake_case 가 된다.
 * frontend/src/utils/flowGraph.js 의 buildGraph 는 같은 칸 이름을 쓰는 화면용 그래프를 브라우저에서 따로 계산한다.
 *
 * @param entrySkillId entry flow 의 skill ID 다.
 * @param steps     entry flow 의 steps 순서다.
 * @param nodes     steps 순서대로 놓인 단계 skill 노드다. 같은 skill 이 steps 에 두 번 있으면 노드도 두 개다.
 * @param edges     flow_order 전체, depends_on 전체, io 전체 순서로 묶인 연결 목록이다.
 * @param missing   끊긴 연결이다. 주문 Agent 계약 오류와 같은 칸(skill_id, missing_inputs, missing_dependencies)을 쓰며,
 *                  첫 오류에서 멈추지 않고 전부 담는다.
 * @param ambiguous 같은 값을 앞선 skill 여럿이 내놓는 곳이다.
 * @param complete  읽지 못한 skill 이 하나라도 있으면 false 다. 이때 missing 과 edges 도 불완전하다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DependencyGraph(
        String entrySkillId,
        List<String> steps,
        List<Node> nodes,
        List<Edge> edges,
        List<Missing> missing,
        List<Ambiguous> ambiguous,
        boolean complete) {

    /**
     * 단계 skill 하나의 노드다. path 와 fileHash 는 SkillSource 값 그대로이며 snapshot 에 파일이 없으면 null 이다.
     * 선언을 읽지 못하면 inputs, outputs, dependsOn 은 빈 목록이다.
     *
     * @param parseError 파일이 없으면 UNKNOWN_SKILL, 형식이 깨졌으면 그 이유다. 정상이면 null 이다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Node(String skillId, String path, String fileHash, List<String> inputs, List<String> outputs,
            List<String> dependsOn, String parseError) {

        /**
         * steps 에 있지만 snapshot 에 SKILL.md 가 없는 skill 의 parseError 값이다. 주문 Agent 가 같은 경우에 쓰는 오류 이름
         * (order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의 load)과 같다.
         */
        public static final String UNKNOWN_SKILL = "UNKNOWN_SKILL";
    }

    /**
     * 두 skill 사이의 연결 하나다.
     *
     * @param type flow_order(steps 순서), depends_on(선행 skill 에서 이 skill 로), io(값을 내놓는 skill 에서 받는 skill 로)
     *             가운데 하나다.
     * @param keys io 일 때 넘어가는 컨텍스트 키이고, 나머지 종류는 빈 목록이다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Edge(String type, String from, String to, List<String> keys) {

        // edge 종류(type) 값이다. 결과의 edges 는 FLOW_ORDER, DEPENDS_ON, IO 순서로 묶인다.
        public static final String FLOW_ORDER = "flow_order";
        public static final String DEPENDS_ON = "depends_on";
        public static final String IO = "io";
    }

    /** 끊긴 연결 하나다. skillId 가 받지 못한 inputs(missingInputs)와 아직 끝나지 않은 depends_on(missingDependencies)을 담는다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Missing(String skillId, List<String> missingInputs, List<String> missingDependencies) {
    }

    /**
     * 같은 값을 앞선 skill 여럿이 내놓는 곳 하나다.
     *
     * @param providers 그 값을 내놓는 앞선 skill 들을 steps 순서로 담는다. io edge 는 마지막(가장 가까운) skill 에서 긋는다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ambiguous(String skillId, String key, List<String> providers) {
    }
}
