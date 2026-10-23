# #72 · partial Skill 목록의 증거 수집·저장

작성일: 2026-10-23 · 상태: 구현 및 로컬 검증 완료 · 자동 수정 제외

## 해결한 문제

기존 Python 진단기는 주문 Agent의 목록이 partial이면 diff 호출 전에 종료했다. 이미 조회한 snapshot·trace도 해당 노드에서 반환하지 못해 실행 기록에는 목록 오류의 상세와 원문 증거가 없었다.

이제 검증된 partial 응답은 별도 경로로 보낸다.

```text
fetch_evidence
  → 고정 snapshot·trace·정상 목록·파싱 오류 저장
collect_partial_sources
  → 양쪽 SKILL.md 원문·파일 해시·크기·encoding 저장
collect_partial_diffs
  → 가능한 diff·hunk·frontmatter_parse_error 저장
  → ERROR 종료: evidence_only=true
```

자동 수정은 하지 않는다. partial 경로에서 LLM, 의존성 그래프, workspace 생성, apply_patch, 시나리오 실행, save_proposal을 호출하지 않는다. 기존 완전한 목록의 정상 진단 경로는 유지했다.

## 구현 변경

- `order_agent.get_snapshot_file`과 MCPTools/StubMCPTools의 같은 이름 함수를 추가했다. 기존 snapshot files REST를 params로 호출하며 주입한 client와 기존 재시도 규칙을 사용한다. 깨진 JSON은 명시적인 조회 오류로 바꾼다.
- `partial_evidence.py`에 목록·manifest·원문·diff 검증과 수집을 분리했다. Python YAML 파서는 추가하지 않았다.
- 정상 항목과 오류 경로가 manifest의 모든 SKILL.md를 빠짐없이 설명하는지 확인한다. 정상 버전만 보존하며 오류 파일의 버전은 추정하지 않는다.
- 원문 응답의 snapshot/path/hash/size와 실제 바이트 SHA-256을 확인한다. LF/CRLF와 base64 원본을 보존한다. 비텍스트 파일은 diff에 보내지 않는다.
- 원문·snapshot 변조와 diff 출처 불일치는 중단한다. 통신 오류와 지원 밖 변경은 사유를 남긴다. 검증된 증거는 유지한다.
- partial 전용 그래프 노드 두 개를 추가해 목록 수집 결과와 원문 수집 결과를 각각 RunStore에 저장한다. 이후 diff 실패나 재시작 복구에서도 앞 단계의 증거를 보존한다.
- AgentState에 evidence를 추가하고 실행 시작 시 초기화한다. 완료 상태는 기존 ERROR를 사용한다.
- 원문 evidence는 상세 조회에서만 제공한다. 최근 목록은 한 번의 SELECT에서 새 evidence를 제외해 큰 원문을 목록 응답으로 반복 전송하지 않는다. SQLite JSON 함수를 사용하며 기존 테스트 DB에서 확인했다.
- 추가·변경 함수의 기능 주석과 관련 계약·운영 설명을 갱신했다.

## API/MCP 영향

상세 계약은 [진단 증거 조회 v1](../contracts/diagnosis-evidence.md)을 따른다.

| 대상 | 변경 |
|---|---|
| Python 상세 `/runs/{thread_id}/events` | evidence 추가. partial 수집 결과와 원문 제공 |
| service 상세 `/api/diagnoses/{thread_id}` | 기존 중계로 새 필드 전달; Java 코드 변경 없음 |
| 실행 목록 `/runs`, `/api/diagnoses` | 새 evidence는 제외. 기존 나머지 필드 유지 |
| 주문 Agent files REST | 기존 계약 재사용. 서버 코드 변경 없음 |
| service MCP | 새 도구·입출력 필드 없음. apply_patch v1.3 유지 |
| 프롬프트·제안 schema·승인 처리 | 변경 없음 |

## 검증

Python 전체 테스트 **583개 통과**. 변경한 Python 파일의 Ruff 검사 통과.

실행 명령:

```bash
cd skillops-agent
.venv/bin/python -m pytest -q
```

핵심 검증:

| 사례 | 결과 |
|---|---|
| owner 누락·깨진 YAML·version 누락 × 실패 진단/저장 이벤트 | 원문·목록 오류·diff와 실행 참조 저장, 자동 수정 관련 호출 없음 |
| 정상/오류 항목 혼합 | 정상 항목 버전과 오류 Skill 원문을 함께 보존 |
| 변경되지 않은 오류 파일 | 원문·목록 오류 저장, 없는 diff를 생성하지 않음 |
| CRLF·base64·잘못된 base64 | 원본 보존 또는 계약 오류, 비텍스트 diff 차단 |
| snapshot/path/hash/size·바이트 변조 | 미검증 원문 저장 차단, 먼저 검증한 증거 보존 |
| 파일 일부 조회 실패·diff 조회 실패 | 오류 코드와 앞 단계 원문 증거 보존 |
| 잘못된 diff 형식·출처 | diff 저장 차단, 원문 증거 유지 |
| 여러 hunks | 연속된 change_no 보존 |
| 추가 Skill·리소스 변경·잘못된 Skill ID | 가능한 원문·manifest 보존, 지원 밖 diff 사유 기록 |
| 파일 수·파일별 크기·합계 한도 | 초과 응답 거부 |
| 수집 노드 완료 후 복구 | SQLite 증거 유지, 중단 상태는 ERROR |
| 상세 REST·서비스 토큰·목록 | 인증된 상세에서 evidence 조회, 목록에는 원문 evidence 없음 |
| 기존 정상 진단·후보 판정·사전 검증 | 기존 회귀 테스트 통과 |

테스트는 HTTP/MCP 응답 대역과 실제 임시 SQLite를 사용했다. 실제 주문 Agent/service 서버, 운영 DB, LLM을 호출한 통합 검증은 수행하지 않았다. Java 운영 코드가 바뀌지 않아 Java 전체 테스트는 다시 실행하지 않았다.

## 보장 범위

저장 단위는 수집 노드의 완료 시점이다. 프로세스가 노드 실행 도중 중단되면 그 노드에서 아직 반환하지 않은 파일까지 복구한다고 보장하지 않는다. snapshot/목록 검사 자체가 실패한 응답은 정상 evidence로 저장하지 않는다.

파일 한도는 원본 바이트 기준으로 snapshot당 5,000,000바이트, 양쪽 합계 10,000,000바이트다. JSON/base64와 diff가 더해진 상세 응답은 이보다 클 수 있다. 원문을 조용히 잘라 저장하지 않는다.

커밋·푸시·배포 및 외부 이슈 체크박스 수정은 수행하지 않았다. 사용자 기존 변경인 order-agent README, docs/review, runner-integration-design 문서는 수정하지 않았다.
