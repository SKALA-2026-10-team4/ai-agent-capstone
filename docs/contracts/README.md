# 계약 5개 (1일차 09시 동결)

다섯 레인(W1~W5)이 서로를 기다리지 않게 하는 인터페이스. **W5가 관리**하고, 변경 요청은 모아서 13시·18시 두 번만 반영한다. 지금 상태는 초안(v0)이다.

| # | 계약 | 파일 | 쓰는 쪽 → 읽는 쪽 |
|---|---|---|---|
| 1 | SKILL.md frontmatter | `skill-frontmatter.md` | W5(작성) → W1(로더·레지스트리), W3(diff·의도) |
| 2 | 트레이스 레코드 | `trace.schema.json` | W1(기록) → W2(러너·MCP), W3(진단), W4(타임라인) |
| 3 | 시나리오 | `scenario.schema.json` | W5(작성) → W2(러너), W3(재현 시나리오 생성) |
| 4 | 수정안(proposal) | `proposal.schema.json` | W3(생성) → W2(save_proposal), W4(비교·승인) |
| 5 | MCP tool 12개 + REST + webhook | `mcp-tools-and-rest.md` | W2(서버) ↔ W3(클라이언트), W1(REST) ↔ W4(화면) |

변경 이력은 각 파일 상단의 `버전` 줄에 적는다.

계약 5의 v1 제안(도구별 입출력 JSON, 필드 설명, 읽고 쓰는 저장 공간)은 `mcp-tools-input-output.md`에 있다. 동결 회의에서 확정되면 `mcp-tools-and-rest.md`와 스키마 파일에 반영한다.
