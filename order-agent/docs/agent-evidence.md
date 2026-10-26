# 주문 Agent · snapshot · trace 구현

2026-10-20 / 1차 주문 MVP 이후 확장. 기존 주문 API와 DB 데이터를 보존하며 추가했다.

## 실제 실행 경로

주문 API → OrderService → OrderProcessingService → OrderHarness → 고정된 snapshot → 엔트리 선택 → order-flow.steps 검증 → 각 단계 Skill 실제 로드 → 입력·도구 요청 검증 → 기존 업무 Mock 실행 → 결과 전달 → 주문·trace 저장.

별도 Agent 서버는 없다. Spring Boot 안의 Harness가 실행을 관리한다. 기본 `fixed`는 모델 응답을 고정 정책으로 대체하며, 파일 로드·계약 검사·업무 도구·DB·trace는 실제로 실행한다. `openai`는 같은 포트에서 Spring AI 2.0.0을 사용한다. 고정 모드가 자연어 Skill 본문의 정책 해석까지 검증하는 것은 아니다.

정상 주문은 order-flow와 단계 5개를 실제 로드한다. steps는 로드한 flow에서 읽으며 모델이 임의로 순서를 다시 만들지 않는다. 모델이 반환한 결과가 재고·결제 상태를 직접 바꾸지 않는다. 도구가 반환한 실제 출력 중 Skill에 선언된 값을 다음 단계에 전달한다.

## 화면에서 확인

http://localhost:5174 아래의 **Trace · Snapshot** 영역을 사용한다.

1. 새 주문을 실행한다. 기존 MVP 주문에는 기록을 소급 생성하지 않는다.
2. 최근 실행을 선택하거나 현재 주문의 실행이 자동 선택되는 것을 확인한다.
3. **Trace 보기**: 제공 후보·원래 선택·이름 해석·실제 로드·도구 인자/결과·오류·최종 상태를 펼쳐본다.
4. **Snapshot 보기**: 실행 당시 파일 목록, 전체/파일별 SHA-256, Skill 본문과 관련 파일을 조회한다.
5. 페이지를 새로고침해도 최근 실행을 다시 조회할 수 있다. 읽기 전용 확인은 `python3 order-agent/scripts/check-evidence.py`(저장소 루트)로 수행할 수 있다. 요청 키/본문의 자동 주문 복구와는 별개다.

trace는 실행별, snapshot은 후보 파일 세트별이다. 하나의 snapshot으로 여러 주문을 실행할 수 있지만 각 execution_id와 trace는 별개다. run_id는 기존 계약용 별칭이며 execution UUID에서 파생한다.

## 시연 후보와 SkillOps를 구분

개발 패널의 정상본·결함본·수정본은 **개발자가 미리 준비한 fixture**다. SkillOps가 실행 전에 자동 진단하거나 수정한 결과가 아니다. 진단 Agent·MCP 서버·승인/PR을 이 앱에서 구현하지 않았다.

- baseline: 정상 게시 Skill 세트.
- broken-contract: inventory.reserved 출력 선언을 제거한 후보. 계약 검사에서 FAILED가 되고 업무 도구는 실행하지 않는다. 실패 trace는 남긴다.
- repaired: 예약 가능 수량 필드와 reserved 선언을 함께 유지한 별도 후보. 원래 snapshot을 덮지 않고 새 snapshot으로 실행한다.

입력을 먼저 채우고 후보 고정 버튼을 누른 뒤 주문을 실행한다. 주문 preset이나 새 주문 작성은 입력을 초기화하므로 후보를 다시 고정한다. 정상·결제 거절 시연에는 재고가 필요하다.

나중에 B 담당 진단 Agent가 실패 trace를 조사하고 후보를 만들면, C-2의 SEALED workspace/버전 참조를 이 실행 경계로 전달한다. 현재 static 검사에서 차단되는 것은 주문 실행 안전장치이며 SkillOps를 호출했다는 뜻이 아니다.

## snapshot 저장과 게시

독립 앱의 Skill 원본은 order-agent/skills다. Docker는 이 완성된 번들을 backend 이미지에 포함한다. bundle.json이 파일 목록과 해시를 고정하며, 누락·추가·편집 중 파일·심볼릭 링크를 거절한다.

```bash
# 저장소 루트: Skill 수정 완료 후 manifest 갱신
python3 order-agent/scripts/publish-skills.py
# 새 게시본을 이미지에 반영
cd order-agent
docker compose up --build -d --wait
```

