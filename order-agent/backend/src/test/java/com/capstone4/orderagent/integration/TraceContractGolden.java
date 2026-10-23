package com.capstone4.orderagent.integration;

/**
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractIntegrationTest.java 가 비교하는 실행 경로별 기대 줄 모음이다.
 * 리팩토링 전에 관찰한 결과를 그대로 적어 두어, 주문 Agent 의 trace 이벤트 순서와 data, 실행 요약, 주문 단계 기록, 재고가 리팩토링 뒤에도
 * 같은지 확인하는 기준으로 쓴다. 상수 하나가 실행 경로 하나(정상, 재고 부족, 결제 거절, 각종 계약 위반과 모델 응답 오류, 모의 실행, 재시작 복구, 도구 재생)다.
 * 줄 모양은 TraceContractIntegrationTest 의 describe 가 만들고, 실행마다 달라지는 식별자, 해시, 시간, 설명 문구는 Canonical 이 자리표시자로 감춘다.
 * 기대 줄에 data 를 적지 않은 이벤트는 SHIPPED 의 같은 위치 줄과 내용이 같아 생략한 것이며, 이런 줄은 KIND:skill_id 만 비교한다.
 * 이벤트 이름이나 data 키를 일부러 바꿀 때는 이 기대 줄과 함께 trace 소비자
 * (service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java, frontend/src/utils/eventView.js,
 * skillops-agent/skillops_agent/contracts.py)를 고친다.
 */
final class TraceContractGolden {
    private TraceContractGolden() {}

