package com.capstone4.orderagent.service;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.adapter.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;
import com.capstone4.orderagent.harness.*;
import com.capstone4.orderagent.repository.*;
import com.capstone4.orderagent.trace.ExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.capstone4.orderagent.entity.enums.ProcessingStepType.*;
import static com.capstone4.orderagent.entity.enums.ProcessingStepStatus.*;

/**
 * 주문 Agent 의 업무 도구를 실행하는 서비스다. 공개 업무 메서드 하나가 짧은 트랜잭션 하나이고 모델 호출은 여기서 하지 않는다.
 * 모델 응답을 기다리는 동안 DB 잠금을 잡지 않도록 실행 루프는 트랜잭션 없이 돌고, 실제 업무 효과는 여기서 도구 단위로 커밋한다.
 * 실행 루프는 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 다.
 * execute 는 업무 효과와 같은 트랜잭션에 멱등 결과와 TOOL_RESULT 를 쓰므로, TOOL_RESULT 가 있으면 그 업무 효과도 커밋된 것이다.
 * OrderHarness 가 initial, status, noSelection, complete, compensateUnpaid 를 부르고
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolDispatcher.java 가 execute 를 부른다.
 *
 * 도구를 하나 더하려면 applyTool 의 switch 에 case 와 처리 메서드를 더하고
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 의 BINDINGS 에 한 줄을 더한다.
 * 거절 뒤 건너뛸 단계는 order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java 의
 * after 가 BUSINESS_FLOW 순서로 정한다. 단계 추가의 전체 순서는 ToolCatalog 클래스 주석에 있다.
 * 여기서 남기는 TOOL_REQUEST, TOOL_RESULT, TOOL_REPLAY, EXECUTION_FINISHED 의 이름과 data 키는 trace 계약이다.
 * frontend/src/utils/eventView.js 가 이 이벤트들의 화면 문장을 만들고, skillops-agent/skillops_agent/contracts.py 는
 * TOOL_REQUEST 의 tool 을 읽어 실제로 요청한 도구 목록을 만든다.
 */
