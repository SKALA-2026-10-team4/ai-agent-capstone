package com.capstone4.skillops.scenario;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * C-2 가 주문 Agent(order-agent/)에서 시험 주문을 돌릴 때 쓰는 HTTP 클라이언트다. C-1 의 조회 전용 클라이언트인
 * service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 와 따로 둔다.
 * 부르는 API 는 다섯 가지다. 후보 snapshot 올리기(POST /api/snapshots/candidates), 모의 실행(POST /api/dry-runs),
 * 실행 기록 읽기(GET /api/executions/{id}/trace), 실패했던 주문 꺼내기(실행 기록의 order_id 로 GET /api/orders/{id}),
 * 상품 단가 읽기(GET /api/products, 의도 scope 의 order.total 계산용)다.
 * 주문 Agent 쪽에서는 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 가
 * snapshot 올리기와 실행 기록을, order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java 가
 * 모의 실행을, order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 가 주문 읽기를 받는다.
 *
 * 재시도하지 않고 폴링도 하지 않는다. 모의 실행은 끝날 때까지 기다리는 동기 호출이고 도는 동안 재고를 잡으므로,
 * 응답이 늦었다고 같은 주문을 또 보내지 않으려는 것이다.
 * 주문 Agent 가 4xx 로 거절하면 오류 본문의 code 를 담은 {@link Rejected}(IllegalArgumentException)를 던진다.
 * 연결 실패와 5xx 는 "연결할 수 없습니다", 응답 시간 초과는 "응답이 늦습니다" 로 알리는 IllegalStateException 을 던진다.
 * 이 번역 규칙은 OrderAgentRunErrors 에 있고, run_scenarios 결과에서는 앞의 것이 INVALID_INPUT, 뒤의 것이 INTERNAL_ERROR 가 된다.
 *
 * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 가 publishCandidate, dryRun, trace 를 부르고,
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 가 originalOrder, executionIdOf, isExecutionId 를 부른다.
 * 기다리는 시간을 바꾸려면 CONNECT_TIMEOUT 과 READ_TIMEOUT 을 고친다.
 */
@Component
public class OrderAgentRunClient {

    /** 하이픈 있는 UUID 모양의 실행 번호(execution_id)다. 대소문자를 모두 받는다. */
    private static final Pattern EXECUTION_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /**
     * run_id 별칭의 모양이다. r_ 뒤에 하이픈을 뺀 execution_id 를 붙인 것이다. 주문 Agent 에서는
     * order-agent/backend/src/main/java/com/capstone4/orderagent/trace/RunId.java 가 같은 규칙을 맡고 EvidenceController 가 쓴다.
     */
    private static final Pattern RUN_ID = Pattern.compile("r_([0-9a-fA-F]{32})");
    /** 응답 본문을 Map 으로 읽기 위한 타입 정보다. */
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    /**
     * 주문 Agent 모의 실행에 넘기는 주문 칸이다. 주문 Agent 의
     * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java 칸에서 snapshot_id 를 뺀 네 칸이며,
     * snapshot 은 모의 실행 요청 맨 위의 snapshot_id 로 따로 보낸다. 시험 주문 order 의 다른 칸은 보내지 않는다.
     * HarnessFlowRunner 의 orderFieldsOf 가 이 목록으로 칸을 추리고, toDryRunOrder 는 같은 키를 같은 순서로 직접 만든다.
     * 둘이 같은지는 service/src/test/java/com/capstone4/skillops/scenario/DryRunOrderFieldsTest.java 가 확인한다.
     */
    static final List<String> DRY_RUN_ORDER_FIELDS = List.of("customer", "items", "payment_method", "shipping");

    /** 주문 Agent 와 연결을 맺을 때 기다리는 시간이다. 넘으면 연결할 수 없다고 알린다. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /**
     * 응답을 기다리는 시간이다. 주문 Agent 의 실행 한도 order-agent.execution-timeout-ms
     * (order-agent/backend/src/main/resources/application.yml, 기본 45000)보다 길어야 한다.
     * 짧으면 느리지만 멀쩡한 모의 실행을 여기서 먼저 끊는데, 주문 Agent 는 계속 돌며 재고를 잡고 있다.
     * 이 관계는 service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java 가 확인한다.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 모의 실행 응답 가운데 쓰는 칸이다. FAILED 로 끝난 실행도 HTTP 200 으로 온다.
     * HarnessFlowRunner 는 executionId 로 실행 기록을 읽고 runId 를 결과의 실행 번호로 쓴다.
     * finalStatus 는 담아 두기만 하고, 채점에는 실행 기록 맨 위의 final_status 를 쓴다.
     */
    public record DryRunStarted(String executionId, String runId, String finalStatus) {
    }

