package com.capstone4.skillops.evaluation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;
import static com.capstone4.skillops.evaluation.EvaluationFixtures.*;

class EvaluationContractsTest {
    /** 객체 키 순서와 배열 순서를 다르게 취급한다. */
    @Test void fingerprintsAreStableButPreserveCaseOrder() {
        assertThat(EvaluationJson.hash(Map.of("b",2,"a",1))).isEqualTo(EvaluationJson.hash(new TreeMap<>(Map.of("a",1,"b",2))));
        assertThat(EvaluationJson.hash(List.of(1,2))).isNotEqualTo(EvaluationJson.hash(List.of(2,1)));
        assertThat(EvaluationJson.read(EvaluationJson.write(plan(Runner.ORDER_HARNESS)),Plan.class)).isEqualTo(plan(Runner.ORDER_HARNESS));
    }
    /** fixed도 실제 주문 실행을 명시적으로 켜야 한다. */
    @Test void fixedRequiresAnIsolatedServerAndPinnedEnvironment() {
        assertThatThrownBy(() -> EvaluationPolicy.validate(submit(Runner.ORDER_HARNESS),set(),new EvaluationConfiguration.Settings(false,null,null)))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("격리");
        assertThatCode(() -> EvaluationPolicy.validate(submit(Runner.ORDER_HARNESS),set(),new EvaluationConfiguration.Settings(true,"http://isolated","isolated-v1")))
                .doesNotThrowAnyException();
    }
    /** 실모델을 지원한다고 잘못 광고하지 않는다. */
    @Test void realModelCannotBypassMissingBudgetControl() {
        Submit old=submit(Runner.ORDER_HARNESS);
        Submit input=new Submit(old.baselineSnapshotId(),old.candidateSnapshotId(),old.evaluationSetId(),1,old.runnerKind(),"OPENAI",2,old.purpose(),old.environmentRef(),old.limits());
        assertThatThrownBy(() -> EvaluationPolicy.validate(input,set(),new EvaluationConfiguration.Settings(true,"http://isolated","isolated-v1")))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("예산");
    }
    /** 잘못된 선택은 금지 대상과 다르더라도 성공이 아니다. */
    @ParameterizedTest @ValueSource(strings={"unknown","","TARGET","nonexistent-skill"})
    void invalidSelectionIsNeverANegativeHit(String value) {
        assertThat(EvaluationRunner.selectionVerdict(new Selection(SelectionMode.FORBID,"target"),value,Set.of("target","other"))).isEqualTo(Verdict.FAIL);
    }
    /** 미관측과 실제 미선택은 다르다. */
    @Test void missingSelectionIsNotNone() {
        Selection expected=new Selection(SelectionMode.EXACT,"none");
        assertThat(EvaluationRunner.selectionVerdict(expected,null,Set.of())).isEqualTo(Verdict.UNMEASURABLE);
        assertThat(EvaluationRunner.selectionVerdict(expected,"none",Set.of())).isEqualTo(Verdict.PASS);
    }
    /** 분모가 없으면 0%를 만들지 않는다. */
    @Test void emptyDenominatorIsUnknown() { assertThat(EvaluationReports.ratio(0,0).value()).isNull(); }
    /** 모의 실행 결과는 승인 근거가 될 수 없다. */
    @Test void contractOnlyNeverBecomesServerVerified() {
        Plan plan=plan(Runner.CONTRACT_ONLY);
        Report report=EvaluationReports.build("job","attempt",Status.SUCCEEDED,plan,trials(plan));
        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
        assertThat(report.verificationStatus()).isEqualTo("NOT_VERIFIED");
        assertThat(report.candidate().inputTokens()).isNull();
        assertThat(report.candidate().successVariance()).isEqualTo(0.0);
    }
    /** 미완료·취소·실패 작업을 성공으로 세지 않는다. */
    @ParameterizedTest @ValueSource(strings={"QUEUED","RUNNING","CANCELLED","STOPPED","FAILED","INTERRUPTED","BLOCKED"})
    void nonCompletedStatusIsInconclusive(String status) {
        Plan plan=plan(Runner.ORDER_HARNESS);
        assertThat(EvaluationReports.build("job","attempt",Status.valueOf(status),plan,trials(plan)).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }
    /** 한 회차가 누락돼도 후보를 승인하지 않는다. */
    @Test void missingRepeatBlocksVerification() {
        Plan plan=plan(Runner.ORDER_HARNESS);
        List<Trial> rows=trials(plan); rows.removeFirst();
        assertThat(EvaluationReports.build("job","attempt",Status.SUCCEEDED,plan,rows).verificationStatus()).isEqualTo("NOT_VERIFIED");
    }
    /** 회귀는 case와 회차를 대응시켜 세고 업무 실패도 보존한다. */
    @Test void failedCandidateCannotBeDeclaredValid() {
        Plan plan=plan(Runner.ORDER_HARNESS);
        List<Trial> rows=trials(plan);
        Trial old=rows.stream().filter(row -> row.arm()==Arm.CANDIDATE && row.suite()==Suite.REGRESSION).findFirst().orElseThrow();
        rows.remove(old);
        rows.add(new Trial(old.trialId(),old.attemptId(),old.arm(),old.caseId(),old.suite(),old.repeatIndex(),old.runId(),old.executionId(),
                Verdict.FAIL,old.selection(),old.intent(),null,List.of("실패"),"TASK_FAILED",10L,null,null,"0",Map.of()));
        Report report=EvaluationReports.build("job","attempt",Status.SUCCEEDED,plan,rows);
        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(report.regressionTrials()).isEqualTo(1);
        assertThat(report.regressionCaseIds()).containsExactly("REGRESSION");
        assertThat(report.verificationStatus()).isEqualTo("NOT_VERIFIED");
    }
    /** 검토 전 라벨로는 승인용 검증이 되지 않는다. */
    @Test void unreviewedLabelsAreNotApprovalEvidence() {
        Plan old=plan(Runner.ORDER_HARNESS);
        Plan input=new Plan(old.request(),old.evaluationSet(),old.setHash(),false,old.baseline(),old.candidate(),old.policyVersion(),old.graderVersion());
        assertThat(EvaluationReports.build("job","attempt",Status.SUCCEEDED,input,trials(input)).verificationStatus()).isEqualTo("NOT_VERIFIED");
    }
    /** 실제 fixed 결과와 검토된 필수 사례가 완성되면 기능 검증으로 표시한다. */
    @Test void completeActualTrialsCanVerifyTheCandidate() {
        Plan plan=plan(Runner.ORDER_HARNESS);
        Report report=EvaluationReports.build("job","attempt",Status.SUCCEEDED,plan,trials(plan));
        assertThat(report.verificationStatus()).isEqualTo("SERVER_VERIFIED");
        assertThat(report.safety()).isEqualTo(Verdict.UNMEASURABLE);
    }
    /** 빈 업무 기준·중복 사례는 접수할 수 없다. */
    @Test void duplicateCasesAreRejected() {
        CaseInput item=set().cases().getFirst();
        assertThatThrownBy(() -> EvaluationPolicy.validate(new SetInput("중복",null,List.of(item,item)))).isInstanceOf(EvaluationException.class);
    }
    /** 기준본부터 성공했다면 실패 해결을 입증했다고 표시하지 않는다. */
    @Test void unreproducedFailureIsNotVerified() {
        Plan plan=plan(Runner.ORDER_HARNESS);
        List<Trial> rows=trials(plan).stream().map(row -> new Trial(row.trialId(),row.attemptId(),row.arm(),row.caseId(),row.suite(),row.repeatIndex(),
                row.runId(),row.executionId(),Verdict.PASS,row.selection(),row.intent(),row.rawSelection(),row.reasons(),null,row.elapsedMs(),null,null,"0",row.trace())).toList();
        assertThat(EvaluationReports.build("job","attempt",Status.SUCCEEDED,plan,rows).verificationStatus()).isEqualTo("NOT_VERIFIED");
    }
    /** 아직 연결하지 않은 신규 Skill 호출 품질을 fixed로 승인하지 않는다. */
    @Test void newSkillApprovalCapabilityIsExplicitlyUnsupported() {
        List<CaseInput> cases=new ArrayList<>(set().cases());
        CaseInput old=cases.getFirst();
        cases.add(new CaseInput("new",Suite.NEW_SKILL,old.order(),old.baselineExpectation(),old.candidateExpectation(),null,null,List.of()));
        SetInput input=new SetInput("신규",null,cases);
        assertThatThrownBy(() -> EvaluationPolicy.validate(submit(Runner.ORDER_HARNESS),input,new EvaluationConfiguration.Settings(true,"http://isolated","isolated-v1")))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("신규 Skill");
    }
    /** 실제 bundle 변경이 없으면 수정 후보로 승인하지 않는다. */
    @Test void identicalBundleCannotBecomeAVerifiedChange() {
        Plan old=plan(Runner.ORDER_HARNESS);
        Plan input=new Plan(old.request(),old.evaluationSet(),old.setHash(),true,old.baseline(),old.baseline(),old.policyVersion(),old.graderVersion());
        assertThat(EvaluationReports.build("job","attempt",Status.SUCCEEDED,input,trials(input)).verificationStatus()).isEqualTo("NOT_VERIFIED");
    }
}
