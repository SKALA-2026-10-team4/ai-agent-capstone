package com.capstone4.skillops.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.scenario.ScenarioRunner;
import com.capstone4.skillops.workspace.WorkspaceService;
import com.capstone4.skillops.workspace.WorkspaceService.PatchInput;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * C-2 의 쓰기와 판정 도구 세 개(⑦ create_workspace, ⑧ apply_patch, ⑨ run_scenarios)를 MCP 도구로 내놓는 입구다.
 * Spring AI MCP Server 의 어노테이션 스캐너가 @McpTool 메서드를 도구로 등록하고, MCP 클라이언트는 POST /mcp 로 부른다.
 * 메서드는 입력을 그대로 넘기기만 한다. 연습 공간을 만들고 고치는 일은
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 가, 시험 주문 실행과 채점은
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 가 맡는다.
 * 결과와 예외는 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java 의 run 이 JSON 글 하나로
 * 바꾼다. 오류는 C-1 도구와 같은 {code, message, retryable, upstream_status, snapshot_id} 모양이다.
 *
 * 입출력 이름은 계약 5 v1(docs/contracts/mcp-tools-input-output.md)을 따른다. @McpToolParam 에 이름 속성이 없어서
 * Java 파라미터 이름이 곧 도구 입력 키가 되므로 파라미터를 snake_case 로 쓴다. 이름을 바꾸면 Agent 의 호출이 깨진다.
 *
 * 부르는 쪽은 skillops-agent/skillops_agent/tools.py 의 MCPTools.create_workspace, apply_patch, run_scenarios 다.
 * 부르는 순서는 skillops-agent/skillops_agent/nodes.py 의 노드가 정한다. fetch_evidence 노드의 _preflight 와
 * _evaluate_intent_checks, ablate 노드의 _reproduce_failure 는 snapshot 으로 연습 공간을 만들어 시험만 돌린다. ablate 노드의
 * _ablate_change 와 verify_candidates 노드의 _verify_candidate 는 연습 공간을 만들고 수정을 넣은 뒤 시험을 돌린다.
 * 실험 하나마다 연습 공간을 새로 만들고 다시 쓰지 않는다. 개발용 skillops-agent/scripts/c2_smoke.py 는 노드를 거치지 않고
 * create_workspace 와 run_scenarios 를 직접 불러 계약을 확인한다. 이 스크립트는 create_workspace 를 인자 없이 불러 운영
 * 버전으로 연습 공간을 만든다.
 * Agent 를 SKILLOPS_USE_STUB_MCP=true 로 띄우면 이 클래스 대신 skillops-agent/skillops_agent/stub_tools.py 의
 * StubMCPTools 가 같은 이름의 도구를 흉내 낸다.
 *
 * 도구 설명 문자열은 MCP 클라이언트가 도구 목록에서 읽는 공개 문서다. run_scenarios 설명의 repeat 기본값과 범위는
 * ScenarioRunner 의 DEFAULT_REPEAT, MIN_REPEAT, MAX_REPEAT 와 같아야 하고,
 * service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java 가 이를 확인한다. 상수를 바꾸면 이 설명과
 * docs/contracts/mcp-tools-input-output.md 의 repeat 설명도 함께 고친다.
 */
@Component
public class WorkspaceTools {

    private final WorkspaceService workspaces;
    private final ScenarioRunner scenarioRunner;

    /** Spring 이 WorkspaceService 와 ScenarioRunner 빈을 넣어 만든다. */
    public WorkspaceTools(WorkspaceService workspaces, ScenarioRunner scenarioRunner) {
        this.workspaces = workspaces;
        this.scenarioRunner = scenarioRunner;
    }