실행 전에 파일 원본 바이트·경로·해시와 후보 메타데이터를 독립 DB의 skill_snapshots/snapshot_files에 하나의 트랜잭션으로 저장한다. 기존 문서의 파일 저장 제안 대신 이 방식을 선택해 파일/메타데이터의 부분 발행과 이중 쓰기 문제를 줄였다. 관련 파일도 보존한다. 읽을 때 파일별·전체 해시를 다시 검사하며 수정/삭제 API는 없다.

이 저장소는 C의 버전 저장소를 중복 운영하기 위한 공용 구현이 아니다. 독립 앱을 실행·검증하기 위한 저장소이며 공용 저장소 제공 시 TODO 경계에서 연결한다.

## 추가 API

| API | 내용 |
|---|---|
| GET /api/executions | 최근 실행 최대 20개, snapshot·상태·비용 참조 |
| GET /api/executions/{execution_id}/trace | 실행 상세와 시간 순서 이벤트 |
| GET /api/traces/{run_id} | r_ 접두 별칭으로 같은 trace 조회 |
| GET /api/snapshots/{snapshot_id} | 메타데이터·전체 해시·파일 manifest |
| GET /api/snapshots/{snapshot_id}/files?path=skills/... | snapshot 내부 파일 원본, UTF-8 또는 base64 |
| POST /api/snapshots | 개발용 {"fixture":"baseline/broken-contract/repaired"} 후보 고정 |
| POST /api/snapshots/candidates | 후보 SKILL.md 묶음을 snapshot으로 발행. 본문 {"files":{경로:텍스트},"metadata":{...}}, 경로는 skills/로 시작(어긋나면 INVALID_BUNDLE_PATH), 파일 1~256개·파일당 1MB(1,000,000바이트) 이하·합계 5MB(5,000,000바이트) 이하(어긋나면 INVALID_BUNDLE_SIZE). metadata.source는 candidate로 고정. 응답은 GET /api/snapshots/{id}와 같은 manifest. order-agent.demo-enabled가 꺼져 있으면 SNAPSHOT_NOT_FOUND |
| POST /api/dry-runs | 후보 snapshot으로 주문 하나를 live 주문처럼 실행한 뒤 예약한 재고를 모두 되돌린다. 본문 {"snapshot_id":UUID,"order":POST /api/orders와 같은 주문}, 최상위 snapshot_id가 order.snapshot_id를 대신한다. 응답 {execution_id, run_id, order_id, final_status, completion}. FAILED로 끝나도 HTTP 200이며 실패는 오류가 아니라 결과다. 주문과 실행 기록은 근거로 남는다. order-agent.demo-enabled가 꺼져 있으면 SNAPSHOT_NOT_FOUND |

실행의 mode는 live 외에 dry_run이 될 수 있다(POST /api/dry-runs로 만든 실행). dry_run 실행은 GET /api/executions/failed(live만 모음)에 나오지 않지만 GET /api/executions(최근 20개)에는 나온다. 모의 실행의 재고는 끝난 뒤 복원된다.

POST /api/orders는 선택적 snapshot_id(UUID)를 받는다. 미지정이면 현재 게시본을 새 snapshot으로 고정한다. 응답에 execution_id·trace_url이 추가되며, 과거 MVP 주문은 null이다. 같은 키의 멱등 비교에는 명시한 snapshot_id도 포함한다. 없는/변조된 snapshot은 주문 접수 전에 거절한다.

실행 mode는 live(모의 실행은 dry_run), model_mode는 fixed/openai다. 고정 응답은 api_calls=0, 토큰은 미관측 null이다. 실패한 API 시도·일시 오류 재시도도 호출 수에 포함하며 인증·형식·오선택은 재시도하지 않는다. timeout 후 늦은 응답은 도구 실행을 할 수 없다.

## 트랜잭션 변경

모델 요청 동안 재고 잠금을 유지하지 않도록 도구별 짧은 트랜잭션으로 변경했다. 각 도구 결과·멱등 operation·실제 결과 이벤트는 업무 효과와 함께 커밋한다. trace 요청 기록은 호출 전에 저장한다.

