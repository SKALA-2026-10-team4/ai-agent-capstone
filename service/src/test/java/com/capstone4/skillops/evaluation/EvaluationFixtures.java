package com.capstone4.skillops.evaluation;

import java.util.*;
import com.capstone4.skillops.scenario.Scenario;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** 평가 계약 테스트에서 사용하는 독립 입력이다. */
public final class EvaluationFixtures {
    /** 테스트 도우미만 제공한다. */
    private EvaluationFixtures() {}
    /** 최소 flow와 단계 원문을 만든다. */
    public static Map<String,String> skills() {
        return Map.of("order-flow","---\nname: order-flow\nversion: 1\nowner: team\ndescription: 주문\ninputs: []\noutputs: []\ndepends_on: []\nsteps: [order-intake]\n---\n주문 처리\n",
                "order-intake","---\nname: order-intake\nversion: 1\nowner: team\ndescription: 접수\ninputs: []\noutputs: [order.accepted]\ndepends_on: []\n---\n접수 처리\n");
    }
    /** 원문 보존을 검증할 불변 snapshot을 만든다. */
    public static FrozenSnapshot snapshot(String id) {
        Map<String,String> texts=new TreeMap<>(skills());
        if (id.startsWith("22222222")) { texts.computeIfPresent("order-intake",(name,text) -> text+"\n후보 문서 보완\n"); }
        return new FrozenSnapshot(id,EvaluationJson.hash(texts),texts,List.of());
    }
    /** 회귀·재현·의도 사례를 명시한다. */
    public static SetInput set() {
        Scenario.Expect expected=new Scenario.Expect(null,"SHIPPED",null,List.of());
        List<CaseInput> cases=new ArrayList<>();
        for (Suite suite:List.of(Suite.REGRESSION,Suite.REPRODUCTION,Suite.INTENT)) {
            cases.add(new CaseInput(suite.name(),suite,Map.of("customer","demo"),expected,expected,null,null,
                    suite==Suite.INTENT?List.of("order.accepted != null"):List.of()));
        }
        return new SetInput("검증 세트",null,cases);
    }
    /** 접수 입력을 명시적으로 만든다. */
    public static Submit submit(Runner runner) {
        return new Submit("11111111-1111-4111-8111-111111111111","22222222-2222-4222-8222-222222222222",
                "set",1,runner,runner==Runner.CONTRACT_ONLY?"NONE":"FIXED",2,
                Purpose.CANDIDATE_VERIFICATION,"isolated-v1",new Limits(120000,0,"0"));
    }
    /** 비교 계획의 입력을 고정한다. */
    public static Plan plan(Runner runner) {
        Submit input=submit(runner);
        return new Plan(input,set(),EvaluationJson.hash(set()),true,snapshot(input.baselineSnapshotId()),
                snapshot(input.candidateSnapshotId()),EvaluationPolicy.VERSION,EvaluationPolicy.GRADER);
    }
    /** 회차별 저장 결과를 생성하며 제출 결과 객체는 사용하지 않는다. */
    public static List<Trial> trials(Plan plan) {
        List<Trial> rows=new ArrayList<>();
        for (CaseInput item:plan.evaluationSet().cases()) {
            for (int round=0;round<plan.request().repeat();round++) {
                for (Arm arm:Arm.values()) {
                    String id=arm+"-"+item.caseId()+"-"+round;
                    rows.add(new Trial(id,"attempt",arm,item.caseId(),item.suite(),round,"r_"+id,"exec_"+id,
                            arm==Arm.BASELINE && item.suite()==Suite.REPRODUCTION?Verdict.FAIL:Verdict.PASS,
                            Verdict.NOT_APPLICABLE,item.suite()==Suite.INTENT?Verdict.PASS:Verdict.NOT_APPLICABLE,
                            null,List.of(),null,10L,null,null,"0",Map.of()));
                }
            }
        }
        return rows;
    }
}