    static final String SHIPPED = """
        summary: completion=COMPLETED final_status=SHIPPED error_category=null raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: none
        order: SHIPPED null payment=PAID shipment=true notification=SENT
        stock: lantern=9 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        PAYMENT SUCCESS null Mock 결제 승인 완료
        SHIPPING SUCCESS null Mock 배송 예약 완료
        NOTIFICATION SUCCESS null Mock 성공 알림 기록 완료
        events:
        EXECUTION_STARTED {"mode":"fixed","order_id":"<order>"}
        SNAPSHOT_FIXED {"hash":"<snapshot-hash>","snapshot_id":"<snapshot>"}
        CANDIDATES_OFFERED {"candidates":[{"description":"<description:inventory-check>","name":"inventory-check","skill_id":"inventory-check","version":1},{"description":"<description:notify>","name":"notify","skill_id":"notify","version":1},{"description":"<description:order-flow>","name":"order-flow","skill_id":"order-flow","version":1},{"description":"<description:order-intake>","name":"order-intake","skill_id":"order-intake","version":1},{"description":"<description:payment>","name":"payment","skill_id":"payment","version":1},{"description":"<description:shipping-reserve>","name":"shipping-reserve","skill_id":"shipping-reserve","version":1}]}
        DECISION_INPUT {"inputs":{"order.customer":"c_1001","order.id":"<order>","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"purpose":"select","skill_hash":"","tool_definition":{}}
        MODEL_RESPONSE {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"skill\\":\\"order-flow\\"}"}
        SKILL_SELECTED {"raw_name":"order-flow","resolved_skill_id":null}
        SKILL_RESOLVED {"raw_name":"order-flow","resolved_skill_id":"order-flow"}
        SKILL_LOADED:order-flow {"hash":"<hash:order-flow>","path":"skills/order-flow/SKILL.md","version":1}
        FLOW_VALIDATED:order-flow {"steps":["order-intake","inventory-check","payment","shipping-reserve","notify"]}
        SKILL_LOADED:order-intake {"hash":"<hash:order-intake>","path":"skills/order-intake/SKILL.md","version":1}
        SKILL_STARTED:order-intake {"hash":"<hash:order-intake>","input":{"order.id":"<order>","order.customer":"c_1001","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"step_no":1,"version":1}
        DECISION_INPUT:order-intake {"inputs":{"order.id":"<order>","order.customer":"c_1001","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"purpose":"tool","skill_hash":"<hash:order-intake>","tool_definition":"<definition:order.inspect>"}
        MODEL_RESPONSE:order-intake {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":{\\"order_id\\":\\"<order>\\"},\\"tool\\":\\"order.inspect\\"}"}
        TOOL_REQUEST:order-intake {"arguments":{"order_id":"<order>"},"tool":"order.inspect"}
        TOOL_RESULT:order-intake {"arguments":{"order_id":"<order>"},"elapsed_ms":"<ms>","result":{"context":{"order.id":"<order>","order.customer":"c_1001","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false},"order.total":30000},"final_status":"PROCESSING"},"tool":"order.inspect"}
        SKILL_COMPLETED:order-intake {"declared_output":{"order.id":"<order>","order.total":30000}}
        SKILL_FINISHED:order-intake {"duration_ms":"<ms>","output":{"order.id":"<order>","order.total":30000},"status":"ok","step_no":1,"version":1}
        SKILL_LOADED:inventory-check {"hash":"<hash:inventory-check>","path":"skills/inventory-check/SKILL.md","version":1}
        SKILL_STARTED:inventory-check {"hash":"<hash:inventory-check>","input":{"order.id":"<order>","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}]},"step_no":2,"version":1}
        DECISION_INPUT:inventory-check {"inputs":{"order.id":"<order>","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}]},"purpose":"tool","skill_hash":"<hash:inventory-check>","tool_definition":"<definition:inventory.reserve>"}
        MODEL_RESPONSE:inventory-check {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":{\\"order_id\\":\\"<order>\\",\\"items\\":[{\\"qty\\":1,\\"sku\\":\\"CAMP-LANTERN-LED\\"}]},\\"tool\\":\\"inventory.reserve\\"}"}
        TOOL_REQUEST:inventory-check {"arguments":{"order_id":"<order>","items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}]},"tool":"inventory.reserve"}
        TOOL_RESULT:inventory-check {"arguments":{"order_id":"<order>","items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}]},"elapsed_ms":"<ms>","result":{"context":{"inventory.available":true,"inventory.reservable_quantity":10,"inventory.reserved":true},"final_status":"PROCESSING"},"tool":"inventory.reserve"}
        SKILL_COMPLETED:inventory-check {"declared_output":{"inventory.available":true,"inventory.reserved":true}}
        SKILL_FINISHED:inventory-check {"duration_ms":"<ms>","output":{"inventory.available":true,"inventory.reserved":true},"status":"ok","step_no":2,"version":1}
        SKILL_LOADED:payment {"hash":"<hash:payment>","path":"skills/payment/SKILL.md","version":1}
        SKILL_STARTED:payment {"hash":"<hash:payment>","input":{"order.id":"<order>","order.total":30000,"order.payment_method":"card","inventory.reserved":true},"step_no":3,"version":1}
        DECISION_INPUT:payment {"inputs":{"order.id":"<order>","order.total":30000,"order.payment_method":"card","inventory.reserved":true},"purpose":"tool","skill_hash":"<hash:payment>","tool_definition":"<definition:payment.charge>"}
        MODEL_RESPONSE:payment {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":{\\"order_id\\":\\"<order>\\",\\"amount\\":30000,\\"method\\":\\"card\\"},\\"tool\\":\\"payment.charge\\"}"}
        TOOL_REQUEST:payment {"arguments":{"order_id":"<order>","amount":30000,"method":"card"},"tool":"payment.charge"}
        TOOL_RESULT:payment {"arguments":{"order_id":"<order>","amount":30000,"method":"card"},"elapsed_ms":"<ms>","result":{"context":{"payment.status":"PAID","payment.id":"pay_mock_<order>"},"final_status":"PROCESSING"},"tool":"payment.charge"}
        SKILL_COMPLETED:payment {"declared_output":{"payment.id":"pay_mock_<order>","payment.status":"PAID"}}
        SKILL_FINISHED:payment {"duration_ms":"<ms>","output":{"payment.id":"pay_mock_<order>","payment.status":"PAID"},"status":"ok","step_no":3,"version":1}
        SKILL_LOADED:shipping-reserve {"hash":"<hash:shipping-reserve>","path":"skills/shipping-reserve/SKILL.md","version":1}
        SKILL_STARTED:shipping-reserve {"hash":"<hash:shipping-reserve>","input":{"order.id":"<order>","order.shipping":{"country":"KR","express":false},"payment.id":"pay_mock_<order>","payment.status":"PAID","inventory.reserved":true},"step_no":4,"version":1}
        DECISION_INPUT:shipping-reserve {"inputs":{"order.id":"<order>","order.shipping":{"country":"KR","express":false},"payment.id":"pay_mock_<order>","payment.status":"PAID","inventory.reserved":true},"purpose":"tool","skill_hash":"<hash:shipping-reserve>","tool_definition":"<definition:shipping.reserve>"}
        MODEL_RESPONSE:shipping-reserve {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":{\\"order_id\\":\\"<order>\\",\\"country\\":\\"KR\\",\\"express\\":false},\\"tool\\":\\"shipping.reserve\\"}"}
        TOOL_REQUEST:shipping-reserve {"arguments":{"order_id":"<order>","country":"KR","express":false},"tool":"shipping.reserve"}
        TOOL_RESULT:shipping-reserve {"arguments":{"order_id":"<order>","country":"KR","express":false},"elapsed_ms":"<ms>","result":{"context":{"shipping.id":"ship_mock_<order>","shipping.eta":"<eta>"},"final_status":"PROCESSING"},"tool":"shipping.reserve"}
        SKILL_COMPLETED:shipping-reserve {"declared_output":{"shipping.id":"ship_mock_<order>","shipping.eta":"<eta>"}}
        SKILL_FINISHED:shipping-reserve {"duration_ms":"<ms>","output":{"shipping.id":"ship_mock_<order>","shipping.eta":"<eta>"},"status":"ok","step_no":4,"version":1}
        SKILL_LOADED:notify {"hash":"<hash:notify>","path":"skills/notify/SKILL.md","version":1}
        SKILL_STARTED:notify {"hash":"<hash:notify>","input":{"order.id":"<order>","order.customer":"c_1001","payment.status":"PAID","shipping.id":"ship_mock_<order>","shipping.eta":"<eta>"},"step_no":5,"version":1}
        DECISION_INPUT:notify {"inputs":{"order.id":"<order>","order.customer":"c_1001","payment.status":"PAID","shipping.id":"ship_mock_<order>","shipping.eta":"<eta>"},"purpose":"tool","skill_hash":"<hash:notify>","tool_definition":"<definition:notify.send>"}
        MODEL_RESPONSE:notify {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":{\\"order_id\\":\\"<order>\\",\\"customer\\":\\"c_1001\\"},\\"tool\\":\\"notify.send\\"}"}
        TOOL_REQUEST:notify {"arguments":{"order_id":"<order>","customer":"c_1001"},"tool":"notify.send"}
        TOOL_RESULT:notify {"arguments":{"order_id":"<order>","customer":"c_1001"},"elapsed_ms":"<ms>","result":{"context":{"notify.sent":true,"notify.channel":"mock"},"final_status":"PROCESSING"},"tool":"notify.send"}
        SKILL_COMPLETED:notify {"declared_output":{"notify.sent":true,"notify.channel":"mock"}}
        SKILL_FINISHED:notify {"duration_ms":"<ms>","output":{"notify.sent":true,"notify.channel":"mock"},"status":"ok","step_no":5,"version":1}
        EXECUTION_FINISHED {"completion":"COMPLETED","final_status":"SHIPPED"}
        """;