- 재고 부족: REJECTED, 후속 도구 미실행.
- 결제 거절: 예약 해제·HOLD, 배송·성공 알림 미실행.
- 결제 전 실행 오류: 이미 예약됐다면 해제하고 FAILED.
- 결제 후 실행 오류: 이미 승인된 결제·배송/알림 결과를 보존하고 FAILED. 자동 환불하지 않는다.
- flow가 필수 단계를 마치지 않았거나 선언 출력이 없으면 성공 처리하지 않는다.
- 모든 단계 결과를 확인한 뒤 주문 SHIPPED와 실행 완료 기록을 같은 트랜잭션으로 확정한다.
- 재시작 중단 복구: 미결제 예약만 해제, 확정 결과 보존, trace INTERRUPTED.
- 실행 규칙(rules) 매치: 도구를 부르지 않고 HOLD 또는 REJECTED(result_code = 규칙 reason). 결제 전이면 예약 해제, 결제 뒤면 결제·예약 보존. 업무 종료라 실행은 COMPLETED.

따라서 MVP 문서의 “모든 Mock 변경을 한 번에 롤백” 설명은 이 확장부터 적용하지 않는다.

## 실제 모델 모드

order-agent/.env에서 ORDER_AGENT_MODEL_MODE=openai, OPENAI_API_KEY, SKILLOPS_MODEL을 설정하고 backend를 재생성한다. 키 값을 이미지·문서·로그에 넣지 않는다. 기본은 fixed다. OPENAI_API_KEY가 없는 상태의 실제 유료 모델 응답은 검증하지 않았다.

이 구현은 [공식 JSON 출력 가이드](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat)의 JSON mode와 Spring AI 설치 소스를 참고했다. JSON 형태만으로 실행을 신뢰하지 않고 이름·도구·인자·입출력 계약을 서버에서 검증한다. 모델 질문에는 Judge 정답·기대 도구 목록을 넣지 않는다.

환경 변수: ORDER_AGENT_MODEL_MODE, SKILLOPS_MODEL, OPENAI_API_KEY, ORDER_AGENT_MAX_API_CALLS, ORDER_AGENT_MAX_TOKENS(관측 토큰 상한), ORDER_AGENT_TIMEOUT_MS, ORDER_AGENT_CALL_TIMEOUT_MS, ORDER_AGENT_DEMO_ENABLED. 실제 과금의 절대 상한이나 timeout된 원격 요청의 과금 취소를 보장하지 않는다.

## 담당자 연결 TODO

| 담당 | 코드 위치 | 나중에 연결할 것 |
|---|---|---|
| A | harness/OrderHarness, SkillLoader | 공용 Harness·Registry 타입과 로더 구현 |
| C-1 | trace/TraceService, workspace/SnapshotService | get_trace/run_id, immutable 파일·버전 저장소 계약 |
| C-2 | OrderHarness, OrderService 실패 처리 | 동일 Harness를 호출하는 TargetAdapter/Runner, 실패 이벤트 전송·중복 제거 |
| B/C-2 | controller/EvidenceController | 진단 Agent의 실제 후보를 SEALED workspace 참조로 수신 |
| A/C-1 | frontend/src/api/evidence.js | 공용 trace/snapshot API의 경로·DTO 변환 |

위 위치에는 담당과 보존할 계약을 적은 TODO 주석을 남겼다. “이미 구현한 로직을 다시 만들라”는 의미가 아니다. Runner·Judge·MCP·진단·승인 기능은 현재 이 확장에도 구현하지 않았다.

## 계약 오류 상세

새 실행의 EXECUTION_ERROR에는 category뿐 아니라 skill_id, phase, missing_inputs, missing_dependencies, message를 기록한다. 이벤트 자체의 skill_id도 실패 Skill로 설정한다. 정적 검사 단계는 static_validation, 실제 단계 입력 검사는 runtime_validation이다. 예전 실행은 당시 증거이므로 소급 수정하지 않는다.

```json
{
  "category": "CONTRACT_VIOLATION",
  "skill_id": "payment",
  "phase": "static_validation",
  "missing_inputs": ["inventory.reserved"],
  "missing_dependencies": [],
  "message": "Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다."
}
```

## 실행 규칙과 RULE_MATCHED (2026-10-26)

단계 Skill frontmatter의 `rules`(형식은 [skill-flow.md](skill-flow.md) 9절)는 실행 중 입력 검증 직후, 결정 요청 전에 Harness가 평가한다. 매치되면 그 단계의 DECISION_INPUT·MODEL_RESPONSE·도구 TOOL_REQUEST/TOOL_RESULT는 남지 않고 아래 순서로 끝난다.

