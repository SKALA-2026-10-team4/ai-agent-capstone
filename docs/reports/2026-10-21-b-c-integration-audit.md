# B Agent ↔ C MCP·Runner 연동 불일치 조사

2026-10-21 · #60 · 조사 기준: feat/#60_skillops-pipeline-integration, HEAD 3233eec

## 결론

C 내부 흐름의 성공과 B 전체 진단 흐름의 성공은 별개다. C의 실제 주문 실행 경로는 구현되어 있고, C 실험 스크립트는 그 계약에 맞는 snapshot·patch·재현 입력을 사용한다. 반면 B는 일부 v1 필드명만 옮겨졌고 실제 HTTP 이벤트 구조·C-1 diff·C-2 snapshot 시작 조건을 소비하지 못한다.

가장 먼저 막히는 곳은 fetch_evidence의 실제 trace 파싱이다. 이를 수정해도 기준 snapshot을 가짜 문자열로 만들고, ablation이 실제 diff hunk를 읽지 못하며, workspace를 실패 snapshot에서 시작하지 않고, 없는 f-01을 호출한다. 따라서 한 필드만 바꾸어 해결되는 문제가 아니다.

추가로 MCP 오류를 정상 JSON으로 취급한 뒤 빈 결과를 all([])로 검사해 실험을 하지 않고도 원인 확정으로 진행할 수 있다. 이 부분은 연결 실패보다 우선해 잘못된 성공 판정을 막아야 한다.

## 조사 범위·검증 방식

- 현재 B tools.py/nodes.py/main.py/프롬프트와 C Java MCP·DTO·Runner·Workspace, 주문 Agent 이벤트 생성 코드를 비교했다.
- C의 실제 실행 경로는 scripts/c2_real_judge.py와 구현·계약 갱신 내용을 근거로 확인했다. 사용자가 전달한 C 성공 결과를 이번 조사에서 재실행한 것으로 표현하지 않는다.
- 외부 모델·운영 주문·DB를 호출하지 않았다. 운영 코드 변경 없이 리포트와 오프라인 재현 스크립트·결과만 추가했다.
- 현재 skillops-agent/.venv에서 nodes 전체 import는 langgraph 미설치로 실패했다. 이는 로컬 환경 제약이며 B/C 계약 오류의 원인으로 단정하지 않는다.
- 대신 AST로 현재 함수 본문만 추출하고 명시적 테스트 대역을 주입해 아래 6개 현상을 확인했다. 서버 통합 테스트는 아니다.
- 재실행: 저장소 루트에서 `python3 docs/reports/b-c-contract-probe.py`
- 기록: [재현 결과](b-c-contract-probe-results.json), [재현 스크립트](b-c-contract-probe.py)

## 현재 C 경로와 이미 일치하는 부분

```text
create_workspace(snapshot_id=실패 snapshot)
 → apply_patch(workspace_id, patches[{skill, base_version, patch}])
 → run_scenarios(workspace_id, scenario_ids 또는 replay_run_ids, extra_checks, repeat)
 → HarnessFlowRunner
 → 주문 Agent 후보 등록·dry-run
 → 실제 trace를 RunOutcome으로 변환
 → ScenarioGrader
 → {workspace_id, all_passed, results[{scenario_id, passed, run_ids, failures}]}
```

- C 실험은 g-01~g-03과 f-<execution UUID>를 사용하며 extra_checks를 실제 Judge에 전달한다.
- B의 diff_skill 요청 키(skill_id/before_snapshot_id/after_snapshot_id)는 C-1과 맞는다. 불일치는 기준 ID의 출처와 응답 파싱이다.
- B의 apply_patch 요청 키(workspace_id, patches의 skill/base_version/patch)는 C-2와 맞는다.
- B의 run_scenarios 래퍼의 workspace_id/scenario_ids/repeat/replay_run_ids/extra_checks와 결과 results/run_ids 이름도 C-2와 대체로 맞는다. 호출 노드의 실제 값과 사용 방식이 문제다.
- HTTP 조회 전환도 반영되어 있다. 이전 보고처럼 모든 조회를 없는 MCP 도구로 호출한다고 설명하면 현재 코드와 다르다.

