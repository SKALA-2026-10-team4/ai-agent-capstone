/**
 * 비어 있는 패키지다. 이 설명 파일 말고는 클래스가 없고, service 의 어떤 코드도 이 패키지를 쓰지 않는다.
 * 저장소 뼈대를 처음 만들 때 W1 작업인 Skill 레지스트리 자리로 만들었다. 계획은 SKILL.md 의 inputs, outputs, depends_on, steps
 * 선언을 모으고, 선언한 입력이 앞선 skill 의 출력에서 공급되는지 계약 일관성을 검사하는 것이었다.
 * 그 뒤로 이 패키지에 클래스를 넣은 커밋이 없어서 지금도 비어 있다. 그 검사는 지금 다른 두 곳에 있다. 주문 Agent 는 실행 전에
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의 validateFlow 로 검사한다.
 * service 는 service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 가 같은 규칙으로 flow 의 정적 연결
 * 관계를 끝까지 계산한다.
 */
package com.capstone4.skillops.registry;
