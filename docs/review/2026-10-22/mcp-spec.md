# MCP 및 내부 업무 도구 명세

> **과거 검토 기록 — 기준 `ec39713`.** 이 문서의 “현재”, 미완료 항목, 테스트 수와 줄 번호는 작성 당시 기준입니다. 최신 구현·계약·#72 완료 상태는 [리뷰 문서 안내](../README.md)를 우선합니다. 아래 제안을 현재 API나 새 작업 승인으로 간주하지 않습니다.

기준: 2026-10-22, `ec39713`. [API](backend-api.md) · [구조·개선 리뷰](architecture-review.md).

## 1. 현재 제공 범위

현재 Java `@McpTool` 등록은 **7개: ping + 업무 도구 6개**입니다. 과거 문서의 ‘12개’는 현재 서버 등록 개수와 다릅니다. Python 클래스 이름이 MCPTools라고 하여 모든 메서드가 MCP 서버 도구인 것은 아닙니다.

| 기능 | 실제 경계 | 구현 | 파이프라인 사용 |
|---|---|---|---|
| ping | MCP | PingTools.ping | smoke/배선 확인 |
| diff_skill | MCP | SkillDiffTools → SkillDiffService | fetch_evidence |
| get_dependency_graph | MCP | DependencyGraphTools → DependencyGraphService | generate_candidates |
| create_workspace | MCP | WorkspaceTools → WorkspaceService.create | 사전 시험·원인 실험·후보 검증 |
| apply_patch | MCP | WorkspaceTools → WorkspaceService.apply | ablate·verify_candidates |
| run_scenarios | MCP | WorkspaceTools → ScenarioRunner.run | 사전 시험·원인 실험·회귀·의도 검증 |
| save_proposal | MCP | ProposalTools → ProposalService.save | save_proposal |
| list_failed_runs | **주문 REST** | Python order_agent.list_failed_runs | 조회 제공; 현행 그래프는 트리거의 실행을 직접 사용 |
| get_trace | **주문 REST** | Python order_agent.get_trace | 실행 증거, 의도용 키, 도구 요청 목록 |
| list_skills | **주문 REST** | Python order_agent.list_skills | snapshot의 version/해석 가능 여부 |
| get_skill | **주문 REST** | Python order_agent.get_skill | 후보 작성의 고정 원문 |
| get_snapshot | **주문 REST** | Python MCPTools.get_snapshot | Manifest와 hash·변경 경로 검증 |
| promote_workspace | 미등록·미구현 | — | 없음 |
| rollback | 미등록·미구현 | — | 없음 |

order.inspect·payment.charge 같은 **Harness 업무 도구**는 MCP 도구와 별개이며 10절에 설명드립니다.

## 2. 전송·결과·오류

서버: `http://localhost:8080/mcp`, Spring AI MCP Streamable HTTP, `protocol: STREAMABLE`, `type: SYNC`, annotation scanner 활성화. 서버 이름 skillops-service, 버전 0.1.0. 현재 SecurityConfig는 `/mcp` 인증을 요구하지 않습니다. Python은 `mcp.Client(url)`을 async context로 열고 SDK의 initialize/list_tools/call_tool 기능을 사용합니다. 세션·프로토콜 협상은 SDK에 위임하며 이 문서에서 protocolVersion을 임의 고정하지 않습니다.

