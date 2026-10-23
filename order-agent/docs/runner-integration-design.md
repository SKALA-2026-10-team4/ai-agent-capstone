# 주문 Agent · Runner 연동 설계안

> **과거 설계 기록 — 2026-10-21.** 아래 본문과 JSON은 당시 제안이며 현재 API 명세가 아니다. 아래에서 말하는 ‘현재·미구현’은 작성 시점을 뜻한다. 2026-10-23 기준 구현은 다음 대응표와 [리뷰 안내](../../docs/review/README.md)를 먼저 확인한다. 이 문서 정리로 제안 API를 구현하거나 공용 계약을 변경하지 않았다.

## 현재 구현과의 대응 (2026-10-23, 2609ff3)

| 과거 초안 내용 | 현재 확인한 구현 |
|---|---|
| scenario/workspace 구현 없음 | [WorkspaceService](../../service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java), [ScenarioRunner](../../service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java), [HarnessFlowRunner](../../service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java)가 존재 |
| `/internal/snapshots/import` 제안 | 실제 후보 발행은 `POST /api/snapshots/candidates`; [주문 Agent 조회 API](diagnosis-read-api.md) 참고 |
| `/internal/executions/dry-run` 제안 | 실제 실행은 `POST /api/dry-runs`; [DryRunController](../backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java) 참고 |
| ws_id·k와 결과 배열 | 실제 run_scenarios는 workspace_id·scenario_ids·replay_run_ids·extra_checks·repeat, 응답은 workspace_id/all_passed/results 객체. [MCP 최신 갱신·9절](../../docs/contracts/mcp-tools-input-output.md) 참고 |
| 실행별 순수 메모리 격리 제안 | 현재 [DryRunService](../backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java)는 DB에 주문·trace를 남기고 예약 재고를 정리하는 방식. 완전한 메모리 격리 구현으로 간주하지 않음 |
| 파서 공통화 제안 | 두 Java 서버에 [공용 파서](../../shared/skill-parser/README.md)를 실제 적용했고 YAML 안전·크기 정책을 통일 |
| 일반 평가 원장·revision·전체 예산·전후 지표 | 별도 [백엔드 평가·비교 설계 범위](../../docs/reports/2026-10-23-backend-plan-and-issue-updates.md). 당시 제안을 그대로 구현 완료로 표시하지 않음 |

2026-10-23에는 partial 증거 수집의 실제 REST·MCP·DB·재시작을 검증했고, 공용 파서 적용 후 주문 Agent DB 통합 테스트도 실행했다. 범위와 수치는 [partial 검증 기록](../../docs/reports/2026-10-23-partial-evidence-integration.md), [공용 파서 검증 기록](../../docs/changes/2026-10-23-shared-skill-parser.md)을 따른다. LLM 호출 품질이나 자연어 정책 개선까지 검증했다는 의미는 아니다.

아래부터는 원래 초안의 기록이다. 요청·응답 예시와 구현 순서를 현재 호출 코드에 바로 적용하지 않는다.

2026-10-21 / 설계 초안. 참고: 사용자가 제공한 「SkillOps 도구 12개.pdf」(계약 v0 설명), 현재 docs/contracts, 독립 order-agent 구현. 구현·공용 계약 변경·운영 반영은 수행하지 않는다. PDF의 작업 순서와 담당 지침은 참고 자료이며 현재 사용자 합의를 대체하지 않는다.

## 1. 현재 상태와 설계 방향

