"""skillops-agent 의 설정 값을 모은다.

접속 주소, 모델, 토큰은 환경 변수로 받고, 진단 파이프라인의 튜닝 값은 아래 코드 상수로 둔다.
import 할 때 현재 작업 폴더에서 위로 올라가며 찾은 첫 .env 를 읽는다. 보통 저장소 루트의 .env 이고
견본은 .env.example 이다. 셸에 같은 이름의 변수가 있으면 셸 값이 이긴다. 값은 import 할 때 한 번만
읽으므로 바꾸려면 프로세스를 다시 띄운다.
"""

import os

from dotenv import find_dotenv, load_dotenv

load_dotenv(find_dotenv(usecwd=True))

# LLM 모델 이름(SKILLOPS_MODEL)이다.
# skillops-agent/skillops_agent/llm.py 의 make_llm 이 ChatOpenAI 를 만들 때 쓴다.
MODEL = os.getenv("SKILLOPS_MODEL", "gpt-4.1-mini")
# service 주소(SKILLOPS_SERVICE_URL)다. 이 모듈에서 MCP_URL 기본값을 만들 때만 쓴다.
SERVICE_URL = os.getenv("SKILLOPS_SERVICE_URL", "http://localhost:8080")
# service MCP 서버 주소(SKILLOPS_MCP_URL)다. skillops-agent/skillops_agent/tools.py 의 MCPTools 와
# skillops-agent/skillops_agent/mcp_client.py 가 이 주소로 접속한다.
# 도구 구현(@McpTool 클래스)은 service/src/main/java/com/capstone4/skillops/mcp/ 에 있다.
MCP_URL = os.getenv("SKILLOPS_MCP_URL", f"{SERVICE_URL}/mcp")
# skillops-agent/skillops_agent/tools.py 의 MCPTools 가 주문 Agent 조회(list_failed_runs,
# get_trace, get_snapshot, list_skills, get_skill)에 쓰는 주소다. SKILLOPS_ORDER_AGENT_URL 이
# 먼저이고, 없으면 ORDER_AGENT_BASE_URL 을 쓴다.
ORDER_AGENT_URL = os.getenv(
    "SKILLOPS_ORDER_AGENT_URL", os.getenv("ORDER_AGENT_BASE_URL", "http://localhost:8081")
)

# 주문 Agent(order-agent/) 진단용 조회 API 주소다. 실행 기록, 실패 목록, skill 목록, skill 본문을
# 직접 부른다. skillops-agent/skillops_agent/order_agent.py 가 client 없이 불릴 때 이 주소를 쓴다.
# service 도 같은 이름의 변수를 읽는다(service/src/main/resources/application.yml).
ORDER_AGENT_BASE_URL = os.getenv("ORDER_AGENT_BASE_URL", "http://localhost:8081")

# /diagnose 엔드포인트를 보호하는 서버 간 토큰(SKILLOPS_SERVICE_TOKEN)이다.
# skillops-agent/skillops_agent/main.py 의 verify_token 이 X-Service-Token 헤더와 비교하며
# /runs 조회도 같은 토큰으로 막는다. 비어 있으면 검증을 건너뛴다(개발 모드). service 에도 같은
# 값을 준다.
SERVICE_TOKEN = os.getenv("SKILLOPS_SERVICE_TOKEN", "")

# true 이면 service(:8080) 없이 stub 응답으로 개발한다. 기본값은 false 다.
# skillops-agent/skillops_agent/tools.py 의 get_tools 가 이 값을 보고
# skillops-agent/skillops_agent/stub_tools.py 의 StubMCPTools 를 돌려준다.
USE_STUB_MCP = os.getenv("SKILLOPS_USE_STUB_MCP", "false").lower() == "true"