    static final String OUT_OF_STOCK = """
        summary: completion=COMPLETED final_status=REJECTED error_category=null raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: none
        order: REJECTED OUT_OF_STOCK payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY REJECTED OUT_OF_STOCK 재고 부족으로 전체 예약을 거절했습니다.
        PAYMENT SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT {"inputs":{"order.customer":"c_1001","order.id":"<order>","order.items":[{"qty":1,"sku":"CAMP-MUG-STEEL"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"purpose":"select","skill_hash":"","tool_definition":{}}
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake {"hash":"<hash:order-intake>","input":{"order.id":"<order>","order.customer":"c_1001","order.items":[{"qty":1,"sku":"CAMP-MUG-STEEL"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"step_no":1,"version":1}
        DECISION_INPUT:order-intake {"inputs":{"order.id":"<order>","order.customer":"c_1001","order.items":[{"qty":1,"sku":"CAMP-MUG-STEEL"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"purpose":"tool","skill_hash":"<hash:order-intake>","tool_definition":"<definition:order.inspect>"}
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake {"arguments":{"order_id":"<order>"},"elapsed_ms":"<ms>","result":{"context":{"order.id":"<order>","order.customer":"c_1001","order.items":[{"qty":1,"sku":"CAMP-MUG-STEEL"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false},"order.total":12000},"final_status":"PROCESSING"},"tool":"order.inspect"}
        SKILL_COMPLETED:order-intake {"declared_output":{"order.id":"<order>","order.total":12000}}
        SKILL_FINISHED:order-intake {"duration_ms":"<ms>","output":{"order.id":"<order>","order.total":12000},"status":"ok","step_no":1,"version":1}
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check {"hash":"<hash:inventory-check>","input":{"order.id":"<order>","order.items":[{"qty":1,"sku":"CAMP-MUG-STEEL"}]},"step_no":2,"version":1}
        DECISION_INPUT:inventory-check {"inputs":{"order.id":"<order>","order.items":[{"qty":1,"sku":"CAMP-MUG-STEEL"}]},"purpose":"tool","skill_hash":"<hash:inventory-check>","tool_definition":"<definition:inventory.reserve>"}
        MODEL_RESPONSE:inventory-check {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":{\\"order_id\\":\\"<order>\\",\\"items\\":[{\\"qty\\":1,\\"sku\\":\\"CAMP-MUG-STEEL\\"}]},\\"tool\\":\\"inventory.reserve\\"}"}
        TOOL_REQUEST:inventory-check {"arguments":{"order_id":"<order>","items":[{"qty":1,"sku":"CAMP-MUG-STEEL"}]},"tool":"inventory.reserve"}
        TOOL_RESULT:inventory-check {"arguments":{"order_id":"<order>","items":[{"qty":1,"sku":"CAMP-MUG-STEEL"}]},"elapsed_ms":"<ms>","result":{"context":{"inventory.available":false,"inventory.reservable_quantity":0,"inventory.reserved":false},"final_status":"REJECTED"},"tool":"inventory.reserve"}
        SKILL_FINISHED:inventory-check {"business_stop":true,"duration_ms":"<ms>","final_status":"REJECTED","output":{"inventory.available":false,"inventory.reserved":false},"status":"ok","step_no":2,"version":1}
        BUSINESS_STOP:inventory-check {"final_status":"REJECTED"}
        EXECUTION_FINISHED {"completion":"COMPLETED","error_category":null,"final_status":"REJECTED"}
        """;

