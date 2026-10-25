# #72 · patch 선행 단계 오류 전달과 후보 판정 연결

작성일: 2026-10-23 · 브랜치: `fix/#72_skill-parser-consistency` · apply_patch 응답 계약 v1.1

## 수정한 문제

graph는 `payment.depends_on: [notify]`에서 notify가 뒤에 있으면 선행 단계 위반을 계산했다. 그러나 workspace가 patch 응답을 만들 때 `missing_dependencies`를 버려 `broken_links: []`만 반환했다. Python은 이를 연결 정상으로 간주할 수 있었다.

이제 service가 기존 입력 누락 목록을 유지하고 선행 단계 누락을 별도로 반환한다. Python 후보 판정은 두 목록을 모두 검사한다.

## 명세 업데이트

- [도구 입출력 명세 8절](../contracts/mcp-tools-input-output.md#8-수정-적용하기--apply_patch): 필드·타입·필수 여부·예제·호환 정책
- [MCP 계약 요약](../contracts/mcp-tools-and-rest.md): 최신 apply_patch 행과 변경 기록
- [성공 응답 JSON Schema](../contracts/apply-patch-result.schema.json)
- [Java/Python 공통 예제](../contracts/examples/apply-patch-dependency-violation.json)
- [Python 연동 안내](../../skillops-agent/docs/c-contract-integration.md)

요청 형식과 기존 응답 필드는 유지하고 다음 필수 필드를 추가했다.

```json
{
  "workspace_id": "w_abc",
  "changed_skills": [{"skill":"payment","version":2}],
  "broken_links": [],
  "dependency_violations": [{"skill":"payment","missing_dependency":"notify"}]
}
```

`broken_links`는 입력 키 누락, `dependency_violations`는 앞서 완료되지 않은 depends_on 단계다. 두 목록 모두 전체 flow를 대상으로 계산하며 없으면 반드시 `[]`를 반환한다. 선언 순서를 유지한다.

## 코드 변경

| 위치 | 변경 |
|---|---|
| WorkspaceService | DependencyViolation DTO와 결과 필드 추가, graph의 선행 단계 오류 보존 |
| WorkspaceTools | 실제 MCP 도구 설명에 새 응답과 두 목록의 의미 명시 |
| Python contracts.patch_contract_ok | 두 목록과 항목 구조를 검증하는 공통 순수 함수 |
| Python nodes | 평가 전에 응답 계약 확인, 두 목록이 모두 비었을 때 no_broken_links=true |
| Python stub·실험 스크립트 | 새 필드 제공·검사·출력과 판정 연결 |

원인 재현 실험을 위해 의미 위반이 있어도 patch는 저장한다. 적용 실패와 후보 불합격은 구분한다. 잘못된 응답으로 판정하지 않도록 새 필드 누락·null·잘못된 타입은 ContractError로 중단한다. 변경 함수에는 기능 주석을 남겼다.

## 호환 및 검증 범위

- 기존 broken_links 항목의 형식과 의미는 유지한다. 기존 필드를 바꾸거나 선행 단계 이름을 missing_input으로 위장하지 않는다.
- 새 Python은 구버전 service의 응답을 허용하지 않는다. service와 Python을 함께 갱신해야 한다. 구버전 소비자가 새 필드를 무시하면 새 판정 규칙은 적용되지 않는다.
- owner/description·완전한 실행 가능성·분석 완전성을 보장하는 변경은 아니다. 이 부분은 후속 검증 범위다.
- 주문 Agent 실행 코드와 작업 전부터 수정돼 있던 README는 변경하지 않았다.

## 검증

- service 533개, Python 453개 테스트 통과. 실패 0건.
- service 전체 테스트: 입력 누락과 선행 단계 위반의 개별/동시 발생, 기존 위반 보존, 뒤 단계·자기 자신·없는 단계, 정상 응답, patch 저장·원자성 회귀를 검사했다.
- Python 전체 테스트: 선행 단계만 위반한 후보의 no_broken_links=false·최종 후보 무효·no_valid_fix 종료, 구버전/잘못된 응답 거부를 검사했다.
- Java MCP 직렬화와 Python 판정이 같은 JSON fixture를 읽는 계약 테스트를 추가했다.
- JSON Schema 유효성과 공통 예제·잘못된 payload의 허용/거부를 확인했다.
- 수정한 Python 파일의 Ruff 검사를 통과했다.

실행 명령:

```bash
cd service
./gradlew test --offline --no-daemon --console=plain

cd ../skillops-agent
.venv/bin/python -m pytest -q
.venv/bin/ruff check skillops_agent/contracts.py skillops_agent/nodes.py skillops_agent/state.py skillops_agent/stub_tools.py tests/test_patch_contract.py tests/test_graph.py tests/test_stub_tools.py scripts/c2_real_judge.py
```

DB를 쓰는 실제 주문 실행·유료 LLM·운영 배포 검증은 수행하지 않았다.
