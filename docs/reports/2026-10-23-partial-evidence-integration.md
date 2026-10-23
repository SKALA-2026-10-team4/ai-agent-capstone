# #72 · partial 증거 수집 실서버 검증

검증일: 2026-10-23 19:11 KST · 구현 기준: `8712c91` · 결과: **3개 사례 모두 통과**

## 검증 범위

대역 응답 대신 실제 주문 Agent REST, 현재 소스로 빌드한 service MCP, 별도 프로세스의 Python 진단 서버, 실제 PostgreSQL·SQLite를 연결했다. owner 누락·깨진 YAML·version 누락 문서에서도 원문·해시·목록 오류·diff를 저장하고 종료하는지 확인했다.

자동 수정 기능은 추가하지 않았다. 원인 분석 LLM이나 실행 평가도 이번 partial 증거 수집 경로에서는 호출하지 않는다. 완전한 문서의 기존 자동 진단 경로를 이번 실서버 검증에서 다시 실행하지는 않았다.

## 구성과 데이터 격리

| 대상 | 검증 구성 |
|---|---|
| 주문 Agent | 기존 로컬 Docker 서버 `127.0.0.1:8081`; 기존 이미지를 재빌드하거나 재시작하지 않음 |
| service | `8712c91` 소스에서 `bootJar --offline`로 빌드, 별도 포트 `18072` |
| service DB | 별도 컨테이너 `skillops-partial-evidence-db-72`, 포트 `15472`, tmpfs PostgreSQL 17 |
| Python 진단 서버 | 현재 소스의 실제 FastAPI 앱, 별도 포트 `18073`; 검사 중 한 번 종료 후 재시작 |
| 실행 기록 | 별도 `.data/partial-evidence-live-20261023-1/runs.sqlite3` |
| 스모크 | [재실행 스크립트](../../skillops-agent/scripts/partial_evidence_smoke.py) |
| 결과 참조 | [검증 결과 JSON](2026-10-23-partial-evidence-smoke.json) |

기존 service DB와 Python 개발용 runs.sqlite3는 사용하지 않았다. 기존 운영 Skill 파일도 수정하지 않았다. 주문 Agent에는 기준 fixture snapshot 1개와 테스트 후보 snapshot 3개만 추가했다. snapshot candidates API를 사용했으므로 운영 편집이나 저장 이벤트 자동 발행을 수행하지 않았다. 테스트 진단 이벤트는 별도의 service로 직접 보냈다.

## 실제 요청 흐름

```text
주문 Agent POST /api/snapshots: 정상 기준 고정
  → GET snapshot files: 원문·해시 확인
  → POST /api/snapshots/candidates: 오류 후보 3종 발행
  → GET snapshot skills: partial=true, 정상 5개와 오류 1개
  → GET 개별 Skill: 400 거부 확인

service POST /internal/skill-changes
  → Python POST /diagnose: 비동기 접수, thread_id 반환
  → 실제 그래프의 목록·원문·diff 수집
  → 실제 service MCP diff_skill
  → SQLite 저장, ERROR 종료
  → Python 상세 조회와 로그인한 service 상세 중계 비교
  → Python 프로세스 종료·재시작 후 같은 evidence 재조회
```

## 결과

| 후보 문서 | 목록 | 저장한 원문 | diff hunk | YAML 파싱 오류 | 최종 결과 |
|---|---|---:|---:|---|---|
| owner 누락 | 정상 5개·오류 1개 | 기준 6개 + 후보 6개 | 1 | 없음: YAML 문법은 정상 | ERROR / COLLECTED |
| 깨진 YAML | 정상 5개·오류 1개 | 기준 6개 + 후보 6개 | 1 | `frontmatter_parse_error` 보존 | ERROR / COLLECTED |
| version 누락 | 정상 5개·오류 1개 | 기준 6개 + 후보 6개 | 1 | 없음: YAML 문법은 정상 | ERROR / COLLECTED |

세 경우 모두 목록의 `INVALID_SKILL_FORMAT` 오류를 보존했고 원문 수집 자체의 collection_errors는 비어 있었다. ERROR는 문서의 메타데이터가 불완전해 자동 진단을 이어가지 않는다는 뜻이다. COLLECTED는 증거 수집 완료이며 문서가 정상이라는 뜻이 아니다.

- 양쪽 원문 12개의 content와 원본 SHA-256을 실제 snapshot 파일과 대조했다.
- SQLite state_json과 HTTP 상세 응답의 evidence가 동일했다.
- 서비스 토큰 없는 Python 기록 조회는 401이었다.
- 로그인한 service 상세 중계에서도 evidence가 동일했다. 최근 목록에는 새 원문 evidence가 포함되지 않았다.
- 세 진단의 진행 노드는 `init → fetch_evidence → collect_partial_sources → collect_partial_diffs`였다.
- 진단 서버를 실제로 종료하고 같은 SQLite로 다시 띄운 뒤 세 evidence의 해시가 모두 유지됐다.

