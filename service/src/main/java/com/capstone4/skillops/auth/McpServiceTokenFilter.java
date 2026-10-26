package com.capstone4.skillops.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * MCP 엔드포인트(/mcp)를 서비스 토큰으로 막는 필터다. SKILLOPS_SERVICE_TOKEN(skillops.agent.service-token)이 설정되어 있으면
 * /mcp 와 그 아래 경로로 오는 모든 요청에 같은 값의 X-Service-Token 헤더를 요구하고, 없거나 다르면 401 과
 * {"code":"UNAUTHORIZED","message":"..."} 본문으로 끝낸다. 값이 비어 있으면 개발 모드로 보고 막지 않으며, 기동할 때 경고를 한 번 남긴다.
 * 같은 토큰으로 service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 가 /internal/skill-changes 를 막고,
 * 진단 Agent 의 skillops-agent/skillops_agent/mcp_client.py 가 MCP 요청마다 이 헤더를 보낸다
 * (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7 절).
 *
 * service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 의 규칙은 /mcp 를 로그인 없이 열어 두므로, 서비스 토큰 검사는
 * Spring Security 필터 사슬 뒤에서 이 필터가 맡는다. Spring Boot 가 Filter 빈을 모든 경로에 자동으로 등록하고, 이 필터가
 * shouldNotFilter 로 /mcp 밖의 경로를 건너뛴다. 비교는 시간이 새지 않도록 MessageDigest.isEqual 로 한다.
 */
@Component
public class McpServiceTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpServiceTokenFilter.class);
    /** 막는 MCP 경로다. application.yml 의 spring.ai.mcp.server 기본 엔드포인트와 같다. */
    static final String MCP_PATH = "/mcp";
    /** 서비스 토큰 헤더 이름이다. DiagnosisController 의 SERVICE_TOKEN_HEADER 와 같다. */
    static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    /** 기준 토큰의 UTF-8 바이트다. 비어 있으면 검사하지 않는다. */
    private final byte[] expected;

    /** Spring 이 skillops.agent.service-token(없으면 빈 글자)으로 만든다. 비어 있으면 /mcp 가 열려 있다고 경고한다. */
    public McpServiceTokenFilter(@Value("${skillops.agent.service-token:}") String serviceToken) {
        String token = serviceToken == null ? "" : serviceToken.strip();
        this.expected = token.getBytes(StandardCharsets.UTF_8);
        if (token.isEmpty()) {
            log.warn("SKILLOPS_SERVICE_TOKEN 이 없어 MCP 엔드포인트(/mcp)를 서비스 토큰 없이 엽니다. 운영에서는 토큰을 설정하세요.");
        }
    }

    /** /mcp 와 그 아래 경로가 아니거나 토큰이 설정되지 않았으면 이 필터를 건너뛴다. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (expected.length == 0) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals(MCP_PATH) || path.startsWith(MCP_PATH + "/"));
    }

    /** 헤더의 토큰이 기준 토큰과 같으면 다음 필터로 넘기고, 아니면 401 로 끝낸다. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(SERVICE_TOKEN_HEADER);
        if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // SecurityConfig 의 writeError 와 같은 {code, message} 모양이다. 고정 문구라 이스케이프하지 않는다.
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"MCP 요청에 올바른 X-Service-Token 이 필요합니다.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
