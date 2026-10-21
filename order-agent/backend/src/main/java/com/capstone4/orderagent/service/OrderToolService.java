package com.capstone4.orderagent.service;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.adapter.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;
import com.capstone4.orderagent.harness.*;
import com.capstone4.orderagent.repository.*;
import com.capstone4.orderagent.trace.ExecutionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.capstone4.orderagent.entity.enums.ProcessingStepType.*;
import static com.capstone4.orderagent.entity.enums.ProcessingStepStatus.*;

/** Each public business operation is a short transaction. No model calls here. */
@Service
public class OrderToolService {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final InventoryMockAdapter inventory;
    private final PaymentMockAdapter payments;
    private final ShippingMockAdapter shipping;
    private final NotificationMockAdapter notifications;
    private final ExecutionRepository evidence;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public OrderToolService(OrderRepository orders,ProductRepository products,InventoryMockAdapter inventory,
            PaymentMockAdapter payments,ShippingMockAdapter shipping,NotificationMockAdapter notifications,
            ExecutionRepository evidence,JdbcTemplate jdbc,Clock clock){
        this.orders=orders;this.products=products;this.inventory=inventory;this.payments=payments;
        this.shipping=shipping;this.notifications=notifications;this.evidence=evidence;this.jdbc=jdbc;this.clock=clock;
    }
    public record Initial(Map<String,Object> context, UUID requestedSnapshotId, boolean dryRun) {}
    @Transactional(readOnly=true)
    public Initial initial(UUID id){var o=orders.findById(id).orElseThrow();return new Initial(context(o),o.getRequestedSnapshotId(),o.isDryRun());}
    @Transactional(readOnly=true)
    public String status(UUID id){return orders.findById(id).orElseThrow().getStatus().name();}
    private Map<String,Object> context(Order o){
        var c=new LinkedHashMap<String,Object>();c.put("order.id",o.getId().toString());c.put("order.customer",o.getCustomer());
        c.put("order.items",o.getItems().stream().map(i->Map.of("sku",i.getSku(),"qty",i.getQuantity())).toList());
        c.put("order.payment_method",o.getPaymentMethod());c.put("order.shipping",Map.of("country",o.getShippingCountry(),"express",o.isShippingExpress()));
        c.put("order.total",o.getTotalAmount());return c;
    }
    @Transactional(timeout=10)
    public Map<String,Object> execute(UUID executionId,UUID orderId,String skill,String tool,Map<String,Object> arguments){
        long started=System.nanoTime();
        var o=orders.lockById(orderId).orElseThrow();
        Integer running=jdbc.queryForObject("select count(*) from agent_executions where id=? and order_id=? and completion='RUNNING'",Integer.class,executionId,orderId);
        if(running==null||running!=1)throw new AgentException("EXECUTION_NOT_RUNNING");
        String hash=Json.hash(arguments);
        var cached=evidence.cached(executionId,tool,hash);
        if(cached.isPresent()){
            evidence.append(executionId,"TOOL_REPLAY",skill,Map.of("tool",tool,"arguments",arguments),clock.instant());
            return Json.object(cached.get());
        }
        if(o.getStatus()!=OrderStatus.PROCESSING)throw new AgentException("ORDER_NOT_PROCESSING");
        if(!Json.hash(ToolCatalog.arguments(tool,context(o))).equals(hash))throw new AgentException("INVALID_TOOL_ARGUMENTS");
        var output=new LinkedHashMap<String,Object>();
        var now=clock.instant();
        switch(tool){
            case "order.inspect" -> output.putAll(context(o));
            case "inventory.reserve" -> {
                var quantities=quantities(o);var locked=products.lockBySkus(quantities.keySet());
                int available=locked.stream().mapToInt(p->p.getStockQuantity()).min().orElse(0);
                boolean reserved=inventory.reserve(locked,quantities);
                output.put("inventory.available",reserved);output.put("inventory.reservable_quantity",available);output.put("inventory.reserved",reserved);
                if(reserved){o.reserved();step(o,INVENTORY,SUCCESS,null,"모든 상품의 재고 예약 완료",now);}
                else{o.reject();step(o,INVENTORY,REJECTED,"OUT_OF_STOCK","재고 부족으로 전체 예약을 거절했습니다.",now);skip(o,PAYMENT,SHIPPING,NOTIFICATION);}
            }
            case "payment.charge" -> {
                var payment=payments.pay(orderId,o.getCustomer(),o.getTotalAmount(),o.getPaymentMethod(),o.isInventoryReserved());
                output.put("payment.status",payment.paid()?"PAID":"DECLINED");
                if(payment.paid()){
                    o.paid(payment.reference());output.put("payment.id",payment.reference());
                    step(o,PAYMENT,SUCCESS,null,"Mock 결제 승인 완료",now);
                }else{
                    step(o,PAYMENT,REJECTED,"PAYMENT_DECLINED","Mock 결제가 거절되었습니다.",now);
                    release(o,executionId,"payment", "PAYMENT_DECLINED");
                    o.hold();skip(o,SHIPPING,NOTIFICATION);
                }
            }
            case "shipping.reserve" -> {
                var result=shipping.reserve(orderId,o.getShippingCountry(),o.isShippingExpress(),o.getPaymentStatus()==PaymentStatus.PAID,o.isInventoryReserved());
                o.shipped(result.reference(),result.estimatedDeliveryDate());output.put("shipping.id",result.reference());output.put("shipping.eta",result.estimatedDeliveryDate().toString());
                step(o,SHIPPING,SUCCESS,null,"Mock 배송 예약 완료",now);
            }
            case "notify.send" -> {
                notifications.send(orderId,o.getCustomer(),o.getShipmentReference());o.notified();
                output.put("notify.sent",true);output.put("notify.channel","mock");
                step(o,NOTIFICATION,SUCCESS,null,"Mock 성공 알림 기록 완료",now);
            }
            default -> throw new AgentException("UNKNOWN_TOOL");
        }
        var result=Map.<String,Object>of("context",output,"final_status",o.getStatus().name());
        // Result evidence and the business effects commit together.
        evidence.operation(executionId,tool,hash,result,clock.instant());
        evidence.append(executionId,"TOOL_RESULT",skill,Map.of("tool",tool,"arguments",arguments,"result",result,
            "elapsed_ms",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)),clock.instant());
        orders.flush();return result;
    }
    @Transactional(timeout=10)
    public void complete(UUID orderId,UUID executionId,long elapsed){
        var order=orders.lockById(orderId).orElseThrow();order.complete();orders.flush();
        evidence.finish(executionId,"COMPLETED","SHIPPED",null,elapsed,clock.instant());
        evidence.append(executionId,"EXECUTION_FINISHED",null,Map.of("completion","COMPLETED","final_status","SHIPPED"),clock.instant());
    }
    @Transactional(timeout=10)
    public void compensateUnpaid(UUID orderId,UUID executionId,String reason){
        var o=orders.lockById(orderId).orElseThrow();
        if(o.isInventoryReserved()&&o.getPaymentStatus()!=PaymentStatus.PAID)release(o,executionId,null,reason);
    }
    private void release(Order o,UUID executionId,String skill,String reason){
        var args=Map.<String,Object>of("order_id",o.getId().toString());
        evidence.append(executionId,"TOOL_REQUEST",skill,Map.of("tool","inventory.release","arguments",args,"reason",reason),clock.instant());
        inventory.release(products.lockBySkus(quantities(o).keySet()),quantities(o));o.released();
        step(o,RELEASE,SUCCESS,null,"예약 재고를 모두 복원했습니다.",clock.instant());
        evidence.append(executionId,"TOOL_RESULT",skill,Map.of("tool","inventory.release","arguments",args,
            "result",Map.of("inventory.reserved",false)),clock.instant());
    }
    @Transactional public void noSelection(UUID id){var o=orders.lockById(id).orElseThrow();o.noSelection();skip(o,INVENTORY,PAYMENT,SHIPPING,NOTIFICATION);}
    private SortedMap<String,Integer> quantities(Order o){var q=new TreeMap<String,Integer>();for(var i:o.getItems())q.put(i.getSku(),i.getQuantity());return q;}
    private void step(Order o,ProcessingStepType type,ProcessingStepStatus state,String code,String message,Instant started){o.addStep(type,state,code,message,started,clock.instant());}
    private void skip(Order o,ProcessingStepType... steps){for(var s:steps)step(o,s,SKIPPED,"UPSTREAM_REJECTED","앞 단계 거절로 실행하지 않았습니다.",clock.instant());}
}
