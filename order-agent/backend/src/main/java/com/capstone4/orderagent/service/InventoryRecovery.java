package com.capstone4.orderagent.service;

import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;
import com.capstone4.orderagent.adapter.InventoryMockAdapter;
import com.capstone4.orderagent.repository.ProductRepository;
import org.springframework.stereotype.Component;

/** Called only inside startup recovery's transaction, before accepting traffic. */
@Component
public class InventoryRecovery {
    private final ProductRepository products;private final InventoryMockAdapter inventory;private final Clock clock;private final com.capstone4.orderagent.trace.ExecutionRepository evidence;
    public InventoryRecovery(ProductRepository products,InventoryMockAdapter inventory,Clock clock,com.capstone4.orderagent.trace.ExecutionRepository evidence){this.products=products;this.inventory=inventory;this.clock=clock;this.evidence=evidence;}
    public void releaseUnpaid(Order order){
        if(!order.isInventoryReserved()||order.getPaymentStatus()==PaymentStatus.PAID)return;
        var qty=new TreeMap<String,Integer>();for(var i:order.getItems())qty.put(i.getSku(),i.getQuantity());
        if(order.getExecutionId()!=null)evidence.append(order.getExecutionId(),"TOOL_REQUEST",null,
            Map.of("tool","inventory.release","reason","PROCESS_INTERRUPTED","arguments",Map.of("order_id",order.getId().toString())),clock.instant());
        inventory.release(products.lockBySkus(qty.keySet()),qty);order.released();
        if(order.getExecutionId()!=null)evidence.append(order.getExecutionId(),"TOOL_RESULT",null,
            Map.of("tool","inventory.release","result",Map.of("inventory.reserved",false)),clock.instant());
        var now=clock.instant();order.addStep(ProcessingStepType.RELEASE,ProcessingStepStatus.SUCCESS,"PROCESS_INTERRUPTED","중단된 실행의 미결제 재고 예약을 복원했습니다.",now,now);
    }
}
