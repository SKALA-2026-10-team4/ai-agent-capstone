# SKILL 저장→진단→개선안→승인 흐름 점검

작성일: 2026-10-26 · develop 충돌 해결 후 병합 작업 트리 기준.

## 결론

저장 이벤트부터 service 중계, Python 진단 그래프, 실제 MCP와 주문 모의 실행, 제안 저장 및 사람 승인·반려까지 연결 동작을 확인했다. 이미지의 핵심 흐름은 구현과 일치한다. 단, RAG 반영은 승인 직후 자동 실행되지 않으며 후보 수와 재시도 표현에도 보정이 필요하다.

이번 검증은 #88의 별도 evaluation API 스모크가 아니다. 기존 SKILL 수정 저장→/diagnose→save_proposal 경로를 실제로 실행했다. LLM 응답만 기존 b_c_contract_smoke.py의 고정 대역을 임시 런처에서 주입했다. 실제 LLM의 추론·생성 품질이나 브라우저 조작을 검증한 것은 아니다.

## 실제로 연결한 경로

```text
운영 화면과 동일한 POST /api/snapshots/edits
  → SnapshotService: snapshot + 저장 이벤트 기록
  → SkillChangeDispatcher: /internal/skill-changes 전달
  → DiagnosisController.changed: Python /diagnose 중계
  → FastAPI BackgroundTasks + SQLite RunStore
  → 실제 LangGraph 노드
  → MCPTools → service MCP → 주문 Agent fixed dry-run
  → ProposalJudge 서버 재판정 → 기존 save_proposal 저장
  → 기존 REST에서 OPERATOR 승인·반려
```

기존 제품 코드는 수정하지 않았다. 임시 Python 런처에서 make_structured_llm만 대체하고, 주소·DB·서비스 토큰은 환경 변수로 분리했다. 별도 PostgreSQL 컨테이너와 service_eval/order_eval DB, 전용 SQLite, 루프백 18080·18081·18000 포트를 사용했다. 기존 사용자 서버·DB를 사용하지 않았다.

## 실제 사례 5개

| 사례 | 확인 결과 |
|---|---|
| 정상 설명 변경 저장 | 이벤트 전달 후 기준·수정본 평가, VALIDATED 종료. 제안서 없음 |
| inventory.reserved 출력을 제거한 결함 저장 | 이벤트 전달→원인 가설→복원 실험→후보 검증→제안 저장. EVALUATED·proposed 후 OPERATOR 승인 성공 |
| 결함이 있는 snapshot을 기준본으로 수정 저장 | BASELINE_FAILED 종료, 제안서 없음 |
| 카드 결제를 금지하려는 담당자 의도와 본문 변경 저장 | INTENT_NOT_REALIZED, 가설·복원 실험을 건너뛰고 encode_rule 후보 검증·제안 저장. 사람 반려 성공 |
| 도구가 생성하지 않는 weather.current 입력 요구 | NEEDS_TOOL, 원인 실험 후 후보 생성 없이 development_request 저장. no_valid_fix, 승인 요청은 409 NO_VALID_FIX |

결함 사례에서 관측한 노드는 init→fetch_evidence→hypothesize→ablate→check_ablation→extract_intent→generate_candidates→verify_candidates→check_valid→rank_and_explain→save_proposal 순서였다.

담당자 의도 사례는 가설 단계를 건너뛰었고, 도구 부재 사례는 extract_intent·generate_candidates·verify_candidates를 실행하지 않았다. 분기는 이미지 설명대로 동작했다.

### DB·상태 대조

- 저장 이벤트 5개 모두 thread_id를 받아 전달 완료, failed_at 기록 0개.
- 최종 제안서: approved 1개, rejected 1개, no_valid_fix 1개.
- 실제 주문 실행 162건, 전부 fixed/dry_run. agent_executions.api_calls 합계 0.
- LLM 대역 호출은 가설 2회·의도 1회·후보 2회·설명 2회. 실제 모델 API는 호출하지 않았다.
- 정상·결함·기준본 실패 사례의 실행 전후 재고와 live 실패 목록은 동일했다. 추가 의도·도구 부재 사례까지 마친 뒤 재고도 초기 시드(랜턴 10·의자 5·머그컵 0)와 같고 live 실행 0건이었다.

