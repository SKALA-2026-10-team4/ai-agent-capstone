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
// TODO(C-1/C-2/B, 계약 확정 후): v1 계약 문서(docs/contracts/mcp-tools-input-output.md 6장)와 칸 이름이 다르다.
// steps 를 run_order 로, edges 를 links 로, from 과 to 를 from_skill 과 to_skill 로, keys 를 value_names 로,
// missing[{skill_id, missing_inputs[]}] 를 broken_links[{skill, missing_input}] 로 바꾸는 변환을 합의한다.
// 변환하더라도 flow_order 와 depends_on edge 는 값 전달 io edge 와 구분한다. 초기 order 입력에서 오는 링크
// (v1 에서는 from_skill 이 order)의 표현도 맞춰야 한다.
// missing_dependencies, ambiguous, complete, parse_error 를 버리거나 불완전한 그래프를 정상으로 표시하지 않는다.
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
