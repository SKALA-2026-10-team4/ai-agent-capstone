package com.capstone4.orderagent.controller;

import java.util.*;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.service.DryRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

/**
 * POST /api/dry-runs 를 받아 후보 snapshot 으로 주문 하나를 모의 실행한다. SkillOps 가 고친 Skill 묶음이 실제 주문 흐름을 통과하는지
 * 재고를 남기지 않고 확인하는 경로다.
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 dryRun 이 이 경로를 부르고, 그 위에서
 * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 가 시나리오마다 모의 실행을 돌린다.
 * skillops-agent/scripts/b_c_contract_smoke.py 도 연동 확인에 부른다.
 * 실제 처리는 order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java 의 run 이 하고,
 * order-agent.demo-enabled 가 꺼져 있으면 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DemoGuard.java 가
 * SNAPSHOT_NOT_FOUND(404)로 막는다.
 */
@RestController
@RequestMapping("/api/dry-runs")
public class DryRunController {
    private final DryRunService service;private final DemoGuard demo;
    /** Spring 이 모의 실행 서비스와 demo 가드를 주입해 빈으로 만든다. */
    public DryRunController(DryRunService service,DemoGuard demo){this.service=service;this.demo=demo;}
    /**
     * 모의 실행 요청 본문이다. JSON 키는 snapshot_id 와 order 이고 order 는 POST /api/orders 본문과 같은 모양이다.
     * 최상위 snapshot_id 가 order.snapshot_id 를 대신한다. 두 값 모두 필수이며 빠지면 VALIDATION_ERROR(400)다.
     */
    public record DryRunRequest(@NotNull UUID snapshotId,@NotNull @Valid CreateOrderRequest order){}
    /**
     * 후보 snapshot 으로 시험 주문을 끝까지 돌린 뒤 예약한 재고를 모두 되돌린다. 실행 기록은 mode 가 dry_run 이라
     * live 만 모으는 실패 목록(GET /api/executions/failed)에 나오지 않는다.
     * 응답은 execution_id, run_id, order_id, final_status, completion 다섯 키다. 실행이 FAILED 로 끝나도 HTTP 200 이며 실패도 결과로 돌려준다.
     * 실행 기록조차 남지 않은 실패만 order_id 를 담은 500 이나 503 이 된다. 없는 snapshot 이면 주문을 만들기 전에 SNAPSHOT_NOT_FOUND(404)다.
     */
    @PostMapping public Map<String,Object> run(@Valid @RequestBody DryRunRequest request){
        demo.requireEnabled();
        return service.run(request.snapshotId(),request.order());
    }
}
