# B의 현재 C 계약 적용 (#60)

> **2026-10-23 · #72 apply_patch v1.3:** `broken_links`와 `dependency_violations`는 `no_broken_links`, `metadata_errors`는 `metadata_valid`, 필수 `flow_errors:[{skill,code,reason,message}]`는 `flow_valid`로 판정한다. 세 검사가 모두 참이어야 후보가 합격한다. 네 오류 목록의 누락·null·잘못된 형태는 ContractError다. 후보 오류 상세를 제안서에 보존한다. 원인 실험에서는 오류 목록의 존재와 형식을 확인하되 내용이 비어 있지 않아도 실험을 계속한다. service와 Python을 함께 배포해야 하며 stub과 실제 MCP 판정 스크립트도 같은 계약을 사용한다. [최신 명세](../../docs/contracts/mcp-tools-input-output.md#8-수정-적용하기--apply_patch)

> 후속 구현: 저장 이벤트 자동 전달·중복 방지·진행 조회·화면 연결은 `docs/skill-save-auto-diagnosis.md`에 정리했다. 아래의 자동 연동 미구현·메모리 상태 관련 설명은 해당 후속 구현으로 대체된다.

2026-10-21. 변경 범위는 skillops-agent/이며 C 서비스·주문 Agent 소스는 변경하지 않는다.

## 실행 계약

- 주문 HTTP: SKILLOPS_ORDER_AGENT_URL, 없으면 ORDER_AGENT_BASE_URL, 기본 http://localhost:8081.
- MCP: SKILLOPS_MCP_URL, 기본 http://localhost:8080/mcp.
- 실제 도구가 기본이다. 오프라인 개발에서만 SKILLOPS_USE_STUB_MCP=true를 지정한다.
- 회귀 suite: SKILLOPS_GOLDEN_IDS 기본 g-01,g-02,g-03. C의 SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent와 일치시킨다.
- C는 SKILLOPS_FLOW_RUNNER=harness로 실행한다. contract-only 결과는 실제 주문 실행 검증을 대신하지 않는다.

## 진단 요청

POST /diagnose. 서비스 토큰이 설정되면 X-Service-Token 필요.

```json
{
  "type": "failure",
  "execution_id": "<실행 UUID>",
  "baseline_snapshot_id": "<수정 전 snapshot UUID>",
  "candidate_snapshot_id": "<수정 후 snapshot UUID>",
  "changed_paths": ["skills/inventory-check/SKILL.md"],
  "change_id": "<사용자 저장 참조>",
  "entry_skill_id": "order-flow"
}
```

- 기존 run_id도 받지만 UUID 또는 r_+32자리만 허용하고 내부 execution_id로 변환한다.
- failure에는 실행 참조가 필요하다. candidate는 trace에서 얻을 수 있고, 둘 다 제공되면 일치를 검사한다.
- baseline은 명시하거나 후보 manifest.metadata.baseline_snapshot_id에서 얻는다. 이전 버전 번호나 가짜 snapshot 문자열로 추정하지 않는다.
- changed_paths를 제공하면 두 manifest의 실제 변경 목록과 비교한다. 생략하면 해시 차이로 계산한다.
- snapshot의 metadata·해시·실행 snapshot 참조, C diff의 before/after 참조를 검사한다.
- 현재 지원은 기존 SKILL.md 변경이다. resources 변경·Skill 추가/삭제의 ablation은 ERROR로 명시한다. 형식 파싱이 불완전한 후보도 무시하지 않고 ERROR로 남긴다.
- 정의되지 않은 요청 필드는 422로 거부한다. skill/version은 호환 필드로 남지만 snapshot 참조를 대신하지 않는다.

응답 202에는 thread_id가 포함된다. GET /runs/{thread_id}/events로 events/error/diagnosis_status/diagnosis/proposal을 조회한다. 접수 직후 첫 state가 생기기 전에는 404일 수 있다. 현재 MemorySaver와 BackgroundTasks는 프로세스 재시작 후 복구되지 않는다.

## 실행 흐름

1. 실제 trace는 skill_id/data 구조로 읽는다. SKILL_SELECTED에서 단계 버전을 추출하지 않는다.
2. 후보 snapshot의 목록에서 patch 기준 버전을 읽고, 전체 manifest에서 실제 변경 파일을 찾는다. 실패 위치와 변경 원인은 구분한다.
3. C-1 hunks를 유지하고 B 내부 change_no만 전체 변경 목록에 1부터 붙인다. hunk_id와 원본 index를 보존한다.
4. 매 workspace는 create_workspace(snapshot_id=후보)로 만든다. graph entry는 실패 skill이 아니라 order-flow다.
5. 원본 후보의 실패가 같은 category로 재현되는지 먼저 검사한다. 이어 단일 hunk의 줄 위치·문맥을 보존한 역patch로 원인 실험을 한다.
6. 후보 patch의 base_version은 실패 snapshot에서 복사한 workspace 버전과 일치해야 한다.
7. 회귀는 g-01~g-06(2026-10-26 행동 변경 계획 2.8, 이전 g-01~g-03), 재현은 f-<execution UUID>로 실행한다. 실행 오류 없이 결과만 다른 기준 주문은 그 기준 주문을 재현으로 쓴다(`repro_scenario_ids`). 결과 개수·반복별 실행 ID·실패 참조·전체 판정이 일치해야 한다.
8. 의도 checks는 재현 주문에 별도 extra_checks로 전달하여 C Judge가 타입별 판정한다. 실패/회귀의 모든 반복을 확인한다. 회귀의 정상 거절 주문에 재현 전용 조건을 일괄 적용하지 않는다.
9. 도구 증거는 TOOL_REQUEST.data.tool에서 읽는다. C가 반환한 r_ 별칭은 UUID로 변환한 뒤 HTTP trace를 조회한다.
10. 계약 연결·재현·회귀·의도를 모두 통과해야 valid다. MCP 오류·잘못된 응답·미실행은 ERROR이며 원인 확정/수정 성공으로 처리하지 않는다.

