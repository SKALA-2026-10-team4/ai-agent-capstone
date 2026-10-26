package com.capstone4.skillops.scenario;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * run_scenarios 의 intent_expectations 를 검사하고 채점에 쓰는 모양으로 바꾼 것이다(docs/plans/2026-10-26-behavior-edit-root-fix.md
 * 2.3, 2.4 절). service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 run 이 parse 로 만들고,
 * 시험 주문마다 scope 문맥(scopeContextOf)으로 맞는 expectation 을 고른다. 채점 규칙은 이렇다.
 * scope 가 null 인 expectation 의 checks 는 모든 시험 주문에 더한다. scope 에 맞는 기준 주문은 시험 주문 파일의 expect 대신
 * 맞는 checks 와 불변식(FAILED 아님, 최종 상태 있음)으로 채점하고 결과에 intent_scoped 를 참으로 둔다. 재현 주문은 불변식에
 * 맞는 checks 를 더한다. extra_checks 는 언제나 그 뒤에 붙는다.
 *
 * 장부(scenario_runs.checks_hash)에는 이 호출이 덧붙인 조건의 지문(checksHash)을 남긴다. save_proposal 재판정
 * (service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java)은 의도의 expectations 로 intentHash 를
 * 다시 만들어, 그 의도로 채점한 실행만 골라 판정한다. 지문은 순서와 앞뒤 공백을 따지지 않는다.
 * 장부(scenario_runs.intent_matches)에는 실행마다 채점에 붙은 expectation 의 자리(canonicalIndices)도 남긴다. 재판정은 이것으로
 * scoped expectation 마다 맞은 실행이 하나 이상 있는지 본다(같은 문서 2.10 절 V3-4).
 */
public final class IntentPlan {

    /** intent_expectations 로 받는 항목 수의 상한이다. */
    public static final int MAX_EXPECTATIONS = 10;
    /** expectation 하나의 checks 개수 상한이다. 하한은 1이다. */
    public static final int MAX_CHECKS = 10;
    /** 의도 지문의 앞머리다. intent_expectations 를 넣은 호출은 extra_checks 와 상관없이 이 지문을 남긴다. */
    static final String INTENT_PREFIX = "i:";
    /** intent_expectations 없이 extra_checks 만 넣은 호출의 지문 앞머리다. */
    static final String EXTRA_PREFIX = "x:";
    /** 의도가 없는 계획이다. */
    static final IntentPlan NONE = new IntentPlan(List.of(), null);

    /** 값 이름이 order.total(또는 그 아래 경로)인 scope 가 있으면 상품 단가로 총액을 계산한다. */
    private static final String TOTAL_KEY = "order.total";

    /**
     * 읽은 expectation 하나다.
     *
     * @param scope     null 이면 모든 시험 주문에 붙는다
     * @param checks    하나 이상이다
     * @param canonical 이 expectation 의 자리(canonicalIndices)다. 장부 intent_matches 에 남는 번호다
     */
    private record Item(Check scope, List<Check> checks, int canonical) {
    }

    private final List<Item> items;
    /** intent_expectations 의 지문(INTENT_PREFIX 포함)이다. 의도가 없으면 null 이다. */
    private final String intentHash;

    private IntentPlan(List<Item> items, String intentHash) {
        this.items = items;
        this.intentHash = intentHash;
    }