    static final String PAYMENT_DECLINED = """
        summary: completion=COMPLETED final_status=HOLD error_category=null raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: none
        order: HOLD PAYMENT_DECLINED payment=DECLINED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        PAYMENT REJECTED PAYMENT_DECLINED Mock 결제가 거절되었습니다.
        RELEASE SUCCESS null 예약 재고를 모두 복원했습니다.
        SHIPPING SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT {"inputs":{"order.customer":"c_declined","order.id":"<order>","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"purpose":"select","skill_hash":"","tool_definition":{}}
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake {"hash":"<hash:order-intake>","input":{"order.id":"<order>","order.customer":"c_declined","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"step_no":1,"version":1}
        DECISION_INPUT:order-intake {"inputs":{"order.id":"<order>","order.customer":"c_declined","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false}},"purpose":"tool","skill_hash":"<hash:order-intake>","tool_definition":"<definition:order.inspect>"}
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake {"arguments":{"order_id":"<order>"},"elapsed_ms":"<ms>","result":{"context":{"order.id":"<order>","order.customer":"c_declined","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false},"order.total":30000},"final_status":"PROCESSING"},"tool":"order.inspect"}
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        MODEL_RESPONSE:payment
        TOOL_REQUEST:payment
        TOOL_REQUEST:payment {"arguments":{"order_id":"<order>"},"reason":"PAYMENT_DECLINED","tool":"inventory.release"}
        TOOL_RESULT:payment {"arguments":{"order_id":"<order>"},"result":{"inventory.reserved":false},"tool":"inventory.release"}
        TOOL_RESULT:payment {"arguments":{"order_id":"<order>","amount":30000,"method":"card"},"elapsed_ms":"<ms>","result":{"context":{"payment.status":"DECLINED"},"final_status":"HOLD"},"tool":"payment.charge"}
        SKILL_FINISHED:payment {"business_stop":true,"duration_ms":"<ms>","final_status":"HOLD","output":{"payment.status":"DECLINED"},"status":"ok","step_no":3,"version":1}
        BUSINESS_STOP:payment {"final_status":"HOLD"}
        EXECUTION_FINISHED {"completion":"COMPLETED","error_category":null,"final_status":"HOLD"}
        """;

    static final String STATIC_CONTRACT_VIOLATION = """
        summary: completion=FAILED final_status=FAILED error_category=CONTRACT_VIOLATION raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED CONTRACT_VIOLATION payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        PAYMENT FAILED CONTRACT_VIOLATION 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        EXECUTION_ERROR:payment {"category":"CONTRACT_VIOLATION","message":"Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다.","missing_dependencies":[],"missing_inputs":["inventory.reserved"],"phase":"static_validation","skill_id":"payment"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"CONTRACT_VIOLATION","final_status":"FAILED"}
        """;

    static final String RUNTIME_MISSING_OUTPUT = """
        summary: completion=FAILED final_status=FAILED error_category=MISSING_SKILL_OUTPUT raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED MISSING_SKILL_OUTPUT payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        RELEASE SUCCESS null 예약 재고를 모두 복원했습니다.
        INVENTORY FAILED MISSING_SKILL_OUTPUT 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_FINISHED:inventory-check {"category":"MISSING_SKILL_OUTPUT","duration_ms":"<ms>","output":{},"status":"error","step_no":2,"version":1}
        TOOL_REQUEST {"arguments":{"order_id":"<order>"},"reason":"MISSING_SKILL_OUTPUT","tool":"inventory.release"}
        TOOL_RESULT {"arguments":{"order_id":"<order>"},"result":{"inventory.reserved":false},"tool":"inventory.release"}
        EXECUTION_ERROR:inventory-check {"category":"MISSING_SKILL_OUTPUT","skill_id":"inventory-check"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"MISSING_SKILL_OUTPUT","final_status":"FAILED"}
        """;

    static final String NO_SELECTION = """
        summary: completion=COMPLETED final_status=REJECTED error_category=NO_SELECTION raw_selection=none resolved_skill_id=null agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: none
        order: REJECTED NO_SELECTION payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"skill\\":\\"none\\"}"}
        SKILL_SELECTED {"raw_name":"none","resolved_skill_id":null}
        EXECUTION_FINISHED {"completion":"COMPLETED","error_category":"NO_SELECTION","final_status":"REJECTED"}
        """;

    static final String UNKNOWN_SKILL = """
        summary: completion=FAILED final_status=FAILED error_category=UNKNOWN_SKILL raw_selection=does-not-exist resolved_skill_id=null agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED UNKNOWN_SKILL payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        RECOVERY FAILED UNKNOWN_SKILL 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"skill\\":\\"does-not-exist\\"}"}
        SKILL_SELECTED {"raw_name":"does-not-exist","resolved_skill_id":null}
        SKILL_LOAD_REJECTED {"category":"UNKNOWN_SKILL","requested_name":"does-not-exist"}
        EXECUTION_ERROR {"category":"UNKNOWN_SKILL"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"UNKNOWN_SKILL","final_status":"FAILED"}
        """;

    static final String ROOT_MISMATCH = """
        summary: completion=FAILED final_status=FAILED error_category=ROOT_SKILL_MISMATCH raw_selection=payment resolved_skill_id=payment agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED ROOT_SKILL_MISMATCH payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        RECOVERY FAILED ROOT_SKILL_MISMATCH 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"skill\\":\\"payment\\"}"}
        SKILL_SELECTED {"raw_name":"payment","resolved_skill_id":null}
        SKILL_RESOLVED {"raw_name":"payment","resolved_skill_id":"payment"}
        SKILL_LOADED:payment {"hash":"<hash:payment>","path":"skills/payment/SKILL.md","version":1}
        EXECUTION_ERROR {"category":"ROOT_SKILL_MISMATCH"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"ROOT_SKILL_MISMATCH","final_status":"FAILED"}
        """;