- 독립 order-agent: Spring Boot의 OrderHarness, SkillLoader, SkillExecutor, Mock, snapshot/trace DB와 조회 API가 구현돼 있다.
- service: snapshot HTTP 클라이언트와 diff_skill/get_dependency_graph MCP 도구가 구현돼 있다. scenario와 workspace는 package-info 경계만 있으며 실행 Runner·후보 workspace 구현은 없다.
- skillops-agent: run_scenarios(ws_id, scenario_ids, k)를 호출하는 Python 클라이언트와 stub이 있다. 실제 Runner가 구현됐다는 뜻은 아니다.
- 기존 g-01 fixture는 과일 SKU를 사용하지만 실제 주문 데모는 캠핑용품이다. golden 6개도 아직 한 파일만 존재한다. 호환 작업 없이 평가를 시작할 수 없다.
- OrderHarness.execute(orderId)는 운영 OrderToolService·lifecycle·TraceService에 직접 연결돼 있다. 현재 일반 주문 API를 반복 호출하는 것으로 dry-run을 대체하지 않는다.

Runner·Judge는 기존 Java service/.../scenario 경계에 두고, TargetAdapter가 HTTP로 독립 주문 Agent를 호출하는 안을 선택한다. 기존 C-2 도구 진입점과 맞추고 별도 Python Runner/HTTP 서버를 만들지 않기 위해서다. 주문 실행 로직은 order-agent의 같은 Harness를 재사용한다. 사용자 담당 범위의 구현은 이 경계를 기준으로 협업하며, C-2 담당이 이미 진행 중인 실제 구현이 있다면 연결하고 중복 생성하지 않는다.

과거 역할 문서는 주문 서비스까지 service 내부에 있는 전제다. 현재 합의인 독립 order-agent와 Docker 구성을 우선한다. 기존 공용 계약 파일은 이 문서에서 덮어쓰지 않으며, 추가 필드는 담당자와 합의 후 반영한다.

## 2. 책임 분리와 전체 흐름

| 구성 | 책임 | 위치 제안 |
|---|---|---|
| Workspace 관리 | 기준 파일 복사·patch·revision·완성 후보 제공 | 기존 service/workspace, C-2 연결 |
| Runner | 시나리오 해석·후보 고정·독립 반복·전체 예산·저장·집계 | service/scenario |
| SingleAgentAdapter | 주문 실행 API 호출·공통 관찰 결과 변환 | service/scenario 내부 단순 클래스 |
| Judge | 기대값과 실제 선택·로드·도구·인자·상태 비교 | service/scenario의 단일 주문 Judge |
| Harness | 선택·검증·로드·업무 도구 실행·실행 지점 관찰 | 기존 order-agent/harness |
| 실행 환경 | live DB 또는 테스트별 Mock 상태·Clock 제공 | order-agent 내부 실행 환경 경계 |
| MCP run_scenarios | 입력 검증 후 Runner 호출, 결과 반환 | 기존 service/mcp 담당 연결 |

```text
SkillOps → run_scenarios → Runner
                           ├─ workspace revision을 읽기 전용으로 고정
                           ├─ snapshot 등록·해시 확인
                           └─ 시나리오 × 반복
                               → SingleAgentAdapter → 주문 Agent 격리 실행 API
                                                      → 같은 Harness + dry-run 도구
                               ← 공통 관찰 결과 + execution/trace 참조
                               → Judge → 결과 저장·집계
```

일반 주문은 기존 API→Harness로 실행하며 Runner를 거치지 않는다. 실패 조사용 조회도 Runner 없이 기존 API를 사용한다. 멀티에이전트·동적 플러그인 등록은 이번 설계에서 제외한다.

## 3. 후보 파일 고정

1. workspace 관리자가 ws_id와 단조 증가 revision, 파일별 해시를 제공한다.
2. 평가 시작 시 revision을 잠그거나 완성된 읽기 전용 export를 만든다. 실행 중인 가변 경로를 넘기지 않는다.
3. SingleAgentAdapter가 메타데이터와 관련 파일 전체를 주문 Agent snapshot 저장소에 등록한다.
4. 파일·전체 해시 검증 성공 후 snapshot_id를 얻는다. 같은 평가의 반복은 이 snapshot만 사용한다.
5. 이후 patch는 새 revision이다. 기존 평가나 snapshot은 수정하지 않는다.

