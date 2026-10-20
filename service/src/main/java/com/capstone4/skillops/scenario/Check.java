package com.capstone4.skillops.scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 확인 조건 한 줄. 예: {@code payment.status == 'PAID'}. 비교는 {@code ==}, {@code !=}, {@code in} 세 가지뿐이다.
 * 실행 결과에 없는 값 이름은 null 로 본다. 숫자는 값으로 비교해서 39000 과 39000.0 을 같게 본다.
 *
 * @param text     조건 원문. 떨어진 이유에 그대로 적는다
 * @param expected 글자는 String, 숫자는 BigDecimal, 참·거짓은 Boolean, null, in 이면 그 목록
 */
public record Check(String text, String key, Op op, Object expected) {

    public enum Op { EQ, NE, IN }

    private static final Pattern LINE = Pattern.compile("^\\s*(\\S+)\\s+(==|!=|in)\\s+(.+?)\\s*$");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    public static Check parse(String text) {
        Matcher m = text == null ? null : LINE.matcher(text);
        if (m == null || !m.matches()) {
            throw unreadable(text);
        }
        Op op = switch (m.group(2)) {
            case "==" -> Op.EQ;
            case "!=" -> Op.NE;
            default -> Op.IN;
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
        } else {
            expected = literal(raw, text);
        }
        return new Check(text, m.group(1), op, expected);
    }

    public boolean holds(Map<String, Object> context) {
        Object actual = context.get(key);
        return switch (op) {
            case EQ -> same(actual, expected);
            case NE -> !same(actual, expected);
            case IN -> ((List<?>) expected).stream().anyMatch(e -> same(actual, e));
        };
    }

    /** 떨어진 이유에 적을 실제 값. 글자는 따옴표로 감싸고, 없으면 null. */
    public String actualText(Map<String, Object> context) {
        Object actual = context.get(key);
        if (actual == null) {
            return "null";
        }
        return actual instanceof String s ? "'" + s + "'" : String.valueOf(actual);
    }

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

    /** 'A, B' 처럼 따옴표 안의 쉼표는 나누지 않는다. */
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

    private static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        return a.equals(b);
    }

    private static IllegalArgumentException unreadable(String text) {
        return new IllegalArgumentException(
                "확인 조건을 읽을 수 없습니다: " + text + " (예: payment.status == 'PAID')");
    }
}
