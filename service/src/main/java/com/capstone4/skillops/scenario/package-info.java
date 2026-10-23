/**
 * C-2 판정 경로다. MCP 도구 run_scenarios 가 연습 공간의 skill 로 시험 주문을 모의 실행하고, 기대 결과와 비교해
 * 합격 여부와 실행 번호를 돌려준다. "LLM 은 제안하고 harness 가 판정한다"에서 판정을 맡는 패키지다.
 * 진단 Agent 는 여기서 받은 결과로 후보 수정안의 합격 여부를 정하고, 실행 번호를 제안서의 근거로 적는다.
 *
 * 흐름은 다음과 같다.
 * 1. service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 runScenarios() 가 도구 입력을 ScenarioRunner.run 에
 *    그대로 넘긴다. 진단 Agent 쪽에서는 skillops-agent/skillops_agent/tools.py 의 MCPTools.run_scenarios 가 이 도구를 부른다.
 * 2. ScenarioRunner 가 입력을 모두 검사하고, 재현 주문의 원래 주문을 OrderAgentRunClient 로 미리 꺼낸다.
 * 3. 시험 주문마다 FlowRunner 로 repeat 번 모의 실행한다. 실행기는 ContractOnlyFlowRunner(기본)나 HarnessFlowRunner 이며,
 *    HarnessFlowRunner 는 주문 Agent 의 실행 기록을 OrderAgentTraceConverter 로 RunOutcome 으로 바꾼다.
 * 4. 실행마다 ScenarioGrader 가 Expectation 과 Check 로 채점하고, RunLedger 가 실행 번호를 scenario_runs 표에 남긴다.
 * 5. 결과는 ScenarioRunReport 로 모아 돌려준다. 진단 Agent 는 skillops-agent/skillops_agent/contracts.py 의 validate_report 로
 *    결과 모양을 검사하고, save_proposal 은 RunLedger 로 근거 실행 번호를 대조한다.
 *
 * 흐름을 바꾸거나 튜닝할 때 고칠 곳은 다음과 같다.
 * 반복 횟수의 기본값과 범위는 ScenarioRunner 의 DEFAULT_REPEAT, MIN_REPEAT, MAX_REPEAT 이다.
 * 검사 순서는 곧 오류 우선순위이며 ScenarioRunner.run 본문의 호출 순서가 정한다.
 * 채점 규칙과 떨어진 이유의 순서는 ScenarioGrader.grade 가, 재현 주문의 합격 기준은 Expectation.replay 가 정한다.
 * 확인 조건 문법은 Check 에 있고, skillops-agent/skillops_agent/contracts.py 의 check_problem 이 같은 문법을 흉내 낸다.
 * 실행기는 service/src/main/resources/application.yml 의 skillops.flow-runner(환경 변수 SKILLOPS_FLOW_RUNNER)로 고른다.
 * 주문 Agent 를 기다리는 시간은 OrderAgentRunClient 의 CONNECT_TIMEOUT 과 READ_TIMEOUT 이다.
 * 모의 실행을 한 번에 하나씩 돌리는 곳은 ScenarioRunner.run 의 순차 반복과 HarnessFlowRunner 의 공정 락 두 곳이다.
 */
package com.capstone4.skillops.scenario;
