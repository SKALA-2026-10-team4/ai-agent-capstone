# 역할별 작업 가이드

2026-10-20 팀 결정. 설계도(`../skillops-design.md`)의 W1~W5 를 아래 다섯 사람 역할로 다시 묶었다. **각자 자기 파일 하나만 읽으면 된다.** 모든 파일은 같은 "전체 구조" 절로 시작하고, 그 다음에 본인이 맡는 폴더와 그 폴더가 다른 폴더·역할과 닿는 지점(연결부)이 온다.

| 역할 | 인원 | 파일 | 한 줄 | 옛 표기 |
|---|---|---|---|---|
| **A** · Spring AI · Boot · Vue | 1 | [`A-springai-boot-vue.md`](A-springai-boot-vue.md) | skill 을 돌리는 엔진(harness)과 REST, 그리고 화면 | W1(harness·api) + W4 |
| **B** · Agent 껍데기 | 1 | [`B-agent-shell.md`](B-agent-shell.md) | SkillOps Agent 의 파이프라인 ①~⑥ 과 MCP 클라이언트 | W3 |
| **C-1** · MCP 구성 — 읽기 tool · 저장소 | 1 | [`C1-mcp-read-and-stores.md`](C1-mcp-read-and-stores.md) | trace·version 저장소와 읽기 tool 6개 | W2 + W1 trace |
| **C-2** · MCP 구성 — workspace · 러너 · 쓰기 tool | 1 | [`C2-mcp-workspace-runner.md`](C2-mcp-workspace-runner.md) | sandbox, 시나리오 러너, 쓰기 tool 5개, webhook, promote | W2 |
| **D** · 현업 서비스 구현 | 1 | [`D-business-service.md`](D-business-service.md) | SKILL.md 본문 6개, 주문 도메인, mock 어댑터, 시나리오, 데모 | W5 + W1 도메인·어댑터 |

## 모두에게 적용되는 규칙

- **문서가 고정하는 것은 폴더·책임·연결부다. 파일 이름과 내부 구조는 각자 정한다.** 다른 역할과 닿는 지점만 계약(`docs/contracts/`)과 이 문서의 "접점" 절로 맞춘다.
- **계약이 먼저다.** 1일차 09시에 동결, 변경은 D 가 모아 13시·18시에만 반영.
- **통합 시각은 고정.** 1일차 18시(F1 end-to-end), 2일차 13시(F1·F2·F3). 그 전엔 서로 기다리지 않고 각자 stub/fixture 로 간다.
- **연결부 표기.** "→ A" 처럼 역할 글자로 상대를 가리킨다. 그 지점을 바꾸면 그 사람에게 말해야 한다는 뜻이다.