# 회귀 세트로 돌릴 시나리오 ID 목록(SKILLOPS_GOLDEN_IDS, 쉼표로 구분)이다.
# skillops-agent/skillops_agent/nodes.py 의 사전 검증(_preflight)과 후보 검증(_verify_candidate)이
# 쓴다.
# service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java 가
# skillops.scenarios-dir 아래 golden 폴더에서 읽은 시나리오 ID 와 같아야 한다. 목록이 비면
# skillops-agent/skillops_agent/nodes.py 의 _run_scenarios 가 ContractError 를 던진다.
GOLDEN_IDS = [
    v.strip() for v in os.getenv("SKILLOPS_GOLDEN_IDS", "g-01,g-02,g-03").split(",") if v.strip()
]

# ── 진단 파이프라인 튜닝 값 ─────────────────────────────────────────────────────
# 흐름의 한도, 반복 횟수, 기본값은 여기서만 바꾼다. 환경 변수로는 열지 않는다.
# 흐름표와 분기 판정은 skillops-agent/skillops_agent/graph.py 에 있고, 노드는
# skillops-agent/skillops_agent/nodes.py 에 있다. 값을 바꾸면
# skillops-agent/tests/test_pipeline_defaults.py 의 튜닝 값 표와, 그 값에 기대는
# skillops-agent/tests/test_flow_characterization.py, skillops-agent/tests/test_graph.py,
# skillops-agent/tests/test_main_api.py 의 단언을 함께 고친다. 표 말고도 깨지는 테스트는 각 상수
# 주석에 적었다.

