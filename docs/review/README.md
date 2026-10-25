# 백엔드 리뷰 문서 안내

정리일: 2026-10-23 · 최신 구현 기준: `2609ff3`

이 디렉터리는 **작성 당시의 코드 리뷰·분석 기록**이다. 과거 문서의 ‘현재’, 미완료 목록, 소스 개수, 테스트 수는 각 문서의 기준 커밋에 해당한다. 최신 구현과 API/MCP 계약은 아래 연결 문서를 우선한다.

## 현재 구현과 검증

| 확인할 내용 | 우선 참고할 문서 |
|---|---|
| #72 공용 파서·YAML 정책·남은 3항목 완료 | [공용 파서 변경·검증 기록](../changes/2026-10-23-shared-skill-parser.md) |
| 공용 모듈 구조와 Gradle·Docker 빌드 | [skill-parser 안내](../../shared/skill-parser/README.md) |
| 현재 SKILL 해석 규칙 | [SKILL 명세의 최종 갱신 절](../contracts/skill-frontmatter.md) |
| partial 증거 수집·상세 REST | [진단 증거 조회 계약](../contracts/diagnosis-evidence.md) |
| MCP apply_patch v1.3 | [MCP 명세의 갱신 기록·apply_patch 절](../contracts/mcp-tools-input-output.md) |
| 실제 REST·MCP·DB·재시작 검증 | [partial 실서버 검증](../reports/2026-10-23-partial-evidence-integration.md) |
| 기획에 따른 다음 백엔드 작업 | [기획 방향 점검](../reports/2026-10-23-proposal-direction-review.md), [작업 순서·이슈 초안](../reports/2026-10-23-backend-plan-and-issue-updates.md) |

`2609ff3`에서 service와 주문 Agent는 같은 `shared/skill-parser`를 사용한다. #72의 19개 구현 항목은 완료했으며 원격 이슈 체크·종료는 별도다. 과거 문서의 ‘간이 문자열 파서’, ‘파서 통합 미완료’, ‘partial에서 diff 전에 즉시 종료’ 설명을 현재 동작으로 적용하지 않는다.

partial 경로는 원문·해시·목록 오류·가능한 diff를 보존하고 종료한다. 정상 목록의 기존 진단 그래프는 유지한다. 자동 수정 보류 요청을 이유로 제품 전체에서 실행 평가를 제외한 것은 아니다. 변경 전후 평가·비교 계약 설계는 별도 후속 범위이며 아직 구현 완료로 표시하지 않는다.

## 날짜별 기록

| 묶음 | 기준 커밋 | 내용 |
|---|---|---|
| [2026-10-22 전체 리뷰](2026-10-22/README.md) | `ec39713` | 당시 API·MCP·아키텍처·리팩토링 검토·함수 색인 |
| [2026-10-23 백엔드 작업안](2026-10-23/backend-work-items.md) | `dc261ba` | 담당 범위·평가 작업·원장·비동기 처리 등 후속 제안 |
| [2026-10-23 후속 정리 검토](2026-10-23/backend-followup-review.md) | `dc261ba` | 이전 담당자의 수정·미해결 항목 대조 |
| [2026-10-23 MCP 정리](2026-10-23/mcp-cleanup.md) | `dc261ba` | 실제 도구·과거 계약·정리 후보 비교 |
| [2026-10-23 파서 분석](2026-10-23/skill-parser-analysis.md) | `dc261ba` | #72 착수 전 함수·호출·형식 차이와 재현 기록 |

2026-10-22 자료는 전체 리뷰 기록이므로 프런트엔드 관련 내용도 포함한다. 그 내용을 이번 백엔드 구현 범위로 확대하지 않는다. 날짜별 문서의 제안과 재현 결과는 보존하되 완료 여부는 최신 변경·검증 기록과 대조한다.

## Runner 설계 초안

[주문 Agent·Runner 연동 설계안](../../order-agent/docs/runner-integration-design.md)은 2026-10-21의 과거 제안이다. 문서 상단에 현재 구현과의 대응표를 추가했다. `/internal/...`, ws_id/k, revision·메모리 격리 등 당시 제안을 현행 API나 완료 기능으로 사용하면 안 된다.

이번 정리는 문서의 기준·목차·링크만 다룬다. 코드, API 계약, 환경 설정, 이슈 상태를 바꾸거나 새로운 구현을 승인하는 문서가 아니다.
