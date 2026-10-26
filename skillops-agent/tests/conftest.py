"""모든 테스트에 공통 환경을 넣는 pytest 설정이다.

autouse fixture offline_credentials 가 테스트마다 환경 변수 세 개를 바꾼다. 가짜 OPENAI_API_KEY 는
skillops-agent/skillops_agent/llm.py 의 make_llm 이 ChatOpenAI 를 만들 수 있게만 하고, 어떤
테스트도 유료 모델을 부르지 않는다. SKILLOPS_RUN_DB 는 테스트마다 임시 경로를 가리켜,
skillops-agent/skillops_agent/run_store.py 의 RunStore() 가 개발용 DB 인
skillops-agent/.data/runs.sqlite3 대신 테스트 전용 파일을 쓰게 한다. LANGSMITH_TRACING 은 false 로
두어 테스트 실행 기록이 LangSmith 로 가지 않게 한다.
"""

import pytest


@pytest.fixture(autouse=True)
def offline_credentials(monkeypatch, tmp_path):
    """테스트마다 가짜 OpenAI 키와 임시 RunStore DB 경로를 환경 변수에 넣는다.

    monkeypatch 가 테스트가 끝나면 원래 값으로 되돌린다.
    """
    # 가짜 키는 LLM client 를 만들 수 있게만 한다. 어떤 테스트도 유료 모델을 부르지 않는다.
    monkeypatch.setenv("OPENAI_API_KEY", "test-not-a-real-key")

    # RunStore 는 인스턴스를 만들 때마다 이 값을 읽으므로 테스트마다 빈 DB 를 쓴다.
    monkeypatch.setenv("SKILLOPS_RUN_DB", str(tmp_path / "runs.sqlite3"))

    # .env 에서 LangSmith 추적을 켜 두어도 테스트 실행 기록은 보내지 않는다.
    monkeypatch.setenv("LANGSMITH_TRACING", "false")
