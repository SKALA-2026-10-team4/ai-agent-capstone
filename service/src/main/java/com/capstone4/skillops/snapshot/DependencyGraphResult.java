package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * MCP 도구 get_dependency_graph 의 결과 record 다. DependencyGraph 의 칸 앞에 snapshot_id 와 snapshot_hash 를 붙여 평탄하게
 * 펼친다. 칸 설명은 DependencyGraph 와 같고, 칸 순서가 곧 JSON 키 순서다.
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java 의 dependencyGraph 가 만들고
 * service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java 의 run 이 JSON 으로 쓴다. 진단 Agent 는
 * skillops-agent/skillops_agent/nodes.py 의 generate_candidates 에서 complete 를 확인한 뒤 이 JSON 을 그대로 후보 생성
 * 프롬프트에 넣는다.
 */
// 칸 이름과 뜻은 docs/contracts/mcp-tools-input-output.md 의 get_dependency_graph 절에 적혀 있으므로, 칸을 바꾸면 그 절도
// 함께 고친다. 칸을 줄이더라도 missing_dependencies, ambiguous, complete, parse_error 를 버리거나 불완전한 그래프를 정상으로
// 표시하지 않는다.
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

    /** manifest 의 snapshot_id 와 hash 를 분석 결과 g 의 칸 앞에 붙여 결과를 만든다. DependencyGraphService 가 부른다. */
    static DependencyGraphResult of(SnapshotManifest manifest, DependencyGraph g) {
        return new DependencyGraphResult(manifest.snapshotId(), manifest.hash(), g.entrySkillId(), g.steps(), g.nodes(),
                g.edges(), g.missing(), g.ambiguous(), g.complete());
    }
}
