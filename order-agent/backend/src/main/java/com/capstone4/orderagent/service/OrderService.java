package com.capstone4.orderagent.service;
import java.util.UUID;
import com.capstone4.orderagent.dto.order.*;
import com.capstone4.orderagent.entity.enums.ProcessingStepType;
import com.capstone4.orderagent.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final OrderLifecycleService lifecycle;
    private final OrderProcessingService processor;
    private final com.capstone4.orderagent.workspace.SnapshotService snapshots;
    public OrderService(OrderLifecycleService lifecycle, OrderProcessingService processor, com.capstone4.orderagent.workspace.SnapshotService snapshots) {
        this.lifecycle = lifecycle; this.processor = processor; this.snapshots=snapshots;
    }
    public record Creation(boolean created, OrderResponse order) {}
    /** No parent transaction: each lifecycle and processing call commits independently. */
    public Creation createOrder(UUID key, CreateOrderRequest request) {
        var input = NormalizedOrder.from(request);
        var existing = lifecycle.existing(key, input.hash());
        if (existing.isPresent()) return new Creation(false, lifecycle.get(existing.get().orderId()));
        if(request.snapshotId()!=null)snapshots.get(request.snapshotId());
        OrderLifecycleService.Accepted accepted;
        try { accepted = lifecycle.accept(key, input); }
        catch (DataIntegrityViolationException conflict) {
            accepted = lifecycle.existing(key, input.hash()).orElseThrow(() -> conflict);
        }
        UUID id = accepted.orderId();
        if (accepted.created() && lifecycle.claim(id)) {
            try { processor.process(id); }
            catch (RuntimeException e) {
                var stage = e instanceof ProcessingFailure f ? f.getStep() : ProcessingStepType.RECOVERY;
                ErrorCode code = hasDatabaseCause(e) ? ErrorCode.PROCESSING_UNAVAILABLE : ErrorCode.INTERNAL_ERROR;
                lifecycle.fail(id, stage, code.name());
                // TODO(C-2 webhook 연동): trace 저장이 확정된 뒤 failure 이벤트(run_id/execution_id)를 전달한다.
                // 전송 실패는 주문 결과를 덮지 않으며 업무 거절·평가 실패의 자동 진단 재귀는 제외한다.
                log.error("Order {} failed at {} ({})", id, stage, e.getClass().getSimpleName());
                throw new BusinessException(code, id);
            }
        }
        return new Creation(accepted.created(), lifecycle.get(id));
    }
    public OrderResponse getOrder(UUID id) { return lifecycle.get(id); }
    public OrderResponse getByRequestId(UUID key) { return lifecycle.byRequest(key); }
    static boolean hasDatabaseCause(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) if (e instanceof DataAccessException) return true;
        return false;
    }
}