@Service
public class OrderToolService {
    private final OrderRepository orders;
    private final InventoryReservations reservations;
    private final PaymentMockAdapter payments;
    private final ShippingMockAdapter shipping;
    private final NotificationMockAdapter notifications;
    private final ExecutionRepository evidence;
    private final Clock clock;
    /** 주문 저장소, 재고 예약, 결제와 배송과 알림 Mock, 실행 기록 저장소, UTC Clock 을 주입받는다. */
    public OrderToolService(OrderRepository orders,InventoryReservations reservations,
            PaymentMockAdapter payments,ShippingMockAdapter shipping,NotificationMockAdapter notifications,
            ExecutionRepository evidence,Clock clock){
        this.orders=orders;this.reservations=reservations;this.payments=payments;
        this.shipping=shipping;this.notifications=notifications;this.evidence=evidence;this.clock=clock;
    }
    /**
     * Harness 가 실행을 시작할 때 읽는 주문 정보다. context 는 주문 컨텍스트, requestedSnapshotId 는 주문에 지정한 snapshot 이고
     * 지정하지 않았으면 null 이다. dryRun 은 모의 실행 여부다.
     */
    public record Initial(Map<String,Object> context, UUID requestedSnapshotId, boolean dryRun) {}
    /**
     * 주문의 초기 컨텍스트, 요청 snapshot, 모의 실행 여부를 읽는다. OrderHarness.execute 가 실행을 시작할 때 부르고,
     * 주문이 없으면 NoSuchElementException 이다.
     */
    @Transactional(readOnly=true)
    public Initial initial(UUID id){var o=orders.findById(id).orElseThrow();return new Initial(context(o),o.getRequestedSnapshotId(),o.isDryRun());}
    /** 주문의 현재 상태 이름을 읽는다. OrderHarness 가 업무 중단이나 실패로 실행을 닫을 때 final_status 로 쓴다. */
    @Transactional(readOnly=true)
    public String status(UUID id){return orders.findById(id).orElseThrow().getStatus().name();}
    /**
     * 주문에서 단계 사이에 넘기는 컨텍스트를 만든다. 키는 order.id, order.customer, order.items(sku, qty), order.payment_method,
     * order.shipping(country, express), order.total 이다. order.inspect 도구의 출력과 서버 인자 대조의 입력도 이 컨텍스트다.
     * 키를 더하거나 빼면 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java 의 INITIAL_INPUT_KEYS 와
     * HIDDEN_INITIAL_KEYS, 그리고 그 주석이 가리키는 복제본을 함께 맞춘다.
     */
    private Map<String,Object> context(Order o){
        var c=new LinkedHashMap<String,Object>();c.put("order.id",o.getId().toString());c.put("order.customer",o.getCustomer());
        c.put("order.items",o.getItems().stream().map(i->Map.of("sku",i.getSku(),"qty",i.getQuantity())).toList());
        c.put("order.payment_method",o.getPaymentMethod());c.put("order.shipping",Map.of("country",o.getShippingCountry(),"express",o.isShippingExpress()));
        c.put("order.total",o.getTotalAmount());return c;
    }
    /**
     * 단계 Skill 이 요청한 도구 하나를 실행한다. 먼저 주문 행을 잠그고, 실행이 아직 이 주문의 RUNNING 실행인지 본다(EXECUTION_NOT_RUNNING).
     * 같은 실행에서 같은 도구를 이미 끝냈으면 저장한 결과를 그대로 돌려준다. 재생을 주문 상태 확인보다 먼저 하므로, 실행이 아직 RUNNING 이면
     * 주문을 REJECTED 나 HOLD 로 끝낸 도구를 같은 인자로 다시 요청해도 같은 결과가 돌아온다.
     * 처음 실행이면 주문이 PROCESSING 인지(ORDER_NOT_PROCESSING), 요청 인자가 서버가 만든 인자와 같은지(INVALID_TOOL_ARGUMENTS) 확인한 뒤
     * 업무 효과를 낸다. 결과의 context 는 다음 단계로 넘길 값이고 final_status 는 도구 실행 뒤 주문 상태이며,
     * OrderHarness 는 final_status 로 다음 단계로 갈지 정한다. 트랜잭션 제한 시간은 10초다.
     */
    @Transactional(timeout=10)
    public Map<String,Object> execute(UUID executionId,UUID orderId,String skill,String tool,Map<String,Object> arguments){
        long started=System.nanoTime();
        var o=orders.lockById(orderId).orElseThrow();
        requireRunning(executionId,orderId);
        String hash=Json.hash(arguments);
        var replayed=replay(executionId,skill,tool,arguments,hash);
        if(replayed.isPresent())return replayed.get();
        if(o.getStatus()!=OrderStatus.PROCESSING)throw new AgentException("ORDER_NOT_PROCESSING");
        requireServerArguments(o,tool,hash);
        var output=applyTool(o,executionId,tool);
        var result=Map.<String,Object>of("context",output,"final_status",o.getStatus().name());
        // 결과 증거(멱등 결과와 TOOL_RESULT)는 업무 효과와 같은 트랜잭션에서 함께 커밋된다.
        evidence.operation(executionId,tool,hash,result,clock.instant());
        evidence.append(executionId,"TOOL_RESULT",skill,Map.of("tool",tool,"arguments",arguments,"result",result,
            "elapsed_ms",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)),clock.instant());
        // flush 로 엔티티 변경의 제약 위반을 이 메서드 안에서 드러내, 실패하면 증거까지 함께 되돌린다.
        orders.flush();return result;
    }
    /** 실행이 아직 이 주문의 RUNNING 실행인지 확인한다. 이미 끝난 실행이나 다른 주문의 실행이면 업무 효과 없이 EXECUTION_NOT_RUNNING 으로 멈춘다. */
    private void requireRunning(UUID executionId,UUID orderId){
        if(!evidence.isRunningFor(executionId,orderId))throw new AgentException("EXECUTION_NOT_RUNNING");
    }
    /**
     * 같은 실행에서 이미 끝낸 도구면 저장된 결과를 TOOL_REPLAY 와 함께 돌려준다. 같은 도구에 다른 인자면 IDEMPOTENCY_CONFLICT 다.
     * 멱등 키는 실행 id 와 도구 이름(agent_tool_operations 의 기본 키)이고, 재생할 때는 TOOL_RESULT 를 새로 남기지 않는다.
     */
    private Optional<Map<String,Object>> replay(UUID executionId,String skill,String tool,Map<String,Object> arguments,String hash){
        var cached=evidence.cached(executionId,tool,hash);
        if(cached.isEmpty())return Optional.empty();
        evidence.append(executionId,"TOOL_REPLAY",skill,Map.of("tool",tool,"arguments",arguments),clock.instant());
        return Optional.of(Json.object(cached.get()));
    }
    /**
     * 요청 인자가 서버 컨텍스트로 만든 도구 인자와 정규화 해시까지 같은지 확인한다. 주문 전체 컨텍스트로 ToolCatalog.arguments 를 다시 만들어
     * 비교하므로, 모델이 주문 id, 금액, 상품, 수량을 지어내도 업무 효과가 나지 않고 INVALID_TOOL_ARGUMENTS 로 멈춘다.
     */
    private void requireServerArguments(Order o,String tool,String hash){
        if(!Json.hash(ToolCatalog.arguments(tool,context(o))).equals(hash))throw new AgentException("INVALID_TOOL_ARGUMENTS");
    }
    /**
     * 도구 하나의 업무 효과를 실행하고 다음 단계 컨텍스트로 넘길 출력을 만든다. 도구를 더할 때 case 를 더하는 곳이다.
     * ToolCatalog 의 BINDINGS 에는 있는데 여기 case 가 없는 도구는 UNKNOWN_TOOL 로 멈춘다.
     */
    private Map<String,Object> applyTool(Order o,UUID executionId,String tool){
        var output=new LinkedHashMap<String,Object>();
        var now=clock.instant();
        switch(tool){
            case "order.inspect" -> output.putAll(context(o));
            case "inventory.reserve" -> reserveInventory(o,output,now);
            case "payment.charge" -> chargePayment(o,executionId,output,now);
            case "shipping.reserve" -> reserveShipping(o,output,now);
            case "notify.send" -> sendNotification(o,output,now);
            default -> throw new AgentException("UNKNOWN_TOOL");
        }
        return output;
    }
    /**
     * 주문 항목 전체를 예약한다. 하나라도 부족하면 REJECTED 로 바꾸고 뒤 업무 단계를 SKIPPED 로 남긴다.
     * 출력은 inventory.available, inventory.reservable_quantity(잠근 직후 가장 적은 재고), inventory.reserved 다.
     */
    private void reserveInventory(Order o,Map<String,Object> output,Instant now){
        var reservation=reservations.reserveAll(o);
        boolean reserved=reservation.reserved();
        output.put("inventory.available",reserved);output.put("inventory.reservable_quantity",reservation.reservableQuantity());output.put("inventory.reserved",reserved);
        if(reserved){o.reserved();step(o,INVENTORY,SUCCESS,null,"모든 상품의 재고 예약 완료",now);}
        else{o.reject();step(o,INVENTORY,REJECTED,"OUT_OF_STOCK","재고 부족으로 전체 예약을 거절했습니다.",now);skip(o,ProcessingStepType.after(INVENTORY));}
    }
    /**
     * Mock 결제를 요청한다. 거절되면 예약을 되돌리고 HOLD 로 바꾼 다음, 결제 뒤의 업무 단계를 SKIPPED 로 남긴다.
     * 출력은 payment.status 와 승인됐을 때의 payment.id 다. 거절 뒤 재고 해제는 모델이 요청하지 않아도 서버가 실행한다.
     */
    private void chargePayment(Order o,UUID executionId,Map<String,Object> output,Instant now){
        var payment=payments.pay(o.getId(),o.getCustomer(),o.getTotalAmount(),o.getPaymentMethod(),o.isInventoryReserved());
        output.put("payment.status",payment.paid()?"PAID":"DECLINED");
        if(payment.paid()){
            o.paid(payment.reference());output.put("payment.id",payment.reference());
            step(o,PAYMENT,SUCCESS,null,"Mock 결제 승인 완료",now);
        }else{
            step(o,PAYMENT,REJECTED,"PAYMENT_DECLINED","Mock 결제가 거절되었습니다.",now);
            release(o,executionId,"payment", "PAYMENT_DECLINED");
            o.hold();skip(o,ProcessingStepType.after(PAYMENT));
        }
    }
    /**
     * 결제와 예약이 끝난 주문의 Mock 배송을 예약한다. 출력은 shipping.id 와 shipping.eta 다.
     * 결제나 예약이 없으면 order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java 가
     * IllegalStateException 을 던져 트랜잭션이 되돌아가고, 실행은 TOOL_ERROR 로 실패한다.
     */
    private void reserveShipping(Order o,Map<String,Object> output,Instant now){
        var result=shipping.reserve(o.getId(),o.getShippingCountry(),o.isShippingExpress(),o.getPaymentStatus()==PaymentStatus.PAID,o.isInventoryReserved());
        o.shipped(result.reference(),result.estimatedDeliveryDate());output.put("shipping.id",result.reference());output.put("shipping.eta",result.estimatedDeliveryDate().toString());
        step(o,SHIPPING,SUCCESS,null,"Mock 배송 예약 완료",now);
    }
    /** 배송이 예약된 주문의 성공 알림을 기록한다. 외부로 보내지 않고 알림 상태만 SENT 로 바꾸며, 출력은 notify.sent 와 notify.channel 이다. */
    private void sendNotification(Order o,Map<String,Object> output,Instant now){
        notifications.send(o.getId(),o.getCustomer(),o.getShipmentReference());o.notified();
        output.put("notify.sent",true);output.put("notify.channel","mock");
        step(o,NOTIFICATION,SUCCESS,null,"Mock 성공 알림 기록 완료",now);
    }
    /**
     * 필수 마지막 단계까지 끝난 주문을 SHIPPED 로 확정한다. 주문 상태, 실행 종료(COMPLETED, SHIPPED), EXECUTION_FINISHED 를
     * 한 트랜잭션으로 커밋하므로 SHIPPED 주문에는 늘 끝난 실행 기록이 붙는다. 배송 참조나 알림이 없으면
     * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java 의 complete 가 IllegalStateException 을 던진다.
     * OrderHarness.completeRun 이 부른다.
     */
    @Transactional(timeout=10)
    public void complete(UUID orderId,UUID executionId,long elapsed){
        var order=orders.lockById(orderId).orElseThrow();order.complete();orders.flush();
        evidence.finish(executionId,"COMPLETED","SHIPPED",null,elapsed,clock.instant());
        evidence.append(executionId,"EXECUTION_FINISHED",null,Map.of("completion","COMPLETED","final_status","SHIPPED"),clock.instant());
    }
    /**
     * 실패한 실행이 결제 전에 잡아 둔 재고를 되돌린다. 결제가 끝난 주문은 확정된 결과를 자동으로 되돌리지 않으려고 건드리지 않는다.
     * OrderHarness.recordFailure 가 실패 category 를 reason 으로 넘겨 부른다.
     */
    @Transactional(timeout=10)
    public void compensateUnpaid(UUID orderId,UUID executionId,String reason){
        var o=orders.lockById(orderId).orElseThrow();
        if(o.isInventoryReserved()&&o.getPaymentStatus()!=PaymentStatus.PAID)release(o,executionId,null,reason);
    }
    /**
     * 예약 재고를 모두 되돌리고 서버 전용 도구 inventory.release 의 TOOL_REQUEST, TOOL_RESULT 와 RELEASE 단계를 남긴다.
     * chargePayment 가 결제 거절 때(skill payment, reason PAYMENT_DECLINED), compensateUnpaid 가 실패 보상 때
     * (skill 없음, reason 실패 category) 부른다. 모델이 요청하지 않은 해제도 다른 도구와 같은 TOOL_REQUEST, TOOL_RESULT 모양으로
     * 남기고, 서버가 해제를 실행한 까닭은 TOOL_REQUEST 의 reason 에 적는다.
     */
    private void release(Order o,UUID executionId,String skill,String reason){
        var args=Map.<String,Object>of("order_id",o.getId().toString());
        evidence.append(executionId,"TOOL_REQUEST",skill,Map.of("tool",ToolCatalog.RELEASE_TOOL,"arguments",args,"reason",reason),clock.instant());
        reservations.releaseAll(o);
        step(o,RELEASE,SUCCESS,null,"예약 재고를 모두 복원했습니다.",clock.instant());
        evidence.append(executionId,"TOOL_RESULT",skill,Map.of("tool",ToolCatalog.RELEASE_TOOL,"arguments",args,
            "result",Map.of("inventory.reserved",false)),clock.instant());
    }
    /** 모델이 엔트리를 고르지 않은 주문을 REJECTED(NO_SELECTION)로 바꾸고 업무 단계 전체를 SKIPPED 로 남긴다. OrderHarness.finishWithoutSelection 이 부른다. */
    @Transactional public void noSelection(UUID id){var o=orders.lockById(id).orElseThrow();o.noSelection();skip(o,ProcessingStepType.BUSINESS_FLOW);}
    /** 주문 처리 단계 한 줄을 더한다. 시작 시각은 호출자가 주고 끝난 시각은 지금이다. */
    private void step(Order o,ProcessingStepType type,ProcessingStepStatus state,String code,String message,Instant started){o.addStep(type,state,code,message,started,clock.instant());}
    /** 앞 단계 거절로 실행하지 않은 단계들을 SKIPPED(UPSTREAM_REJECTED)로 남긴다. 목록은 ProcessingStepType.after 나 BUSINESS_FLOW 에서 온다. */
    private void skip(Order o,List<ProcessingStepType> steps){for(var s:steps)step(o,s,SKIPPED,"UPSTREAM_REJECTED","앞 단계 거절로 실행하지 않았습니다.",clock.instant());}
}
