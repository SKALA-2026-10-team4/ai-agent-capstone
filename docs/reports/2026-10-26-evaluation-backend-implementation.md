# #88 백엔드 계약 구현과 기획 정합성 검증

작성일: 2026-10-26 · 브랜치: `feat/#88_skill-backend-contract`

> 후속 변경: 사용자 요청으로 새 MCP 4개를 JWT 없이 호출하도록 전환했다. 이 보고서의 실서버 인증 검증·12회 스모크 결과는 전환 전 기록이다. 무인증 전환 후 별도 실서버 수용 검증도 완료했으며 현재 정책·키 범위·추가 테스트·실서버 결과는 [MCP 인증 간소화 기록](2026-10-26-evaluation-mcp-anonymous.md)을 따른다.

## 구현 결과

AI 코드 변경 없이 **고정 입력 접수 → 비동기 전후 실행 → 실제 증거 저장 → 서버 판정 → 검증된 snapshot 제안 → 사람 승인** 경로를 구현했다. 기존 Python·주문 Agent·프런트엔드 코드는 변경하지 않았다. 기존 service 실행·제안·승인 코드는 재사용하며 새 평가 모듈·도구·저장 경계를 추가했다.

현행 명세는 [평가 계약 v1.0](../contracts/evaluation-backend.md), 요청 구조는 [JSON Schema](../contracts/evaluation-requests.schema.json)다. 설계 초안보다 구현 범위를 좁힌 부분은 아래에 명시한다. #88 전체 완료나 실제 LLM 품질 검증 완료를 뜻하지 않는다.

## 주요 변경

| 모듈 | 변경 이유·내용 |
|---|---|
| `EvaluationTypes`, `EvaluationPolicy` | typed 요청·결과, 역할·상태·필수 suite·모드·입력 크기·예산 범위 정의 |
| `EvaluationJson` | 객체 키 순서에 독립적인 fingerprint, 원문 UTF-8 해시, null·배열 순서 보존 |
| `EvaluationService`, `EvaluationStore` | 평가셋 검토·고정 계획·접수 멱등성·결과 조회·시도 이력·DB 페이지 조회 |
| `EvaluationRecords`, `EvaluationRepositories` | 평가셋·job·attempt·trial·확정 보고서 저장. 실제 run_id 중복 귀속 방어 |
| `EvaluationWorker` | DB 선점·lease·fencing, 회차별 기준/후보 실행, 취소·중단·부분 결과 보존 |
| `EvaluationRunner` | 기존 snapshot/실행 클라이언트·공용 파서·채점기 재사용. 매 회차 workspace를 다시 읽지 않음 |
| `EvaluationReports` | 실제 저장 결과로 업무·선택·회귀·분모·편차·관측 상태 계산 |
| `EvaluationController`, `EvaluationErrors` | 인증된 REST 접수·조회·취소·재실행·구조화 오류 |
| `EvaluationTools` | REST와 같은 서비스를 사용하는 MCP 4개 추가. 후속 변경으로 MCP JWT 검사 제거, REST 권한 유지 |
| `VerifiedProposal*` | 제출된 valid/results를 받지 않고 서버 평가로 승인 가능한 snapshot 제안 생성 |

새로 작성한 함수·기능에는 짧은 주석을 남겼다. 기존 도구 7개·apply_patch v1.3·기존 승인 API 모양은 유지했다. MCP 실제 목록은 기존 7개+신규 4개=11개다.

## 원인 증거와 후보 합격의 구분

- 기준본의 재현 실패는 버리지 않는다. 그 실패가 고친 후보에서 성공으로 바뀌었는지 확인한다.
- 동일 case·회차·입력·snapshot을 연결하며 제출자가 성공 수를 임의로 정할 수 없다.
- 필수 suite는 REGRESSION·REPRODUCTION·INTENT이고 반복 누락·미관측·실패가 있으면 서버 검증 제안으로 저장하지 않는다.
- 기준본부터 성공한 재현 사례만으로는 실패 해결을 검증했다고 표시하지 않는다.
- 제안은 실행된 후보 snapshot/hash를 가리킨다. 원인 분석은 `NOT_PERFORMED`로 보존하고 본문에도 원인 실험·LLM 평가를 했다고 쓰지 않는다.
- 기존 `save_proposal`은 호환을 위해 남겼으므로 기존 Python 생산자의 제출 results 전체를 재구성하는 한계도 남는다. 후속 보강으로 합격 후보의 인용 실행이 실제 실패인 경우는 기존 경로에서도 거부한다([보강 기록](2026-10-26-legacy-proposal-validation.md)). 새 `save_proposal_v2`와 REST 경로부터 서버 검증을 적용한다.

## 자동화 검증

