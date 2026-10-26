package com.capstone4.skillops.scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 확인 조건 한 줄을 읽고 실행 결과에 맞는지 판단한다. 예를 들어 {@code payment.status == 'PAID'} 처럼 쓴다.
 * 문법은 값 이름, 비교, 값의 세 칸이고 칸 사이에는 공백이 하나 이상 있어야 한다(조건 문법 v2,
 * docs/plans/2026-10-26-behavior-edit-root-fix.md 2.1 절).
 * 비교는 {@code ==}, {@code !=}, {@code in} 과 숫자 비교 {@code >}, {@code >=}, {@code <}, {@code <=} 의 일곱 가지다.
 * 값은 '글자', true, false, null, 숫자이며 in 뒤에만 [..] 목록(빈 목록 허용)이 온다. 숫자 비교의 오른쪽은 숫자만 받고,
 * 실제 값이 숫자가 아니면 거짓이다. 숫자는 값으로 비교해서 39000 과 39000.0 을 같게 보지만, true·false 는 숫자 1·0 과 같지 않다.
 *
 * 값 찾기 규칙은 이렇다. 문맥에 그 이름이 그대로 있으면 그 값이다. 없으면 점으로 나눈 가장 긴 앞부분이 문맥에 있는지 보고,
 * 그 값이 Map 이면 나머지 경로로 내려간다. 더 짧은 앞부분으로 다시 시도하지 않는다. 끝내 못 찾으면 null 이다.
 * 예를 들어 문맥에 order.shipping 이 {country: 'US'} 로 있으면 order.shipping.country 는 'US' 다.
 *
 * 확인 조건은 네 곳에서 온다. 시험 주문 파일의 expect.assertions 는 Expectation.of 가 읽고, run_scenarios 의 extra_checks 와
 * intent_expectations 는 service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 parseChecks() 와
 * service/src/main/java/com/capstone4/skillops/scenario/IntentPlan.java 의 parse 가 읽는다. SKILL.md 의 rules[].when 은
 * service/src/main/java/com/capstone4/skillops/skill/SkillRules.java 가 읽는다. 모두 parse 를 부른다.
 * 채점은 service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의 failedCheckReasons() 가 holds 와 actualText 로 한다.
 *
 * 같은 문법을 세 모듈이 함께 쓰고, 모두 docs/contracts/examples/condition-cases.json 으로 시험한다. 주문 Agent 의
 * order-agent/backend 의 RuleCondition 이 rules 를 평가하고, skillops-agent/skillops_agent/contracts.py 의 check_problem 이
 * 읽기만 흉내 낸다. skillops-agent/skillops_agent/nodes.py 의 _split_checks 는 그 결과로 서비스가 읽지 못할 조건을
 * run_scenarios 를 부르기 전에 걸러 낸다. 조건 하나라도 읽지 못하면 run_scenarios 전체가 거절되기 때문이다.
 * 그래서 LINE, NUMBER, literal, splitOutsideQuotes, lookup 을 바꾸면 그 두 곳과 공용 사례 파일도 같이 고친다.
 *
 * @param text     조건 원문이다. 떨어진 이유에 그대로 적는다
 * @param key      값 이름이다. ScenarioGrader 가 만든 문맥에서 이 이름으로 실제 값을 찾는다
 * @param op       비교 방법이다
 * @param expected 기대 값이다. 글자는 String, 숫자는 BigDecimal, 참과 거짓은 Boolean, null 은 null 이고, in 이면 그 값들의 고칠 수 없는 목록이다.
 *                 숫자 비교면 언제나 BigDecimal 이다
 */
public record Check(String text, String key, Op op, Object expected) {

    /** 비교 방법이다. EQ 는 ==, NE 는 !=, IN 은 in 목록에 들어 있는지, GT·GE·LT·LE 는 >, >=, <, <= 숫자 비교다. */
    public enum Op { EQ, NE, IN, GT, GE, LT, LE }