도구 호출은 다음과 같은 JSON-RPC 의미를 갖습니다. 실제 HTTP 헤더·세션은 SDK가 관리하도록 해 주세요.

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"diff_skill","arguments":{"skill_id":"payment","before_snapshot_id":"<UUID>","after_snapshot_id":"<UUID>"}}}
```

ping을 제외한 도구는 성공 객체를 **단일 text content의 JSON 문자열**로 반환합니다. structuredContent의 제공을 코드에서 보장하지 않습니다.

```json
{"isError":false,"content":[{"type":"text","text":"{\"workspace_id\":\"w_example\",\"all_passed\":true,\"results\":[]}"}]}
```

위 결과는 래핑 방식의 예시이며 비어 있는 시험 결과가 유효하다는 뜻은 아닙니다.

```json
{"isError":true,"content":[{"type":"text","text":"{\"code\":\"INVALID_INPUT\",\"message\":\"...\",\"retryable\":false,\"upstream_status\":null,\"snapshot_id\":null}"}]}
```

공통 오류 필드: code(string), message(string), retryable(boolean), upstream_status(integer/null), snapshot_id(string/null). HTTP 200만 보고 성공이라고 판단하지 않습니다.

- C-1 `ToolResults`: OrderAgentException을 구조화합니다. 그 밖의 RuntimeException은 이 래퍼에서 처리하지 않으므로 SDK 기본 오류로 나갈 수 있습니다.
- C-2 `WorkspaceToolResults`: OrderAgentException 보존, IllegalArgumentException→INVALID_INPUT, 다른 RuntimeException→INTERNAL_ERROR. 후자의 두 오류는 retryable=false, upstream_status/snapshot_id=null입니다.
- C-2 HTTP 클라이언트의 timeout/연결·5xx가 IllegalStateException으로 감싸지면 최종 MCP에서는 INTERNAL_ERROR, retryable=false가 됩니다. 실제 재시도 가능성을 완전하게 표현하는 계약은 아닙니다.
- Python `_call`은 is_error/isError 모두 확인하고 content JSON을 파싱합니다. 오류 JSON이면 MCPToolError, 파싱 불가·빈 본문이면 ContractError를 발생시킵니다. `_call` 자체의 자동 재시도는 없습니다.

아래는 Java 매개변수·record에 따른 **논리 입력 명세**입니다. 실제 SDK가 생성하는 required/$defs/추가필드 정책까지 동일하다고 단정하지 않습니다. 서버 기동 환경에서는 `tools/list` 결과와 이 명세를 비교해 계약 검사를 추가하시는 것을 권합니다.

## 3. ping

입력 `{}`. 반환 text는 JSON이 아닌 `pong <Instant>` 문자열입니다. 시스템을 변경하지 않습니다. 서버 연결은 확인하지만 DB·주문 서버·모델의 정상 동작까지 증명하지 않습니다.

구현: [PingTools](../../../service/src/main/java/com/capstone4/skillops/mcp/PingTools.java).

## 4. diff_skill

용도: 정상 기준 snapshot과 실패/편집 snapshot에서 동일 Skill의 변경을 조사합니다. **baseline을 알고 있을 때** 사용하며 version-1을 기준 snapshot으로 추측하지 않습니다.

```json
{"skill_id":"inventory-check","before_snapshot_id":"<기준 UUID>","after_snapshot_id":"<후보 UUID>"}
```

세 필드 모두 string·필수입니다. skill_id는 `[a-z0-9][a-z0-9-]*`, snapshot은 UUID 검증을 받습니다. 이 이름 규칙은 주문 SkillLoader보다 느슨합니다.

| 출력 필드 | 타입·상세 |
|---|---|
| skill_id | string |
| before, after | `{snapshot_id,snapshot_hash,path,file_hash}`. 해당 쪽에 파일이 없으면 path/file_hash null |
| status | modified/added/removed/unchanged |
| frontmatter_changes | `{field:string,before:any,after:any,added:string[],removed:string[]}[]` |
| hunks | `{index:integer,hunk_id:string,part:string,header:string,before:string,after:string,diff:string}[]` |
| frontmatter_parse_error | string/null |
| related_files | `{path,status,before_hash,after_hash}[]` |

hunk.index는 **0부터**, Python change_no는 **1부터**입니다. hunk.part는 frontmatter/body. before/after는 제거·추가한 줄만, diff는 해당 hunk 하나를 포함한 unified diff입니다. 3줄 context를 사용합니다. hunk_id는 양 파일 hash, hunk header, 변경 내용을 SHA-256한 앞 12자리로 만든 `h_...`이며 같은 입력에서 안정적입니다. 영구 고유성이 수학적으로 보장되는 전체 SHA-256 ID는 아닙니다.

정방향 diff는 **before 파일**에 적용하는 내용입니다. 실패 snapshot에서 해당 변경을 취소하려면 Python reverse_hunk처럼 좌표와 +/-를 뒤집어야 합니다. 복수 hunk를 하나씩 실험할 때 후보 쪽 좌표를 잃지 않아야 합니다.

YAML이 깨져도 줄 diff는 보존하고 frontmatter_changes=[], frontmatter_parse_error를 반환합니다. related_files는 같은 Skill 폴더의 resource를 포함하되 내용 diff 없이 해시만 비교합니다. 두 snapshot 모두 해당 SKILL.md가 없으면 SKILL_NOT_FOUND. upstream·인자 오류도 공통 오류 래퍼입니다. 읽기 전용이며 저장 효과가 없습니다.

구현: [SkillDiffTools](../../../service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java), [SkillDiffer](../../../service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java).

## 5. get_dependency_graph

```json
{"snapshot_id":"<UUID>","entry_skill_id":"order-flow"}
```

두 필드 모두 string·필수. entry_skill_id는 diff_skill과 같은 규칙입니다. flow와 steps의 SKILL.md를 고정 snapshot에서 읽어 전체 정적 관계를 계산합니다. 실행 여부·실제 도구 성공·자연어 정책의 유효성을 판정하는 도구는 아닙니다.

| 출력 필드 | 구조 |
|---|---|
| snapshot_id, snapshot_hash | 조회 원본 식별·해시 |
| entry_skill_id, steps | 시작 flow, 선언한 단계 순서 |
| nodes | `{skill_id,path,file_hash,inputs,outputs,depends_on,parse_error}[]` |
| edges | `{type,from,to,keys:string[]}[]`; type=flow_order/depends_on/io |
| missing | `{skill_id,missing_inputs:string[],missing_dependencies:string[]}[]` |
| ambiguous | `{skill_id,key,providers:string[]}[]` |
| complete | boolean; 단계 파일이 없거나 파싱 불가이면 false |

초기 공급 키는 order.id, order.items, order.customer, order.payment_method, order.shipping입니다. steps 순서대로 inputs가 주문 입력이나 이전 outputs에서 공급되는지, depends_on이 이미 끝난 단계인지 확인합니다. 여러 공급자가 있으면 마지막 공급자를 io edge로 연결하고 ambiguous에 모두 남깁니다.

첫 오류에서 중단하지 않고 뒤 관계까지 계산합니다. 따라서 `complete=true`가 ‘계약 위반 없음’을 의미하지는 않습니다. complete와 missing·ambiguous를 함께 보셔야 합니다. 누락/불량 단계도 분석 과정의 done에 포함하는 현재 동작은 실행기와 차이가 있습니다. entry 자체가 없으면 SKILL_NOT_FOUND, entry의 steps를 읽을 수 없으면 INVALID_FLOW입니다.

구현: [DependencyAnalyzer](../../../service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java). Python은 generate_candidates에서 complete=true를 요구합니다. WorkspaceService.apply도 같은 분석기를 사용하나 결과를 broken_links로 축약합니다.

## 6. create_workspace

```json
{"snapshot_id":"<주문 snapshot UUID>"}
```

또는 `{ "start_versions": {"payment":1} }`, 또는 `{}`.

| 입력 | 타입 | 규칙 |
|---|---|---|
| start_versions | object<string,integer>, 선택 | 명시하지 않은 Skill은 현재 live 버전. null 값 불가 |
| snapshot_id | string, 선택 | 불변 주문 snapshot에서 생성. 비어 있지 않은 start_versions와 동시 사용 불가 |

둘 다 없으면 service 설정의 `skills-dir`를 읽습니다. 단순 live 폴더 저장소는 현재 선언 버전만 제공하며 과거 버전 요청을 지원하지 않습니다. 같은 version 번호로 다른 snapshot 바이트를 식별할 수 없습니다.

```json
{"workspace_id":"w_XXXXXXXX","skill_versions":{"inventory-check":1,"payment":1},"created_at":"2026-10-22T00:00:00Z"}
```

workspace_id는 `w_` + 영숫자 8자리입니다. snapshot 기반이면 skills/ 아래 SKILL.md와 모든 UTF-8 resource를 복사하고 source_snapshot_id를 보존합니다. SKILL.md는 SkillHeader.parse의 version을 요구합니다. 바이너리 resource는 SKILL_FILE_NOT_TEXT로 실패합니다. 원문을 모두 읽은 뒤 workspaces를 저장하므로 읽기 실패 시 부분 workspace를 저장하지 않습니다.

상태는 OPEN. live 운영 파일을 수정하지 않습니다. harness Runner에서 실행하려면 **snapshot_id로 만든 workspace**가 필요합니다. 생성된 skill_versions는 patch 기준이며 다음 apply_patch의 표시 version과 혼용하지 않습니다.

대표 오류: 없는 Skill·없는 live 버전·빈 snapshot Skill 세트·함께 사용한 시작점→INVALID_INPUT, 파일/서버 오류→공통 래퍼. 생성 재시도는 새 workspace를 만듭니다. 요청 멱등 키는 없습니다.

## 7. apply_patch

```json
{"workspace_id":"w_XXXXXXXX","patches":[{"skill":"inventory-check","base_version":1,"patch":"@@ -7,1 +7,1 @@\n-outputs: [inventory.available]\n+outputs: [inventory.available, inventory.reserved]\n"}]}
```

위 patch는 형식 설명용이며 원문의 실제 줄과 일치할 때만 적용됩니다.

| 입력 | 조건 |
|---|---|
| workspace_id | string, 필수; 존재하고 OPEN이어야 함 |
| patches | 비어 있지 않은 array, 필수 |
| patches[].skill | workspace에 존재하는 비공백 이름 |
| patches[].base_version | integer; 생성 당시 baseVersion과 일치 |
| patches[].patch | 비어 있지 않은 unified diff |

`---/+++` 파일 헤더는 생략할 수 있고 실제 적용 대상은 skill 필드로 결정됩니다. `@@ -old[,count] +new[,count] @@`와 본문 +/-/공백 prefix를 검사합니다. hunk 위치·문맥이 맞아야 하며 fuzz 적용은 하지 않습니다. 삽입의 `-N,0` 좌표를 보정하고 원문의 마지막 줄바꿈 여부를 유지합니다. CRLF까지 바이트 그대로 보존하는 편집기는 아닙니다.

한 요청 안에서는 순서대로 복사본에 적용하여 모두 성공한 뒤 저장합니다. 같은 Skill을 여러 번 주면 앞 수정 결과에 다음 patch를 적용합니다. 하나라도 실패하면 전부 취소됩니다. 단, 여러 동시 호출 사이의 DB 잠금·낙관적 버전은 구현되어 있지 않습니다.

```json
{"workspace_id":"w_XXXXXXXX","changed_skills":[{"skill":"inventory-check","version":2}],"broken_links":[{"skill":"payment","missing_input":"inventory.reserved"}]}
```

version=baseVersion+그 Skill에 적용한 patch 횟수이며 **SKILL.md 안 version 줄을 자동 변경하지 않습니다**. 다음 patch의 base_version도 여전히 생성 당시 값입니다. 이 숫자는 변경 동시성 제어용 expected revision이 아닙니다.

변경한 Skill의 선언을 분석할 수 없으면 거부합니다. 입력 공급이 끊겨도 patch는 저장되고 broken_links로 알려줍니다. 현재 broken_links는 missing_inputs만 포함하여 **missing_dependencies·ambiguous·complete 전체를 표현하지 않습니다**. 빈 broken_links만으로 모든 실행 계약 정상이라고 판단하지 말아 주세요.

오류: 없는 workspace/Skill, OPEN 아님, 버전 불일치, 문맥 불일치, 잘못된 patch/선언→INVALID_INPUT. 저장 장애→INTERNAL_ERROR. 이미 성공한 patch를 그대로 재시도하면 원문 불일치가 날 수 있으므로 멱등 적용 API로 취급하지 않습니다.

구현: [WorkspaceService](../../../service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java), [PatchApplier](../../../service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java).

## 8. run_scenarios

```json
{"workspace_id":"w_XXXXXXXX","scenario_ids":["g-01","g-02","g-03"],"replay_run_ids":["r_11111111111111111111111111111111"],"extra_checks":["payment.status == 'PAID'"],"repeat":2}
```

이 예시의 extra_checks는 **모든 시나리오에** 적용되므로 정상적인 재고 거절 g-02에도 PAID를 요구하게 됩니다. 실제 의도 검증에서는 Python처럼 재현 주문만 별도로 호출해 주세요.

| 입력 | 타입·기본 | 규칙 |
|---|---|---|
| workspace_id | string, 필수 | 조회 가능한 workspace |
| scenario_ids | string[], 선택 | catalog ID 또는 `f-<하이픈 UUID>` |
| replay_run_ids | string[], 선택 | execution UUID 또는 r_32hex |
| extra_checks | string[], 선택 | 이번 호출의 모든 주문에 추가할 조건 |
| repeat | integer, 기본 2 | 1~5 |

scenario_ids/replay_run_ids 중 적어도 한 목록은 비어 있지 않아야 합니다. scenario_ids의 `f-01`처럼 UUID가 아닌 f-ID는 catalog에서 찾습니다. 결과는 scenario_ids 다음 replay_run_ids 순서입니다. 중복 ID를 전역 거부하거나 전체 시나리오 수를 제한하는 코드는 없습니다. 시나리오 JSON의 k는 현재 ScenarioRunner에서 반복 횟수 선택에 사용하지 않습니다.

실행 전에 모든 ID·조건·시나리오 기대값을 검증하고 재현 원본 주문을 조회합니다. 입력 오류 때문에 앞의 주문만 실행되는 상황을 줄였습니다. 실행 중 네트워크·저장 실패가 나면 전체 결과 객체 대신 도구 오류가 나고, 이미 만들어진 실행·ledger 일부는 남을 수 있습니다.

```json
{
  "workspace_id":"w_XXXXXXXX",
  "all_passed":false,
  "results":[{
    "scenario_id":"g-01","replay_of_run_id":null,"passed":false,
    "run_ids":["r_11111111111111111111111111111111","r_22222222222222222222222222222222"],
    "failures":[{"run_id":"r_11111111111111111111111111111111","skill":"payment","reason":"..."}]
  }]
}
```

각 시나리오는 repeat만큼 모두 실행하며 한 번이라도 불합격이면 passed=false입니다. all_passed는 모든 결과 passed의 AND입니다. failures[].skill은 특정 오류 단계를 식별하지 못하면 null입니다. reason은 사람이 읽는 한국어 문자열이며 구조화된 오류 목록은 아닙니다. Python validate_report는 workspace·시나리오 순서·계획한 결과 수·반복 수·중복 run ID·UUID 형식·failure 참조·AND 일관성을 추가 검증합니다.

### 8.1 실행기 선택

| 설정 | 동작 | 제한 |
|---|---|---|
| contract-only (기본) | 선언 inputs/outputs만 따라가며 canned 값 생성 | LLM·실제 업무 도구 없음, 본문 정책 무시, replay 불가, r_fake... ID |
| harness | workspace 전체 파일을 후보 snapshot으로 발행 → 주문 dry-run → trace→RunOutcome 변환 | snapshot 기반 workspace 필수, 실제 재고를 잠시 사용, live 데이터에 영향받음 |

harness는 정렬된 (경로,문자열)의 길이 포함 SHA-256으로 발행 snapshot을 프로세스 메모리에 캐시합니다. 이는 주문 snapshot의 metadata 포함 hash와 다른 목적의 값입니다. 여러 workspace의 같은 파일 묶음은 캐시를 공유하여 candidate metadata.workspace_id가 최초 workspace를 가리킬 수 있습니다. 캐시 snapshot이 404이면 한 번 다시 발행합니다.

한 service 프로세스의 ReentrantLock으로 dry-run을 직렬화하지만 live 주문·다른 프로세스·주문 dry-run REST 직접 호출과는 격리되지 않습니다. 파일도 매 trial에 filesOf로 다시 읽으므로 동시 patch 중인 workspace revision 전체가 한 평가에 고정되지 않습니다.

기본 catalog는 `scenarios/golden`의 g-01(과일 SKU) 하나입니다. 실제 주문용 g-01~03은 `scenarios/order-agent/golden`에 있습니다. 도구 description의 g-01~06은 현재 fixture 수와 다릅니다. harness 사용 시 `SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent`를 설정해야 합니다(service/ 기준).

### 8.2 Judge 규칙

정상 catalog 시나리오: 성공한 Step의 Skill 순서가 expect.skills_called와 **완전히 일치**하는지, final_status 일치, shipping-reserve 성공 output에 shipping.id가 있는 횟수, assertions와 extra_checks를 검사합니다. null인 기대 필드는 검사하지 않습니다. TOOL_REQUEST/RESULT의 실제 인자·호출 횟수를 Judge에서 직접 비교하지 않습니다.

재현 주문: Expectation.replay는 **final_status가 FAILED가 아닌지**만 요구합니다. REJECTED/HOLD도 통과할 수 있고 completion은 RunOutcome에 없으므로 검사하지 않습니다. ‘원래 성공해야 할 업무가 복구됨’보다 약한 조건입니다. 이 결과를 Python fixes_failure와 ablation confirmed에 사용한다는 점이 현재 중요한 제약입니다.

### 8.3 조건 문법

```text
payment.status == 'PAID'
inventory.reserved == true
notify.channel in ['mock', 'email']
inventory.reservable_quantity != null
```

지원: `==`, `!=`, `in`; 리터럴은 작은따옴표 문자열, true/false/null, 음수를 포함한 정수·소수. in은 대괄호 목록만 허용합니다. `>`, `>=`, exists, AND, 함수 호출, eval은 없습니다. 키는 JSONPath가 아니라 **점이 포함된 flat Map 키**입니다. Step input·output을 순서대로 merge하여 뒤의 값이 덮어씁니다. 키가 없으면 null로 간주하여 `없는키 != 'PAID'`도 참이 될 수 있습니다. 숫자는 BigDecimal 값 비교입니다.

### 8.4 저장 효과·예산

각 trial을 scenario_runs에 `{run_id,workspace_id,scenario_id,passed,recorded_at}`로 저장합니다. harness는 추가로 주문·trace·후보 snapshot을 주문 DB에 저장합니다. 현재 ledger에는 revision/hash/expectation/extra_checks/실제 관찰 결과·판정 이유가 없습니다. 전체 평가 timeout·LLM 비용 총량·planned/executed/error/not_run 집계는 없습니다. 단일 주문 Harness의 45초·12 API·관측 토큰 10,000 제한만 별도 적용됩니다.

## 9. save_proposal

진단·검증을 마친 제안서를 Java service DB에 저장합니다. REST 저장 API는 없으며 Python save_proposal node가 이 MCP를 사용합니다.

```json
{"proposal":{
  "thread_id":"<진단 UUID>",
  "trigger":{"type":"failure","execution_id":"<UUID>"},
  "baseline_snapshot_id":"<UUID>","snapshot_id":"<UUID>",
  "failure":{"execution_id":"<UUID>","skill":"payment","category":"CONTRACT_VIOLATION","message":"...","missing_inputs":["inventory.reserved"],"at":"2026-10-22T00:00:00Z"},
  "root_cause":{"skill":"inventory-check","change_no":1,"hunk_id":"h_example","from_version":1,"to_version":1,"summary":"...","before":"...","after":"...","proof_run_ids":["r_<control>","r_<restored>"]},
  "intent":{"text":"...","checks":["inventory.reserved == true"],"dropped_checks":[],"confirmed":null},
  "candidates":[{
    "candidate_no":1,"kind":"same_skill","workspace_id":"w_XXXXXXXX",
    "patches":[{"skill":"inventory-check","base_version":1,"patch":"<unified diff>"}],
    "results":{"fixes_failure":true,"keeps_old_orders":{"passed":3,"total":3},"keeps_intent":true,"no_broken_links":true,"changed_skill_count":1,"tools_called":["order.inspect","inventory.reserve","payment.charge","shipping.reserve","notify.send"]},
    "valid":true,"validation_error":null,"evidence_run_ids":["r_<actual trial>"],"rank":1,"explanation":"..."
  }],
  "recommended_candidate_no":1,"status":"proposed"
}}
```

위 예시의 `<...>`가 포함된 run ID는 설명용입니다. 실제 호출에는 올바른 형식의 존재하는 실행 참조가 필요합니다.

### 9.1 필드와 실제 서버 검증

proposal은 Map이며 독립 JSON Schema 전체를 강제하는 DTO가 아닙니다. 예시 필드는 Python의 현재 전송 내용입니다. 실제 검증 범위를 구분해 주세요.

| 필드·조건 | 서버의 현재 처리 |
|---|---|
| proposal | null/빈 Map 거부 |
| thread_id | 있으면 기존 제안서를 먼저 조회하여 그대로 반환. 다른 payload 여부 비교 없음; 없으면 새 제안서 |
| status | proposed 또는 no_valid_fix만 최초 저장 허용 |
| candidates[].candidate_no | 배열 위치에 맞춰 1부터 빠짐없이 |
| validation_error | 비공백이면 불합격이며 evidence 검사를 건너뜀 |
| workspace_id/results/evidence_run_ids | 적용 오류가 없는 후보에는 필수 |
| evidence_run_ids | 해당 workspace의 ledger에 모두 존재해야 함 |
| valid | boolean으로 제공한 경우 재계산 값과 일치해야 함 |
| results | fixes_failure=true, keeps_old_orders.total>0 및 passed=total, keeps_intent=true, no_broken_links=true이면 합격 |
| proposed | 합격 후보 ≥1, root_cause.proof_run_ids가 전역 ledger에 모두 존재, 추천 번호가 합격 후보 |
| no_valid_fix | 합격 후보가 없어야 함; root_cause가 없어도 저장 가능 |
| trigger·snapshot 참조·kind·patch·rank·설명·의도 본문 | 전체 의미·hash·revision·형식 일관성 검증은 없음 |

서버는 **제출된 results의 논리식은 재계산하지만 ledger의 실제 passed에서 다시 계산하지는 않습니다**. 근거 ID가 workspace에 속한다는 사실만으로 실패 복구·회귀 전체·의도 보존의 증명이 완성되는 것은 아닙니다. root proof도 전역 존재 확인이며 같은 진단·snapshot·실험 쌍인지 확인하지 않습니다. 이는 [리뷰](architecture-review.md)의 최우선 보강 대상입니다.

### 9.2 결과·저장

```json
{"proposal_id":"p_<UUID 32hex>","status":"proposed","valid_candidate_nos":[1]}
```

같은 thread_id 재호출은 최초 저장 레코드를 반환하며 이미 approved/rejected이면 그 상태가 반환될 수 있습니다. 동시 최초 저장의 unique 충돌을 재조회로 복구하는 코드는 없습니다.

저장 테이블 proposals에는 원문 payload, 고정 document, 계산된 valid_candidate_nos, thread_id, status, created_at, decided_at, 낙관적 version이 들어갑니다. 본문은 ProposalDocumentWriter가 정해진 한국어 틀로 작성하고 AI 설명만 후보 explanation을 포함합니다. ToneCheck는 표현을 경고 목록에 남기지만 저장을 차단하지 않습니다.

입력·근거·상태 불일치는 INVALID_INPUT. DB 등 RuntimeException은 INTERNAL_ERROR입니다. 운영 파일·승인 행·snapshot의 내용을 변경하지 않습니다.

## 10. Harness 내부 업무 도구 전체

다음 도구는 네트워크 MCP 서버에 등록되지 않았습니다. 모델은 JSON으로 도구 실행을 요청하고 ToolDispatcher/OrderToolService가 권한·인자를 검증한 뒤 처리합니다.

| Skill | 도구 | 허용 arguments | 반환 context 및 효과 |
|---|---|---|---|
| order-intake | order.inspect | `{order_id}` | order.id/customer/items/payment_method/shipping/total, 서버 확정 주문 조회 |
| inventory-check | inventory.reserve | `{order_id,items:[{sku,qty}]}` | inventory.available(boolean), inventory.reservable_quantity(integer), inventory.reserved(boolean); 전량 예약 또는 전량 거절 |
| payment | payment.charge | `{order_id,amount,method}` | payment.status=PAID/DECLINED, 성공 시 payment.id; 거절 시 재고 해제·HOLD |
| shipping-reserve | shipping.reserve | `{order_id,country,express}` | shipping.id, shipping.eta; 결제·예약 확인 후 Mock 배송 참조 저장 |
| notify | notify.send | `{order_id,customer}` | notify.sent=true, notify.channel=mock; 배송 참조 확인 후 Mock 알림 기록 |
| 서버 보상 | inventory.release | `{order_id}` | inventory.reserved=false; 미결제 실패·결제 거절·재시작 복구. 모델 선택 허용 목록에 없음 |

일반 도구 반환은 `{context:object,final_status:string}`. Harness는 선언 outputs만 다음 단계로 전달합니다. 따라서 tool이 inventory.reserved를 만들었어도 Skill outputs에서 삭제했다면 뒤 단계는 그 값을 받지 못합니다. 성공적인 선언 흐름이어도 실제 도구가 선언한 출력을 만들지 않으면 MISSING_SKILL_OUTPUT입니다.

서버는 주문 row lock, execution RUNNING 여부, 주문 PROCESSING 여부, 현재 서버 주문 문맥으로 재계산한 인자 전체의 hash 일치를 검사합니다. tool과 skill의 고정 binding도 확인합니다. 임의 금액·다른 주문 ID·추가 인자·다른 도구를 모델이 요청하면 거부합니다. 도구 결과와 업무 효과는 하나의 짧은 트랜잭션에서 커밋합니다.

`agent_tool_operations`의 키는 (execution_id,tool)이며 같은 인자 hash의 재호출은 결과를 재사용합니다. 다른 인자는 IDEMPOTENCY_CONFLICT입니다. 현 구조는 한 실행에서 동일 도구를 다른 입력으로 여러 번 쓰는 범용 반복 Agent가 아닙니다.

reservable_quantity는 현재 잠근 상품들의 **최소 원재고 수량**입니다. 여러 SKU의 주문 수량에 따른 ‘주문 세트 예약 가능 개수’로 해석하지 않도록 주의해 주세요. 실제 전량 예약 성공의 기준은 inventory.reserved입니다.

## 11. 호출 상황별 안내

1. **변경 수집**: get_snapshot(REST)으로 양쪽 파일 hash를 비교하고 바뀐 SKILL.md에 diff_skill을 호출합니다. trace의 실행 snapshot/hash도 대조합니다.
2. **저장 직후 점검**: 기준·후보 snapshot으로 각각 create_workspace → run_scenarios. 기준도 실패하면 BASELINE_FAILED로 중단합니다.
3. **원인 실험**: 후보 그대로 replay 1회로 원래 오류를 확인한 뒤, 가설별 새 workspace에서 reverse_hunk → apply_patch → replay 1회입니다.
4. **후보 생성**: 고정 후보 snapshot의 get_dependency_graph와 get_skill(REST) 원문을 모델에 전달합니다. 모델은 전체 수정 파일을 제안하고 Python이 unified diff를 생성합니다.
5. **후보 검증**: 후보별 새 workspace → apply_patch → golden 반복 → replay 반복 → replay에만 extra_checks 반복. 결과가 모두 충족되어야 valid입니다.
6. **검토 요청**: save_proposal → Java 제안서 조회/승인 REST. 현행 흐름은 여기서 종료하며 promote/rollback은 없습니다.

Python의 StubMCPTools는 개발용 모의 응답입니다. stub 통과를 실제 MCP 서버·실제 주문 실행·모델 정책 검증으로 보고하지 않도록 부탁드립니다.
