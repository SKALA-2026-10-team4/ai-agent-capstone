package com.capstone4.skillops.api;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.capstone4.skillops.scenario.RunLedger;
import com.capstone4.skillops.scenario.ScenarioCatalog;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 화면이 실행 번호(r_…)를 사람이 읽을 이름으로 바꿀 때 쓰는 모의 실행 장부 조회 REST API 다.
 * 제안서의 '근거 실행', '원인 확인 실행' 칸에는 실행 번호만 있어서, 담당자가 어느 시험 주문을 돌려 통과했는지 알 수 없다.
 * GET /api/runs?ids=r_a,r_b 는 장부(service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java)에서 찾은 줄마다
 * 시험 주문 번호, 시험 주문 이름, 통과 여부를 돌려주고, 장부에 없는 번호는 응답에서 뺀다.
 * 시험 주문 이름은 service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java 에서 읽고, 실패 재현 주문(f-…)처럼
 * 목록에 없는 번호면 null 이다. 로그인한 사람만 읽는다(service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java).
 * 화면은 frontend/src/api/runs.js 의 getRuns() 로 부르고, frontend/src/views/ProposalView.vue 가 쓴다.
 */
@RestController
public class RunController {

    /** 한 번에 찾는 실행 번호의 상한이다. 제안서 한 건의 근거 실행은 후보와 원인 실험을 모두 합쳐도 이보다 적다. */
    static final int MAX_IDS = 200;
    /** 실행 번호 모양이다. order-agent/backend/src/main/java/com/capstone4/orderagent/trace/RunId.java 가 만드는 모양과 같다. */
    private static final Pattern RUN_ID = Pattern.compile("r_[0-9a-f]{32}");

    private final RunLedger ledger;
    private final ScenarioCatalog catalog;

    /** Spring 이 장부(JpaRunLedger)와 시험 주문 목록 빈을 넣어 만든다. */
    public RunController(RunLedger ledger, ScenarioCatalog catalog) {
        this.ledger = ledger;
        this.catalog = catalog;
    }

    /**
     * 실행 번호 목록을 장부에서 찾아 {items: [...]} 로 돌려준다. 순서는 요청한 순서를 따르고, 같은 번호를 두 번 보내도 한 번만 담는다.
     * 이름을 붙이는 조회라서 모양이 틀린 번호는 오류로 막지 않고 장부에 없는 번호처럼 뺀다. 앞에서부터 MAX_IDS 개까지만 찾는다.
     */
    @GetMapping("/api/runs")
    public Map<String, List<RunView>> find(@RequestParam List<String> ids) {
        return Map.of("items", ids.stream().distinct().filter(id -> RUN_ID.matcher(id).matches()).limit(MAX_IDS)
                .flatMap(id -> ledger.find(id).stream())
                .map(run -> new RunView(run.runId(), run.scenarioId(), scenarioName(run.scenarioId()), run.passed()))
                .toList());
    }

    /** 시험 주문 목록에 있는 번호면 그 이름을, 없으면 null 을 돌려준다. */
    private String scenarioName(String scenarioId) {
        return catalog.ids().contains(scenarioId) ? catalog.get(scenarioId).name() : null;
    }

    /** 응답 한 줄이다. JSON 칸 이름은 snake_case 이고, frontend/src/api/runs.js 의 getRuns() 가 이 모양을 받는다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunView(String runId, String scenarioId, String scenarioName, boolean passed) {
    }
}
