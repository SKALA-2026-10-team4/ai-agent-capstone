package com.capstone4.orderagent.service;

import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;
import com.capstone4.orderagent.harness.ToolCatalog;
import org.springframework.stereotype.Component;

/**
 * 재시작 복구 중에 결제 전 예약 재고를 되돌리고 그 증거를 남긴다. OrderLifecycleService 의 recover 가 기동 복구 트랜잭션 안에서,
 * 트래픽을 받기 전에만 부른다. 자체 트랜잭션이 없고 InventoryReservations 의 비관적 잠금을 쓰므로 그 트랜잭션 밖에서 부르면 안 된다.
 * 여기서 남기는 TOOL_REQUEST, TOOL_RESULT 는 실행 루프가 남기는 이벤트와 같은 trace 계약에 속한다.
 */
@Component
public class InventoryRecovery {
    private final InventoryReservations reservations;private final Clock clock;private final com.capstone4.orderagent.trace.ExecutionRepository evidence;
    /** 재고 예약 도구, UTC Clock, 실행 기록 저장소를 주입받는다. */
    public InventoryRecovery(InventoryReservations reservations,Clock clock,com.capstone4.orderagent.trace.ExecutionRepository evidence){this.reservations=reservations;this.clock=clock;this.evidence=evidence;}
    /**
     * 예약이 있고 결제가 끝나지 않은 주문만 재고를 되돌린다. 결제가 끝난 주문의 예약은 확정된 결과라 건드리지 않는다.
     * 주문에 실행 기록이 있으면 그 실행에 inventory.release 의 TOOL_REQUEST(reason PROCESS_INTERRUPTED)와 TOOL_RESULT 를 남기고,
     * 주문에는 RELEASE 단계를 남긴다.
     */
    public void releaseUnpaid(Order order){
        if(!order.isInventoryReserved()||order.getPaymentStatus()==PaymentStatus.PAID)return;
        if(order.getExecutionId()!=null)evidence.append(order.getExecutionId(),"TOOL_REQUEST",null,
            Map.of("tool",ToolCatalog.RELEASE_TOOL,"reason","PROCESS_INTERRUPTED","arguments",Map.of("order_id",order.getId().toString())),clock.instant());
        reservations.releaseAll(order);
        if(order.getExecutionId()!=null)evidence.append(order.getExecutionId(),"TOOL_RESULT",null,
            Map.of("tool",ToolCatalog.RELEASE_TOOL,"result",Map.of("inventory.reserved",false)),clock.instant());
        var now=clock.instant();order.addStep(ProcessingStepType.RELEASE,ProcessingStepStatus.SUCCESS,"PROCESS_INTERRUPTED","중단된 실행의 미결제 재고 예약을 복원했습니다.",now,now);
    }
}
