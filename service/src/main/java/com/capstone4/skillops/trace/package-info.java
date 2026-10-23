/**
 * 비어 있는 패키지다. 이 설명 파일 말고는 클래스가 없고, service 의 어떤 코드도 이 패키지를 쓰지 않는다.
 * 저장소 뼈대를 처음 만들 때 W1 작업인 실행 트레이스 자리로 만들었다. 계획은 run_id 단위로 skill 호출 순서, 입출력, 에러를
 * docs/contracts/trace.schema.json 모양으로 저장하는 것이었다.
 * 이 패키지에 실행 기록 저장소를 넣은 커밋(1814824)이 한 번 있었지만 같은 날 되돌려졌고(9d0c27e), 그 뒤로 클래스를 넣은 커밋이
 * 없어서 지금은 비어 있다. 되돌린 커밋에는 이유가 적혀 있지 않다.
 * 지금 실행 기록은 주문 Agent 의 order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java 가 저장한다.
 * service 는 service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 trace 로 주문 Agent 의
 * GET /api/executions/{id}/trace 를 불러 그 기록을 읽는다.
 */
package com.capstone4.skillops.trace;
