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

2026-10-23 · #72 파싱 공통화 마무리: 두 Java 서버가 [skill-parser 공용 모듈](../../shared/skill-parser/README.md)을 사용한다. 중복 키·alias·태그·YAML 크기 제한은 [SKILL 현재 계약](skill-frontmatter.md)의 최종 갱신 절을 따른다. MCP 필드와 apply_patch v1.3은 유지한다.

2026-10-23 · #72 partial 증거 수집: [진단 상세 evidence 계약](diagnosis-evidence.md)을 추가했다. 기존 원문 REST와 diff MCP를 재사용하며, 진단 상세에만 원문 증거를 제공한다. partial 경로는 증거 저장 후 종료하고 자동 수정은 수행하지 않는다. MCP v1.3 필드는 변경하지 않았다.

2026-10-23 · #72: `apply_patch` 응답 v1.3의 [JSON Schema](apply-patch-result.schema.json)와 [Java/Python 공통 예제](examples/apply-patch-dependency-violation.json)를 추가했다. 필수 `dependency_violations`와 기존 `broken_links`를 함께 검사하고, `metadata_errors`는 별도 `metadata_valid` 판정에 사용한다. [필수 정보 오류 예제](examples/apply-patch-metadata-error.json)와 [두 Java 모듈의 공유 문서](examples/skill-metadata-cases.json)도 추가했다. 자세한 호환 규칙은 [도구 입출력 명세](mcp-tools-input-output.md#8-수정-적용하기--apply_patch)를 따른다.

계약 5의 v1 제안(도구별 입출력 JSON, 필드 설명, 읽고 쓰는 저장 공간)은 `mcp-tools-input-output.md`에 있다. 동결 회의에서 확정되면 `mcp-tools-and-rest.md`와 스키마 파일에 반영한다.

2026-10-23 · #72 4단계: 필수 `flow_errors`와 후보 결과 `flow_valid`를 추가했다. [flow 오류 예제](examples/apply-patch-flow-error.json), [두 Java 모듈의 공통 flow 사례](examples/flow-structure-cases.json), [변경 내역](../changes/2026-10-23-flow-structure-validation.md)을 참고한다.