    /**
     * 확인 조건 한 줄의 모양이다. 공백 없는 값 이름, 비교, 값 순서이고 앞뒤 공백은 무시한다.
     * 두 글자 비교(>=, <=)를 한 글자 비교(>, <)보다 먼저 맞춰 본다.
     */
    private static final Pattern LINE = Pattern.compile("^\\s*(\\S+)\\s+(==|!=|in|>=|<=|>|<)\\s+(.+?)\\s*$");
    /** 숫자로 읽는 값의 모양이다. -12 나 3.5 같은 모양만 받고, +1 이나 1e3 같은 모양은 받지 않는다. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * 조건 한 줄을 읽는다. 모양이 틀리거나 값을 읽을 수 없으면 예시를 붙인 IllegalArgumentException 을 던진다.
     * 숫자 비교의 오른쪽이 숫자가 아니면(글자, null, true, false, 목록) 읽을 수 없는 조건이다.
     * extra_checks 의 오류는 이 문구 그대로 run_scenarios 의 INVALID_INPUT 오류 message 가 되고,
     * 시험 주문 파일의 오류는 ScenarioRunner 가 어느 파일인지를 앞에 붙인다.
     */
    public static Check parse(String text) {
        Matcher m = text == null ? null : LINE.matcher(text);
        if (m == null || !m.matches()) {
            throw unreadable(text);
        }
        Op op = switch (m.group(2)) {
            case "==" -> Op.EQ;
            case "!=" -> Op.NE;
            case "in" -> Op.IN;
            case ">" -> Op.GT;
            case ">=" -> Op.GE;
            case "<" -> Op.LT;
            default -> Op.LE;
        };
        String raw = m.group(3);
        Object expected;
        if (op == Op.IN) {
            if (!raw.startsWith("[") || !raw.endsWith("]")) {
                throw unreadable(text);
            }
            List<Object> items = new ArrayList<>();
            for (String part : splitOutsideQuotes(raw.substring(1, raw.length() - 1))) {
                items.add(literal(part, text));
            }
            expected = Collections.unmodifiableList(items);
        } else if (isOrdering(op)) {
            // 숫자 비교의 오른쪽은 숫자만 읽는다. 글자나 null 을 받으면 늘 거짓인 조건이 되어 실수를 숨기기 때문이다.
            if (!NUMBER.matcher(raw).matches()) {
                throw unreadable(text);
            }
            expected = new BigDecimal(raw);
        } else {
            expected = literal(raw, text);
        }
        return new Check(text, m.group(1), op, expected);
    }

    /**
     * 문맥에서 key 의 값을 찾아 조건이 맞으면 true 를 돌려준다. 값을 찾는 규칙은 lookup 을 따르고, 없으면 null 로 보고 비교한다.
     * 숫자 비교는 실제 값이 숫자(Number)일 때만 비교하고, 숫자가 아니면 거짓이다.
     */
    public boolean holds(Map<String, Object> context) {
        Object actual = lookup(context, key);
        return switch (op) {
            case EQ -> same(actual, expected);
            case NE -> !same(actual, expected);
            case IN -> ((List<?>) expected).stream().anyMatch(e -> same(actual, e));
            case GT -> compared(actual) > 0;
            case GE -> compared(actual) >= 0;
            case LT -> compared(actual) < 0;
            case LE -> compared(actual) <= 0;
        };
    }

    /** 떨어진 이유에 적을 실제 값을 글로 만든다. 글자는 작은따옴표로 감싸고, 값이 없으면 null 이라고 적는다. */
    public String actualText(Map<String, Object> context) {
        Object actual = lookup(context, key);
        if (actual == null) {
            return "null";
        }
        return actual instanceof String s ? "'" + s + "'" : String.valueOf(actual);
    }

