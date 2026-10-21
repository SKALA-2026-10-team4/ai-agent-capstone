package com.capstone4.orderagent.service;

import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.adapter.InventoryMockAdapter;
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

/** Runs an order against a snapshot like a live order, then returns every reserved unit. The order and trace stay as evidence. */
@Service
public class DryRunService {
    private static final Logger log = LoggerFactory.getLogger(DryRunService.class);
    private final OrderLifecycleService lifecycle;private final OrderProcessingService processor;private final SnapshotService snapshots;
    private final OrderRepository orders;private final ProductRepository products;private final InventoryMockAdapter inventory;
    private final TraceService traces;private final TransactionTemplate tx;private final Clock clock;
    public DryRunService(OrderLifecycleService lifecycle,OrderProcessingService processor,SnapshotService snapshots,OrderRepository orders,
            ProductRepository products,InventoryMockAdapter inventory,TraceService traces,PlatformTransactionManager transactions,Clock clock){
        this.lifecycle=lifecycle;this.processor=processor;this.snapshots=snapshots;this.orders=orders;this.products=products;
        this.inventory=inventory;this.traces=traces;this.tx=new TransactionTemplate(transactions);this.tx.setTimeout(10);this.clock=clock;
    }
    /** No parent transaction, like OrderService. The top-level snapshotId replaces order.snapshot_id. A FAILED run is a result, not an error. */
    public Map<String,Object> run(UUID snapshotId,CreateOrderRequest request){
        snapshots.get(snapshotId);
        var input=NormalizedOrder.from(new CreateOrderRequest(request.customer(),request.items(),request.paymentMethod(),request.shipping(),snapshotId));
        // accept() applies useSnapshot(snapshotId); the dry-run flag commits with the order row.
        UUID id=tx.execute(s->{var o=orders.findById(lifecycle.accept(UUID.randomUUID(),input).orderId()).orElseThrow();o.markDryRun();return o.getId();});
        ErrorCode error=ErrorCode.INTERNAL_ERROR;
        if(lifecycle.claim(id)){
            try{processor.process(id);}
            catch(RuntimeException e){
                var stage=e instanceof ProcessingFailure f?f.getStep():ProcessingStepType.RECOVERY;
                if(OrderService.hasDatabaseCause(e))error=ErrorCode.PROCESSING_UNAVAILABLE;
                lifecycle.fail(id,stage,error.name());
                log.info("Dry run order {} failed at {} ({})",id,stage,e.getClass().getSimpleName());
            }
        }
        UUID execution=tx.execute(s->cleanup(id));
        if(execution==null)throw new BusinessException(error,id);
        var trace=traces.get(execution);var result=new LinkedHashMap<String,Object>();
        for(var key:List.of("execution_id","run_id","order_id","final_status","completion"))result.put(key,trace.get(key));
        return result;
    }
    /** Releases whatever the run still holds, PAID or not: a SHIPPED dry run keeps its reservation until here. */
    private UUID cleanup(UUID id){
        var o=orders.lockById(id).orElseThrow();
        if(o.isInventoryReserved()){
            var qty=new TreeMap<String,Integer>();for(var i:o.getItems())qty.put(i.getSku(),i.getQuantity());
            inventory.release(products.lockBySkus(qty.keySet()),qty);o.released();
            var now=clock.instant();o.addStep(ProcessingStepType.RELEASE,ProcessingStepStatus.SUCCESS,"DRY_RUN_CLEANUP","모의 실행이 끝나 예약 재고를 모두 복원했습니다.",now,now);
        }
        return o.getExecutionId();
    }
}