평가가 필요하다고 편집 workspace 전체를 영구 잠글 필요는 없다. 고정 export만 불변으로 보존하면 다음 후보 편집을 계속할 수 있다. 처음에는 순차 평가로 구현하고 병렬 편집 잠금은 workspace 담당 계약을 따른다.

후보 등록 API 제안(미구현): POST /internal/snapshots/import. 입력은 source {ws_id, revision}, metadata, files[{path, encoding, content, sha256}], manifest 해시다. 출력은 snapshot_id/hash/files manifest다. 서버가 해시를 직접 검증·계산하며 응답 hash를 평가에 보존한다. 현재 snapshot 전체 해시는 메타데이터를 포함하므로 다른 알고리즘을 임의 사용하지 않는다. 인코딩·파일 크기 제한은 기존 SnapshotService를 재사용한다. 임의 호스트 경로/URL을 읽게 하지 않는다.

실행 전 완료 후보라는 의미는 바이트 고정이 끝났다는 뜻이다. CONTRACT_VIOLATION처럼 의도적으로 결함을 재현할 후보는 등록 자체를 막지 않고 실행 시 실패 증거를 남길 수 있어야 한다. 반면 경로 탈출·변조·불완전 복사는 등록을 거부한다.

공용 저장소를 나중에 사용하면 import는 공용 불변 revision 해석으로 교체한다. 동일 후보 파일을 두 저장소에서 각각 수정하는 이중 원장을 만들지 않는다.

## 4. Harness 실행 경계와 상태 격리

공통 실행 입력을 OrderHarnessRequest(snapshotSource, businessInput, executionEnvironment, limits, references)로 정리하고, 결과를 HarnessResult로 반환하도록 기존 execute(orderId)를 감싸거나 점진적으로 추출한다. 선택·검증·로드·단계 진행을 live/dry-run으로 복사하지 않는다.

업무 효과 접근은 작은 OrderExecutionEnvironment 인터페이스로 묶는다. initial context, 도구 dispatch, 상태 확인·완료·실패·미결제 보상을 제공한다. live 구현은 기존 OrderToolService/OrderLifecycleService로 위임하고 dry-run은 실행별 메모리 상태를 사용한다. 관찰 기록은 같은 로더·도구 dispatch 지점에서 발생시킨다. 거대한 범용 Agent 프레임워크로 확장하지 않는다.

실행별 dry-run 상태:

- fixture의 상품·재고·서버 가격·고객·결제 정책, 고정 Clock, 필요 시 seed로 초기화한다.
- 재고·결제·배송·알림 효과를 실행 전용 메모리에 기록한다. dry-run이어도 앞 단계 출력과 실제 예약·해제·승인 상태는 일관되게 유지한다.
- 기존 Mock의 업무 정책은 공유하고 live JPA 저장소에는 접근하지 않는다. 재고 전체/일부 예약·결제 거절 해제 등의 규칙을 두 구현에 중복 하드코딩하지 않는다.
- 실제 주문·상품 테이블과 분리한다. snapshot/trace 증거 저장은 허용하며 실행 mode=dry-run을 명시한다.
- dry-run 실패는 운영 실패 목록에서 제외하고 진단 webhook을 발행하지 않는다. 시험이 다시 진단을 일으키는 순환을 차단한다.

고정 응답 provider는 구조와 Mock 정책을 검증한다. 자연어 본문을 바꾼 정책의 유효성을 증명하지 못한다. 그런 assertion은 실제 모델 또는 명시적인 실행 정책 구현으로 검증해야 하며 검증 불가를 pass로 처리하지 않는다.

## 5. 대상 실행 API 제안

POST /internal/executions/dry-run (미구현). 평가 한 회만 실행하며 반복·채점은 하지 않는다. idempotency_key는 평가·시나리오·반복별 고정값이다. 아래 전체 요청은 서버용으로, 모델에는 order와 선택/실행에 필요한 Skill 정보만 전달한다.

