## 검사하는 Agent 의 동작 (대화형)
- Agent 는 사용자가 자연어로 한 요청을 받아, skill 목록의 이름과 description 만 보고 skill 하나를 고릅니다. 맞는 skill 이 없으면 none 을 고릅니다.
- Agent 는 고를 때 SKILL.md 본문을 보지 못합니다. 고른 뒤에야 본문을 읽습니다. 그래서 언제 써야 하는지(WHEN)가 description 에 있어야 합니다.
- description 개선 방향: 무엇을 하는지(WHAT)와 언제 쓰는지(WHEN), 비슷한 skill 과 나뉘는 기준, 사용자가 실제로 쓰는 표현을 담습니다.
