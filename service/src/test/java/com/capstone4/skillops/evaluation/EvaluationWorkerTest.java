package com.capstone4.skillops.evaluation;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;
import static com.capstone4.skillops.evaluation.EvaluationFixtures.*;

class EvaluationWorkerTest {
    private final EvaluationStore store=mock(EvaluationStore.class);
    private final EvaluationRunner runner=mock(EvaluationRunner.class);
    private final EvaluationWorker worker=new EvaluationWorker(store,runner);
    /** 실행 전 문서 차단은 외부 호출을 만들지 않는다. */
    @Test void invalidDocumentStopsBeforeAnyExecution() {
        Plan old=plan(Runner.ORDER_HARNESS);
        FrozenSnapshot bad=new FrozenSnapshot("bad","hash",Map.of("order-flow","broken"),List.of("YAML"));
        Plan plan=new Plan(old.request(),old.evaluationSet(),old.setHash(),true,bad,old.candidate(),old.policyVersion(),old.graderVersion());
        var claim=new EvaluationStore.Claim("attempt","token",plan);
        worker.run(claim);
        verifyNoInteractions(runner);
        verify(store).finish(eq(claim),eq(Status.BLOCKED),contains("DOCUMENT_INVALID"));
    }
    /** 외부 호출 응답이 모호하면 재시도하지 않는다. */
    @Test void ambiguousNetworkFailureIsRecordedWithoutRetry() {
        var claim=new EvaluationStore.Claim("attempt","token",plan(Runner.ORDER_HARNESS));
        when(store.running(claim)).thenReturn(true);
        when(runner.execute(anyString(),any(),any(),any(),anyInt())).thenThrow(new IllegalStateException("timeout"));
        worker.run(claim);
        verify(runner,times(1)).execute(anyString(),any(),any(),any(),anyInt());
        verify(store).record(eq(claim),argThat(row -> row.task()==Verdict.UNMEASURABLE && row.errorCode().equals("UPSTREAM_ERROR")));
        verify(store).finish(eq(claim),eq(Status.INTERRUPTED),anyString());
    }
    /** 취소 이후에는 새 주문을 시작하지 않는다. */
    @Test void cancellationPreventsNextExecution() {
        var claim=new EvaluationStore.Claim("attempt","token",plan(Runner.ORDER_HARNESS));
        when(store.running(claim)).thenReturn(false);
        worker.run(claim);
        verifyNoInteractions(runner);
        verify(store).finish(eq(claim),eq(Status.CANCELLED),anyString());
    }
}