    static final String UNSUPPORTED_STEP = """
        summary: completion=FAILED final_status=FAILED error_category=UNSUPPORTED_SKILL raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED UNSUPPORTED_SKILL payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        RECOVERY FAILED UNSUPPORTED_SKILL 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED {"candidates":[{"description":"<description:gift-wrap>","name":"gift-wrap","skill_id":"gift-wrap","version":1},{"description":"<description:inventory-check>","name":"inventory-check","skill_id":"inventory-check","version":1},{"description":"<description:notify>","name":"notify","skill_id":"notify","version":1},{"description":"<description:order-flow>","name":"order-flow","skill_id":"order-flow","version":1},{"description":"<description:order-intake>","name":"order-intake","skill_id":"order-intake","version":1},{"description":"<description:payment>","name":"payment","skill_id":"payment","version":1},{"description":"<description:shipping-reserve>","name":"shipping-reserve","skill_id":"shipping-reserve","version":1}]}
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        EXECUTION_ERROR {"category":"UNSUPPORTED_SKILL"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"UNSUPPORTED_SKILL","final_status":"FAILED"}
        """;

    static final String TOOL_NOT_ALLOWED = """
        summary: completion=FAILED final_status=FAILED error_category=TOOL_NOT_ALLOWED raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED TOOL_NOT_ALLOWED payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        RELEASE SUCCESS null 예약 재고를 모두 복원했습니다.
        PAYMENT FAILED TOOL_NOT_ALLOWED 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        MODEL_RESPONSE:payment {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":{\\"order_id\\":\\"x\\",\\"country\\":\\"KR\\"},\\"tool\\":\\"shipping.reserve\\"}"}
        TOOL_REQUEST:payment {"arguments":{"order_id":"x","country":"KR"},"tool":"shipping.reserve"}
        TOOL_ERROR:payment {"arguments":{"order_id":"x","country":"KR"},"category":"TOOL_NOT_ALLOWED","tool":"shipping.reserve"}
        SKILL_FINISHED:payment {"category":"TOOL_NOT_ALLOWED","duration_ms":"<ms>","output":{},"status":"error","step_no":3,"version":1}
        TOOL_REQUEST {"arguments":{"order_id":"<order>"},"reason":"TOOL_NOT_ALLOWED","tool":"inventory.release"}
        TOOL_RESULT {"arguments":{"order_id":"<order>"},"result":{"inventory.reserved":false},"tool":"inventory.release"}
        EXECUTION_ERROR:payment {"category":"TOOL_NOT_ALLOWED","skill_id":"payment"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"TOOL_NOT_ALLOWED","final_status":"FAILED"}
        """;

    static final String SELECT_NOT_STRING = """
        summary: completion=FAILED final_status=FAILED error_category=MODEL_FORMAT_ERROR raw_selection=123 resolved_skill_id=null agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED MODEL_FORMAT_ERROR payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        RECOVERY FAILED MODEL_FORMAT_ERROR 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"skill\\":123}"}
        SKILL_SELECTED {"raw_name":"123","resolved_skill_id":null}
        EXECUTION_ERROR {"category":"MODEL_FORMAT_ERROR"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"MODEL_FORMAT_ERROR","final_status":"FAILED"}
        """;

    static final String SELECT_NOT_JSON = """
        summary: completion=FAILED final_status=FAILED error_category=MODEL_FORMAT_ERROR raw_selection=null resolved_skill_id=null agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED MODEL_FORMAT_ERROR payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        RECOVERY FAILED MODEL_FORMAT_ERROR 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE {"input_tokens":null,"output_tokens":null,"raw_response":"not json"}
        DECISION_ERROR {"attempt":1,"category":"MODEL_FORMAT_ERROR"}
        EXECUTION_ERROR {"category":"MODEL_FORMAT_ERROR"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"MODEL_FORMAT_ERROR","final_status":"FAILED"}
        """;

    static final String TOKEN_LIMIT = """
        summary: completion=FAILED final_status=FAILED error_category=TOKEN_LIMIT_EXCEEDED raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=10001 output_tokens=0
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED TOKEN_LIMIT_EXCEEDED payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        RELEASE SUCCESS null 예약 재고를 모두 복원했습니다.
        PAYMENT FAILED TOKEN_LIMIT_EXCEEDED 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        MODEL_RESPONSE:payment {"input_tokens":10001,"output_tokens":0,"raw_response":"json:{}"}
        DECISION_ERROR:payment {"attempt":1,"category":"TOKEN_LIMIT_EXCEEDED"}
        SKILL_FINISHED:payment {"category":"TOKEN_LIMIT_EXCEEDED","duration_ms":"<ms>","output":{},"status":"error","step_no":3,"version":1}
        TOOL_REQUEST {"arguments":{"order_id":"<order>"},"reason":"TOKEN_LIMIT_EXCEEDED","tool":"inventory.release"}
        TOOL_RESULT {"arguments":{"order_id":"<order>"},"result":{"inventory.reserved":false},"tool":"inventory.release"}
        EXECUTION_ERROR:payment {"category":"TOKEN_LIMIT_EXCEEDED","skill_id":"payment"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"TOKEN_LIMIT_EXCEEDED","final_status":"FAILED"}
        """;

