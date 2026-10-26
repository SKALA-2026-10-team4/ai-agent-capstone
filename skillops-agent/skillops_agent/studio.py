"""LangGraph Studio(langgraph dev)에 넘길 그래프를 만든다.

skillops-agent/langgraph.json 이 이 모듈의 diagnose_graph 와 trigger_graph 를 가리킨다.
Studio 서버는 상태 저장소를 스스로 붙이므로, MemorySaver 를 붙여 compile 한 앱용 전역 그래프 대신
checkpointer=False 로 다시 compile 한 그래프를 넘긴다. 노드와 간선은 graph.build_graph,
trigger_graph.build_trigger_graph 가 만드는 것과 같다. Studio 는 이 파일을 패키지 밖에서 경로로
불러오므로 상대 import 대신 절대 import 를 쓴다.
"""

from skillops_agent.graph import build_graph
from skillops_agent.trigger_graph import build_trigger_graph

# 회귀 진단 그래프(graph.py 의 FLOW)다.
diagnose_graph = build_graph(checkpointer=False)

# 호출(트리거) 진단 그래프(trigger_graph.py 의 TRIGGER_FLOW)다.
trigger_graph = build_trigger_graph(checkpointer=False)