    /**
     * intent_expectations 를 검사하고 읽는다. null 이나 빈 목록이면 NONE 이다. 항목 수가 MAX_EXPECTATIONS 를 넘거나, checks 가
     * 1~MAX_CHECKS 개가 아니거나, scope·checks 가운데 조건 문법으로 읽지 못하는 줄이 있으면 몇 번째 항목인지 밝힌
     * IllegalArgumentException 을 던진다. 이 오류는 run_scenarios 의 INVALID_INPUT 이 된다.
     */
    public static IntentPlan parse(List<IntentExpectation> expectations) {
        if (expectations == null || expectations.isEmpty()) {
            return NONE;
        }
        if (expectations.size() > MAX_EXPECTATIONS) {
            throw new IllegalArgumentException("intent_expectations 는 " + MAX_EXPECTATIONS + "개까지 넣을 수 있습니다 (받은 개수 "
                    + expectations.size() + ")");
        }
        List<Integer> canonical = canonicalIndices(expectations);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < expectations.size(); i++) {
            items.add(item(expectations.get(i), i + 1, canonical.get(i)));
        }
        return new IntentPlan(List.copyOf(items), intentHash(expectations));
    }

    /** expectation 하나를 검사하고 읽는다. no 는 오류 문구에 쓰는 1부터의 순번이고, canonical 은 그 expectation 의 자리다. */
    private static Item item(IntentExpectation expectation, int no, int canonical) {
        String where = "intent_expectations 의 " + no + "번째 ";
        List<String> checks = expectation == null ? null : expectation.checks();
        if (checks == null || checks.isEmpty() || checks.size() > MAX_CHECKS) {
            throw new IllegalArgumentException(where + "항목에 checks 를 1~" + MAX_CHECKS + "개 넣으세요 (받은 개수 "
                    + (checks == null ? 0 : checks.size()) + ")");
        }
        Check scope = null;
        if (expectation.scope() != null) {
            try {
                scope = Check.parse(expectation.scope());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + "scope: " + e.getMessage(), e);
            }
        }
        List<Check> parsed = new ArrayList<>();
        for (String check : checks) {
            try {
                parsed.add(Check.parse(check));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + "checks: " + e.getMessage(), e);
            }
        }
        return new Item(scope, List.copyOf(parsed), canonical);
    }

    /** 의도가 없으면 true 다. */
    boolean isEmpty() {
        return items.isEmpty();
    }

    /** scope 가운데 order.total 을 쓰는 것이 있으면 true 다. ScenarioRunner 가 이때만 상품 단가를 읽는다. */
    boolean needsTotal() {
        return items.stream().map(Item::scope).filter(Objects::nonNull)
                .anyMatch(s -> s.key().equals(TOTAL_KEY) || s.key().startsWith(TOTAL_KEY + "."));
    }

    /** scope 가 null 인 expectation 의 checks 를 순서대로 모은다. 모든 시험 주문에 더한다. */
    List<Check> unscopedChecks() {
        return items.stream().filter(i -> i.scope() == null).flatMap(i -> i.checks().stream()).toList();
    }

    /** scope 가 있고 그 scope 가 scopeContext 에서 참인 expectation 의 checks 를 순서대로 모은다. 맞는 것이 없으면 빈 목록이다. */
    List<Check> scopedChecks(Map<String, Object> scopeContext) {
        return items.stream().filter(i -> i.scope() != null && i.scope().holds(scopeContext))
                .flatMap(i -> i.checks().stream()).toList();
    }

    /**
     * 이 시험 주문의 채점에 붙는 expectation 의 자리(canonicalIndices)를 작은 것부터 겹치지 않게 모은다. scope 가 null 인 expectation 은
     * 늘 붙으므로 늘 들어가고, scope 가 있는 것은 scopeContext 에서 참일 때만 들어간다. ScenarioRunner 가 장부 intent_matches 로 남긴다.
     */
    List<Integer> matchedIndices(Map<String, Object> scopeContext) {
        return items.stream().filter(i -> i.scope() == null || i.scope().holds(scopeContext)).map(Item::canonical)
                .distinct().sorted().toList();
    }

    /**
     * 이 호출이 채점에 덧붙인 조건의 지문을 돌려준다. intent_expectations 가 있으면 의도 지문(INTENT_PREFIX)을, 없고 extra_checks 만
     * 있으면 EXTRA_PREFIX 지문을, 둘 다 없으면 null 을 돌려준다. ScenarioRunner 가 실행마다 장부의 checks_hash 로 남긴다.
     */
    String checksHash(List<Check> extraChecks) {
        if (intentHash != null) {
            return intentHash;
        }
        if (extraChecks.isEmpty()) {
            return null;
        }
        return EXTRA_PREFIX + sha256(String.join("\n", extraChecks.stream().map(c -> c.text().strip()).sorted().toList()));
    }

    /**
     * expectation 목록의 의도 지문을 만든다. 목록이 null 이거나 비면 null 이다. 검사는 하지 않으므로 읽지 못하는 줄이 섞여도 지문은
     * 나온다. expectation 마다 scope(없으면 ~)와 정렬한 checks 를 잇고, 그 줄들을 다시 정렬해 SHA-256 을 낸다.
     * save_proposal 재판정이 제안서의 intent.expectations 로 같은 값을 만들어 장부의 checks_hash 와 비교한다.
     */
    public static String intentHash(List<IntentExpectation> expectations) {
        if (expectations == null || expectations.isEmpty()) {
            return null;
        }
        List<String> lines = expectations.stream().map(IntentPlan::lineOf).sorted().toList();
        return INTENT_PREFIX + sha256(String.join("\n", lines));
    }

    /**
     * expectation 마다, 의도 지문의 줄(lineOf)을 정렬한 목록에서 그 expectation 줄의 자리(0부터)를 돌려준다. 같은 줄이 둘이면 같은
     * 자리다. 지문(intentHash)이 같은 두 목록은 순서나 앞뒤 공백이 달라도 같은 expectation 이 같은 자리를 받는다. 그래서 장부의
     * intent_matches 를 save_proposal 재판정이 다시 만든 목록과 맞춰 볼 수 있다. null 이면 빈 목록이다.
     */
    public static List<Integer> canonicalIndices(List<IntentExpectation> expectations) {
        if (expectations == null) {
            return List.of();
        }
        List<String> lines = expectations.stream().map(IntentPlan::lineOf).toList();
        List<String> sorted = lines.stream().sorted().toList();
        return lines.stream().map(sorted::indexOf).toList();
    }

    /** expectation 하나를 지문의 한 줄로 만든다. scope(없으면 ~)와 정렬한 checks 를 잇는다. 앞뒤 공백은 뗀다. */
    private static String lineOf(IntentExpectation e) {
        String scope = e == null || e.scope() == null ? "~" : e.scope().strip();
        List<String> checks = e == null || e.checks() == null ? List.of()
                : e.checks().stream().map(c -> c == null ? "" : c.strip()).sorted().toList();
        return scope + "\u001f" + String.join("\u001e", checks);
    }

    /**
     * 시험 주문의 처음 값으로 scope 문맥을 만든다. 키는 order.payment_method, order.customer, order.shipping(묶음 그대로라
     * order.shipping.country 와 order.shipping.express 를 Check 의 경로 찾기로 읽는다)이고, withTotal 이면 order.total 을 더한다.
     * order.total 은 items 마다 단가 × 수량의 합이며, 단가를 모르는 상품이 있거나 수량이 숫자가 아니면 null 이다.
     * 단가는 ScenarioRunner 가 run_scenarios 한 번에 한 번 주문 Agent 의 GET /api/products 로 읽어 넘긴다.
     */
    static Map<String, Object> scopeContextOf(Map<String, Object> order, boolean withTotal, Map<String, BigDecimal> prices) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> source = order == null ? Map.of() : order;
        context.put("order.payment_method", source.get("payment_method"));
        context.put("order.customer", source.get("customer"));
        context.put("order.shipping", source.get("shipping"));
        if (withTotal) {
            context.put(TOTAL_KEY, totalOf(source.get("items"), prices));
        }
        return context;
    }

    /** 주문 줄의 단가 × 수량을 더한다. 줄 하나라도 단가나 수량을 알 수 없으면 null 이다. */
    private static BigDecimal totalOf(Object items, Map<String, BigDecimal> prices) {
        if (!(items instanceof List<?> lines)) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Object line : lines) {
            if (!(line instanceof Map<?, ?> item) || !(item.get("qty") instanceof Number qty)
                    || !(item.get("sku") instanceof String sku) || prices.get(sku) == null) {
                return null;
            }
            total = total.add(prices.get(sku).multiply(new BigDecimal(qty.toString())));
        }
        return total;
    }

    /** UTF-8 글의 SHA-256 을 소문자 16진수로 낸다. */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
