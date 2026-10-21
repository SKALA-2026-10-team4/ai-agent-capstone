package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** MCP 도구 get_dependency_graph 의 결과. 칸 설명은 {@link DependencyGraph} 와 같다. */
// TODO(C-1/C-2/B, 계약 확정 후): steps->run_order, edges->links, from/to->from_skill/to_skill,
// keys->value_names, missing[{skill_id,missing_inputs[]}]->broken_links[{skill,missing_input}] 변환을 합의한다.
// flow_order/depends_on은 값 전달 io와 구분한다. 초기 order 입력 링크의 표현도 맞춰야 한다.
// missing_dependencies, ambiguous, complete/parse_error를 버리거나 불완전 그래프를 정상으로 표시하지 않는다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DependencyGraphResult(
        String snapshotId,
        String snapshotHash,
        String entrySkillId,
        List<String> steps,
        List<DependencyGraph.Node> nodes,
        List<DependencyGraph.Edge> edges,
        List<DependencyGraph.Missing> missing,
        List<DependencyGraph.Ambiguous> ambiguous,
        boolean complete) {

    static DependencyGraphResult of(SnapshotManifest manifest, DependencyGraph g) {
        return new DependencyGraphResult(manifest.snapshotId(), manifest.hash(), g.entrySkillId(), g.steps(), g.nodes(),
                g.edges(), g.missing(), g.ambiguous(), g.complete());
    }
}
