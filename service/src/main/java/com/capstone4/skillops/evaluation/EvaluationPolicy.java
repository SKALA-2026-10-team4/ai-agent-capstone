package com.capstone4.skillops.evaluation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import com.capstone4.skillops.scenario.Check;
import com.capstone4.skillops.scenario.Expectation;
import com.capstone4.skillops.scenario.Scenario;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;
import static com.capstone4.skillops.evaluation.EvaluationException.require;

/** 평가 생산자가 필수 검사·모드·예산을 임의로 생략하지 못하게 한다. */
public final class EvaluationPolicy {
    public static final String VERSION="order-functional-v1+fixed-policy-v1";
    public static final String GRADER="scenario-grader-v1";
    public static final String FIXED_MODEL="fixed-policy-v1";
    /** 정적 정책만 제공한다. */
    private EvaluationPolicy() {}
    /** 평가셋의 완전성·라벨·조건 문법을 먼저 검사한다. */
    public static void validate(SetInput input) {
        require(input!=null && input.name()!=null && !input.name().isBlank() && input.name().length()<=128,"평가셋 이름이 필요합니다");
        require(input.cases()!=null && !input.cases().isEmpty() && input.cases().size()<=20,"사례 수는 1~20입니다");
        Set<String> ids=new HashSet<>();
        for (CaseInput item:input.cases()) {
            require(item!=null && item.caseId()!=null && item.caseId().matches("[A-Za-z0-9_-]{1,64}") && ids.add(item.caseId()),"사례 ID가 없거나 중복입니다");
            require(item.suite()!=null && item.order()!=null && !item.order().isEmpty(),"suite와 주문 입력이 필요합니다");
            require(item.order().keySet().stream().allMatch(Set.of("customer","items","payment_method","shipping")::contains),"주문 입력의 알 수 없는 필드입니다");
            validateExpectation(item.baselineExpectation()); validateExpectation(item.candidateExpectation());
            if (item.suite()!=Suite.NEW_SKILL) {
                require(EvaluationJson.hash(item.baselineExpectation()).equals(EvaluationJson.hash(item.candidateExpectation()))
                        && Objects.equals(item.baselineSelection(),item.candidateSelection()),"신규 Skill 사례 외에는 양쪽 기대 조건이 같아야 합니다");
            }
            for (Selection selection:new Selection[]{item.baselineSelection(),item.candidateSelection()}) {
                if (selection!=null) {
                    require(selection.mode()!=null && selection.skill()!=null
                            && selection.skill().matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"),"잘못된 선택 기대값입니다");
                }
            }
            if (item.intentChecks()!=null) { item.intentChecks().forEach(Check::parse); }
            if (item.suite()==Suite.INTENT) { require(item.intentChecks()!=null && !item.intentChecks().isEmpty(),"의도 사례에는 검사 조건이 필요합니다"); }
        }
        require(EvaluationJson.write(input).length()<=200000,"평가셋이 너무 큽니다");
        require(input.targetSkill()==null || input.targetSkill().matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"),"대상 Skill 이름이 잘못됐습니다");
    }
    /** 업무 성공을 아무 조건 없이 통과시키는 평가셋은 거부한다. */
    private static void validateExpectation(Scenario.Expect expectation) {
        require(expectation!=null && (expectation.finalStatus()!=null || expectation.shipments()!=null
                || (expectation.skillsCalled()!=null && !expectation.skillsCalled().isEmpty())
                || (expectation.assertions()!=null && !expectation.assertions().isEmpty())),"비어 있지 않은 업무 기대 조건이 필요합니다");
        Expectation.of(expectation);
    }
    /** 지원하지 않는 실모델·외부 실행 제어를 지원한다고 가정하지 않는다. */
    public static void validate(Submit request, SetInput set, EvaluationConfiguration.Settings settings) {
        require(request!=null && request.runnerKind()!=null && request.purpose()!=null,"실행기와 평가 목적이 필요합니다");
        try { java.util.UUID.fromString(request.baselineSnapshotId()); java.util.UUID.fromString(request.candidateSnapshotId()); }
        catch (RuntimeException error) { throw new EvaluationException(400,"INVALID_EVALUATION_INPUT","snapshot ID는 UUID여야 합니다"); }
        require(request.repeat()>=1 && request.repeat()<=5 && request.evaluationSetVersion()==1,"repeat는 1~5, 평가셋 버전은 1입니다");
        require(request.limits()!=null,"예산이 필요합니다");
        Limits limits=request.limits();
        require(limits.wallTimeMs()>=1000 && limits.wallTimeMs()<=300000 && limits.maxTotalTokens()>=0,"잘못된 실행 한도입니다");
        require(limits.maxCostUsd()!=null && limits.maxCostUsd().length()<=32
                && limits.maxCostUsd().matches("(0|[1-9][0-9]*)(\\.[0-9]+)?"),"비용은 32자 이하의 음수가 아닌 십진 문자열입니다");
        try { require(new BigDecimal(limits.maxCostUsd()).signum()>=0,"비용 한도는 음수가 될 수 없습니다"); }
        catch (NumberFormatException | NullPointerException error) { throw new EvaluationException(400,"INVALID_EVALUATION_INPUT","비용은 0 이상의 십진 문자열입니다"); }
        if (request.runnerKind()==Runner.CONTRACT_ONLY) { require("NONE".equals(request.modelMode()),"정적 실행의 모델 모드는 NONE입니다"); }
        else {
            if (!"FIXED".equals(request.modelMode())) {
                throw new EvaluationException(422,"BUDGET_ENFORCEMENT_UNSUPPORTED","실모델의 내부 예산·중단 제어는 아직 연결되지 않았습니다");
            }
            if (!settings.fixedHarnessEnabled() || settings.isolatedOrderAgentUrl()==null
                    || settings.environmentRef()==null || !settings.environmentRef().equals(request.environmentRef())) {
                throw new EvaluationException(422,"EVALUATION_CAPABILITY_UNSUPPORTED","격리 fixed 서버와 환경 버전을 명시적으로 설정해야 합니다");
            }
        }
        if (request.purpose()==Purpose.CANDIDATE_VERIFICATION) {
            require(set.cases().stream().anyMatch(item -> item.suite()==Suite.REGRESSION),"후보 검증에는 기존 기능 회귀 사례가 필요합니다");
            require(set.cases().stream().anyMatch(item -> item.suite()==Suite.REPRODUCTION || item.suite()==Suite.NEW_SKILL),"후보 검증에는 실패 재현 또는 신규 Skill 사례가 필요합니다");
            require(set.cases().stream().anyMatch(item -> item.suite()==Suite.INTENT),"후보 검증에는 명시적인 의도 검사 사례가 필요합니다");
            if (set.cases().stream().anyMatch(item -> item.suite()==Suite.NEW_SKILL)) {
                throw new EvaluationException(422,"EVALUATION_CAPABILITY_UNSUPPORTED","신규 Skill 호출 품질의 승인 근거는 실제 모델·선택 관측 확장 후 지원합니다");
            }
        }
    }
}