    static final String TOOL_FORMAT_ERROR = """
        summary: completion=FAILED final_status=FAILED error_category=MODEL_FORMAT_ERROR raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED MODEL_FORMAT_ERROR payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        RELEASE SUCCESS null 예약 재고를 모두 복원했습니다.
        PAYMENT FAILED MODEL_FORMAT_ERROR 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        MODEL_RESPONSE:payment {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":\\"oops\\",\\"tool\\":\\"payment.charge\\"}"}
        SKILL_FINISHED:payment {"category":"MODEL_FORMAT_ERROR","duration_ms":"<ms>","output":{},"status":"error","step_no":3,"version":1}
        TOOL_REQUEST {"arguments":{"order_id":"<order>"},"reason":"MODEL_FORMAT_ERROR","tool":"inventory.release"}
        TOOL_RESULT {"arguments":{"order_id":"<order>"},"result":{"inventory.reserved":false},"tool":"inventory.release"}
        EXECUTION_ERROR:payment {"category":"MODEL_FORMAT_ERROR","skill_id":"payment"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"MODEL_FORMAT_ERROR","final_status":"FAILED"}
        """;

    static final String INVALID_TOOL_ARGUMENTS = """
        summary: completion=FAILED final_status=FAILED error_category=INVALID_TOOL_ARGUMENTS raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED INVALID_TOOL_ARGUMENTS payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        RELEASE SUCCESS null 예약 재고를 모두 복원했습니다.
        PAYMENT FAILED INVALID_TOOL_ARGUMENTS 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        MODEL_RESPONSE:payment {"input_tokens":null,"output_tokens":null,"raw_response":"json:{\\"arguments\\":{\\"order_id\\":\\"wrong-order\\",\\"amount\\":1,\\"method\\":\\"card\\"},\\"tool\\":\\"payment.charge\\"}"}
        TOOL_REQUEST:payment {"arguments":{"order_id":"wrong-order","amount":1,"method":"card"},"tool":"payment.charge"}
        TOOL_ERROR:payment {"arguments":{"order_id":"wrong-order","amount":1,"method":"card"},"category":"INVALID_TOOL_ARGUMENTS","tool":"payment.charge"}
        SKILL_FINISHED:payment {"category":"INVALID_TOOL_ARGUMENTS","duration_ms":"<ms>","output":{},"status":"error","step_no":3,"version":1}
        TOOL_REQUEST {"arguments":{"order_id":"<order>"},"reason":"INVALID_TOOL_ARGUMENTS","tool":"inventory.release"}
        TOOL_RESULT {"arguments":{"order_id":"<order>"},"result":{"inventory.reserved":false},"tool":"inventory.release"}
        EXECUTION_ERROR:payment {"category":"INVALID_TOOL_ARGUMENTS","skill_id":"payment"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"INVALID_TOOL_ARGUMENTS","final_status":"FAILED"}
        """;

    static final String BUSINESS_TOOL_FAILURE = """
        summary: completion=FAILED final_status=FAILED error_category=TOOL_ERROR raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED TOOL_ERROR payment=PAID shipment=false notification=NOT_ATTEMPTED
        stock: lantern=9 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        PAYMENT SUCCESS null Mock 결제 승인 완료
        SHIPPING FAILED TOOL_ERROR 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        MODEL_RESPONSE:payment
        TOOL_REQUEST:payment
        TOOL_RESULT:payment
        SKILL_COMPLETED:payment
        SKILL_FINISHED:payment
        SKILL_LOADED:shipping-reserve
        SKILL_STARTED:shipping-reserve
        DECISION_INPUT:shipping-reserve
        MODEL_RESPONSE:shipping-reserve
        TOOL_REQUEST:shipping-reserve
        TOOL_ERROR:shipping-reserve {"arguments":{"order_id":"<order>","country":"KR","express":false},"category":"TOOL_ERROR","tool":"shipping.reserve"}
        SKILL_FINISHED:shipping-reserve {"category":"TOOL_ERROR","duration_ms":"<ms>","output":{},"status":"error","step_no":4,"version":1}
        EXECUTION_ERROR:shipping-reserve {"category":"TOOL_ERROR","skill_id":"shipping-reserve"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"TOOL_ERROR","final_status":"FAILED"}
        """;

    static final String DECISION_PROVIDER_CRASH = """
        summary: completion=FAILED final_status=FAILED error_category=EXECUTION_ERROR raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED EXECUTION_ERROR payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        RELEASE SUCCESS null 예약 재고를 모두 복원했습니다.
        PAYMENT FAILED EXECUTION_ERROR 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        SKILL_FINISHED:payment {"category":"EXECUTION_ERROR","duration_ms":"<ms>","output":{},"status":"error","step_no":3,"version":1}
        TOOL_REQUEST {"arguments":{"order_id":"<order>"},"reason":"EXECUTION_ERROR","tool":"inventory.release"}
        TOOL_RESULT {"arguments":{"order_id":"<order>"},"result":{"inventory.reserved":false},"tool":"inventory.release"}
        EXECUTION_ERROR:payment {"category":"EXECUTION_ERROR","skill_id":"payment"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"EXECUTION_ERROR","final_status":"FAILED"}
        """;

