package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * flow 하나의 정적 연결 관계. 선언(frontmatter)만 보고 계산하므로 실제 실행 여부는 trace 로 확인해야 한다.
 *
 * @param steps     entry flow 의 steps 순서
 * @param missing   끊긴 연결. 주문 Agent 계약 오류와 같은 모양이며, 첫 오류에서 멈추지 않고 전부 담는다
 * @param ambiguous 같은 값을 앞선 skill 여럿이 내놓는 곳
 * @param complete  읽지 못한 skill 이 하나라도 있으면 false. 이때 missing·edges 도 불완전하다
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

    /** @param parseError 파일이 없으면 UNKNOWN_SKILL, 형식이 깨졌으면 그 이유. 정상이면 null */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Node(String skillId, String path, String fileHash, List<String> inputs, List<String> outputs,
            List<String> dependsOn, String parseError) {
    }

    /**
     * @param type flow_order(steps 순서) · depends_on(선행 skill → 이 skill) · io(값을 내놓는 skill → 받는 skill)
     * @param keys io 일 때 넘어가는 컨텍스트 키, 나머지는 빈 목록
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Edge(String type, String from, String to, List<String> keys) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Missing(String skillId, List<String> missingInputs, List<String> missingDependencies) {
    }

    /** @param providers 그 값을 내놓는 앞선 skill 들, steps 순서. io edge 는 마지막(가장 가까운) skill 로 그린다 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ambiguous(String skillId, String key, List<String> providers) {
    }
}
