package com.capstone4.skillops.mcp;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * MCP 배선 확인용 ping 도구를 등록한다. 서버가 떠 있고 MCP 세션이 이어지는지만 확인하며 다른 서비스는 부르지 않는다.
 * skillops-agent/scripts/mcp_smoke.py 가 도구 목록에서 ping 을 찾은 뒤 호출하고, skillops-agent/skillops_agent/tools.py 의
 * MCPTools.ping 도 이 도구를 부른다. 결과가 문자열이라 SDK 가 JSON 으로 바꾸지 않고 글 그대로 담으므로, tools.py 의
 * _success_payload 는 ping 결과만 JSON 이 아닌 글로 받아 준다.
 * 처음에는 실제 도구 12개를 이 패키지에 더할 계획이었다. 지금 이 서버의 다른 도구는 같은 패키지
 * (service/src/main/java/com/capstone4/skillops/mcp/)의 SkillDiffTools, DependencyGraphTools(C-1)와 WorkspaceTools,
 * ProposalTools(C-2)에 있다. get_trace 같은 조회는 진단 Agent 가 skillops-agent/skillops_agent/order_agent.py 에서 주문
 * Agent HTTP API 를 직접 부른다.
 */
@Component
public class PingTools {

    private static final Logger log = LoggerFactory.getLogger(PingTools.class);

    /** 호출을 로그에 남기고 "pong " 뒤에 현재 서버 시각(Instant 의 ISO-8601 글)을 붙여 돌려준다. */
    @McpTool(name = "ping", description = "MCP 배선 확인용. 'pong'과 서버 시간을 돌려준다.")
    public String ping() {
        log.info("ping() 호출됨");
        return "pong " + Instant.now();
    }
}
