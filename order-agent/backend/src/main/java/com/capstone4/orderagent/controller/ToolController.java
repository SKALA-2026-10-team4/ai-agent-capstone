package com.capstone4.orderagent.controller;

import java.util.*;
import com.capstone4.orderagent.harness.ToolCatalog;
import org.springframework.web.bind.annotation.*;

/**
 * GET /api/tools 로 주문 Agent 가 가진 업무 도구 목록과 처음부터 공급되는 입력 키를 돌려주는 읽기 전용 API 다.
 * 응답은 {initial_input_keys:[...], tools:[{tool, skill, description, produces:[...]}]} 이고, 내용은 모두
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 의 BINDINGS 와 initialInputKeys 에서 온다.
 * DB 와 snapshot 을 읽지 않으므로 실행 중인 서버 코드의 도구 표를 그대로 보여 준다(게시 번들의 SKILL.md 와는 무관하다).
 * 시연 전용 API 가 아니라 DemoGuard 를 거치지 않는다.
 *
 * SkillOps Agent 가 담당자 수정이 어떤 도구도 만들지 않는 값을 요구하는지 판단할 때 사본 없이 이 API 를 읽는다
 * (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절). 키 k 는 initial_input_keys 와 모든 produces 의 합집합에 그대로 있거나
 * 그 가운데 p 에 대해 p + '.' 로 시작하면 덮인다. 응답 키와 모양은 모듈 밖 계약이라
 * order-agent/backend/src/test/java/com/capstone4/orderagent/controller/ToolControllerTest.java 가 고정한다.
 * 명세 문서는 order-agent/docs/diagnosis-read-api.md 10절이다.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {
    /** 처음 입력 키, 도구 목록 순으로 키를 넣은 응답을 만든다. 도구 항목은 ToolCatalog.tools 가 BINDINGS 줄 순서로 만든다. */
    @GetMapping public Map<String,Object> list(){
        var response=new LinkedHashMap<String,Object>();
        response.put("initial_input_keys",ToolCatalog.initialInputKeys());
        response.put("tools",ToolCatalog.tools());
        return response;
    }
}
