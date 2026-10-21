package com.capstone4.skillops.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.scenario.ScenarioRunReport;
import com.capstone4.skillops.scenario.ScenarioRunner;
import com.capstone4.skillops.workspace.WorkspaceService;
import com.capstone4.skillops.workspace.WorkspaceService.CreatedWorkspace;

/**
 * C-2 의 쓰기·판정 도구 가운데 ⑦ create_workspace, ⑨ run_scenarios. 입출력은 docs/contracts/mcp-tools-input-output.md.
 * 파라미터 이름이 곧 도구 입력 키라서 snake_case 로 쓴다(@McpToolParam 에 이름 속성이 없다).
 * C-1 의 읽기 도구와 파일을 나눈다.
 */
@Component
public class WorkspaceTools {

    private final WorkspaceService workspaces;
    private final ScenarioRunner scenarioRunner;

    public WorkspaceTools(WorkspaceService workspaces, ScenarioRunner scenarioRunner) {
        this.workspaces = workspaces;
        this.scenarioRunner = scenarioRunner;
    }

    // TODO(C-2/B, 계약 확정 후): 서버 start_versions/workspace_id와 Python base/ws_id가 다르다.
    // Python 요청과 응답 파싱(nodes.py 포함)을 함께 변경하고 stub도 동일한 계약으로 맞춘다.
    @McpTool(name = "create_workspace",
            description = "운영 중인 skill 은 그대로 두고 연습용 복사본(연습 공간)을 만든다. "
                    + "돌려준 workspace_id 를 apply_patch, run_scenarios 에 넣는다.")
    public CreatedWorkspace createWorkspace(
            @McpToolParam(required = false, description = "skill 이름 → 시작 버전. 적지 않은 skill 은 운영 버전")
            Map<String, Integer> start_versions) {
        return workspaces.create(start_versions);
    }

    // TODO(C-2/B, 계약 확정 후): 서버 workspace_id/scenario_ids/extra_checks/repeat와 Python ws_id/k가 다르다.
    // v1 replay_run_ids는 이 시그니처에 아직 없다. 원본 실행 조회·재현 등록 계약을 추가한 뒤 호출한다.
    // 응답 객체의 results/run_ids/failures.skill/reason을 Python의 결과 배열/runs/step/error 파싱과 맞춘다.
    @McpTool(name = "run_scenarios",
            description = "연습 공간의 skill 로 시험 주문을 모의 실행하고 기대 결과와 비교해 채점한다. "
                    + "같은 주문을 repeat 번(기본 2) 돌려 모두 통과해야 합격이다. 실제 결제·배송은 일어나지 않는다.")
    public ScenarioRunReport runScenarios(
            @McpToolParam(description = "create_workspace 가 돌려준 연습 공간 번호") String workspace_id,
            @McpToolParam(description = "돌릴 시험 주문 번호 목록. 기준 주문은 g-01~g-06") List<String> scenario_ids,
            @McpToolParam(required = false,
                    description = "이번 시험에만 모든 주문에 덧붙이는 확인 조건. 예: inventory.예약가능수량 != null")
            List<String> extra_checks,
            @McpToolParam(required = false, description = "같은 주문을 돌릴 횟수(1~5, 기본 2)") Integer repeat) {
        return scenarioRunner.run(workspace_id, scenario_ids, extra_checks, repeat);
    }
}