| 대상 | 결과 |
|---|---|
| service 전체 | 745개 통과, 실패·오류·skip 0 |
| 위 항목 중 새 PostgreSQL 계약 테스트 | 8개 통과: 동시 접수, 동시 선점, 입력 충돌, 보존된 결과·보고서, lease 소실, 취소, 재실행, 작업 간 증거 혼합 |
| 공용 skill-parser | 9개 통과, 실패·오류·skip 0. service 합계와 별도 |
| 기존 Python 전체 | 643개 통과. Python 코드는 변경하지 않음 |
| 기존 Python MCP 스모크 | 변경하지 않은 `scripts/mcp_smoke.py`로 새 서버의 tools/list·ping 성공 |

추가 단위·경계 검증은 없는 Skill의 음성 사례 오집계 방지, 실제 none/미관측 구분, 분모 0, 후보 실패·회귀, 라벨 미검토, 반복 누락, CRLF·원문 해시 보존, 실제 모델 설정 불일치, trace 유실 시 알려진 실행 ID 보존, 새 REST 권한, 실패 평가의 제안 생성 거부를 포함한다.

실DB 테스트는 환경 변수를 지정한 경우에만 일회용 PostgreSQL에서 실행한다. create-drop은 테스트 전용 DB에만 적용했다.

```bash
cd service
SKILLOPS_EVALUATION_INTEGRATION_TESTS=true \
SKILLOPS_EVAL_TEST_DB_URL=jdbc:postgresql://127.0.0.1:<테스트포트>/eval_test \
./gradlew test --offline --no-daemon --console=plain
```

## 실제 서버 수용 검증

기존 Docker의 8081 서버·DB와 분리하여 일회용 PostgreSQL에 `order_eval`, `service_eval`, `eval_test`를 만들었다. 실제 주문 Agent는 18081, 새 service는 18080에서 fixed·격리 환경으로 실행했다. OPENAI_API_KEY는 not-set이며 실제 LLM 호출은 없었다.

사용한 [수용 검증 스크립트](../../service/scripts/evaluation_contract_smoke.py):

```bash
skillops-agent/.venv/bin/python service/scripts/evaluation_contract_smoke.py \
  --service http://127.0.0.1:18080 \
  --order-agent http://127.0.0.1:18081 \
  --environment eval-88-isolated-v1 \
  --output /private/tmp/evaluation-88-smoke.json
```

검증 시나리오는 다음과 같다.

1. 기존 baseline fixture를 읽고 알림 단계가 빠진 기준본과 정상 알림 단계가 있는 후보본을 각각 불변 snapshot으로 발행한다.
2. 실제 golden 주문을 회귀·재현·의도 사례로 등록하고 운영자가 라벨을 검토한다.
3. 같은 키의 접수가 동일 evaluation/attempt를 반환하는지 확인한다.
4. 실제 fixed harness로 기준 3회+후보 3회를 실행한다. 기준 업무 성공률 0/3, 후보 3/3, 필수 측정 누락 0, 회귀 0으로 PASS·SERVER_VERIFIED를 확인한다.
5. 서버 검증 제안을 중복 저장해도 같은 proposal_id이며, 기존 승인 API에서 사람의 의도 확인 후 approved가 되는지 확인한다.
6. 실패하는 후보로 별도 평가 6회를 실행한다. FAIL·NOT_VERIFIED이며 검증 제안 저장은 409 EVIDENCE_MISMATCH인지 확인한다.
7. 실제 MCP의 접수·비교 보고서·v2 제안 저장과 기존 도구 공존을 확인한다. JWT 없는 새 도구 접근도 거부된다.
8. 실제 dry-run 뒤 재고가 복원되고 live 실패 목록이 바뀌지 않았는지 확인한다. 평가용 주문·trace·제안·승인 이력은 격리 DB에 의도적으로 생성한다.

스모크 한 번은 성공 평가 6회+실패 평가 6회로 총 12회의 실제 fixed 실행을 생성한다. 반복 검증 전체를 12회로 표기하지 않는다. 최신 결과는 [JSON 요약](2026-10-26-evaluation-backend-smoke.json)에 보관하며 인증 토큰·개인정보·전체 trace는 포함하지 않는다.

최종 버전의 service 프로세스를 재시작한 뒤 확정 보고서가 동일하고 완료 수 6과 approved 상태가 보존됨을 확인했다. 시도 이력·사례 DB 페이지·등록 평가셋의 해시와 실제 응답의 요청 Schema 호환도 확인했다. 개발 중 반복 수용 검증 전체의 주문 저장소 집계는 49개 실행, 전부 fixed/dry_run, 모델 API 호출 합계 0회였다.

## 기획 대비 판단