```json
{
  "schema_version": "1.0",
  "idempotency_key": "<평가-시나리오-반복 식별자>",
  "evaluation_id": "<평가 UUID>",
  "diagnosis_run_id": null,
  "scenario_id": "g-01",
  "repeat_index": 1,
  "snapshot_id": "<고정 snapshot UUID>",
  "snapshot_hash": "<SHA-256>",
  "fixture_id": "camping-stocked-v1",
  "clock": "2026-10-21T00:00:00Z",
  "model_mode": "fixed",
  "order": {
    "customer": "c_1001",
    "items": [{"sku": "CAMP-LANTERN-LED", "qty": 1}],
    "payment_method": "card",
    "shipping": {"country": "KR", "express": false}
  },
  "limits": {"timeout_ms": 30000, "max_api_calls": 10}
}
```

fixture는 서버의 검증된 테스트 데이터를 가리킨다. 임의 코드나 DB 조작은 받지 않는다. 등록 fixture로 표현할 수 없는 재현 데이터는 허용된 구조화 필드만 별도 계약으로 전달한다. 기대값·expect·정답 도구 목록·assertion은 이 API에 포함하지 않는다.

응답안:

```json
{
  "execution_id": "<실행 UUID>",
  "run_id": "r_<실행 UUID의 하이픈 제거 값>",
  "evaluation_id": "<평가 UUID>",
  "snapshot_id": "<snapshot UUID>",
  "snapshot_hash": "<SHA-256>",
  "mode": "dry-run",
  "completion": "COMPLETED",
  "final_status": "SHIPPED",
  "trace_url": "/api/executions/<실행 UUID>/trace",
  "usage": {"api_calls": 0, "input_tokens": null, "output_tokens": null, "elapsed_ms": 12}
}
```

응답 예시는 요약이다. Judge에 필요한 실제 선택·로드·도구 요청/결과·관찰 context는 같은 execution의 trace 또는 구조화 result에서 제공해야 한다. 기존 trace는 단계별 입력·시간을 완전하게 제공하는 공용 Trace와 다르므로 관찰 필드를 보강해야 한다. 누락된 시간을 0으로 만들어 계약을 맞추지 않는다.

대상 Skill 실행 실패도 실행 기록을 확보했다면 completion=FAILED의 결과를 반환한다. 입력 오류·미등록 fixture·없는 snapshot·인증 실패는 HTTP 요청 오류다. 통신 timeout은 execution 실패로 추측하지 말고 멱등 키 조회를 통해 완료 여부를 확인한다. 동기 실행을 기본으로 시작하되 실행 한도보다 짧은 gateway timeout이 있다면 작업 접수+조회 계약으로 바꾼다.

같은 키·같은 요청은 같은 실행을 반환하고 다른 내용이면 409다. POST를 맹목적으로 재시도하지 않는다. 실행 중복 방지에는 동시 요청 잠금/유일 제약이 필요하다. 모델 timeout 후 늦게 도착한 응답이 도구를 실행할 수 없어야 한다.

## 6. run_scenarios 입력과 시나리오

기존 ws_id, scenario_ids, k는 유지하되 revision과 inline 재현·assertion을 선택적으로 받는 확장을 제안한다. 기존 Python 클라이언트도 합의 후 확장한다. 별도 임시 등록 도구를 추가하지 않아 진단 흐름의 호출 수와 수명 관리를 줄인다.

```json
{
  "ws_id": "<workspace ID>",
  "revision": "<완성 revision>",
  "scenario_ids": ["g-01"],
  "inline_scenarios": [
    {
      "id": "f-01",
      "source_execution_id": "<원래 실패 실행 UUID>",
      "order": {
        "customer": "c_1001",
        "items": [{"sku": "CAMP-LANTERN-LED", "qty": 1}],
        "payment_method": "card",
        "shipping": {"country": "KR", "express": false}
      },
      "fixture_id": "camping-stocked-v1",
      "expect": {"final_status": "SHIPPED", "shipments": 1}
    }
  ],
  "intent_assertions": [
    {"path": "inventory.reservable_quantity", "op": "exists"}
  ],
  "k": 2,
  "limits": {"timeout_ms": 120000, "max_api_calls": 40}
}
```

