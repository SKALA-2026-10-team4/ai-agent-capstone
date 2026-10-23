/**
 * 비어 있는 패키지다. 이 설명 파일 말고는 클래스가 없고, service 의 어떤 코드도 이 패키지를 쓰지 않는다.
 * 저장소 뼈대를 처음 만들 때 W1 작업인 Mock 어댑터(재고, 결제, 배송, 알림) 자리로 만들었다. 계획은 live 모드에서는 실제 연동을
 * 붙이고 dry-run 모드에서는 결정적인 응답을 돌려주는 것이었다.
 * 그 뒤로 이 패키지에 클래스를 넣은 커밋이 없어서 지금도 비어 있다. 네 어댑터는 별도 앱인 주문 Agent 에 만들어졌고,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ 의 InventoryMockAdapter, PaymentMockAdapter,
 * ShippingMockAdapter, NotificationMockAdapter 가 그것이다.
 */
package com.capstone4.skillops.adapter;
