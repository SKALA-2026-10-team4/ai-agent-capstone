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
 * C-2 의 쓰기·판정 도구 가운데 ⑦ create_workspace, ⑧ apply_patch, ⑨ run_scenarios.
 * 입출력 이름은 계약 5 v1(docs/contracts/mcp-tools-input-output.md)을 따른다. B 의 skillops_agent/tools.py(#52)가 이 이름으로 부른다.
 * 파라미터 이름이 곧 도구 입력 키라서 snake_case 로 쓴다(@McpToolParam 에 이름 속성이 없다).
 * 오류는 C-1 도구와 같은 {code, message, retryable, upstream_status, snapshot_id} 모양이다.
 */
@Component
public class WorkspaceTools {

    private final WorkspaceService workspaces;
    private final ScenarioRunner scenarioRunner;

    public WorkspaceTools(WorkspaceService workspaces, ScenarioRunner scenarioRunner) {
        this.workspaces = workspaces;
        this.scenarioRunner = scenarioRunner;
    }

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

    // TODO(C-2/B): 재현 주문(replay_run_ids)은 아직 없다. 원본 실행 조회(주문 Agent #39 API)·재현 등록 계약을 정한 뒤 더한다.
    @McpTool(name = "run_scenarios", description = """
            연습 공간의 skill 로 시험 주문을 모의 실행하고 기대 결과와 비교해 채점한다.
            같은 주문을 repeat 번(기본 2) 돌려 모두 통과해야 합격이다. 실제 결제·배송은 일어나지 않는다.
            돌려주는 값: {workspace_id, all_passed, results:[{scenario_id, replay_of_run_id, passed, run_ids:[run_id],
            failures:[{run_id, skill, reason}]}]}. 재현 주문은 아직 없어서 replay_of_run_id 는 항상 null 이다.
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    public CallToolResult runScenarios(
            @McpToolParam(description = "create_workspace 가 돌려준 연습 공간 번호") String workspace_id,
            @McpToolParam(description = "돌릴 시험 주문 번호 목록. 기준 주문은 g-01~g-06") List<String> scenario_ids,
            @McpToolParam(required = false,
                    description = "이번 시험에만 모든 주문에 덧붙이는 확인 조건. 예: inventory.예약가능수량 != null")
            List<String> extra_checks,
            @McpToolParam(required = false, description = "같은 주문을 돌릴 횟수(1~5, 기본 2)") Integer repeat) {
        return WorkspaceToolResults.run(() -> scenarioRunner.run(workspace_id, scenario_ids, extra_checks, repeat));
    }
}
