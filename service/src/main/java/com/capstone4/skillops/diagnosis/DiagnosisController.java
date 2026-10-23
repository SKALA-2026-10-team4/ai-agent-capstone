package com.capstone4.skillops.diagnosis;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.capstone4.skillops.common.ApiError;

/**
 * 진단 Agent(skillops-agent)로 가는 요청을 중계하는 REST 컨트롤러이며, 서버 쪽 토큰 경계 역할을 한다. Agent 를 부르는 서비스
 * 토큰은 이 서버 안에만 두고, 브라우저는 그 토큰을 받지 않는다. 브라우저와 주문 Agent 는 이 컨트롤러를 거쳐 Agent 에 닿는다.
 * 맡는 경로는 셋이다.
 * POST /internal/skill-changes 는 주문 Agent 가 보내는 Skill 저장 이벤트를 받아 Agent 의 POST /diagnose 로 넘긴다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java 의 dispatch() 가 이 경로를
 * 부르고 응답의 thread_id 를 저장한다. 이 경로는 로그인 대신 X-Service-Token 헤더로 보호한다.
 * GET /api/diagnoses 와 GET /api/diagnoses/{threadId} 는 로그인한 화면이 진단 기록을 읽는 경로이고, Agent 의 GET /runs 와
 * GET /runs/{thread_id}/events 를 그대로 중계한다. frontend/src/api/diagnoses.js 의 listDiagnoses() 와 getDiagnosis() 가
 * /service/api/diagnoses 로 부르고, 두 함수를 쓰는 frontend/src/views/DiagnosisView.vue 는 목록과 진행 중인 진단을
 * frontend/src/config.js 의 DIAGNOSIS_POLL_MS 간격으로 다시 읽는다.
 * Agent 쪽 경로는 skillops-agent/skillops_agent/main.py 에 있다. 서비스 토큰(SKILLOPS_SERVICE_TOKEN)은 주문 Agent, 이 service,
 * Agent 가 같은 값을 쓴다(docs/skill-save-auto-diagnosis.md).
 * 시간 제한은 CONNECT_TIMEOUT 과 READ_TIMEOUT 에서, Agent 오류를 이 서버의 응답으로 바꾸는 규칙은 upstream 과 unavailable 에서
 * 고친다.
 */
@RestController
public class DiagnosisController {

    /** 서버 사이 요청에서 서비스 토큰을 싣는 헤더 이름이다. 들어오는 요청 검사와 Agent 로 나가는 요청에 함께 쓴다. */
    static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    // Agent 와 연결을 맺을 때까지 기다리는 한도다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    // 연결한 뒤 응답을 기다리는 한도다. Agent 의 POST /diagnose 는 진단을 접수만 하고 바로 답한다.
    // 한도를 넘기면 ResourceAccessException 이 나고 unavailable 이 502 로 바꾼다.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    // Agent 로 가는 클라이언트다. 공개 생성자로 만들면 기본 주소와 서비스 토큰 헤더가 미리 붙어 있다.
    private final RestClient agent;
    // 주문 Agent 가 보낸 요청을 검사하는 기준 토큰이다. 공개 생성자에서는 같은 값이 Agent 로 보내는 헤더에도 들어간다.
    private final String serviceToken;

    /**
     * Spring 이 빈을 만들 때 쓰는 생성자다. Agent 주소는 skillops.agent.base-url(SKILLOPS_AGENT_URL)에서, 서비스 토큰은
     * skillops.agent.service-token(SKILLOPS_SERVICE_TOKEN, 없으면 빈 문자열)에서 읽는다. 두 값은
     * service/src/main/resources/application.yml 에 있다.
     * 생성자가 둘이라 Spring 이 고를 수 있도록 @Autowired 를 붙였다.
     */
    @Autowired
    public DiagnosisController(@Value("${skillops.agent.base-url}") String url,
            @Value("${skillops.agent.service-token:}") String token) {
        this(agentClient(url, token), token);
    }

    /**
     * 만든 RestClient 를 그대로 받는 생성자다. 공개 생성자가 이 생성자에 위임한다.
     * service/src/test/java/com/capstone4/skillops/diagnosis/ 의 테스트들은 직접 만든 클라이언트를 넣을 때 이 생성자를 쓰며, 그
     * 클라이언트는 대부분 MockRestServiceServer 에 묶여 있다.
     */
    DiagnosisController(RestClient agent, String token) {
        this.agent = agent;
        this.serviceToken = token;
    }

