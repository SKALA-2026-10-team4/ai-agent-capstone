package com.capstone4.orderagent.service;

import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.entity.enums.*;
import com.capstone4.orderagent.exception.*;
import com.capstone4.orderagent.repository.*;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.workspace.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 후보 snapshot 으로 주문 하나를 live 주문처럼 실행한 뒤 예약한 재고를 모두 되돌린다. 주문과 trace 는 증거로 남긴다.
 * 실행 기록은 mode 가 dry_run 이라 진단 대상인 live 실패 목록에 나오지 않는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java 가 POST /api/dry-runs 를 이 서비스로 보내고,
 * 모듈 밖에서는 service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 dryRun 이 시험 실행 판정에 쓴다.
 */
@Service
public class DryRunService {
    private static final Logger log = LoggerFactory.getLogger(DryRunService.class);
    private final OrderLifecycleService lifecycle;private final OrderProcessingService processor;private final SnapshotService snapshots;
    private final OrderRepository orders;private final InventoryReservations reservations;
    private final TraceService traces;private final TransactionTemplate tx;private final Clock clock;
    /** 협력 서비스와 UTC Clock 을 주입받고, 접수와 정리에 쓸 TransactionTemplate 을 다른 짧은 트랜잭션과 같은 10초 제한으로 만든다. */
    public DryRunService(OrderLifecycleService lifecycle,OrderProcessingService processor,SnapshotService snapshots,OrderRepository orders,
            InventoryReservations reservations,TraceService traces,PlatformTransactionManager transactions,Clock clock){
        this.lifecycle=lifecycle;this.processor=processor;this.snapshots=snapshots;this.orders=orders;
        this.reservations=reservations;this.traces=traces;this.tx=new TransactionTemplate(transactions);this.tx.setTimeout(10);this.clock=clock;
    }
    /**
     * 모의 실행 하나를 돌리고 실행 요약을 돌려준다. OrderService 처럼 부모 트랜잭션이 없다. 요청 최상위의 snapshotId 가 주문 본문의
     * snapshot_id 를 대신하고, 실행이 FAILED 로 끝나도 오류가 아니라 결과로 돌려준다.
     * 순서는 snapshot 확인, 무작위 요청 키로 접수와 dry_run 표시 커밋, claim, 처리, 남은 예약 정리, 실행 요약 반환이다.
     * 반환 Map 의 다섯 키(execution_id, run_id, order_id, final_status, completion)가 응답 계약이며,
     * OrderAgentRunClient.dryRun 은 그 가운데 execution_id, run_id, final_status 를 읽는다.
     * 실행 기록이 아예 없으면(실행을 시작하기 전에 실패) 처리 실패에서 분류한 오류 코드로, 분류가 없으면 INTERNAL_ERROR 로
     * order-agent/backend/src/main/java/com/capstone4/orderagent/exception/BusinessException.java 를 던진다.
     */
    public Map<String,Object> run(UUID snapshotId,CreateOrderRequest request){
        snapshots.get(snapshotId);
        var input=NormalizedOrder.from(new CreateOrderRequest(request.customer(),request.items(),request.paymentMethod(),request.shipping(),snapshotId));
        // accept 가 useSnapshot(snapshotId) 를 적용한다. dry-run 표시는 주문 행과 같은 트랜잭션에서 커밋되므로 Harness 가 읽을 때 이미 dry_run 이다.
        UUID id=tx.execute(s->{var o=orders.findById(lifecycle.accept(UUID.randomUUID(),input).orderId()).orElseThrow();o.markDryRun();return o.getId();});
        ErrorCode error=ErrorCode.INTERNAL_ERROR;
        if(lifecycle.claim(id)){
            var failure=processor.processClaimed(id);
            if(failure.isPresent()){
                error=failure.get().code();
                log.info("Dry run order {} failed at {} ({})",id,failure.get().stage(),failure.get().cause().getClass().getSimpleName());
            }
        }
        UUID execution=tx.execute(s->cleanup(id));
        if(execution==null)throw new BusinessException(error,id);
        var trace=traces.get(execution);var result=new LinkedHashMap<String,Object>();
        for(var key:List.of("execution_id","run_id","order_id","final_status","completion"))result.put(key,trace.get(key));
        return result;
    }
    /**
     * 실행이 아직 잡고 있는 예약 재고를 결제 여부와 무관하게 모두 되돌리고 RELEASE(DRY_RUN_CLEANUP) 단계를 남긴다.
     * SHIPPED 로 끝난 모의 실행도 여기까지 예약을 들고 있다. 주문의 실행 id 를 돌려주며 실행이 시작되지 않았으면 null 이다.
     */
    private UUID cleanup(UUID id){
        var o=orders.lockById(id).orElseThrow();
        if(o.isInventoryReserved()){
            reservations.releaseAll(o);
            var now=clock.instant();o.addStep(ProcessingStepType.RELEASE,ProcessingStepStatus.SUCCESS,"DRY_RUN_CLEANUP","모의 실행이 끝나 예약 재고를 모두 복원했습니다.",now,now);
        }
        return o.getExecutionId();
    }
}
