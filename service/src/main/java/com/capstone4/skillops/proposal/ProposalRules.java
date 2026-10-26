package com.capstone4.skillops.proposal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 제안서 Map 에서 칸을 꺼내는 작은 도우미와 후보의 합격 조건을 한곳에 모은다.
 * 제안서는 SkillOps Agent 가 보낸 JSON 을 그대로 담은 Map 이라서 칸이 없거나 모양이 다를 수 있다. 도우미는 그런 값을 예외 없이 null 이나
 * 빈 목록으로 바꾼다. 저장 입구(service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 save),
 * 저장 검사(service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java),
 * 본문 문장(service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java),
 * 의도 확인 표시(service/src/main/java/com/capstone4/skillops/proposal/Proposal.java 의 withIntentConfirmed)가
 * 같은 방식으로 칸을 읽도록 이 클래스를 쓴다.
 * 합격 조건을 바꾸려면 passes 를 고친다. 이때 Agent 가 같은 식으로 valid 를 정하는 skillops-agent/skillops_agent/nodes.py 의 _judge 도
 * 함께 고친다. 둘이 다르면 save_proposal 이 서버 값으로 덮고 server_overrides 에 남긴다(ProposalJudge).
 */
final class ProposalRules {

    /** 정적 도우미만 모은 클래스라서 인스턴스를 만들지 않는다. */
    private ProposalRules() {
    }

    /**
     * 후보가 모든 확인을 통과했는지 다시 계산한다. 적용에 실패했거나 시험 결과가 없는 후보는 시험하지 않았으므로 떨어진다.
     * 합격하려면 실패했던 주문이 끝까지 처리되고(fixes_failure), 기준 주문이 하나 이상이면서 모두 이전과 같게 처리되고
     * (keeps_old_orders 의 passed 와 total 이 같고 total 이 1 이상), 의도 확인 조건이 지켜지고(keeps_intent), 끊긴 Skill 연결이
     * 없어야 한다(no_broken_links). metadata_valid와 flow_valid가 있으면 각각 Boolean true여야 한다.
     * 과거 저장 제안서와 구버전 생산자 호환을 위해 해당 칸의 누락은 기존 판정을 유지한다.
     * 서버만 쓰는 evidence_matches(후보 patches 와 연습 공간 기록이 맞는지)도 있으면 Boolean true 여야 한다.
     * 최신 Python은 검사 결과를 반드시 보낸다. 참·거짓 칸은 Boolean true만 참으로 본다.
     * ProposalJudge 의 judge 가 서버 값으로 덮은 뒤 valid 를 정할 때, ProposalDocumentWriter 가 합격 수와 판정 글을 쓸 때 부른다.
     */
    static boolean passes(Map<String, Object> candidate) {
        if (!tested(candidate)) {
            return false;
        }
        Map<String, Object> results = map(candidate.get("results"));
        OldOrders oldOrders = oldOrders(results);
        Integer total = oldOrders.total();
        return bool(results.get("fixes_failure")) && total != null && total > 0 && total.equals(oldOrders.passed())
                && bool(results.get("keeps_intent")) && bool(results.get("no_broken_links"))
                && (!results.containsKey("metadata_valid") || bool(results.get("metadata_valid")))
                && (!results.containsKey("flow_valid") || bool(results.get("flow_valid")))
                && (!results.containsKey("evidence_matches") || bool(results.get("evidence_matches")));
    }

    /**
     * 후보를 원문에 적용했는지 본다. validation_error 가 없거나 공백뿐이면 적용한 것으로 본다.
     * ProposalValidator 는 적용한 후보만 근거 실행을 검사한다.
     */
    static boolean applied(Map<String, Object> candidate) {
        return str(candidate.get("validation_error")) == null;
    }

    /** 후보를 원문에 적용했고 시험 결과(results 객체)도 있는지 본다. passes 와, 시험한 후보 수를 세는 ProposalDocumentWriter 가 쓴다. */
    static boolean tested(Map<String, Object> candidate) {
        return applied(candidate) && map(candidate.get("results")) != null;
    }

    /** 시험 결과의 기준 주문 통과 수(passed)와 전체 수(total)다. 칸이 없거나 숫자가 아니면 null 이다. */
    record OldOrders(Integer passed, Integer total) {
    }

    /**
     * results.keeps_old_orders 에서 passed 와 total 을 꺼낸다. results 나 keeps_old_orders 가 없으면 둘 다 null 이다.
     * passes 와 ProposalDocumentWriter 가 같은 방식으로 기준 주문 수를 읽도록 한곳에 두었다.
     */
    static OldOrders oldOrders(Map<String, Object> results) {
        Map<String, Object> oldOrders = results == null ? null : map(results.get("keeps_old_orders"));
        return oldOrders == null ? new OldOrders(null, null)
                : new OldOrders(integer(oldOrders.get("passed")), integer(oldOrders.get("total")));
    }

    /** 값이 Map 이면 그대로 돌려주고 아니면 null 을 돌려준다. JSON 객체 칸을 꺼낼 때 쓴다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 값이 목록이면 null 원소를 뺀 고칠 수 없는 목록을 돌려주고, 목록이 아니면 빈 목록을 돌려준다. maps 와 strings 가 이 메서드를 거친다. */
    static List<Object> list(Object value) {
        return value instanceof List<?> l ? l.stream().filter(Objects::nonNull).map(Object.class::cast).toList()
                : List.of();
    }

    /** 목록에서 Map 원소만 골라 돌려준다. 후보 목록처럼 객체 목록인 칸을 꺼낼 때 쓰며, Map 이 아닌 원소는 버린다. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        return list(value).stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
    }

    /** 목록의 원소를 모두 글자로 바꿔 돌려준다. 실행 번호 목록이나 의도 확인 조건처럼 글자 목록인 칸을 꺼낼 때 쓴다. */
    static List<String> strings(Object value) {
        return list(value).stream().map(String::valueOf).toList();
    }

    /** 값을 글자로 바꾼다. null 이거나 공백뿐인 글자는 없는 것으로 보고 null 을 돌려준다. */
    static String str(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    /** 값이 숫자면 정수로 바꾸고 아니면 null 을 돌려준다. "1" 같은 글자는 숫자로 보지 않고, 소수는 정수 부분만 남긴다. */
    static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    /** 값이 Boolean true 일 때만 참이다. "true" 같은 글자나 없는 값은 거짓으로 본다. */
    static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }
}