    /**
     * 문맥에서 값 이름 하나의 값을 찾는다. 이름이 그대로 있으면 그 값을, 없으면 점으로 나눈 가장 긴 앞부분이 문맥에 있을 때
     * 그 값이 Map 이면 나머지 경로로 한 칸씩 내려간 값을 돌려준다. 앞부분 값이 Map 이 아니거나 경로 중간에 칸이 없으면 null 이고,
     * 더 짧은 앞부분으로 다시 찾지 않는다. 앞부분이 하나도 없으면 null 이다.
     * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 scope 문맥(order.shipping 묶음)도 이 규칙으로 읽힌다.
     */
    public static Object lookup(Map<String, Object> context, String key) {
        if (context.containsKey(key)) {
            return context.get(key);
        }
        String[] parts = key.split("\\.", -1);
        for (int cut = parts.length - 1; cut >= 1; cut--) {
            String prefix = String.join(".", Arrays.copyOfRange(parts, 0, cut));
            if (context.containsKey(prefix)) {
                return descend(context.get(prefix), Arrays.copyOfRange(parts, cut, parts.length));
            }
        }
        return null;
    }

    /** value 에서 path 를 한 칸씩 따라 내려간다. 중간 값이 Map 이 아니거나 칸이 없으면 null 이다. */
    private static Object descend(Object value, String[] path) {
        Object current = value;
        for (String part : path) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    /** 숫자 비교인지 본다. */
    private static boolean isOrdering(Op op) {
        return op == Op.GT || op == Op.GE || op == Op.LT || op == Op.LE;
    }

    /**
     * 실제 값과 기대 숫자를 비교한 결과(-1, 0, 1)를 돌려준다. 실제 값이 숫자가 아니면 어떤 숫자 비교도 맞지 않도록
     * 비교 방향에 맞춰 늘 거짓이 되는 값을 돌려준다. Boolean 은 Number 가 아니므로 여기서 숫자로 보지 않는다.
     */
    private int compared(Object actual) {
        if (!(actual instanceof Number number)) {
            // >, >= 에는 -1, <, <= 에는 1 을 돌려줘 어느 쪽도 참이 되지 않게 한다.
            return op == Op.GT || op == Op.GE ? -1 : 1;
        }
        return new BigDecimal(number.toString()).compareTo((BigDecimal) expected);
    }

    /**
     * 값 한 칸을 읽는다. '글자', true, false, null, 숫자 순서로 보고, 어느 것도 아니면 원문 text 를 밝힌
     * IllegalArgumentException 을 던진다.
     */
    private static Object literal(String raw, String text) {
        String v = raw.trim();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1);
        }
        if (v.equals("true") || v.equals("false")) {
            return Boolean.valueOf(v);
        }
        if (v.equals("null")) {
            return null;
        }
        if (NUMBER.matcher(v).matches()) {
            return new BigDecimal(v);
        }
        throw unreadable(text);
    }

    /** in 목록의 안쪽을 쉼표로 나눈다. 'A, B' 처럼 작은따옴표 안의 쉼표는 나누지 않고, 안쪽이 비어 있으면 빈 목록을 돌려준다. */
    private static List<String> splitOutsideQuotes(String inner) {
        List<String> parts = new ArrayList<>();
        if (inner.isBlank()) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : inner.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /**
     * 두 값이 같은지 본다. 하나라도 null 이면 둘 다 null 일 때만 같다. 숫자끼리는 BigDecimal 값으로 비교한다.
     * Boolean 은 Number 가 아니므로 true 와 1 은 같지 않다.
     */
    private static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        return a.equals(b);
    }

    /** 읽을 수 없는 조건의 오류를 만든다. 고쳐 쓸 수 있게 예시를 함께 적는다. */
    private static IllegalArgumentException unreadable(String text) {
        return new IllegalArgumentException(
                "확인 조건을 읽을 수 없습니다: " + text + " (예: payment.status == 'PAID')");
    }
}
