package com.capstone4.skillops.evaluation;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;
import static com.capstone4.skillops.evaluation.EvaluationFixtures.*;

/** 명시적으로 지정한 일회용 PostgreSQL에서만 실행하는 저장·경합 계약 테스트다. */
@DataJpaTest(properties={"spring.datasource.url=${SKILLOPS_EVAL_TEST_DB_URL}",
        "spring.datasource.username=eval_test","spring.datasource.password=eval_test_local",
        "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({EvaluationStore.class,EvaluationService.class,EvaluationPersistenceTest.Configuration.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named="SKILLOPS_EVALUATION_INTEGRATION_TESTS",matches="true")
class EvaluationPersistenceTest {
    @Autowired EvaluationService service;
    @Autowired EvaluationStore store;
    @MockitoBean EvaluationRunner runner;
    /** 실제 외부 주문 대신 고정 입력을 제공한다. */
    @TestConfiguration static class Configuration {
        /** 격리 환경을 명시적으로 활성화한 설정이다. */
        @Bean EvaluationConfiguration.Settings settings() { return new EvaluationConfiguration.Settings(true,"http://isolated","isolated-v1"); }
    }
    /** 테스트마다 저장소를 정리하고 고정 snapshot 응답을 준비한다. */
    @BeforeEach void prepare() {
        store.reports().deleteAll(); store.trials().deleteAll(); store.attempts().deleteAll(); store.jobs().deleteAll(); store.sets().deleteAll();
        when(runner.freeze(anyString())).thenAnswer(call -> snapshot(call.getArgument(0)));
    }
    /** 검토된 평가셋으로 접수할 입력을 만든다. */
    private Submit input() {
        SetView registered=service.register(set()); service.review(registered.evaluationSetId(),"operator");
        Submit old=submit(Runner.ORDER_HARNESS);
        return new Submit(old.baselineSnapshotId(),old.candidateSnapshotId(),registered.evaluationSetId(),1,old.runnerKind(),"FIXED",2,old.purpose(),old.environmentRef(),old.limits());
    }
    /** 동시 접수에서도 job과 첫 시도는 한 개만 생성한다. */
    @Test void concurrentSameKeyCreatesOneJob() throws Exception {
        Submit input=input();
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            Callable<Accepted> call=() -> { start.await(); return service.submit(input,"same","operator"); };
            Future<Accepted> left=pool.submit(call); Future<Accepted> right=pool.submit(call); start.countDown();
            assertThat(left.get(10,TimeUnit.SECONDS).evaluationId()).isEqualTo(right.get(10,TimeUnit.SECONDS).evaluationId());
        }
        assertThat(store.jobs().count()).isEqualTo(1); assertThat(store.attempts().count()).isEqualTo(1);
    }
    /** 두 작업자가 동시에 선점해도 같은 주문을 두 번 실행하지 않는다. */
    @Test void concurrentWorkersClaimExactlyOnce() throws Exception {
        service.submit(input(),"key","operator");
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            Callable<Optional<EvaluationStore.Claim>> call=() -> { start.await(); return store.claim(); };
            Future<Optional<EvaluationStore.Claim>> left=pool.submit(call);
            Future<Optional<EvaluationStore.Claim>> right=pool.submit(call);
            start.countDown();
            int claims=(left.get(10,TimeUnit.SECONDS).isPresent()?1:0)+(right.get(10,TimeUnit.SECONDS).isPresent()?1:0);
            assertThat(claims).isEqualTo(1);
        }
    }
    /** 같은 키에 다른 입력을 보내면 거부한다. */
    @Test void differentInputWithSameKeyIsConflict() {
        Submit input=input(); service.submit(input,"key","operator");
        Submit other=new Submit(input.baselineSnapshotId(),input.candidateSnapshotId(),input.evaluationSetId(),1,input.runnerKind(),"FIXED",1,input.purpose(),input.environmentRef(),input.limits());
        assertThatThrownBy(() -> service.submit(other,"key","operator")).isInstanceOf(EvaluationException.class).hasMessageContaining("입력이 다릅니다");
    }
    /** 실행을 재구성해도 저장된 판정·확정 보고서가 유지된다. */
    @Test void completedEvaluationPersistsReportAndEvidence() {
        Accepted accepted=service.submit(input(),"key","operator");
        EvaluationStore.Claim claim=store.claim().orElseThrow();
        for (Trial old:trials(claim.plan())) {
            store.record(claim,new Trial(old.trialId(),claim.attemptId(),old.arm(),old.caseId(),old.suite(),old.repeatIndex(),old.runId(),old.executionId(),
                    old.task(),old.selection(),old.intent(),old.rawSelection(),old.reasons(),old.errorCode(),old.elapsedMs(),null,null,"0",Map.of("evidence","saved")));
        }
        store.finish(claim,Status.SUCCEEDED,null);
        EvaluationService reopened=new EvaluationService(store,runner,new EvaluationConfiguration.Settings(true,"http://isolated","isolated-v1"));
        Report report=reopened.report(accepted.evaluationId(),accepted.attemptId());
        assertThat(report.verificationStatus()).isEqualTo("SERVER_VERIFIED");
        assertThat(store.reports().count()).isEqualTo(1);
        assertThat(reopened.get(accepted.evaluationId()).completed()).isEqualTo(12);
        assertThat(reopened.list(0,20).items().getFirst().completed()).isEqualTo(12);
        assertThat(reopened.cases(accepted.evaluationId(),accepted.attemptId(),0,1).items().getFirst().trace()).containsEntry("evidence","saved");
    }
    /** lease 소실은 ERROR를 성공으로 바꾸거나 외부 실행을 다시 전송하지 않는다. */
    @Test void expiredWorkerCannotOverwriteInterruptedState() {
        Accepted accepted=service.submit(input(),"key","operator");
        EvaluationStore.Claim claim=store.claim().orElseThrow();
        EvaluationAttempt current=store.attempts().findById(accepted.attemptId()).orElseThrow();
        current.leaseUntil=Instant.now().minusSeconds(1); store.attempts().saveAndFlush(current);
        store.recover(); store.finish(claim,Status.SUCCEEDED,null);
        assertThat(service.get(accepted.evaluationId()).status()).isEqualTo(Status.INTERRUPTED);
        assertThat(service.report(accepted.evaluationId(),accepted.attemptId()).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThatThrownBy(() -> service.retry(accepted.evaluationId(),accepted.attemptId(),"retry","operator")).isInstanceOf(EvaluationException.class);
        assertThat(store.claim()).isEmpty();
    }
    /** 대기 중 취소는 실제 실행을 생성하지 않는다. */
    @Test void cancellationBeforeClaimMakesNoExecution() {
        Accepted accepted=service.submit(input(),"key","operator");
        service.cancel(accepted.evaluationId(),accepted.attemptId());
        assertThat(store.claim()).isEmpty();
        assertThat(service.get(accepted.evaluationId()).completed()).isZero();
        verify(runner,never()).execute(anyString(),any(),any(),any(),anyInt());
    }
    /** 취소된 계획을 새 시도로 재실행하고 원본을 유지한다. */
    @Test void retryIsIdempotentAndKeepsOriginalAttempt() {
        Accepted first=service.submit(input(),"key","operator"); service.cancel(first.evaluationId(),first.attemptId());
        Accepted retry=service.retry(first.evaluationId(),first.attemptId(),"retry","operator");
        assertThat(service.retry(first.evaluationId(),first.attemptId(),"retry","operator").attemptId()).isEqualTo(retry.attemptId());
        assertThat(retry.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(store.attempt(first.evaluationId(),first.attemptId()).status).isEqualTo(Status.CANCELLED);
    }
    /** 다른 작업의 시도를 근거로 조회할 수 없다. */
    @Test void crossJobEvidenceIsRejected() {
        Submit input=input();
        Accepted first=service.submit(input,"one","operator"); Accepted second=service.submit(input,"two","operator");
        assertThatThrownBy(() -> service.report(first.evaluationId(),second.attemptId())).isInstanceOf(EvaluationException.class);
    }
}