    static final String INCOMPLETE_FLOW = """
        summary: completion=FAILED final_status=FAILED error_category=INCOMPLETE_FLOW raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED INCOMPLETE_FLOW payment=PAID shipment=true notification=NOT_ATTEMPTED
        stock: lantern=9 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        PAYMENT SUCCESS null Mock 결제 승인 완료
        SHIPPING SUCCESS null Mock 배송 예약 완료
        SHIPPING FAILED INCOMPLETE_FLOW 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow {"steps":["order-intake","inventory-check","payment","shipping-reserve"]}
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        MODEL_RESPONSE:payment
        TOOL_REQUEST:payment
        TOOL_RESULT:payment
        SKILL_COMPLETED:payment
        SKILL_FINISHED:payment
        SKILL_LOADED:shipping-reserve
        SKILL_STARTED:shipping-reserve
        DECISION_INPUT:shipping-reserve
        MODEL_RESPONSE:shipping-reserve
        TOOL_REQUEST:shipping-reserve
        TOOL_RESULT:shipping-reserve
        SKILL_COMPLETED:shipping-reserve
        SKILL_FINISHED:shipping-reserve
        EXECUTION_ERROR {"category":"INCOMPLETE_FLOW"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"INCOMPLETE_FLOW","final_status":"FAILED"}
        """;

    static final String NESTED_FLOW = """
        summary: completion=FAILED final_status=FAILED error_category=CONTRACT_VIOLATION raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED CONTRACT_VIOLATION payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        RECOVERY FAILED CONTRACT_VIOLATION 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED {"candidates":[{"description":"<description:inventory-check>","name":"inventory-check","skill_id":"inventory-check","version":1},{"description":"<description:notify>","name":"notify","skill_id":"notify","version":1},{"description":"<description:order-flow>","name":"order-flow","skill_id":"order-flow","version":1},{"description":"<description:order-intake>","name":"order-intake","skill_id":"order-intake","version":1},{"description":"<description:payment>","name":"payment","skill_id":"payment","version":1},{"description":"<description:shipping-reserve>","name":"shipping-reserve","skill_id":"shipping-reserve","version":1},{"description":"<description:sub-flow>","name":"sub-flow","skill_id":"sub-flow","version":1}]}
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        EXECUTION_ERROR:sub-flow {"category":"CONTRACT_VIOLATION","message":"단계 Skill에 중첩 flow를 사용할 수 없습니다.","missing_dependencies":[],"missing_inputs":[],"phase":"static_validation","reason":"NESTED_FLOW_NOT_SUPPORTED","skill_id":"sub-flow"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"CONTRACT_VIOLATION","final_status":"FAILED"}
        """;

    static final String DUPLICATE_FLOW_STEPS = """
        summary: completion=FAILED final_status=FAILED error_category=INVALID_FLOW raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED INVALID_FLOW payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        RECOVERY FAILED INVALID_FLOW 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        EXECUTION_ERROR {"category":"INVALID_FLOW"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"INVALID_FLOW","final_status":"FAILED"}
        """;

    static final String INVALID_CANDIDATE = """
        summary: completion=FAILED final_status=FAILED error_category=INVALID_SKILL_FORMAT raw_selection=null resolved_skill_id=null agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: BusinessException INTERNAL_ERROR order_id=true
        order: FAILED INVALID_SKILL_FORMAT payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        RECOVERY FAILED INVALID_SKILL_FORMAT 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        EXECUTION_ERROR {"category":"INVALID_SKILL_FORMAT"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"INVALID_SKILL_FORMAT","final_status":"FAILED"}
        """;

    static final String SNAPSHOT_TAMPERED = """
        summary: completion=FAILED final_status=FAILED error_category=SNAPSHOT_TAMPERED raw_selection=null resolved_skill_id=null agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: AgentException SNAPSHOT_TAMPERED
        order: FAILED SNAPSHOT_TAMPERED payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=9 chair=5 mug=0
        steps:
        RECOVERY FAILED SNAPSHOT_TAMPERED 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        INVENTORY SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        PAYMENT SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        EXECUTION_ERROR {"category":"SNAPSHOT_TAMPERED"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"SNAPSHOT_TAMPERED","final_status":"FAILED"}
        """;

