package com.capstone4.skillops.scenario;

import java.util.List;

/**
 * 채점 기준. null 인 칸은 채점하지 않는다.
 *
 * @param mustNotFail 재현 주문 기준. 최종 상태가 FAILED 가 아니어야 한다
 */
public record Expectation(List<String> skillsCalled, String finalStatus, Integer shipments, List<Check> checks,
        boolean mustNotFail) {

    public static Expectation of(Scenario.Expect expect) {
        List<Check> checks = expect.assertions() == null
                ? List.of()
                : expect.assertions().stream().map(Check::parse).toList();
        return new Expectation(expect.skillsCalled(), expect.finalStatus(), expect.shipments(), checks, false);
    }

    /** 실패했던 주문을 다시 돌릴 때의 기준: 오류로 멈추지 않고 끝까지 처리될 것. */
    public static Expectation replay() {
        return new Expectation(null, null, null, List.of(), true);
    }
}