    /**
     * ⑦ create_workspace 도구다. 운영 skill 을 건드리지 않고 연습용 복사본을 만든다.
     * 두 입력을 WorkspaceService.create 에 그대로 넘긴다. 둘을 함께 쓸 수 없다는 검사와 어디서 복사할지는 그쪽이 정한다.
     * Agent 의 노드는 지금 언제나 snapshot_id 만 넣어 부른다. 결과의 skill_versions 는 apply_patch 의 base_version 기준이 된다.
     * skillops-agent/skillops_agent/nodes.py 의 _ablate_change 는 이 값을 base_version 에 그대로 넣고,
     * _verify_candidate 는 후보의 base_version 이 이 값과 같은지 먼저 맞춰 본다.
     */
    @McpTool(name = "create_workspace", description = """
            운영 중인 skill 은 그대로 두고 연습용 복사본(연습 공간)을 만든다.
            시작점은 둘 중 하나다. start_versions 는 운영 폴더의 skill 을, snapshot_id 는 주문 Agent 가 보관한 skill 묶음
            (skills/ 아래 파일 전부)을 복사한다. 둘 다 없으면 운영 버전이다. 함께 쓸 수 없다.
            돌려주는 값: {workspace_id, skill_versions, created_at}. workspace_id 를 apply_patch, run_scenarios 에 넣는다.
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    public CallToolResult createWorkspace(
            @McpToolParam(required = false, description = "skill 이름 → 시작 버전. 적지 않은 skill 은 운영 버전. snapshot_id 와 함께 쓸 수 없다")
            Map<String, Integer> start_versions,
            @McpToolParam(required = false,
                    description = "주문 Agent snapshot 번호(UUID). 주면 그 snapshot 의 skill 로 시작한다. start_versions 와 함께 쓸 수 없다")
            String snapshot_id) {
        return WorkspaceToolResults.run(() -> workspaces.create(start_versions, snapshot_id));
    }

    /**
     * ⑧ apply_patch 도구다. 연습 공간의 SKILL.md 에 unified diff 를 넣고 끊긴 연결을 알려 준다.
     * WorkspaceService.apply 가 patches 를 적힌 순서대로 모두 적용하거나 하나도 적용하지 않는다.
     * patches 항목의 키(skill, base_version, patch)는 WorkspaceService.PatchInput 의 @JsonNaming 이 snake_case 로 읽는다.
     * Agent 쪽에서는 skillops-agent/skillops_agent/nodes.py 의 _ablate_change 가 바뀐 덩어리 하나를 되돌리는 수정을,
     * _verify_candidate 가 후보 수정안 전체를 넣는다. 원문과 맞지 않는 수정은 INVALID_INPUT 오류가 되고,
     * _verify_candidate 는 그 후보를 탈락시킨 뒤 다음 후보로 넘어간다.
     */
    @McpTool(name = "apply_patch", description = """
            연습 공간의 SKILL.md 에 수정 내용(unified diff)을 넣고, 고친 뒤 skill 사이의 연결이 끊긴 곳이 있는지 알려 준다.
            운영 중인 skill 은 바뀌지 않는다. patches 는 적힌 순서대로 적용하고, 하나라도 원문과 맞지 않으면 전부 취소한다(연습 공간은 그대로).
            patch 는 diff_skill 이 돌려준 덩어리별 diff 를 그대로 넣거나 같은 형식으로 만든다. '---', '+++' 머리줄은 없어도 된다.
            base_version 은 연습 공간 안 그 skill 의 시작 버전(create_workspace 의 skill_versions)과 같아야 한다.
            끊긴 연결이 있어도 수정은 들어간다. 판단은 부른 쪽이 한다. 고치면 SKILL.md 맨 위 칸을 읽을 수 없게 되는 수정은 거부한다.
            돌려주는 값: {workspace_id, changed_skills:[{skill, version}], broken_links:[{skill, missing_input}]}.
            version 은 시작 버전 + 그 skill 에 수정이 들어간 횟수다. broken_links 가 비어 있으면 연결이 모두 이어져 있다.
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    public CallToolResult applyPatch(
            @McpToolParam(description = "create_workspace 가 돌려준 연습 공간 번호") String workspace_id,
            @McpToolParam(description = "넣을 수정 내용 목록. 항목마다 {skill: 고칠 skill 이름, base_version: 기준 버전, patch: unified diff}")
            List<PatchInput> patches) {
        return WorkspaceToolResults.run(() -> workspaces.apply(workspace_id, patches));
    }

    /**
     * ⑨ run_scenarios 도구다. 연습 공간의 skill 로 시험 주문을 모의 실행하고 기대 결과와 비교해 채점한다.
     * 다섯 입력을 그대로 ScenarioRunner.run 에 넘긴다. 입력 검사와 repeat 기본값, 범위는 그쪽이 정한다.
     * Agent 의 노드는 skillops-agent/skillops_agent/nodes.py 의 _run_scenarios 로만 이 도구를 부르고, 결과는
     * skillops-agent/skillops_agent/contracts.py 의 validate_report 가 검사한다. Agent 는 재현 주문을 replay_run_ids 가
     * 아니라 scenario_ids 에 f-&lt;execution_id&gt; 로 넣는다.
     */
    @McpTool(name = "run_scenarios", description = """
            연습 공간의 skill 로 시험 주문을 모의 실행하고 기대 결과와 비교해 채점한다.
            같은 주문을 repeat 번(기본 2) 돌려 모두 통과해야 합격이다. 실제 결제·배송은 일어나지 않는다.
            scenario_ids 와 replay_run_ids 가운데 하나는 넣어야 한다.
            재현 주문은 주문 Agent 에서 실패했던 실제 주문을 다시 돌리는 것이다. 오류(FAILED)로 멈추지 않으면 합격이다.
            scenario_ids 에 f-<execution_id> 로 넣거나, replay_run_ids 에 execution_id(UUID) 또는 run_id(r_ + 32자리)로 넣는다.
            재현 주문은 skillops.flow-runner=harness 일 때만 된다. 이때는 연습 공간을 snapshot_id 로 만들어야 주문 Agent 에서 돌릴 수 있다.
            돌려주는 값: {workspace_id, all_passed, results:[{scenario_id, replay_of_run_id, passed, run_ids:[run_id],
            failures:[{run_id, skill, reason}]}]}. results 는 scenario_ids, replay_run_ids 순서다.
            재현 주문의 scenario_id 는 f-<execution_id>, replay_of_run_id 는 넣은 실행 번호다. 기준 주문이면 replay_of_run_id 는 null 이다.
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    public CallToolResult runScenarios(
            @McpToolParam(description = "create_workspace 가 돌려준 연습 공간 번호") String workspace_id,
            @McpToolParam(required = false,
                    description = "돌릴 시험 주문 번호 목록. 기준 주문은 g-01~g-06, 재현 주문은 f-<execution_id>")
            List<String> scenario_ids,
            @McpToolParam(required = false,
                    description = "재현할 실패 실행 번호 목록. execution_id(UUID) 또는 run_id(r_ + 32자리 16진수)")
            List<String> replay_run_ids,
            @McpToolParam(required = false,
                    description = "이번 시험에만 모든 주문에 덧붙이는 확인 조건. 예: inventory.예약가능수량 != null")
            List<String> extra_checks,
            @McpToolParam(required = false, description = "같은 주문을 돌릴 횟수(1~5, 기본 2)") Integer repeat) {
        return WorkspaceToolResults.run(
                () -> scenarioRunner.run(workspace_id, scenario_ids, replay_run_ids, extra_checks, repeat));
    }
}
