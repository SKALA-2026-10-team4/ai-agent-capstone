package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** MCP 도구 get_dependency_graph 의 결과. 칸 설명은 {@link DependencyGraph} 와 같다. */
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
