"""SkillOps Agent 패키지다.

SKILL.md 변경으로 깨진 주문 Agent 흐름을 실제 실행 증거로 진단하고, service(C)의 격리 검증을
통과한 수정안만 추천한다. 제안서에는 떨어진 후보와 시험하지 못한 후보도 함께 담기고, 통과한
후보가 없으면 no_valid_fix 제안서를 저장한다. LLM 은 가설, 변경 의도, 수정안, 설명을 내놓을 뿐이고,
원인 확정과 합격 판정은 C 의 시나리오 실행 결과로만 한다.

모듈은 모두 skillops-agent/skillops_agent/ 폴더에 있고 다음처럼 일을 나눈다.
- main.py 는 FastAPI 진입점이다. service 가 POST /diagnose 로 진단을 요청한다.
- graph.py 는 흐름표(FLOW)와 분기 판정으로 LangGraph 그래프를 조립한다.
- nodes.py 에는 노드 함수 11개와 노드별 도우미가 있다.
- state.py 에는 노드가 함께 쓰는 상태(AgentState)와 진단 상태 상수가 있다.
- config.py 에는 접속 설정과 파이프라인 튜닝 값(재시도 한도, 반복 횟수 등)이 있다.
- contracts.py 에는 C 와 주문 Agent 응답을 검사하고 patch 를 만드는 계약 도우미가 있다.
- llm.py 는 구조화 출력 LLM 을 만들고, prompts.py 는 skillops-agent/prompts/*.md 를 읽는다.
- tools.py 는 MCP 도구와 주문 Agent 조회를 부른다. MCP 세션은 mcp_client.py 가, 주문 Agent HTTP
  조회는 order_agent.py 가 맡는다. stub_tools.py 는 개발용 대역이다.
- run_store.py 는 진단 접수와 진행 기록을 SQLite 에 저장한다.
"""
