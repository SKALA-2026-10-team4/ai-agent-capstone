package com.capstone4.skillops.proposal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 제안서를 읽을 때 쓰는 작은 도우미와 합격 조건. 제안서는 B 가 보낸 JSON 그대로(Map)라서 칸을 꺼내는 일을 한곳에 모았다.
 */
final class ProposalRules {

    private ProposalRules() {
    }

    /**
     * 후보가 모든 확인을 통과했는지. 적용에 실패한 후보(validation_error)는 시험하지 않았으므로 떨어진다.
     * 실패가 고쳐지고, 기준 주문이 하나 이상이면서 모두 그대로 처리되고, 의도가 지켜지고, 끊긴 연결이 없어야 한다.
     */
    static boolean passes(Map<String, Object> candidate) {
        if (str(candidate.get("validation_error")) != null) {
            return false;
        }
        Map<String, Object> results = map(candidate.get("results"));
        if (results == null) {
            return false;
        }
        Map<String, Object> oldOrders = map(results.get("keeps_old_orders"));
        Integer passed = oldOrders == null ? null : integer(oldOrders.get("passed"));
        Integer total = oldOrders == null ? null : integer(oldOrders.get("total"));
        return bool(results.get("fixes_failure")) && total != null && total > 0 && total.equals(passed)
                && bool(results.get("keeps_intent")) && bool(results.get("no_broken_links"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 목록이 아니면 빈 목록. null 원소는 뺀다 */
    static List<Object> list(Object value) {
        return value instanceof List<?> l ? l.stream().filter(Objects::nonNull).map(Object.class::cast).toList()
                : List.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        return list(value).stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
    }

    static List<String> strings(Object value) {
        return list(value).stream().map(String::valueOf).toList();
    }

    /** 빈 글자도 없는 것으로 본다 */
    static String str(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }
}
