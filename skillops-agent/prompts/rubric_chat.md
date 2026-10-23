## 의미 검사 Rubric (대화형 Agent 기준)
대상 skill 의 description 을 아래 다섯 항목으로 판단하세요. 항목마다 passed(true/false), reason(판단 근거 한 문장), fix(고칠 방향 한 문장, 통과면 빈 글)를 냅니다. 심각도는 코드가 정하므로 적지 않습니다.
- 3-1: description 에 무엇을 하는지(WHAT)와 언제 쓰는지(WHEN)가 모두 있는가.
- 3-2: 사용자가 이 일을 요청할 때 실제로 쓰는 표현이 description 에 충분히 있는가.
- 3-3: description 이 SKILL.md 본문의 업무와 같은 일을 말하는가. 본문에 없는 일을 말하거나 본문의 핵심 업무를 빠뜨리지 않는가.
- 3-4: 언제 써야 하는지가 본문에만 있고 description 에는 없지 않은가. 본문에만 있으면 passed=false 입니다.
- 3-5: description 이 비슷한 다른 skill 의 일까지 끌어올 만큼 넓지 않은가.