## 우선순위별 발견 사항

### P0-1. 실제 trace 구조와 B 파서 불일치: 증거 수집부터 중단

근거:

- B `skillops-agent/skillops_agent/nodes.py:183`: SKILL_SELECTED의 event['skill'], event['version'] 사용.
- B `nodes.py:191`: EXECUTION_ERROR의 event['skill'] 사용.
- 실제 `order-agent/.../trace/TraceService.java:24`: SKILL_SELECTED는 선택 이벤트이며 raw_name/resolved_skill_id가 data에 있다. 단계 version을 제공하지 않는다.
- 실제 `order-agent/.../harness/OrderHarness.java:129`: 실제 파일 version/hash는 SKILL_LOADED의 data에 있다. 오류 위치는 event.skill_id 및 data.skill_id다.

실제 구조를 넣은 오프라인 fetch_evidence 결과는 KeyError: 'version'이었다(우변 접근이 먼저 평가됨). 이 지점을 우회해도 skill 접근이 맞지 않는다.

수정 방향: 실제 이벤트를 한 어댑터에서 읽는다. 선택=SKILL_SELECTED, 실제 로드=SKILL_LOADED, 단계 입력/출력=SKILL_STARTED/SKILL_FINISHED.data, 오류=EXECUTION_ERROR.skill_id/data를 구분한다. 정적 검사는 단계 실행 전 실패하므로 로드되지 않은 전체 Skill 메타데이터는 실패 snapshot의 목록/본문 API에서 읽고 실행된 것처럼 만들지 않는다.

### P0-2. 진단 입력에 기준·수정 snapshot이 없고 가짜 snapshot ID를 생성

근거: `main.py:19` DiagnoseRequest는 type/run_id/skill/version만 받는다. `nodes.py:194`는 '<snapshot>_prev', `nodes.py:201`은 snap_vN을 만든다. 실제 C-1은 존재하는 snapshot UUID를 요구한다.

또한 version>1일 때만 diff를 읽어 같은 version=1인 서로 다른 snapshot 변경을 놓친다. 실패 위치 payment를 변경 원인으로 가정해 inventory-check 수정의 diff를 놓친다.

수정 방향: trigger에 execution_id, baseline_snapshot_id, candidate_snapshot_id, changed_paths, change_id를 명시한다(최소 실행 경로와 저장 이벤트 경로의 필수 조건은 별도 정의). 기준 ID는 저장 metadata 또는 검증 조정 서비스가 제공한다. 실패 skill은 증상, changed_paths는 조사 후보로 사용한다. version 선언값만으로 이전 파일을 찾지 않는다. 현재 새 필드를 보내도 기본 Pydantic 모델에서 정의되지 않은 필드는 보존되지 않는다.

### P0-3. C-1 diff 응답은 hunks인데 B는 changes를 읽음

근거:

- C `snapshot/DiffSkillResult.java`: hunks[{index, hunk_id, part, header, before, after, diff}], frontmatter_changes, before/after snapshot·파일 해시.
- B `nodes.py:257,307`: changes[{change_no,...}]를 읽는다.
- B StubMCPTools.diff_skill만 changes를 반환하여 문제를 숨긴다.

결과: 실제 diff를 받아도 changes=[]가 되어 hunk 복원 patch를 적용하지 않는다. 문서의 change_no는 1부터인데 B stub은 0, 실제 C index도 0부터여서 이름 변경과 번호 변경도 구분해야 한다.

수정 방향: C 실제 hunks를 B 내부 모델로 명시적으로 변환하거나 B가 직접 소비한다. hunk_id와 before/after 해시를 유지한다. 역patch는 실제 hunk 위치·문맥을 보존해 만들고 현재 '@@ -1 +1 @@' 단일 줄 문자열 조합을 제거한다. 여러 줄 변경에 현재 방식은 유효한 역patch를 보장하지 않는다.

### P0-4. B가 snapshot_id로 workspace를 생성할 수 없음

근거:

