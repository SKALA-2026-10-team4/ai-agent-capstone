package com.capstone4.orderagent.harness;

import java.util.*;

/**
 * 주문 Agent 실행 흐름에서 코드로 정한 튜닝 값을 한곳에 모은다.
 * 루트 flow 이름, 미선택 응답, 필수 마지막 단계, 초기 입력 키, 단계 수 한도, 업무를 이어 갈 주문 상태,
 * 결정 재시도 규칙을 바꾸려면 이 클래스부터 본다.
 * 실행 한도(전체 시간, API 호출 수, 관측 토큰, 호출 타임아웃)는 코드가 아니라
 * order-agent/backend/src/main/resources/application.yml 의 order-agent 설정과 그 환경 변수로 바꾼다.
 * 단계 Skill 과 도구의 바인딩은 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 의
 * BINDINGS 표에, 거절 뒤 건너뛸 업무 단계 순서는
 * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java 의 BUSINESS_FLOW 에 있다.
 * 단계 하나를 추가할 때 고칠 곳은 ToolCatalog 클래스 주석에 정리돼 있다.
 * OrderHarness, SkillLoader, SkillExecutor, FixedDecisionProvider 가 이 값을 읽는다.
 */
final class OrderFlowPolicy {
    /** 상수만 모은 클래스라 객체를 만들지 않는다. */
    private OrderFlowPolicy() {}

    /**
     * 엔트리로 선택돼야 하는 루트 flow Skill 이름이다. 다른 Skill 로 해석되면 OrderHarness 가 ROOT_SKILL_MISMATCH 로 멈추고,
     * fixed 모드는 늘 이 이름을 고른다. 같은 이름을 service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의
     * ENTRY_SKILL, skillops-agent/skillops_agent/config.py 의 DEFAULT_ENTRY_SKILL_ID, frontend/src/utils/skillSet.js 의
     * ENTRY_SKILL 이 따로 들고 있으므로 바꿀 때 함께 바꾼다.
     */
    static final String ROOT_FLOW_ID = "order-flow";
    /**
     * 모델이 어떤 엔트리도 고르지 않겠다고 답할 때 쓰는 이름이다. 이 값이면 OrderHarness 가 주문을 REJECTED(NO_SELECTION)로 끝내고
     * 실행은 COMPLETED 로 닫는다. OpenAiDecisionProvider 의 시스템 프롬프트가 같은 문자열을 모델에 알려 주므로 함께 바꾼다.
     */
    static final String NO_SELECTION = "none";
    /**
     * flow 가 끝났다고 인정하려면 반드시 완료돼야 하는 단계 Skill 이다. 루프가 끝났는데 이 단계가 완료되지 않았으면
     * OrderHarness 가 INCOMPLETE_FLOW 로 실패시켜, steps 에서 알림 단계를 뺀 flow 가 SHIPPED 로 확정되는 일을 막는다.
     */
    static final String REQUIRED_FINAL_STEP = "notify";
    /**
     * 엔트리 선택과 첫 단계 전에 초기 주문 컨텍스트에서 빼는 키다. order.total 은 서버가 계산한 금액이라
     * order-intake 단계가 order.inspect 도구 결과를 선언 출력으로 받아 컨텍스트에 올린다.
     */
    static final List<String> HIDDEN_INITIAL_KEYS = List.of("order.total");
    /**
     * flow 정적 검증이 처음부터 공급된다고 보는 입력 키다.
     * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 context 가 만드는 키에서
     * HIDDEN_INITIAL_KEYS 를 뺀 나머지와 같아야 한다. 같은 목록이
     * service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 의 ORDER_INPUT_KEYS,
     * service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java 의 get_dependency_graph 도구 설명,
     * frontend/src/utils/flowGraph.js 의 ORDER_KEYS 에도 있으므로 키를 더하거나 빼면 네 곳을 함께 고친다.
     * GET /api/tools 의 initial_input_keys 도 이 목록이다(ToolCatalog.initialInputKeys). SkillOps Agent 는 사본 없이 그 API 를 읽는다.
     */
    static final List<String> INITIAL_INPUT_KEYS = List.of("order.id", "order.items", "order.customer", "order.payment_method", "order.shipping");
    /** 루트 flow 의 steps 에 둘 수 있는 단계 수 상한이다. 넘으면 SkillLoader.validateFlow 가 단계를 읽기 전에 INVALID_FLOW 로 거절한다. */
    static final int MAX_FLOW_STEPS = 12;
    /**
     * 단계 Skill 하나의 rules 에 둘 수 있는 실행 규칙 수 상한이다. 넘으면 SkillLoader.load 가 INVALID_SKILL_FORMAT 으로 거절한다.
     * docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2 절의 계약 값이라 service 의 apply_patch 정적 검사도 같은 상한을 쓴다.
     */
    static final int MAX_SKILL_RULES = 10;
    /**
     * 도구 실행 뒤 주문 상태가 이 값이면 다음 단계로 넘어간다. 그 밖의 상태(재고 부족 REJECTED, 결제 거절 HOLD)는
     * 정상 업무 종료로 보고 OrderHarness 가 BUSINESS_STOP 을 남긴 뒤 실행을 COMPLETED 로 닫는다.
     */
    static final Set<String> CONTINUE_STATUSES = Set.of("PROCESSING", "SHIPPED");
    /**
     * agent_executions.mode 에 남기는 실행 종류다. 실패 목록을 조회하는
     * order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java 의 failed 가 mode 가 live 인 실행만 고르므로,
     * 모의 실행(dry_run)은 진단 대상 실패 목록에 나오지 않는다.
     */
    static final String RUN_MODE_LIVE = "live";
    static final String RUN_MODE_DRY_RUN = "dry_run";
    /** 결정 요청 하나의 최대 시도 횟수다. 첫 시도를 포함하므로 2 는 재시도 한 번을 뜻하고, 재시도도 ExecutionControl 의 같은 예산과 마감을 쓴다. */
    static final int DECISION_MAX_ATTEMPTS = 2;
    /**
     * 다시 시도할 결정 오류 category 다. OpenAiDecisionProvider.classify 와 SkillExecutor 의 awaitRemote 가 이 이름을 만든다.
     * 인증 오류(API_AUTH), 응답 형식 오류(MODEL_FORMAT_ERROR), 예산과 토큰 한도 초과는 다시 시도하지 않으며
     * order-agent/backend/src/test/java/com/capstone4/orderagent/harness 의 DecisionLimitTest 와 SkillExecutorTraceOrderTest 가
     * 이 구분을 고정한다.
     */
    static final Set<String> RETRYABLE_DECISION_ERRORS = Set.of("API_TIMEOUT", "API_RATE_LIMIT", "API_TRANSIENT");
}