## 제안서 저장 (C save_proposal)

save_proposal은 이제 C에 있다. B의 save_proposal 노드는 제안서(`nodes.proposal_payload`)를 보내고 `{proposal_id, status, valid_candidate_nos}`를 받아 `proposal.id`, `storage_status=SAVED`로 남긴다. C가 근거 실행이나 합격 조건으로 거부하면 진단은 ERROR로 끝난다. 승인·거절은 화면과 service가 맡고 B로는 오지 않는다. 운영 반영(promote_workspace)은 아직 없다.

## 정상 변경과 S5

- type=skill_saved, candidate_snapshot_id를 주면 B가 C의 회귀 suite로 사전 검증한다. 모두 통과하면 VALIDATED로 종료하고 LLM/수정안을 호출하지 않는다. 별도 저장 이벤트 송신·중복 처리·UI 조회 연결은 이번 범위가 아니다.
- S5는 성공한 실제 주문 실행 UUID와 diagnosis_only=true, intent_checks를 함께 전달한다. 예: card 주문에서 `payment.status != 'PAID'`처럼 미충족 여부를 관찰할 수 있는 조건을 지정한다.
- B는 조건을 C replay Judge에 전달하고 실제 조건 실패가 있으면 INTENT_VIOLATION과 실패 이유·실행 증거를 반환한다. 후보 생성·자동 해결은 하지 않는다.
- 현재 C의 조건은 context 값 비교다. '특정 도구가 한 번도 호출되지 않음' 같은 trace 조건 전체를 지원하는 것은 아니다. 비교 경로가 없는 조건을 보류 정책 전체 검증으로 과장하지 않는다.

## 검증 방법

```bash
cd skillops-agent
uv sync --frozen
uv run pytest
uv run ruff check skillops_agent tests scripts/b_c_contract_smoke.py
```

테스트는 실제 C 모양의 trace/hunks/graph/report fixture, HTTP 대역, fake LLM을 사용한다. 스텁 통과를 실서버 또는 실제 LLM 통과로 표현하지 않는다.

실제 C·주문 Agent가 켜져 있으면:

```bash
SKILLOPS_USE_STUB_MCP=false uv run scripts/b_c_contract_smoke.py
```

이 실험은 LLM 의사결정만 고정 대역으로 바꾸고 나머지는 B 그래프→실제 MCP→주문 Agent dry-run을 사용한다. 준비된 F1 snapshot과 dry-run 기록을 추가하지만 live 주문은 생성하지 않으며 전후 상품 재고와 운영 실패 목록을 비교한다. 자연어 정책에 대한 실제 모델 능력 검증은 포함하지 않는다.

## 2026-10-21 검증 결과

- B 단위·HTTP 계약·그래프 테스트 53개 통과, 관련 Python Ruff 검사 통과.
- 실제 C MCP + 주문 Agent HTTP 실험 통과. LLM 결정만 고정 대역으로 제공했다.
- F1: 원본 오류 재현 → inventory-check hunk 역patch 실험 → 수정 후보 회귀·재현·의도 확인 → EVALUATED/NOT_SAVED.
- S1: 실제 저장 API로 설명만 수정한 snapshot → 회귀 통과 → VALIDATED(LLM 호출 없음).
- S5: 실제 본문 정책 변경 → card dry-run → C 조건 실패 → INTENT_VIOLATION(수정 후보 생성 없음).
- 상품 재고와 live 실패 목록은 실험 전후 동일했다. snapshot·dry-run 증거는 새로 생성했다.
- [실제 실행 참조와 결과](b-c-contract-smoke-result.json)를 함께 보존한다. 해당 UUID는 이번 로컬 DB 실행의 참조이며 다른 환경에서 그대로 사용하지 않는다.
- 실제 유료 LLM의 가설·후보 생성 품질, 저장 이벤트 자동 송신, 제안 영구 저장·승인·반영은 검증하지 않았다.

실행 환경은 공용 화면 5173, 주문 Agent 8081, C 8080, B 8000이다. 검증용 C는 별도 임시 PostgreSQL(15432, 컨테이너 skillops-b-contract-db)을 사용하고 기존 C DB는 변경하지 않았다. 주문 Agent는 최신 API가 들어 있는 기존 소스로 백엔드 이미지를 재빌드했으며 DB 볼륨은 유지했다.

재시작한 실제 B HTTP 서버에서도 POST /diagnose → thread_id 반환 → GET /runs/{thread_id}/events → VALIDATED를 확인했다(정상 수정본, 오류 없음). 검증 thread_id: 85e58de6-9fb3-4c7a-a419-102d468be6f9.