- C `mcp/WorkspaceTools.java:33`: create_workspace(start_versions?, snapshot_id?).
- C `scenario/HarnessFlowRunner.java:58`: source snapshot이 없는 운영 폴더 workspace는 주문 Agent 실행을 거부.
- B `tools.py:96`: start_versions만 지원.
- B `nodes.py:268,466`: create_workspace()를 무인자로 호출.

결과: workspace 생성 자체는 가능해도 루트 skills/에서 시작하므로 harness 모드 실제 실행은 실패한다. 현재 주문 Agent의 캠핑 Skill과 다른 파일로 patch를 시도할 수도 있다.

수정 방향: B 래퍼와 stub에 snapshot_id를 추가하고 ablation·후보 검증 모두 후보/실패 snapshot을 명시해 복제한다. patch의 base_version은 해당 create_workspace 응답 skill_versions를 기준으로 검증한다.

### P0-5. B가 요청하는 재현·golden ID가 C 카탈로그와 불일치

근거:

- B `nodes.py:282`: ablation에서 f-01 고정 호출.
- C ScenarioRunner.plan: 재현은 f-<유효 UUID> 또는 replay_run_ids. f-01은 일반 카탈로그 조회로 흘러가 존재하지 않음.
- B `nodes.py:455`: g-01~g-06 고정.
- 캠핑 카탈로그 `scenarios/order-agent/golden/`는 g-01~g-03만 존재. 기본 루트 카탈로그는 과일 SKU의 g-01뿐.

수정 방향: ablation은 실제 execution_id로 replay_run_ids를 사용한다. 검증은 합의된 suite/카탈로그 목록을 사용하고, 우선 캠핑 g-01~g-03으로 통합한 후 6개 회귀가 필요하면 C 카탈로그를 함께 확장한다. B를 g-01~g-06으로 유지한 채 성공을 기대하면 안 된다. S1~S6 변경 상황과 golden 주문 g-NN은 다른 목록이다.

### P0-6. MCP 오류가 빈 성공 실험으로 바뀜

근거:

- C WorkspaceToolResults는 isError=true와 {code,message,retryable,...}를 반환.
- B `tools.py:38`은 텍스트 JSON만 파싱하고 is_error를 확인하지 않음.
- B `nodes.py:283-285`는 오류 dict에서 results를 []로 기본 처리하고 all([])=True로 원인 확정.
- B `nodes.py:323`은 실제 실행 증거가 없으면 r_ablation_stub를 사용.

오프라인 재현: 실제 C 형식의 hunks와 C 오류 payload를 넣었을 때 patch 미적용·f-01 요청·run_ids=[]인데 confirmed=True가 됐다. 이는 오류 payload를 대역으로 주입한 함수 동작 확인이며 실제 C 서버가 그 결과를 반환했다는 실측은 아니다.

수정 방향: MCP SDK의 is_error를 우선 확인해 도구 오류 예외로 처리한다. 정상 응답도 스키마와 계획 대비 결과 수·반복 수·실제 실행 참조를 검사한다. 결과 누락은 미완료이지 성공이 아니다. 가짜 증거·가짜 patch fallback을 실연동 경로에서 제거한다.

### P1-1. graph의 entry와 응답 구조 불일치

근거: B `nodes.py:399`는 root cause skill을 entry_skill_id로 전달한다. 일반적으로 inventory-check/payment이며 steps가 없다. C DependencyAnalyzer.flowSteps는 시작 flow에 비어 있지 않은 steps를 요구하므로 INVALID_FLOW가 된다.

C의 응답은 steps/nodes/edges/missing/ambiguous/complete인데 B stub은 run_order/links/broken_links다. 현재 후보 프롬프트에는 dict 그대로 들어가므로 이 필드 차이만으로 Python 예외가 나는 것은 아니지만 실제 완전성·파싱 오류를 검사하지 않고 stub과 다른 정보를 LLM에 준다.

수정 방향: entry_skill_id=order-flow(또는 명시적으로 저장한 시작 flow)로 호출하고 complete/ambiguous/parse 오류를 보존한다. apply_patch의 broken_links는 C-2 결과로 현재 B와 맞으므로 C-1 graph 결과와 혼동하지 않는다.

### P1-2. run_ids를 execution UUID 조회 API에 그대로 전달