    static final String DRY_RUN_SHIPPED = """
        summary: completion=COMPLETED final_status=SHIPPED error_category=null raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=dry_run model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: none
        order: SHIPPED null payment=PAID shipment=true notification=SENT
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        INVENTORY SUCCESS null 모든 상품의 재고 예약 완료
        PAYMENT SUCCESS null Mock 결제 승인 완료
        SHIPPING SUCCESS null Mock 배송 예약 완료
        NOTIFICATION SUCCESS null Mock 성공 알림 기록 완료
        RELEASE SUCCESS DRY_RUN_CLEANUP 모의 실행이 끝나 예약 재고를 모두 복원했습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        FLOW_VALIDATED:order-flow
        SKILL_LOADED:order-intake
        SKILL_STARTED:order-intake
        DECISION_INPUT:order-intake
        MODEL_RESPONSE:order-intake
        TOOL_REQUEST:order-intake
        TOOL_RESULT:order-intake
        SKILL_COMPLETED:order-intake
        SKILL_FINISHED:order-intake
        SKILL_LOADED:inventory-check
        SKILL_STARTED:inventory-check
        DECISION_INPUT:inventory-check
        MODEL_RESPONSE:inventory-check
        TOOL_REQUEST:inventory-check
        TOOL_RESULT:inventory-check
        SKILL_COMPLETED:inventory-check
        SKILL_FINISHED:inventory-check
        SKILL_LOADED:payment
        SKILL_STARTED:payment
        DECISION_INPUT:payment
        MODEL_RESPONSE:payment
        TOOL_REQUEST:payment
        TOOL_RESULT:payment
        SKILL_COMPLETED:payment
        SKILL_FINISHED:payment
        SKILL_LOADED:shipping-reserve
        SKILL_STARTED:shipping-reserve
        DECISION_INPUT:shipping-reserve
        MODEL_RESPONSE:shipping-reserve
        TOOL_REQUEST:shipping-reserve
        TOOL_RESULT:shipping-reserve
        SKILL_COMPLETED:shipping-reserve
        SKILL_FINISHED:shipping-reserve
        SKILL_LOADED:notify
        SKILL_STARTED:notify
        DECISION_INPUT:notify
        MODEL_RESPONSE:notify
        TOOL_REQUEST:notify
        TOOL_RESULT:notify
        SKILL_COMPLETED:notify
        SKILL_FINISHED:notify
        EXECUTION_FINISHED
        """;

    static final String DRY_RUN_FAILED = """
        summary: completion=FAILED final_status=FAILED error_category=CONTRACT_VIOLATION raw_selection=order-flow resolved_skill_id=order-flow agent_id=camping-order-agent mode=dry_run model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: none
        order: FAILED CONTRACT_VIOLATION payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=10 chair=5 mug=0
        steps:
        INTAKE SUCCESS null 주문 접수 완료
        PAYMENT FAILED CONTRACT_VIOLATION 실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.
        SHIPPING SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        NOTIFICATION SKIPPED UPSTREAM_FAILED 앞 단계 오류로 실행하지 않았습니다.
        events:
        EXECUTION_STARTED
        SNAPSHOT_FIXED
        CANDIDATES_OFFERED
        DECISION_INPUT
        MODEL_RESPONSE
        SKILL_SELECTED
        SKILL_RESOLVED
        SKILL_LOADED:order-flow
        EXECUTION_ERROR:payment {"category":"CONTRACT_VIOLATION","message":"Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다.","missing_dependencies":[],"missing_inputs":["inventory.reserved"],"phase":"static_validation","skill_id":"payment"}
        EXECUTION_FINISHED {"completion":"FAILED","error_category":"CONTRACT_VIOLATION","final_status":"FAILED"}
        """;

    static final String RECOVERY_UNPAID = """
        summary: completion=INTERRUPTED final_status=FAILED error_category=PROCESS_INTERRUPTED raw_selection=null resolved_skill_id=null agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: none
        order: FAILED PROCESS_INTERRUPTED payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=9 chair=5 mug=0
        steps:
        RELEASE SUCCESS PROCESS_INTERRUPTED 중단된 실행의 미결제 재고 예약을 복원했습니다.
        RECOVERY FAILED PROCESS_INTERRUPTED 이전 실행이 중단되었습니다. 확정된 결과는 보존하고 미결제 예약은 해제했습니다.
        events:
        TOOL_REQUEST {"arguments":{"order_id":"<order>"},"reason":"PROCESS_INTERRUPTED","tool":"inventory.release"}
        TOOL_RESULT {"result":{"inventory.reserved":false},"tool":"inventory.release"}
        EXECUTION_INTERRUPTED {"category":"PROCESS_INTERRUPTED"}
        """;

    static final String TOOL_REPLAY = """
        summary: completion=FAILED final_status=null error_category=null raw_selection=null resolved_skill_id=null agent_id=camping-order-agent mode=live model_mode=fixed model=fixed-policy-v1 api_calls=0 input_tokens=null output_tokens=null
        failure: none
        order: HOLD null payment=NOT_ATTEMPTED shipment=false notification=NOT_ATTEMPTED
        stock: lantern=9 chair=5 mug=0
        steps:
        events:
        EXECUTION_STARTED
        TOOL_RESULT:order-intake {"arguments":{"order_id":"<order>"},"elapsed_ms":"<ms>","result":{"context":{"order.id":"<order>","order.customer":"c_1001","order.items":[{"qty":1,"sku":"CAMP-LANTERN-LED"}],"order.payment_method":"card","order.shipping":{"country":"KR","express":false},"order.total":30000},"final_status":"PROCESSING"},"tool":"order.inspect"}
        TOOL_REPLAY:order-intake {"arguments":{"order_id":"<order>"},"tool":"order.inspect"}
        TOOL_REPLAY:order-intake {"arguments":{"order_id":"<order>"},"tool":"order.inspect"}
        """;
}
