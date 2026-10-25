# 계약 문서

프로세스와 담당자 사이에서 맞춰야 하는 형식이다. 2026-10-26 현재 코드 기준이며, 코드와 다르면 코드가 맞다. 형식을 바꾸는 사람은 이 문서와 쓰는 쪽 담당자에게 함께 알린다.

| # | 계약 | 파일 | 쓰는 쪽과 읽는 쪽 |
|---|---|---|---|
| 1 | SKILL.md frontmatter | `skill-frontmatter.md` | 업무 담당자가 쓰고 주문 Agent 실행기, service 분석기가 읽는다 |
| 3 | 시험 주문 | `scenario.schema.json` | `scenarios/` 파일을 service 의 `run_scenarios` 가 읽는다 |
| 4 | 제안서 | `proposal.schema.json` | SkillOps Agent 가 만들고 service 의 `save_proposal` 이 검사·저장한다 |
| 5 | MCP 도구 · REST · 저장 이벤트 | `mcp-tools-and-rest.md` (개요), `mcp-tools-input-output.md` (도구별 입출력) | service 와 SkillOps Agent, 화면과 service |
| 6 | 백엔드 평가·비교 v1.0 | [evaluation-backend.md](evaluation-backend.md) | service의 고정 입력·비동기 작업·실행 결과·검증 제안서 REST/MCP |

2026-10-26 · #88: 백엔드 평가·비교 계약 v1.0을 추가했다. 새 MCP 도구 4개와 REST를 추가하며 기존 7개·apply_patch v1.3은 유지한다. Python·AI 코드는 변경하지 않았다. 실제 지원 범위와 실모델 미지원 조건은 계약 6을 따른다.

계약 2(실행 기록)는 주문 Agent 가 정한다. 형식은 `order-agent/docs/agent-evidence.md` 에 있다.

변경 이력은 각 파일 상단의 `버전` 줄에 적는다.

2026-10-23 · #72 파싱 공통화 마무리: 두 Java 서버가 [skill-parser 공용 모듈](../../shared/skill-parser/README.md)을 사용한다. 중복 키·alias·태그·YAML 크기 제한은 [SKILL 현재 계약](skill-frontmatter.md)의 최종 갱신 절을 따른다. MCP 필드와 apply_patch v1.3은 유지한다.

2026-10-23 · #72 partial 증거 수집: [진단 상세 evidence 계약](diagnosis-evidence.md)을 추가했다. 기존 원문 REST와 diff MCP를 재사용하며, 진단 상세에만 원문 증거를 제공한다. partial 경로는 증거 저장 후 종료하고 자동 수정은 수행하지 않는다. MCP v1.3 필드는 변경하지 않았다.

2026-10-23 · #72: `apply_patch` 응답 v1.3의 [JSON Schema](apply-patch-result.schema.json)와 [Java/Python 공통 예제](examples/apply-patch-dependency-violation.json)를 추가했다. 필수 `dependency_violations`와 기존 `broken_links`를 함께 검사하고, `metadata_errors`는 별도 `metadata_valid` 판정에 사용한다. [필수 정보 오류 예제](examples/apply-patch-metadata-error.json)와 [두 Java 모듈의 공유 문서](examples/skill-metadata-cases.json)도 추가했다. 자세한 호환 규칙은 [도구 입출력 명세](mcp-tools-input-output.md#8-수정-적용하기--apply_patch)를 따른다.

2026-10-23 · #72 4단계: 필수 `flow_errors`와 후보 결과 `flow_valid`를 추가했다. [flow 오류 예제](examples/apply-patch-flow-error.json), [두 Java 모듈의 공통 flow 사례](examples/flow-structure-cases.json), [변경 내역](../changes/2026-10-23-flow-structure-validation.md)을 참고한다.