근거: C OrderAgentRunClient.DryRunStarted는 execution_id와 run_id를 구분하며 HarnessFlowRunner는 started.runId()를 결과로 반환한다. B `_check_intent`, verify_candidates는 결과 run_ids[0]을 tools.get_trace로 넘기고 tools.py는 /api/executions/{값}/trace를 호출한다.

결과: r_<32자리> 별칭은 UUID 경로에 맞지 않아 400이 된다. C는 replay 입력에서 UUID와 별칭을 변환하지만 B HTTP 조회는 변환하지 않는다.

수정 방향: 별칭을 UUID로 명시 변환하거나 기존 /api/traces/{run_id} 경로로 조회한다. 진단 thread_id와도 구분한다.

### P1-3. 의도·도구 관찰 위치와 조건 문법 불일치

근거:

- B `_check_intent`는 EXECUTION_FINISHED.output을 읽는다. 실제 출력은 SKILL_FINISHED.data.output이고 전체 종료는 data.completion/final_status다.
- B `nodes.py:491`은 ev.tool을 읽는다. 실제 도구는 TOOL_REQUEST.data.tool이다.
- B _eval_check는 Python True를 문자열 'true'와 비교해 실패하고, 잘못된 문법은 True, in은 문자열 포함 검사다. C Check는 boolean/number/null과 목록 포함을 타입별로 평가한다.
- B run_scenarios 래퍼는 extra_checks를 지원하지만 verify_candidates는 전달하지 않고 첫 재현 trace만 별도로 검사한다.

오프라인 재현: 실제 성공 출력 형식에서 payment.status == 'PAID'는 False, notify.sent == true도 False, 'invalid' 문법은 True가 됐다.

수정 방향: 한 가지 타입 있는 조건 규칙으로 통일한다. C Judge에 extra_checks를 전달할 범위를 명시한다. 지금은 모든 주문에 적용되므로 정상 결제 의도 조건을 재고 부족 주문에까지 일괄 적용해서는 안 된다. 후보별·대상 시나리오별 조건 또는 별도 검증 호출이 필요하다. 모든 필요한 반복을 판정한다.

### P1-4. 제안 저장 도구 부재와 유효성 기준 누락

B `tools.py:127`, nodes.save_proposal은 save_proposal을 호출하지만 현재 Java @McpTool 목록에는 해당 도구가 없다. 현재 확인되는 도구는 ping/diff_skill/get_dependency_graph/create_workspace/apply_patch/run_scenarios다. C-2의 실제 판정 경로가 성공해도 제안 저장까지 성공했다는 뜻은 아니다.

또한 B `nodes.py:503`의 valid는 functional/regression/intent만 포함하고 contract_ok는 제외한다. 회귀 결과 누락도 len([]) 기준 통과로 계산할 수 있다.

수정 방향: 제안 저장 담당과 실제 도구/API를 확정한다. 검증 구간까지만 첫 연결을 한다면 저장 단계를 명시적으로 종료/미지원 상태로 분리한다. 유효성에는 계약·평가 완결성·필수 증거를 포함한다.

### P2. 설정·프롬프트·테스트가 불일치를 숨김

- B SKILLOPS_USE_STUB_MCP 기본값 true. 실연동 시 false를 명시해야 한다.
- C 기본 flow-runner는 contract-only. 실제 연동은 SKILLOPS_FLOW_RUNNER=harness, SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent로 시작한다.
- B tools.py는 SKILLOPS_ORDER_AGENT_URL, 별도 order_agent.py와 C는 ORDER_AGENT_BASE_URL을 사용한다. 세 주소가 같은 주문 Agent를 가리키는지 확인한다.
- prompts/hypothesize.md는 hunk_index, extract_intent.md는 assertions, generate_candidates.md는 local/contract/flow와 intent.assertions를 지시한다. B 구조화 스키마는 change_no/checks/same_skill·linked_skills·run_order다. 실제 LLM의 혼동·검증 실패 가능성이 있으나 이번 조사에서 모델을 호출해 재현하지 않았다.
- B stub trace는 event.skill/version/output을 평면 배치하고 도구 결과를 항상 통과시킨다. 현재 mock 테스트 성공은 실제 HTTP/C 계약 호환의 근거가 아니다.
- main.py의 /diagnose는 내부 thread_id를 202 응답으로 돌려주지 않아 호출자가 진행 조회 ID를 알기 어렵다. BackgroundTasks 예외와 별도 환경 오류 상태도 관찰 계약으로 보완한다.
- 계약 문서 상단 #55 갱신과 본문에 과거 제안이 공존한다. 'v1'이라는 명칭만으로 동일 계약이라고 가정하지 말고 실제 요청·응답 fixture를 동결해야 한다.