    /**
     * 주문 Agent 가 요청을 4xx 로 거절했다는 예외다. IllegalArgumentException 이라 run_scenarios 에서는 INVALID_INPUT 이 된다.
     * OrderAgentRunErrors.fromResponse 가 만들고 원인 예외는 달지 않는다. code 는 오류 본문의 code 이고, 없으면 null 이다.
     */
    public static final class Rejected extends IllegalArgumentException {

        private final int status;
        private final String code;

        /** 거절 문구, HTTP 상태, 오류 code 로 만든다. */
        Rejected(String message, int status, String code) {
            super(message);
            this.status = status;
            this.code = code;
        }

        /** 주문 Agent 가 답한 HTTP 상태 코드다. */
        public int status() {
            return status;
        }

        /** 오류 본문의 code 다. 본문이 JSON 이 아니거나 code 가 없으면 null 이다. */
        public String code() {
            return code;
        }

        /**
         * 주문 Agent 에 그 snapshot 이 없다는 거절(404, SNAPSHOT_NOT_FOUND)이면 true 다. 주문 Agent DB 를 비우고 다시 켰을 때 나온다.
         * 주문 Agent 의 demo 기능이 꺼져 있을 때도
         * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DemoGuard.java 가 같은 code 로 거절한다.
         * HarnessFlowRunner 의 startWithCachedSnapshot 이 snapshot 을 다시 올릴지 정할 때 본다.
         */
        boolean snapshotNotFound() {
            return status == 404 && "SNAPSHOT_NOT_FOUND".equals(code);
        }
    }

    /** baseUrl 과 시간 제한을 묶은 HTTP 클라이언트다. */
    private final RestClient http;
    /** 오류 문구에 쓰는 주문 Agent 주소다. 끝의 / 는 뗀다. */
    private final String baseUrl;

