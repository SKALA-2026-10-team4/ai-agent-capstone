package com.capstone4.skillops.mcp;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * MCP 배선 확인용 tool. skillops-agent의 scripts/mcp_smoke.py 가 호출한다.
 * 실제 tool 12개(get_trace, diff_skill, run_scenarios, ...)는 W2가 이 패키지에 추가한다.
 */
@Component
public class PingTools {

    private static final Logger log = LoggerFactory.getLogger(PingTools.class);

    @McpTool(name = "ping", description = "MCP 배선 확인용. 'pong'과 서버 시간을 돌려준다.")
    public String ping() {
        log.info("ping() 호출됨");
        return "pong " + Instant.now();
    }
}
