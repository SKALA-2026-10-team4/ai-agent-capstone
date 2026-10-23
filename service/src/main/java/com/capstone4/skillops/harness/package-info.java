/**
 * 비어 있는 패키지다. 이 설명 파일 말고는 클래스가 없고, service 의 어떤 코드도 이 패키지를 쓰지 않는다.
 * 저장소 뼈대를 처음 만들 때 W1 작업인 Harness 자리로 만들었다. 계획은 SKILL.md 의 frontmatter 를 레지스트리에, 본문을 system
 * prompt 에 넣는 로더와 ChatClient 와 @Tool 로 skill 을 실행하는 실행기를 두고, live/ 와 workspace/ 가운데 하나를 골라 읽는 것이었다.
 * 그 뒤로 이 패키지에 클래스를 넣은 커밋이 없어서 지금도 비어 있다. Skill 을 읽고 실행하는 harness 는 별도 앱인 주문 Agent 의
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ 에 만들어졌다(SkillLoader, SkillExecutor, OrderHarness 등).
 * service 는 skillops.flow-runner 가 harness 일 때만 그 harness 를 쓴다.
 * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 가
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 로 주문 Agent 의 POST /api/dry-runs 를 불러
 * 시험 주문을 돌린다.
 */
package com.capstone4.skillops.harness;