## 자동 수정·주문 실행 미호출 확인

진단 서버의 경계에 감사 기록을 추가했다. **네트워크 응답·파싱·수집·그래프·DB 저장을 대역으로 바꾸지 않았다.**

- MCP `_call`의 도구 이름을 기록했다. 관찰된 호출은 실제 `diff_skill` **3회**뿐이었다.
- 그 외 도구는 시도할 경우 기록 후 실패하도록 보호했다. 예상하지 않은 호출이 있었다면 검증을 통과하지 못한다.
- LLM 생성 함수에도 같은 기록·차단 경계를 뒀다. **LLM 호출 시도는 0회**였다.
- 주문 Agent로 나가는 진단 서버의 HTTP 요청은 모두 GET이었다.
- 임시 PostgreSQL을 별도로 조회한 결과 `workspaces=0`, `proposals=0`, `approvals=0`이었다.
- 실제 상품 재고, 최근 주문 실행 목록, 실패 실행 목록은 검증 전후 동일했다.

따라서 이 결과는 유료 LLM의 응답 품질이나 실행 평가 능력을 검증한 것이 아니다. 이번 경로가 해당 호출을 시도하지 않고 증거 수집만 완료한다는 것을 검증한 결과다.

## 재실행

주문 Agent가 실행 중이고, service가 **별도 테스트 DB**에 연결돼 있어야 한다. service의 진단 Agent 주소와 스크립트의 agent-port를 맞추고 service와 스크립트에 같은 임시 서비스 토큰을 설정한다.

```bash
# service: ORDER_AGENT_BASE_URL=http://127.0.0.1:8081
# service: SKILLOPS_AGENT_URL=http://127.0.0.1:18073
# service: SKILLOPS_SERVICE_TOKEN=<검증 전용 토큰>
# service: SERVER_PORT=18072 및 별도 PostgreSQL 접속 설정

cd skillops-agent
export SKILLOPS_SERVICE_TOKEN='<service와 같은 검증 전용 토큰>'
.venv/bin/python scripts/partial_evidence_smoke.py \
  --order-url http://127.0.0.1:8081 \
  --service-url http://127.0.0.1:18072 \
  --agent-port 18073 \
  --runtime .data/partial-evidence-live-new-run \
  --output ../docs/reports/partial-evidence-new-run.json
```

runtime 디렉토리는 새 경로여야 한다. 스크립트는 Python 진단 서버를 직접 시작·종료하고 실제 앱을 재시작한다. demo 로그인은 별도 service의 기본 operator 계정을 사용한다. 계정이 다른 환경에서는 검증용 계정 입력을 먼저 조정한다. 임시 service/PostgreSQL의 시작·종료와 DB 행 수 조회는 스크립트 외부에서 수행한다.

실행할 때마다 주문 Agent DB에 테스트 snapshot 4개를 추가한다. 기존 snapshot을 삭제하거나 주문을 생성하지 않는다. 원문·로컬 감사·로그 파일은 `.data`에 남기고 결과 JSON에는 토큰·비밀번호·고객 주문 본문을 포함하지 않는다.

## 검증 후 상태와 한계

- 임시 service 프로세스, 임시 Python 프로세스와 임시 PostgreSQL 컨테이너를 종료했다. tmpfs DB는 제거했다.
- 기존 주문 Agent 컨테이너 세 개는 계속 healthy 상태다.
- 테스트 snapshot 네 개는 기존 주문 Agent DB에 남아 있고, 진단 세 개는 별도 로컬 SQLite에 남아 있다. 참조 UUID는 결과 JSON에 기록했다.
- 기존 Python 테스트 583개 통과 결과에 더해 이번 실서버 스모크 3개가 통과했다. 새 스모크 파일은 Ruff·포맷 검사를 통과했다.
- 테스트 도중 원문 변조·네트워크 장애를 실제 서버에 주입하지는 않았다. 해당 경계는 앞선 Python 테스트로 검증했다.
- 기존 정상 진단 전체, 실제 LLM 분석, 실제 실행 평가, 수정 없이 원인만 분석하는 새 경로는 이번 실험 대상이 아니다.
- 운영 코드와 API/MCP 계약은 이번 검증에서 추가 변경하지 않았다. 이 후속 검증 자료와 스크립트는 아직 커밋하지 않았다.