expect와 intent_assertions는 Runner/Judge에만 남기고 TargetAdapter에서 실행 요청을 만들 때 제거한다. assertion의 exists는 기존 ==/!=/in 계약에 없는 확장 제안이다. 초기에는 ==/!=/in과 제한된 필드 경로 문법을 사용하고 존재 확인이 필요하면 exists를 명시적으로 합의한다. eval·임의 스크립트 실행은 금지한다. 읽을 수 없는 경로·미지원 연산은 판정 불가/평가기 오류이며 통과가 아니다.

동일 ID 중복은 거부한다. k는 요청값 우선, 없으면 기존 시나리오 k 또는 기본 2로 하되 우선순위를 계약에 고정한다. 전체 요청 상한·허용 시나리오 수를 검증한다. k=3은 성공하더라도 독립 초기화해 3회 실행한다. 잘못된 선택·도구 인자는 API 일시 오류 재시도 대상이 아니다. 모델 일시 오류 재시도는 Harness가 처리하고 모든 시도는 실행·평가 전체 예산에 합산한다.

## 7. Judge와 평가 결과

actual = targetAdapter.execute(request), judgment = judge.grade(expectation, actual)의 두 단계로 분리한다.

단일 주문 Judge가 확인할 것:

- 실제 선택 이름·해석 ID·실제 로드 파일/해시. 로드된 flow와 완료된 단계 Skill을 구분한다.
- 필요한 도구가 실제 호출됐는지, 서버 계산 금액·주문/상품·수량 등 필요한 인자가 일치하는지.
- 최종 주문 상태, 결제·재고 예약/해제·배송 수·알림의 관찰 결과.
- 구조화 업무 조건과 담당자 의도 조건. 자연어 “사용했다” 설명은 증거로 사용하지 않는다.
- 필요한 부분 순서(결제 승인 전 배송 금지)는 검사하지만 불필요한 전체 고정 순서는 강제하지 않는다. 기존 skills_called 순서 필드는 v0 호환 모드로 두고 필수 Skill/도구·부분 순서로 확장한다.

재고 부족·결제 거절은 해당 expectation과 맞으면 PASS다. 정적 계약 오류 재현도 기대 오류를 명시한 원인 실험이면 PASS가 가능하다. 다만 동일 오류의 재현 한 번으로 원인이 확정됐다고 자동 단정하지 않고 기준 성공·단일 hunk 적용·복수 변경 상호작용을 함께 기록한다.

Trial status는 PASS/FAIL/ERROR/NOT_RUN이다. FAIL은 기대 불일치, ERROR는 통신 미확정·평가기 오류 등, NOT_RUN은 예산/시간 때문에 실행하지 못한 경우다. 목표 오류와 다른 모델 인증 오류로 실패한 것을 결함 재현 성공으로 판정하지 않는다.

평가 status는 COMPLETED/INCOMPLETE/ERROR를 구분한다. passed=true는 계획한 모든 시나리오×k가 실행되고 모든 판정이 PASS일 때만 가능하다. 남은 실행을 생략했으면 passed=false와 미완료 이유를 저장한다. 오류가 있어도 기록·참조는 반환한다.

Runner는 evaluation_id, ws_id/revision, snapshot_id/hash, scenario와 expectation의 고정 내용/해시, 모델·fixture·Clock·limits, trial별 execution_id/run_id·judgment·오류·usage, planned/executed/pass/fail/error/not_run 집계를 저장한다. Judge 오류가 나도 대상 trace는 보존한다. 동일 시나리오 expectation/fixture의 고정 버전으로 기준/결함/수정 후보를 비교한다.

