/**
 * 비어 있는 패키지다. 이 설명 파일 말고는 클래스가 없고, service 의 어떤 코드도 이 패키지를 쓰지 않는다.
 * 2026-10-20 에 역할(A, B, C-1, C-2, D)을 나눌 때 D 담당의 현업 주문 도메인 자리로 만들었다. 계획은 주문과 주문 항목 엔티티를 두고,
 * 주문을 저장한 뒤 harness 를 live 모드로 실행해 상태를 저장하고 실패하면 webhook 을 보내는 주문 서비스를 두는 것이었다.
 * 그 뒤로 이 패키지에 클래스를 넣은 커밋이 없어서 지금도 비어 있다. 주문 도메인은 별도 앱인 주문 Agent 에 만들어졌다.
 * 엔티티는 order-agent/backend/src/main/java/com/capstone4/orderagent/entity/ 의 Order 와 OrderItem 이고, 주문 생성 흐름은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java 가 조율한다.
 */
package com.capstone4.skillops.domain;
