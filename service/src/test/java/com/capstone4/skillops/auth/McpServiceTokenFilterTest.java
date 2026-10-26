package com.capstone4.skillops.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * service/src/main/java/com/capstone4/skillops/auth/McpServiceTokenFilter.java 가 MCP 엔드포인트(/mcp)를 서비스 토큰으로 막는지
 * 보호한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7 절). 토큰이 설정되면 /mcp 와 그 아래 경로에 같은 값의
 * X-Service-Token 이 있어야 하고, 없거나 다르면 401 과 {code, message} 본문으로 끝난다. 다른 경로와 토큰이 빈 개발 모드는 막지 않는다.
 */
class McpServiceTokenFilterTest {

    /** 요청 하나를 필터에 통과시키고, 다음 필터까지 갔으면 true 를 돌려준다. */
    static boolean passes(McpServiceTokenFilter filter, MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null;
    }

    static MockHttpServletRequest request(String method, String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (token != null) {
            request.addHeader("X-Service-Token", token);
        }
        return request;
    }

    @Test
    void mcpNeedsTheServiceTokenWhenConfigured() throws Exception {
        McpServiceTokenFilter filter = new McpServiceTokenFilter("s3cret");

        MockHttpServletResponse missing = new MockHttpServletResponse();
        assertThat(passes(filter, request("POST", "/mcp", null), missing)).isFalse();
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getContentAsString())
                .isEqualTo("{\"code\":\"UNAUTHORIZED\",\"message\":\"MCP 요청에 올바른 X-Service-Token 이 필요합니다.\"}");

        MockHttpServletResponse wrong = new MockHttpServletResponse();
        assertThat(passes(filter, request("GET", "/mcp/stream", "other"), wrong)).isFalse();
        assertThat(wrong.getStatus()).isEqualTo(401);

        MockHttpServletResponse right = new MockHttpServletResponse();
        assertThat(passes(filter, request("POST", "/mcp", "s3cret"), right)).isTrue();
        assertThat(right.getStatus()).isEqualTo(200);
    }

    /** /mcp 밖의 경로(/mcpx 같은 비슷한 이름 포함)는 이 필터가 막지 않는다. */
    @Test
    void otherPathsAreNotChecked() throws Exception {
        McpServiceTokenFilter filter = new McpServiceTokenFilter("s3cret");

        assertThat(passes(filter, request("GET", "/api/health", null), new MockHttpServletResponse())).isTrue();
        assertThat(passes(filter, request("GET", "/mcpx", null), new MockHttpServletResponse())).isTrue();
    }

    /** 토큰이 비어 있으면 개발 모드로 보고 /mcp 를 연다. */
    @Test
    void blankTokenLeavesMcpOpen() throws Exception {
        for (String blank : new String[] { "", "  ", null }) {
            McpServiceTokenFilter filter = new McpServiceTokenFilter(blank);

            assertThat(passes(filter, request("POST", "/mcp", null), new MockHttpServletResponse())).isTrue();
        }
    }
}
