/**
 * 비어 있는 패키지다. 이 설명 파일 말고는 클래스가 없고, service 의 어떤 코드도 이 패키지를 쓰지 않는다.
 * 2026-10-20 에 역할(A, B, C-1, C-2, D)을 나눌 때 C-2 담당의 webhook 자리로 만들었다. 계획은 Skill 저장(skill_saved)과 장애(failure)
 * 이벤트를 SkillOps Agent 의 POST /diagnose 로 비동기로 보내고, 보내지 못해도 주문 흐름에는 영향을 주지 않는 것이었다.
 * 그 뒤로 이 패키지에 클래스를 넣은 커밋이 없어서 지금도 비어 있다. Skill 저장 이벤트는 이 패키지를 거치지 않는 다른 길로 간다.
 * 주문 Agent 의 order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java 가 저장 이벤트를
 * service 의 POST /internal/skill-changes 로 보내고, service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java
 * 가 그 이벤트를 Agent 의 POST /diagnose 로 중계한다.
 * 장애 이벤트를 보내는 코드는 아직 없다. order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java 에 그
 * 일을 적은 TODO 가 남아 있다.
 */
package com.capstone4.skillops.webhook;
