package com.capstone4.skillops.approval;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SkillOps Agent 의 POST /approve 를 부른다. 같은 수정안을 두 번 처리하지 않도록 재시도하지 않는다
 * (Agent 는 이미 처리한 수정안에 404 를 돌려준다).
 */
@Component
public class AgentClient {

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public AgentClient(@Value("${skillops.agent.base-url}") String baseUrl,
            @Value("${skillops.agent.service-token:}") String serviceToken) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory())
                .defaultHeaders(h -> {
                    if (!serviceToken.isBlank()) {
                        h.set("X-Service-Token", serviceToken);
                    }
                }));
    }

    AgentClient(RestClient.Builder builder) {
        this.http = builder.build();
    }

    /** status: approved | rejected, token: 서비스가 발급한 approval_token(거절이면 null) */
    public Reply approve(String proposalId, String status, String token) {
        Map<String, Object> request = new HashMap<>();
        request.put("proposal_id", proposalId);
        request.put("status", status);
        request.put("token", token);
        try {
            JsonNode body = http.post()
                    .uri("/approve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return new Reply(body != null && body.path("accepted").asBoolean(false),
                    body == null ? null : body.path("status").asString(null));
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            String detail = detail(e);
            if (code == 404) {
                throw new AgentException("PROPOSAL_NOT_FOUND", detail, 404);
            }
            throw new AgentException("AGENT_ERROR", "SkillOps Agent 가 " + code + " 를 돌려주었습니다: " + detail, 502);
        } catch (ResourceAccessException e) {
            throw new AgentException("AGENT_UNAVAILABLE", "SkillOps Agent 에 연결하지 못했습니다.", 502);
        }
    }

    public record Reply(boolean accepted, String status) {
    }

    // FastAPI 오류 본문은 {"detail": "..."} 모양이다.
    private String detail(RestClientResponseException e) {
        try {
            JsonNode body = json.readTree(e.getResponseBodyAsString());
            JsonNode detail = body.get("detail");
            if (detail != null && !detail.isNull()) {
                // 404 는 글, 422(입력 검증)는 목록으로 온다.
                return detail.isString() ? detail.asString() : detail.toString();
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON 이 아니면 상태 코드만 알린다.
        }
        return e.getStatusText();
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        // uvicorn 은 HTTP/2 업그레이드(h2c) 요청의 본문을 받지 못하므로 HTTP/1.1 로 고정한다.
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }
}