## S5와 정상 변경은 별도 진입·판정 설계 필요

현재 B는 failure 또는 skill_saved 요청 모두 가설→ablation→후보 생성으로 이어지는 흐름이다. 정상 변경의 사전 검증 후 종료 경로와 S5의 '진단만 하고 종료' 경로는 분리되어 있지 않다.

S5의 정책 미반영 주문은 현재 fixed에서 SHIPPED로 끝나 EXECUTION_ERROR도 없고 운영 실패 목록에도 잡히지 않는다. 따라서 실행 실패만 트리거하면 검출할 수 없다. 검증 조정 서비스/Judge의 의도 불일치 결과와 기대값 참조를 B에 전달해야 한다. C replay 기본 기준도 단지 FAILED가 아닌지 보므로 그 자체로 card 보류 의도 위반을 검출하지 못한다. 이는 필드명만 맞추는 것 외의 남은 기능이다. 이번 합의대로 S5는 오류 검출·설명까지 수행하고 자동 수정 완료로 처리하지 않는다.

## 권장 수정 순서와 담당 협의

| 순서 | 수정 | 주 담당 경계 | 완료 증거 |
| --- | --- | --- | --- |
| 1 | MCP 오류 처리·응답 검증, 가짜 성공 방지 | B | C 오류 payload 주입 시 예외/평가 오류, confirmed=False |
| 2 | 실제 trace 어댑터·UUID/별칭 변환·snapshot 출처 | B + 이벤트 송신 담당 | 실제 F1 trace에서 변경 원인과 실패 지점 분리, 존재하는 두 snapshot으로 diff |
| 3 | C-1 hunks·graph 계약 고정, 프롬프트·stub 갱신 | B/C-1 | 실제 응답 fixture로 파싱·역patch 검증 |
| 4 | snapshot_id workspace·버전 고정·실제 replay | B/C-2 | source snapshot 일치, f-UUID 또는 replay_run_ids 성공 |
| 5 | 실제 catalog와 scoped intent 조건·전체 반복 검증 | B/C-2 | g-01~g-03 및 재현 결과·증거 누락 없이 평가 |
| 6 | 제안 저장 계약, 정상 종료·S5 진단 전용 경로 | B/저장·조정 담당 | 미구현 도구 호출 없음, S1 정상 종료·S5 의도 위반 설명 |

C의 작동 중인 요청·응답을 B의 어댑터 기준으로 삼는 안을 권장한다. 다만 코드 변경 전에 C-1 hunks 유지 또는 v1 변환 위치, trigger schema, 제안 저장 경계를 담당자와 확정한다. 현재 동작하는 C를 B stub에 맞춰 임의로 되돌리면 안 된다.

## 첫 종단 연결 인수 조건

- 실제 S2/F1 실패 execution UUID, 기준·실패 snapshot UUID를 명시해 시작한다.
- fetch_evidence에서 예외 없이 실제 이벤트·변경 파일을 읽고 payment 실패와 inventory-check 변경을 구분한다.
- 실패 snapshot에서 만든 workspace에 지정 hunk의 역patch를 적용하고 실제 replay로 비교한다.
- patch 실패·없는 시나리오·MCP 오류 시 원인 확정이나 후보 유효 판정이 나오지 않는다.
- 수정 후보는 실제 캠핑 회귀와 재현·의도 조건을 통과하고 모든 실행 참조를 조회할 수 있다.
- C 내부 실험 성공과 B 포함 종단 성공을 따로 기록한다. 이번 리포트만으로 B 종단 연결 완료를 주장하지 않는다.
