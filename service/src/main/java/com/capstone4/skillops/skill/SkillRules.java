package com.capstone4.skillops.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.capstone4.skillops.scenario.Check;

/**
 * 단계 skill frontmatter 의 선언형 실행 규칙 rules 를 읽고 정적으로 검사한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2 절).
 * 규칙 하나는 {when: 조건 문법 v2 한 줄, then: hold 또는 reject, reason: 대문자 코드} 이고, 주문 Agent 가 단계의 입력 검증 직후
 * 위에서부터 첫 참 규칙 하나로 주문을 멈춘다. service 는 실행하지 않고 apply_patch 결과에 오류만 알린다.
 *
 * 형식 오류는 service/src/main/java/com/capstone4/skillops/skill/SkillMetadataValidator.java 가 metadata_errors 의
 * {field: rules, code: INVALID_SKILL_FORMAT} 으로, 조건 값 이름이 그 skill 의 inputs 가 아닌 오류는
 * service/src/main/java/com/capstone4/skillops/skill/FlowStructureValidator.java 의 ruleKeyErrors 가 flow_errors 의
 * {code: CONTRACT_VIOLATION, reason: RULE_KEY_NOT_INPUT} 으로 낸다. 주문 Agent 의 SkillLoader 가 같은 판정을 하며 두 모듈 모두
 * docs/contracts/examples/skill-rules-cases.json 으로 시험한다. 규칙을 바꾸면 그 사례 파일과 주문 Agent 쪽도 함께 고친다.
 * 진단 Agent 의 skillops-agent/skillops_agent/skill_quality/rule_checker.py 는 rules 를 아는 칸으로 다룬다.
 * save_proposal 재판정(service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java 의 weakens_contract)도 read 로 읽는다.
 */
public final class SkillRules {

    /** frontmatter 의 칸 이름이다. */
    public static final String FIELD = "rules";
    /** skill 하나에 둘 수 있는 규칙 수의 상한이다. */
    public static final int MAX_RULES = 10;
    /** 규칙 항목이 가져야 하는 키다. 셋 다 필수이고 다른 키는 받지 않는다. */
    private static final Set<String> KEYS = Set.of("when", "then", "reason");
    /** then 에 쓸 수 있는 값이다. hold 는 주문 보류(HOLD), reject 는 주문 거절(REJECTED)이다. */
    private static final Set<String> ACTIONS = Set.of("hold", "reject");
    /** reason 의 모양이다. 주문의 resultCode 로 그대로 남으므로 대문자로 시작하는 40자 이하 코드만 받는다. */
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    /**
     * 규칙 하나다.
     *
     * @param when   조건 문법 v2 한 줄이다
     * @param then   hold 또는 reject 다
     * @param reason 멈춘 이유 코드다
     */
    public record Rule(String when, String then, String reason) {

        /** when 의 값 이름이다. 읽을 수 없는 조건이면 null 이다. read 를 거친 규칙은 언제나 읽을 수 있다. */
        public String key() {
            try {
                return Check.parse(when).key();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** 정적 검사만 제공하므로 객체 생성을 막는다. */
    private SkillRules() {
    }

    /**
     * frontmatter 칸에서 rules 를 읽는다. 칸이 없거나 null 이거나 빈 목록이면 규칙이 없는 것이라 빈 목록이다.
     * 형식이 틀리면 첫 문제를 밝힌 IllegalArgumentException 을 던진다. 단계 skill 인지는 보지 않으며 그 검사는 problemOf 가 한다.
     */
    public static List<Rule> read(Map<String, Object> fields) {
        Object value = fields.get(FIELD);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException("rules 는 규칙 목록이어야 합니다");
        }
        if (items.size() > MAX_RULES) {
            throw new IllegalArgumentException("rules 는 " + MAX_RULES + "개까지 둘 수 있습니다 (받은 개수 " + items.size() + ")");
        }
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            rules.add(rule(items.get(i), i + 1));
        }
        return List.copyOf(rules);
    }

    /**
     * rules 칸의 형식 문제를 한 줄로 돌려준다. 문제가 없으면 null 이다. 규칙이 있는 flow skill(steps 가 비어 있지 않은 skill)도 문제다.
     * SkillMetadataValidator 가 metadata_errors 의 rules 오류 message 로 쓴다.
     */
    public static String problemOf(Map<String, Object> fields) {
        List<Rule> rules;
        try {
            rules = read(fields);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (!rules.isEmpty() && fields.get("steps") instanceof List<?> steps && !steps.isEmpty()) {
            return "rules 는 단계 skill 에만 둘 수 있습니다. flow skill(steps 가 있는 skill)에는 둘 수 없습니다";
        }
        return null;
    }

    /**
     * 조건 값 이름이 선언 inputs 에 없는 첫 규칙의 값 이름을 돌려준다. 모두 선언됐으면 null 이다.
     * 값 이름 k 는 선언 input i 와 같거나 i + '.' 으로 시작해야 한다. 예약 키(order.final_status, order.shipments)도 예외가 아니다.
     */
    public static String undeclaredKey(List<Rule> rules, List<String> inputs) {
        for (Rule rule : rules) {
            String key = rule.key();
            if (key != null && !isCoveredBy(key, inputs)) {
                return key;
            }
        }
        return null;
    }

    /** 값 이름 key 가 inputs 가운데 하나와 같거나 그 input 아래 경로(input + '.')면 true 다. */
    public static boolean isCoveredBy(String key, List<String> inputs) {
        return inputs.stream().anyMatch(input -> key.equals(input) || key.startsWith(input + "."));
    }

    /**
     * 목록 한 칸을 규칙으로 읽는다. Map 이어야 하고 키는 정확히 when, then, reason 이며, when 은 조건 문법 v2 로 읽혀야 하고
     * then 은 hold 나 reject, reason 은 REASON 모양이어야 한다. 어긋나면 몇 번째 규칙인지 밝힌 IllegalArgumentException 이다.
     */
    private static Rule rule(Object item, int no) {
        if (!(item instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("rules 의 " + no + "번째 항목은 {when, then, reason} 묶음이어야 합니다");
        }
        if (!map.keySet().equals(KEYS)) {
            throw new IllegalArgumentException("rules 의 " + no + "번째 항목은 when, then, reason 세 칸만 가져야 합니다 (받은 칸 "
                    + map.keySet() + ")");
        }
        if (!(map.get("when") instanceof String when)) {
            throw new IllegalArgumentException("rules 의 " + no + "번째 when 은 조건 한 줄이어야 합니다");
        }
        try {
            Check.parse(when);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rules 의 " + no + "번째 when 을 읽을 수 없습니다: " + when, e);
        }
        if (!(map.get("then") instanceof String then) || !ACTIONS.contains(then)) {
            throw new IllegalArgumentException("rules 의 " + no + "번째 then 은 hold 나 reject 여야 합니다");
        }
        if (!(map.get("reason") instanceof String reason) || !REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("rules 의 " + no + "번째 reason 은 대문자로 시작하는 40자 이하 코드여야 합니다");
        }
        return new Rule(when, then, reason);
    }
}