| 기획의 요구 | 현재 확인 |
|---|---|
| 실제 Agent 실행·과정 기록 | 실제 주문 fixed harness·dry-run trace를 저장하고 snapshot/hash·모드·실행 ID를 대조 |
| 같은 조건의 변경 전후 평가 | snapshot·평가셋·주문 입력·기대 조건·반복 수·정책을 접수 시 고정 |
| 객관적 비교·회귀 판정 | 서버 저장 trial에서 업무 성공률·선택 지표·회귀·편차를 계산. 미측정은 null/UNMEASURABLE |
| 근거를 갖춘 승인 요청 | 통과한 서버 평가만 새 제안으로 저장하고 기존 사람 승인 경로로 연결 |
| 장시간 작업·중단 증거 | durable DB 접수·진행·취소·lease·부분 결과·확정 보고서·시도 이력 구현 |
| 운영 반영 제외 | 승인 기록까지 수행하며 자동 배포·운영 Skill 변경은 수행하지 않음 |
| 실제 LLM 호출 품질·신규 Skill 호출 개선 | 아직 미지원. fixed 기능 검증으로 대신 입증했다고 표시하지 않음 |
| 안전성·실모델 비용 제어 | 안전성은 UNMEASURABLE, 실모델은 예산 제어 미지원으로 접수 거부 |

따라서 **기획의 실제 실행·전후 비교·근거 승인 방향에 맞는 백엔드 기반을 확인했다.** 실제 LLM의 호출 개선·원인 진단·자동 수정·안전성까지 제품 전체가 완성됐다는 판단은 하지 않는다.

## 남은 협업·구현 경계

- Python 생산자가 새 평가·v2 제안 계약을 사용하도록 전환하는 작업과 라벨·성공 기준 합의.
- 별도 description 선택 실험을 실제 대상 Agent 평가와 구분해 연결하는 작업. Python 해석·오집계 수정도 해당 담당 범위다.
- 주문 Agent 내부 deadline·토큰·비용 강제·멱등 조회·중단 제어 확장. 현재 wall time·취소는 다음 실행을 막고 늦은 결과를 보존하며 진행 중 주문의 즉시 중단은 보장하지 않는다.
- 신규 Skill 호출 품질 승인, 안전성 측정, 실모델·RAG 노출 기준의 수용 검증.
- 기존 legacy 제안 저장·승인 경로를 새 검증으로 전환할 시점과 호환 정책의 합의.

#88의 AI 제외 백엔드 우선 구현은 위 지원 범위까지다. 미지원 항목을 조용히 성공 처리하지 않으며, 이슈 전체 종료는 담당자 합의와 후속 연동 범위를 확인한 뒤 판단한다. 초기 평가 구현에서는 기존 담당자의 코드를 수정하지 않았다. 이후 사용자 승인으로 기존 제안 검증을 보강했으며 별도 보강 기록에 남겼다. 검증 당시에는 커밋하지 않았고, 이후 사용자 요청으로 단위별 로컬 커밋을 진행했다. 원격 이슈 변경·push·배포는 수행하지 않았다.

## 이슈 댓글에 복사할 진행 요약

```markdown
### 백엔드 우선 구현 완료

- [x] 평가셋 등록·검토와 기준/후보 snapshot·원문·해시·반복 입력 고정
- [x] DB 기반 비동기 접수·멱등 처리·상태·취소·재시작·시도 이력
- [x] 실제 fixed 주문 harness 연결과 trial·trace·서버 판정·비교 보고서 저장
- [x] 실패 재현 증거와 후보 성공 근거 구분, 미관측·분모 0·반복 누락 처리
- [x] REST 및 MCP 4개 추가, 기존 7개 도구·apply_patch v1.3 유지
- [x] 서버 검증 제안서 저장과 기존 사람 승인 API 연결
- [x] service 745개·공용 파서 9개·기존 Python 643개 테스트 통과
- [x] 격리 실서버의 정상/실패 후보·MCP·승인·재시작·재고 복원 검증

### 남은 범위

- [ ] AI 담당자의 라벨·성공 기준 합의와 새 평가/v2 제안 계약 연동
- [ ] 주문 Agent 내부 예산·중단·멱등 조회 제어를 연결한 실제 LLM 평가
- [ ] 신규 Skill 호출 품질 승인과 안전성 측정
- [ ] 기존 legacy 제안 경로의 전환 시점·호환 정책 합의

현재 SERVER_VERIFIED는 fixed 기능 검증 범위이며 실제 LLM 품질 검증을 뜻하지 않습니다.
Python·주문 Agent·프런트엔드 코드는 수정하지 않았고 자동 수정·운영 배포도 수행하지 않았습니다.
#88 전체 완료·종료 상태로는 아직 변경하지 않습니다.

명세: docs/contracts/evaluation-backend.md
검증: docs/reports/2026-10-26-evaluation-backend-implementation.md
```
