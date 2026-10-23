# 계약 문서

프로세스와 담당자 사이에서 맞춰야 하는 형식이다. 2026-10-23 현재 코드 기준이며, 코드와 다르면 코드가 맞다. 형식을 바꾸는 사람은 이 문서와 쓰는 쪽 담당자에게 함께 알린다.

| # | 계약 | 파일 | 쓰는 쪽과 읽는 쪽 |
|---|---|---|---|
| 1 | SKILL.md frontmatter | `skill-frontmatter.md` | 업무 담당자가 쓰고 주문 Agent 실행기, service 분석기가 읽는다 |
| 3 | 시험 주문 | `scenario.schema.json` | `scenarios/` 파일을 service 의 `run_scenarios` 가 읽는다 |
| 4 | 제안서 | `proposal.schema.json` | SkillOps Agent 가 만들고 service 의 `save_proposal` 이 검사·저장한다 |
| 5 | MCP 도구 · REST · 저장 이벤트 | `mcp-tools-and-rest.md` (개요), `mcp-tools-input-output.md` (도구별 입출력) | service 와 SkillOps Agent, 화면과 service |

계약 2(실행 기록)는 주문 Agent 가 정한다. 형식은 `order-agent/docs/agent-evidence.md` 에 있다.