주문 원문·전체 trace·토큰을 제외한 증거는 [수용 결과 JSON](2026-10-26-skill-save-flow-smoke.json)에 보관한다.

## 이미지에서 보정할 설명

### 1. 후보는 고정 3개가 아니다

generate_candidates.md는 same_skill·linked_skills·run_order·encode_rule의 4가지 유형을 요청한다. 코드가 담당자 의도에서 결정적으로 만드는 encode_rule 후보도 추가된다. 실제 개수는 LLM 출력과 결정적 후보 수에 따라 다르다. 이번 결함 사례는 1개, 담당자 의도 사례는 encode_rule 2개와 LLM 후보 1개가 만들어졌다. 생성된 후보가 모두 합격한다는 뜻은 아니다.

권장 표현: **의도를 유지하는 수정 후보를 만들고, 실행 규칙 후보도 포함해 실제 실행으로 검증한다.**

### 2. 후보 재시도는 총 최대 3회다

MAX_CANDIDATE_RETRIES=3이며 check_valid가 검증 회차를 올린 뒤 한도를 확인한다. '처음 실행하고 추가로 3번'이 아니라 후보 생성·검증을 총 최대 3회 수행한다. 단계 한도나 오류 때문에 더 일찍 종료할 수도 있다.

### 3. 모든 실패가 제안서로 저장되지는 않는다

정상 검증과 기준본 실패는 제안 없이 종료한다. 후보가 모두 불합격이거나 정해진 경로에서 원인 가설을 소진하면 no_valid_fix로 기록할 수 있다. 통신·계약·모델 예외는 guarded에서 ERROR로 종료되며 제안 저장까지 도달하지 않을 수 있다. 이미지의 '유효 후보가 없어도 제안서를 남긴다'는 정상적으로 진단을 마친 경로에 한정한다.

### 4. 승인·반려 직후 RAG가 자동 갱신되지는 않는다

ApprovalService.recordDecision은 제안 상태와 결정 기록을 저장하며 RAG 수집을 호출하지 않는다. 승인된 결과를 다음 진단 자료에 반영하려면 다음 과정이 필요하다.

1. 계정을 설정해 scripts/build_rag_data.py를 별도로 실행한다. SKILLOPS_RAG_SERVICE_USERNAME/PASSWORD가 필요하다.
2. 수집기가 승인·반려된 제안에서 사례 변환 조건을 만족하는 항목을 골라 RAG 파일을 만든다. 모든 제안이 사례가 되는 것은 아니다.
3. rag._load는 파일을 lru_cache로 보관하므로, 재생성된 자료를 읽으려면 현재 구현에서는 Agent 프로세스를 재시작해야 한다.

실제 생성한 제안 3개에 cases_from_proposals를 적용했을 때 사례 1개가 만들어졌다. 승인된 결함 수정은 포함됐지만, 실패 정보나 저장할 해법이 없는 항목 등은 제외될 수 있다. 이번 점검에서는 기존 RAG 파일을 덮어쓰거나 Agent를 새 자료로 재시작하지 않았다.

권장 표현: **승인·반려 기록 중 조건을 만족하는 결과는 별도 수집·갱신 과정을 거쳐 이후 진단의 참고 자료로 사용할 수 있다.**

## 남은 확인과 경계

- 실제 LLM의 가설·의도 추론·후보 생성 품질은 미검증이다. 고정 대역으로 확인한 연결 동작과 구분한다.
- 브라우저에서 버튼을 누르는 E2E는 수행하지 않았고, 같은 백엔드 저장·조회·승인 API를 호출했다.
- 반복 소진·모델/통신 오류 등 모든 예외 조합을 이번 실서버 실험에서 재현하지는 않았다. 앞선 전체 단위·통합 테스트와 그래프 분기 코드를 함께 확인했다.
- 승인 뒤 운영 Skill 자동 반영·배포는 이 흐름에 포함하지 않는다.
- RAG의 승인 이벤트 기반 자동 수집과 캐시 갱신은 별도 후속 작업이다. 사용자 요청이 점검이므로 관련 코드는 수정하지 않았다.

점검용 프로세스 3개와 일회용 DB 컨테이너는 정리했다. 기존 사용자 컨테이너는 그대로다. 제품 코드·기존 RAG 데이터는 수정하지 않았고, 이번 점검 문서·JSON은 검증 후 사용자 요청으로 별도 로컬 커밋에 기록한다.