```text
SKILL_LOADED:<skill> → SKILL_STARTED:<skill>
→ RULE_MATCHED:<skill>   {skill_id, rule_index, when, then, reason}
→ (결제 전이고 예약이 있으면) TOOL_REQUEST:<skill> {tool: inventory.release, arguments, reason: <규칙 reason>}
                              TOOL_RESULT:<skill>  {tool: inventory.release, arguments, result: {inventory.reserved: false}}
→ SKILL_FINISHED:<skill> {final_status, business_stop: true, rule_reason, step_no, version, status: ok, output: {}, duration_ms}
→ BUSINESS_STOP:<skill>  {final_status, rule_reason}
→ EXECUTION_FINISHED     {completion: COMPLETED, final_status, error_category: null}
```

- rule_index는 rules 목록에서 0부터 센 번호다. 처음 참인 규칙 하나만 적용한다.
- 재고 해제 기록은 결제 거절의 해제와 같은 inventory.release 모양이며 skill_id는 규칙 단계, reason은 규칙 reason이다.
- 주문 단계 기록: 규칙 단계 종류가 SKIPPED(결과 코드 = reason, "업무 규칙에 따라 주문을 보류/거절해 실행하지 않았습니다."), 해제했다면 RELEASE SUCCESS, 업무 순서의 뒤 단계는 SKIPPED(UPSTREAM_REJECTED). order-intake 규칙은 INTAKE SKIPPED 뒤 업무 단계 전체가 SKIPPED다.
- 결제 상태는 바꾸지 않는다. 결제 전 규칙이면 payment NOT_ATTEMPTED, 결제 뒤 단계(shipping-reserve, notify)의 규칙이면 승인된 결제가 그대로 남는다. 정책은 결제 전 단계에 걸도록 안내한다.
- BUSINESS_STOP의 rule_reason은 규칙 종료에만 있는 추가 칸이다(값 = 규칙 reason = 주문 result_code). 재고 부족·결제 거절 같은 도구 업무 종료의 BUSINESS_STOP은 예전처럼 `{final_status}`만 담는다. 운영 화면(`frontend/src/utils/eventView.js`)은 rule_reason이 있으면 "실행 규칙 <reason> 에 따른 보류/거절"로 설명한다.
- service의 OrderAgentTraceConverter는 모르는 이벤트(RULE_MATCHED)와 BUSINESS_STOP을 읽지 않고, SKILL_FINISHED가 도구 업무 종료(결제 거절)와 같은 모양이라 채점은 그대로다. SkillOps Agent(`skillops-agent/skillops_agent/contracts.py`)도 data의 모르는 칸을 무시한다.
- 조건 키가 선언 입력이 아니면 flow 정적 검사에서 실패한다. EXECUTION_ERROR data 예:

```json
{
  "category": "CONTRACT_VIOLATION",
  "skill_id": "payment",
  "phase": "static_validation",
  "reason": "RULE_KEY_NOT_INPUT",
  "missing_inputs": [],
  "missing_dependencies": [],
  "rule_keys": ["order.customer"],
  "message": "실행 규칙 조건의 값 이름이 Skill의 선언 입력이 아닙니다: order.customer"
}
```

기대 줄은 `order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractGolden.java`의 RULE_HOLD, RULE_REJECT_AT_INTAKE(DB 테스트), DB 없이 도는 확인은 harness 패키지의 OrderHarnessRuleTest, RuleConditionContractTest(`docs/contracts/examples/condition-cases.json`), SkillRulesContractTest(`docs/contracts/examples/skill-rules-cases.json`)와 service 패키지의 OrderToolServiceRuleStopTest다.

수정 저장 API(POST /api/snapshots/edits)는 선택 칸 `intent`(담당자 의도)를 받아 snapshot metadata와 저장 이벤트 본문에 같은 모양으로 남긴다. 형식은 [skill-edit-save-api.md](skill-edit-save-api.md)를 따른다.

## 이슈 #39 진단용 조회 API (2026-10-21)

- GET /api/executions/failed: 파라미터 없이 전체 live 실패(FAILED/INTERRUPTED), 최근 실패순. 업무 거절과 dry-run 제외.
- GET /api/snapshots/{id}/skills: 같은 실행 로더로 검증한 메타데이터와 partial/parse_errors.
- GET /api/snapshots/{id}/skills/{skill_id}: snapshot 원문·메타데이터·파일 해시. 기존 파일 조회 API도 유지.
- 새 실행 trace에 SKILL_STARTED/FINISHED로 실제 입력·출력·상태·측정 시간을 추가. 정적 차단은 단계 실행으로 표시하지 않음.

요청·응답과 MCP v1과의 차이는 [진단용 조회 API 명세](diagnosis-read-api.md)를 따른다. 이 확장은 공용 Registry·Runner·실패 이벤트 전송을 구현하지 않는다.
