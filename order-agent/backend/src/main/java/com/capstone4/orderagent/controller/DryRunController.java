package com.capstone4.orderagent.controller;

import java.util.*;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.harness.AgentException;
import com.capstone4.orderagent.service.DryRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dry-runs")
public class DryRunController {
    private final DryRunService service;private final boolean demo;
    public DryRunController(DryRunService service,@Value("${order-agent.demo-enabled}") boolean demo){this.service=service;this.demo=demo;}
    public record DryRunRequest(@NotNull UUID snapshotId,@NotNull @Valid CreateOrderRequest order){}
    /** 후보 snapshot으로 시험 주문을 돌린다. 재고는 되돌리고, 실행 기록은 mode=dry_run이라 실패 목록(live)에 나오지 않는다. */
    @PostMapping public Map<String,Object> run(@Valid @RequestBody DryRunRequest request){
        if(!demo)throw new AgentException("SNAPSHOT_NOT_FOUND");
        return service.run(request.snapshotId(),request.order());
    }
}
