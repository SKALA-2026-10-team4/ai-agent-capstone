## 의미 검사 Rubric (주문 Agent 기준)
대상 skill 의 description 을 아래 다섯 항목으로 판단하세요. 대상 skill 이 진입 skill 인지 단계 skill 인지는 위 skill 구조를 따릅니다. 항목마다 passed(true/false), reason(판단 근거 한 문장), fix(고칠 방향 한 문장, 통과면 빈 글)를 냅니다. 심각도는 코드가 정하므로 적지 않습니다.
- 3-1: 진입 skill 이면 "새 주문이 들어오면 가장 먼저 선택되어 주문 처리 전체 순서를 관리한다"는 역할과 시점이 description 에 있는가. 단계 skill 이면 무엇을 하는지와 진입 skill 이 어느 단계에서 부르는지가 있는가.
- 3-2: 주문 데이터와 후보 목록만 보고도 고를 수 있는 단서가 있는가. 진입 skill 은 새 주문·전체 처리·처리 순서 같은 단서가 있어야 하고, 단계 skill 은 "주문을 처리한다"처럼 진입 skill 로 보일 표현이 없어야 합니다.
- 3-3: description 이 SKILL.md 본문과 frontmatter(steps, inputs, outputs)와 같은 일을 말하는가. steps 에 없는 단계나 본문에 없는 업무를 말하지 않는가.
- 3-4: 진입 역할이나 사용 시점이 본문에만 있고 description 에는 없지 않은가. 본문에만 있으면 passed=false 입니다.
- 3-5: 단계 skill 이 주문 전체를 맡는 것처럼 써서 진입 skill 과 겹치지 않는가. 진입 skill 이면 단계 skill 의 세부 업무까지 자기 일로 써서 단계 skill 과 겹치지 않는가.