    /**
     * 진단 Agent 로 가는 RestClient 를 만든다. JDK HttpClient 를 HTTP/1.1 로 고정해 HTTP/2 업그레이드 헤더(Upgrade)를 보내지
     * 않게 하고, 연결과 읽기 시간 제한을 건다. 모든 요청에 서비스 토큰 헤더를 붙이며, 토큰이 비어 있어도 빈 값으로 붙인다.
     * service/src/test/java/com/capstone4/skillops/diagnosis/DiagnosisControllerCharacterizationTest.java 가 실제 HTTP 서버로
     * 이 요청 모양을 확인한다.
     */
    private static RestClient agentClient(String baseUrl, String serviceToken) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken).build();
    }

    /**
     * 주문 Agent 가 보낸 Skill 저장 이벤트를 검사한 뒤 본문 그대로 Agent 의 POST /diagnose 로 넘기고, Agent 의 응답 본문
     * (accepted, thread_id, duplicate, trigger)을 그대로 돌려준다. 서비스 토큰을 먼저 보고 이벤트 모양을 나중에 보며, 어느 검사에서든
     * 막히면 Agent 를 부르지 않는다.
     * Agent 는 202 로 답하지만 이 메서드는 본문만 돌려주므로 주문 Agent 는 200 을 받는다. 같은 change_id 와 같은 본문을 다시 보내면
     * Agent 가 기존 thread_id 를 돌려주므로, 재전송해도 진단은 하나만 생긴다.
     * 호출하는 쪽인 order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java 의 dispatch() 는
     * 기본 3초 간격으로 대기 중인 이벤트 하나를 보내고, 실패하면 시도 횟수와 오류를 남긴 뒤 다음 차례에 다시 보낸다.
     */
    @PostMapping("/internal/skill-changes")
    public Map<?, ?> changed(@RequestHeader(value = SERVICE_TOKEN_HEADER, defaultValue = "") String token,
            @RequestBody Map<String, Object> event) {
        requireServiceToken(token);
        requireSavedChange(event);
        return agent.post().uri("/diagnose").contentType(MediaType.APPLICATION_JSON).body(event).retrieve()
                .body(Map.class);
    }

    /**
     * 진단 기록 목록을 읽는다. Agent 의 GET /runs 응답({items: [...]})을 그대로 돌려준다.
     * frontend/src/api/diagnoses.js 의 listDiagnoses() 가 /service/api/diagnoses 로 부른다.
     * service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 가 이 경로와 상세 경로에 로그인을 요구한다.
     */
    @GetMapping("/api/diagnoses")
    public Map<?, ?> list() {
        return agent.get().uri("/runs").retrieve().body(Map.class);
    }

    /**
     * 진단 하나의 상태와 기록을 읽는다. Agent 의 GET /runs/{thread_id}/events 응답을 그대로 돌려준다. threadId 를 UUID 로 받으므로
     * UUID 가 아닌 값은 Agent 를 부르기 전에 400 으로 끝난다.
     * frontend/src/api/diagnoses.js 의 getDiagnosis() 가 부른다. Agent 가 없는 진단이라고 404 로 답하면 upstream 이 404 로 돌려준다.
     */
    @GetMapping("/api/diagnoses/{threadId}")
    public Map<?, ?> status(@PathVariable UUID threadId) {
        return agent.get().uri("/runs/{id}/events", threadId).retrieve().body(Map.class);
    }

    /**
     * Agent 가 오류 상태로 답했을 때의 응답을 만든다. Agent 의 404 는 404 로 두고, 그 밖의 상태는 모두 502 로 바꾼다.
     * code 는 DIAGNOSIS_UPSTREAM_ERROR 이고 message 끝에 Agent 의 상태 숫자를 붙인다. 예를 들어 서비스 토큰이 Agent 와 다르면
     * Agent 의 401 이 502 로 나가고, 같은 change_id 에 다른 본문을 보내면 Agent 의 409 가 502 로 나간다.
     * 이 처리기는 이 컨트롤러의 세 경로에만 적용된다.
     */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> upstream(RestClientResponseException e) {
        int status = e.getStatusCode().value() == 404 ? 404 : 502;
        return ApiError.response(status, "DIAGNOSIS_UPSTREAM_ERROR",
                "진단 Agent가 요청을 처리하지 못했습니다 (" + e.getStatusCode().value() + ").");
    }

    /** Agent 에 연결하지 못했거나 시간 제한을 넘겼을 때 502 DIAGNOSIS_UNAVAILABLE 을 돌려준다. 문구는 다시 조회하라고 안내한다. */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiError> unavailable(ResourceAccessException e) {
        return ApiError.response(502, "DIAGNOSIS_UNAVAILABLE",
                "진단 Agent에 연결하지 못했습니다. 다시 조회해 주세요.");
    }

    /**
     * 서버 사이 요청의 서비스 토큰을 확인한다. 이 service 의 토큰이 비어 있거나 공백뿐이면 확인하지 않는다(개발 모드).
     * 토큰이 설정되어 있는데 헤더가 없거나 값이 다르면 401 ResponseStatusException 을 던진다. 이 401 은 ApiError 본문이 아니다.
     */
    private void requireServiceToken(String token) {
        if (!serviceToken.isBlank() && !serviceToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * Skill 저장 이벤트만 받는다. type 이 skill_saved 이고 change_id 가 있어야 하며, 아니면 400 ResponseStatusException 을 던진다.
     * Agent 의 POST /diagnose 는 failure 이벤트도 받지만 이 중계 경로는 저장 이벤트만 넘긴다. Agent 는 change_id 와 본문이 모두
     * 같은 요청을 같은 저장의 재전송으로 본다.
     * 이벤트 본문은 order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의
     * changeEventPayload 가 만든다.
     */
    private static void requireSavedChange(Map<String, Object> event) {
        if (!"skill_saved".equals(event.get("type")) || event.get("change_id") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A saved change is required");
        }
    }
}