기존 run_scenarios는 결과 배열 반환이므로 응답을 갑자기 객체로 바꾸지 않는다. v0 배열의 각 항목에 evaluation_id/status/planned_runs/executed_runs/judgments를 추가하고, runs/failures는 호환용으로 유지하는 안을 협의한다. save_proposal의 evidence 참조는 실제 실행·평가와 후보 해시를 연결해 검증해야 하며 문자열 ID 존재만 확인해서는 안 된다.

## 8. PDF와 현재 구현의 차이·후속 협의

| 항목 | 설계안/미확정 사항 |
|---|---|
| 12개 도구 | 진단 12개 + ping 별도라는 PDF 설명을 따른다. 이번 작업은 run_scenarios와 대상 실행 경계만 구체화 |
| 실패 목록 since | PDF에는 있지만 사용자 최신 합의대로 요청 파라미터 없이 목록 반환. dry-run은 제외 |
| 버전 번호 | 실제 diff MCP는 snapshot ID·hunk_id 기반. Registry 번호→불변 snapshot 변환 계약 필요 |
| 재현·의도 입력 | inline_scenarios/intent_assertions 확장 제안. 현재 호출 코드와 JSON Schema는 미지원 |
| patch와 graph 검사 | 공통 순수 계약 검사기 재사용을 목표로 하고 Harness 검증과 MCP 분석의 결과 차이를 계약 테스트로 확인. 타 담당 코드를 임의 이동하지 않음 |
| F1의 수정 후보 | 예약 가능 수량이 양수라는 사실만으로 reserved=true를 만들면 안 됨. 실제 전량 예약 성공 결과를 유지해야 함 |
| F1 후보 다 | 현재 ToolCatalog·서버가 inventory.reserved를 필수로 사용하는 경계가 있어 payment의 선언만 바꿔 수량을 받도록 한 후보가 자동 통과한다고 보장 못함 |
| golden 데이터 | g-01 과일 SKU와 cod 허용 범위 등 실제 Mock과 일치 필요. 6개 golden 시나리오의 의미·fixture·expectation 합의 후 작성 |
| trace 저장소 | 현재 order-agent DB를 실행 증거의 원본으로 유지하고 C-1은 조회·정규화. 공용 원장으로 이전 시 별도 마이그레이션 합의 |
| 승인·promote | 기존 담당 범위. Runner가 승인/운영 파일 변경을 소유하지 않음. 평가한 revision/해시와 승인·반영 revision/해시 일치 확인 |

## 9. 구현 순서와 검증 계획

1. 담당자와 ws revision/export, inline 입력, run_scenarios 응답 호환, execution/result 계약을 확정한다.
2. 공통 Harness 실행 입력·환경 경계를 추출하고 기존 live 회귀 테스트를 통과시킨다.
3. dry-run 환경·단일 실행 API·후보 import를 구현한다. 운영 재고 불변·실행별 초기화·late response 차단을 검증한다.
4. SingleAgentAdapter와 단일 Judge를 만든다. 기대값 누출 방지·실제 인자/상태 비교·판정 불가 처리를 검사한다.
5. Runner에 k회·전체 예산·저장·집계를 연결한다. 조기 성공 종료 금지·API 재시도 합산·NOT_RUN을 검증한다.
6. C-2 run_scenarios와 B Python 클라이언트에 연결한다. 다른 쓰기·승인 도구는 담당 구현을 재사용한다.
7. 캠핑용 golden/재현/의도 fixture로 기준→결함→수정 비교. dry-run 실패의 webhook 미발행과 조회 가능성을 확인한다.
8. API 키가 있을 때 실제 모델 검증. 없으면 고정 응답 검증 범위와 미검증 자연어 정책을 보고서에 명시한다.

최소 데모는 정상 주문·재고 부족·결제 거절·F1 계약 결함/수정 비교다. F2/F3·자연어 업무 정책은 실제 실행 가능 범위와 기대값을 합의한 뒤 추가한다. 이 문서만으로 구현 완료나 모델 검증 완료를 의미하지 않는다.