# 노드 실행 단계 한도(PB-006)다. 초기 상태에 max_steps 가 없을 때 쓴다.
# skillops-agent/skillops_agent/main.py 의 _initial_state 는 이 값을 max_steps 로 넣는다.
# skillops-agent/skillops_agent/nodes.py 의 init 과 skillops-agent/skillops_agent/graph.py 의
# _step_limit_reached 는 max_steps 가 없을 때 이 값을 쓴다. 같은 graph.py 의 route_ablation 과
# route_valid 가 재시도 한도 다음으로 이 한도를 본다.
# 기본 재시도 한도(3, 3)로는 한 실행의 step_count 가 22를 넘지 않으므로 지금은 재시도 한도가
# 먼저 걸린다. 재시도 한도를 키울 때의 안전장치다. 바꾸면
# skillops-agent/tests/test_flow_characterization.py 의 test_saved_change_validated_without_llm 과
# skillops-agent/tests/test_main_api.py 의
# test_run_pipeline_invokes_graph_with_initial_state_and_limits 도 고친다.
MAX_STEPS_DEFAULT = 40
# 한 번의 그래프 실행에서 LangGraph 가 허용하는 최대 단계 수(recursion_limit)다.
# skillops-agent/skillops_agent/main.py 의 _run_pipeline 이 graph.ainvoke 에 넘긴다.
# 이 그래프는 노드를 하나씩 실행하므로 노드 실행 횟수의 상한이 된다. 넘으면 LangGraph 가
# GraphRecursionError 를 던지고 _run_pipeline 이 ERROR 로 저장한다. 바꾸면
# skillops-agent/tests/test_main_api.py 의
# test_run_pipeline_invokes_graph_with_initial_state_and_limits 도 고친다.
RECURSION_LIMIT = 100
# 원인 가설을 다시 세우는 최대 횟수다. 다 쓰면 원인 없이 제안서를 저장한다.
# skillops-agent/skillops_agent/nodes.py 의 check_ablation 이 hypothesis_retry 를 올리고,
# skillops-agent/skillops_agent/graph.py 의 route_ablation 이 이 값에 닿으면 escalate 로
# save_proposal 에 보낸다. 바꾸면 skillops-agent/tests/test_flow_characterization.py 의
# test_hypothesis_retry_escalates_to_proposal_after_three_rejections 도 고친다.
MAX_HYPOTHESIS_RETRIES = 3
# 후보 수정안을 다시 만드는 최대 횟수다. 다 쓰면 no_valid_fix 제안서를 저장한다.
# skillops-agent/skillops_agent/nodes.py 의 check_valid 가 candidate_retry 를 올리고 진행 메시지의
# 분모로도 쓴다. skillops-agent/skillops_agent/graph.py 의 route_valid 가 이 값에 닿으면
# save_proposal 로 보낸다. 바꾸면 skillops-agent/tests/test_flow_characterization.py 의
# test_candidate_retry_stops_after_three_rounds, test_candidate_loop_stops_at_step_limit,
# test_candidate_format_errors_keep_partial_patches 도 고친다.
MAX_CANDIDATE_RETRIES = 3
# 아래 네 값은 단계별 시나리오 반복 횟수다. skillops-agent/skillops_agent/nodes.py 의
# _run_scenarios 를 거쳐 run_scenarios 도구의 repeat 로 가고,
# skillops-agent/skillops_agent/contracts.py 의 validate_report 는 시나리오마다 실행 ID 가 정확히
# 이 수만큼 왔는지 본다. 값은
# service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 MIN_REPEAT(1) 이상
# MAX_REPEAT(5) 이하여야 하고, 벗어나면 run_scenarios 가 INVALID_INPUT 으로 거절한다. 바꾸면
# skillops-agent/tests/test_flow_characterization.py 의 실행 ID 개수와 repeat 단언도 고친다.
# 저장 직후 사전 검증에서 기준과 후보 snapshot 의 회귀 세트를 돌리는 횟수다.
PREFLIGHT_REPEAT = 2
# diagnosis_only 요청의 의도 조건 검사 횟수다.
INTENT_CHECK_REPEAT = 2
# 원본 재현 확인(control)과 hunk 복원 실험의 횟수다. root_cause.proof_run_ids 는 이 값의 두 배만큼
# 실행 ID 를 담는다.
ABLATION_REPEAT = 1
# 후보 검증(회귀, 재현, 의도)의 횟수다. 후보 하나의 evidence_run_ids 는
# (회귀 시나리오 수 + 2) × VERIFY_REPEAT 개이고, 의도 조건이 없으면 (회귀 시나리오 수 + 1) ×
# VERIFY_REPEAT 개다. skillops-agent/tests/test_graph.py 의 근거 실행 10개 단언과
# skillops-agent/tests/test_flow_characterization.py 의 10개, 8개 단언이 이 값에 기댄다.
VERIFY_REPEAT = 2
# 의존성 그래프를 읽을 시작 skill 이다. 요청에 entry_skill_id 가 없을 때 쓴다.
# skillops-agent/skillops_agent/main.py 의 DiagnoseRequest.entry_skill_id 기본값이고,
# skillops-agent/skillops_agent/nodes.py 의 _repair_inputs 도 trigger 에 값이 없으면 이 값을 쓴다.
# 주문 흐름의 시작 skill 은 skills/order-flow/SKILL.md 다. 같은 이름을
# order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java 의
# ROOT_FLOW_ID, service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의
# ENTRY_SKILL, frontend/src/utils/skillSet.js 의 ENTRY_SKILL 이 따로 들고 있으므로 함께 바꾼다.
# skillops-agent/skillops_agent/stub_tools.py 의 get_dependency_graph 는 order-flow 가 아니면
# INVALID_FLOW 를 던진다. 그래서 바꾸면 skillops-agent/tests/test_flow_characterization.py 와
# skillops-agent/tests/test_graph.py 의 흐름 테스트 대부분과, 요청 기본값을 비교하는
# skillops-agent/tests/test_main_api.py 의 테스트를 함께 고친다.
DEFAULT_ENTRY_SKILL_ID = "order-flow"
# LLM 설명이 없을 때 순위 설명에 인용할 근거 실행 개수다.
# skillops-agent/skillops_agent/nodes.py 의 _fallback_explanation 이 쓴다. 바꾸면
# skillops-agent/tests/test_flow_characterization.py 의 대체 설명('rank N: ...') 단언도 고친다.
RANK_FALLBACK_EVIDENCE_COUNT = 2
# LLM 샘플링 온도다. skillops-agent/skillops_agent/llm.py 의 make_llm 이 쓴다.
# 같은 입력에 출력이 흔들리지 않게 0.0 으로 둔다.
LLM_TEMPERATURE = 0.0
