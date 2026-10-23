package com.capstone4.skillops.scenario;

import java.util.List;

/**
 * 실행 한 번을 채점하는 기준이다. 값이 null 인 칸은 채점하지 않는다.
 * 기준 주문은 시험 주문 파일의 expect 로 of 가 만들고, 재현 주문은 replay 가 만든다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 expectationOf() 와 Planned.replay 가 이 둘을 부르고,
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의 grade() 가 기준을 읽는다.
 *
 * @param skillsCalled 성공해야 하는 skill 의 순서다
 * @param finalStatus  기대하는 최종 상태다
 * @param shipments    기대하는 배송 건수다
 * @param checks       시험 주문 파일의 확인 조건이다. 없으면 빈 목록이다
 * @param mustNotFail  재현 주문 기준이다. true 면 최종 상태가 FAILED 가 아니어야 한다
 */
public record Expectation(List<String> skillsCalled, String finalStatus, Integer shipments, List<Check> checks,
        boolean mustNotFail) {

    /** 시험 주문 파일의 expect 로 기준을 만든다. assertions 는 Check.parse 로 모두 읽고, 하나라도 틀리면 IllegalArgumentException 을 던진다. */
    public static Expectation of(Scenario.Expect expect) {
        List<Check> checks = expect.assertions() == null
                ? List.of()
                : expect.assertions().stream().map(Check::parse).toList();
        return new Expectation(expect.skillsCalled(), expect.finalStatus(), expect.shipments(), checks, false);
    }

    /**
     * 실패했던 주문을 다시 돌릴 때의 기준을 만든다. 오류로 멈추지 않고 끝까지 처리되면 합격이라 REJECTED 나 HOLD 로 끝나도 된다.
     * 재현 주문의 합격 기준을 바꾸려면 이 메서드를 고친다. run_scenarios 의 extra_checks 는 재현 주문에도 붙는다.
     */
    public static Expectation replay() {
        return new Expectation(null, null, null, List.of(), true);
    }
}
