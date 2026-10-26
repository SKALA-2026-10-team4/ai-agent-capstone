package com.capstone4.skillops.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 실행 한 번을 채점하는 기준이다. 값이 null 인 칸은 채점하지 않는다.
 * 기준 주문은 시험 주문 파일의 expect 로 of 가 만들고, 재현 주문은 replay 가, 담당자 의도의 scope 에 든 기준 주문은 inScope 가 만든다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 expectationOf() 와 withIntent() 가 이들을 부르고,
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의 grade() 가 기준을 읽는다.
 *
 * @param skillsCalled 성공해야 하는 skill 의 순서다
 * @param finalStatus  기대하는 최종 상태다
 * @param shipments    기대하는 배송 건수다
 * @param checks       확인 조건이다. 시험 주문 파일의 조건이나 의도의 checks 이며, 없으면 빈 목록이다
 * @param mustNotFail  불변식 기준이다. true 면 최종 상태가 FAILED 가 아니어야 하고 최종 상태가 있어야(null 이 아니어야) 한다
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
     * 최종 상태가 없는(null) 실행은 떨어진다. 재현 주문의 합격 기준을 바꾸려면 이 메서드를 고친다.
     * run_scenarios 의 extra_checks 와 의도의 checks 는 재현 주문에도 붙는다.
     */
    public static Expectation replay() {
        return new Expectation(null, null, null, List.of(), true);
    }

    /**
     * 담당자 의도의 scope 에 든 기준 주문의 기준을 만든다. 시험 주문 파일의 expect 는 쓰지 않고, 불변식(mustNotFail)과
     * 맞는 expectation 의 checks 로만 채점한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.4 절).
     */
    public static Expectation inScope(List<Check> checks) {
        return new Expectation(null, null, null, List.copyOf(checks), true);
    }

    /** 확인 조건을 뒤에 더한 기준을 돌려준다. 더할 조건이 없으면 자기 자신이다. 의도의 scope 없는 checks 를 붙일 때 쓴다. */
    public Expectation plus(List<Check> more) {
        if (more.isEmpty()) {
            return this;
        }
        List<Check> all = new ArrayList<>(checks);
        all.addAll(more);
        return new Expectation(skillsCalled, finalStatus, shipments, List.copyOf(all), mustNotFail);
    }
}