    /**
     * Spring 이 skillops.order-agent.base-url 값으로 만든다. 이 값은 service/src/main/resources/application.yml 에 있고
     * 환경 변수 ORDER_AGENT_BASE_URL 로 바꾼다. 연결 시간과 응답 시간은 requestFactory 가 건다.
     */
    @Autowired
    public OrderAgentRunClient(@Value("${skillops.order-agent.base-url}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()), baseUrl);
    }

    /** 테스트는 MockRestServiceServer 를 묶은 builder 를 넘긴다. 여기서 받는 baseUrl 은 오류 문구에만 쓰고 끝의 / 를 뗀다. */
    OrderAgentRunClient(RestClient.Builder builder, String baseUrl) {
        this.http = builder.build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 파일 묶음을 후보 snapshot 으로 올린다(POST /api/snapshots/candidates). 경로는 skills/ 로 시작해야 하며,
     * 경로와 크기 검사는 주문 Agent 의 order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 에 있는
     * publish 가 한다. 응답에 snapshot_id 가 없으면 IllegalStateException 을 던진다.
     *
     * @return 새 snapshot_id
     */
    public String publishCandidate(Map<String, String> files, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", files);
        body.put("metadata", metadata);
        String path = "/api/snapshots/candidates";
        Map<String, Object> manifest = postJson("후보 snapshot 올리기", path, body);
        return required(manifest, "snapshot_id", path);
    }

    /**
     * 주문 하나를 snapshot 으로 모의 실행한다(POST /api/dry-runs). 실행이 끝날 때까지 기다리는 동기 호출이라 READ_TIMEOUT 안에 끝나야 한다.
     * 주문 Agent 는 끝난 뒤 재고를 되돌리고, 이 실행은 live 실패 목록에 나오지 않는다.
     * 응답에 execution_id 나 run_id 가 없으면 IllegalStateException 을 던진다.
     */
    public DryRunStarted dryRun(String snapshotId, Map<String, Object> order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot_id", snapshotId);
        body.put("order", order);
        String path = "/api/dry-runs";
        Map<String, Object> started = postJson("모의 실행", path, body);
        return new DryRunStarted(required(started, "execution_id", path), required(started, "run_id", path),
                (String) started.get("final_status"));
    }

    /** 실행 기록 전체를 읽는다(GET /api/executions/{id}/trace). 없는 실행이면 {@link Rejected}(404)를 던진다. */
    public Map<String, Object> trace(String executionId) {
        return getJson("실행 기록 읽기", "/api/executions/{id}/trace", executionId);
    }

    /**
     * 실패했던 실행의 원래 주문을 꺼낸다. 실행 기록의 order_id 로 주문을 읽고, 모의 실행에 넣을 칸만
     * customer, items(sku, qty), payment_method, shipping(country, express) 모양으로 돌려준다.
     * 실행이나 주문이 없거나(404) 실행 기록에 order_id 가 없으면 같은 문구의 IllegalArgumentException 을 던지고,
     * 404 가 아닌 거절은 Rejected 그대로 던진다.
     * ScenarioRunner 의 withOriginalOrders() 가 모의 실행 전에 부른다.
     *
     * @param executionIdOrRunId execution_id(UUID) 또는 run_id(r_ 와 32자리 16진수)다
     * @throws IllegalArgumentException 번호 모양이 틀렸거나 주문 Agent 에 그 실행이나 주문이 없을 때 던진다
     */
    public Map<String, Object> originalOrder(String executionIdOrRunId) {
        String executionId = executionIdOf(executionIdOrRunId);
        if (executionId == null) {
            throw new IllegalArgumentException("재현할 실행 번호는 UUID 나 r_ 와 32자리 16진수(run_id) 여야 합니다: "
                    + executionIdOrRunId);
        }
        Map<String, Object> order;
        try {
            Object orderId = trace(executionId).get("order_id");
            if (!(orderId instanceof String id)) {
                throw notFound(executionIdOrRunId, null);
            }
            order = getJson("주문 읽기", "/api/orders/{id}", id);
        } catch (Rejected e) {
            if (e.status() == 404) {
                throw notFound(executionIdOrRunId, e);
            }
            throw e;
        }
        return toDryRunOrder(order);
    }

    /**
     * 판매 중인 상품의 SKU 별 단가를 읽는다(GET /api/products 의 items[].sku, items[].unit_price).
     * 주문 Agent 쪽에서는 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ProductController.java 가 받는다.
     * 담당자 의도의 scope 가 order.total 을 쓸 때 ScenarioRunner 의 withIntent() 가 run_scenarios 한 번에 한 번만 부르고,
     * 시험 주문의 처음 총액(단가 × 수량의 합)을 계산한다. 단가가 숫자가 아닌 줄은 버린다.
     */
    public Map<String, BigDecimal> unitPrices() {
        String path = "/api/products";
        Map<String, Object> body = call("상품 목록 읽기", path, () -> http.get().uri(path).retrieve().body(MAP));
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (Map<String, Object> item : JsonMaps.listOfMaps(body.get("items"))) {
            if (item.get("sku") instanceof String sku && item.get("unit_price") instanceof Number price) {
                prices.put(sku, new BigDecimal(price.toString()));
            }
        }
        return prices;
    }

    /** 하이픈 있는 UUID 모양의 실행 번호(execution_id)이면 true 를 돌려준다. null 이면 false 다. ScenarioRunner 의 replaySuffixOf 가 쓴다. */
    static boolean isExecutionId(String text) {
        return text != null && EXECUTION_ID.matcher(text).matches();
    }

    /**
     * execution_id(UUID) 나 run_id(r_ 와 32자리 16진수)를 하이픈 있는 소문자 UUID 로 바꾼다. 둘 다 아니면 null 을 돌려준다.
     * r_ 뒤에 하이픈 있는 UUID 를 붙인 번호와 f- 로 시작하는 번호는 받지 않는다.
     */
    static String executionIdOf(String executionIdOrRunId) {
        if (executionIdOrRunId == null) {
            return null;
        }
        if (isExecutionId(executionIdOrRunId)) {
            return executionIdOrRunId.toLowerCase(Locale.ROOT);
        }
        Matcher run = RUN_ID.matcher(executionIdOrRunId);
        if (!run.matches()) {
            return null;
        }
        String hex = run.group(1).toLowerCase(Locale.ROOT);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /**
     * 주문 Agent 에 저장된 주문에서 모의 실행에 넣을 칸만 꺼낸다. 키는 DRY_RUN_ORDER_FIELDS 와 같은 순서이고 값이 없어도 null 로 둔다.
     * items 가운데 Map 이 아닌 칸은 버리고 각 줄은 sku 와 qty 만 남기며, shipping 은 country 와 express 만 남긴다.
     * 칸을 더하거나 빼면 DRY_RUN_ORDER_FIELDS 도 함께 고친다.
     */
    private static Map<String, Object> toDryRunOrder(Map<String, Object> storedOrder) {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("customer", storedOrder.get("customer"));
        original.put("items", JsonMaps.listOfMaps(storedOrder.get("items")).stream().map(item -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("sku", item.get("sku"));
            line.put("qty", item.get("qty"));
            return line;
        }).toList());
        original.put("payment_method", storedOrder.get("payment_method"));
        Map<String, Object> shipping = JsonMaps.mapOf(storedOrder.get("shipping"));
        Map<String, Object> destination = new LinkedHashMap<>();
        destination.put("country", shipping.get("country"));
        destination.put("express", shipping.get("express"));
        original.put("shipping", destination);
        return original;
    }

    /** 재현할 실행을 주문 Agent 에서 찾을 수 없다는 IllegalArgumentException 을 만든다. 404 거절에서 왔으면 그 거절을 원인으로 단다. */
    private static IllegalArgumentException notFound(String executionIdOrRunId, Rejected cause) {
        IllegalArgumentException e = new IllegalArgumentException(
                "재현할 실행 " + executionIdOrRunId + " 을 주문 Agent 에서 찾을 수 없습니다");
        if (cause != null) {
            e.initCause(cause);
        }
        return e;
    }

    /** JSON 본문을 POST 하고 JSON 응답을 읽는다. action 은 오류 문구에 쓰는 동작 이름이다. */
    private Map<String, Object> postJson(String action, String path, Map<String, Object> body) {
        return call(action, path, () -> http.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP));
    }

    /** 경로 변수 하나({id})로 GET 한다. 요청은 URI 템플릿으로 보내고, 오류 문구에는 id 를 이어 붙인 경로를 쓴다. */
    private Map<String, Object> getJson(String action, String template, String id) {
        return call(action, template.replace("{id}", String.valueOf(id)),
                () -> http.get().uri(template, id).retrieve().body(MAP));
    }

    /**
     * 요청을 보내고 실패를 C-2 예외로 바꾼다. 4xx 는 {@link Rejected}, 5xx 와 연결 실패와 시간 초과는 IllegalStateException 이고,
     * 성공했는데 본문이 비어 있어도 IllegalStateException 이다. 문구와 예외를 고르는 규칙은 OrderAgentRunErrors 에 있다.
     */
    private <T> T call(String action, String path, Supplier<T> request) {
        String url = baseUrl + path;
        T body;
        try {
            body = request.get();
        } catch (RestClientResponseException e) {
            throw OrderAgentRunErrors.fromResponse(action, url, e);
        } catch (ResourceAccessException e) {
            throw OrderAgentRunErrors.fromAccess(url, e);
        }
        if (body == null) {
            throw OrderAgentRunErrors.emptyBody(action, url);
        }
        return body;
    }

    /** 응답 본문에서 비어 있지 않은 글자 칸을 꺼낸다. 없으면 어느 경로의 응답인지 밝힌 IllegalStateException 을 던진다. */
    private static String required(Map<String, Object> body, String field, String path) {
        if (body.get(field) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalStateException("주문 Agent 의 " + path + " 응답에 " + field + " 가 없습니다");
    }

    /** CONNECT_TIMEOUT 과 READ_TIMEOUT 을 건 JDK HttpClient 요청 팩토리를 만든다. 시간을 바꾸려면 두 상수를 고친다. */
    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
