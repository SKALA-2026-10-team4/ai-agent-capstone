# MCP 도구 입출력 안내

계약 5 · 현재 코드 기준 · 2026-10-23 갱신 · 기준 코드 `service/src/main/java/com/capstone4/skillops/mcp/`

SkillOps 서비스(`service/`)는 MCP 서버로 도구 7개를 내놓습니다. SkillOps Agent(`skillops-agent/`)는 이 도구에 값을 보내고 돌려받은 결과만 보고 판단합니다. 이 문서는 도구마다 **무엇을 보내고, 무엇을 돌려받고, 그 사이에 서비스가 어떤 저장 공간을 읽고 쓰는지**를 정리한 것입니다.

2026-10-23 · #72 공용 파서: service와 주문 Agent가 동일한 skill-parser 모듈을 사용합니다. 분석·workspace·실행 모두 YAML frontmatter 100,000 코드 포인트 제한과 같은 안전 옵션을 적용합니다. 초과 시 기존 파싱 오류 필드·코드로 처리하고 diff 원문 hunks는 보존합니다. 입력·응답 필드와 apply_patch v1.3은 변경하지 않습니다. [현재 SKILL 해석 계약](skill-frontmatter.md#72-최종-갱신--서버-공용-파싱-모듈-2026-10-23).

2026-10-23 · #72 partial 목록의 증거 수집 연동: Python은 기존 원문 파일 REST와 `diff_skill` MCP를 사용해 증거만 저장하고 종료합니다. 새 MCP tool·필드 변경은 없으며 apply_patch v1.3을 유지합니다. 진단 상세 REST의 새 evidence 필드는 [별도 계약](diagnosis-evidence.md)을 따릅니다.

> **2026-10-23 갱신 (#72 4단계, apply_patch v1.3).** 필수 `flow_errors:[{skill,code,reason,message}]`를 추가했습니다. 연결 검사 `no_broken_links`, 필수 정보 검사 `metadata_valid`, 실행 구조 검사 `flow_valid`가 모두 통과해야 후보가 합격합니다. 네 오류 목록은 누락·null·잘못된 타입이면 계약 오류입니다. service와 Python을 함께 갱신해야 합니다. 도구 애플리케이션 계약 버전이며 MCP protocolVersion과 별개입니다. [응답 JSON Schema](apply-patch-result.schema.json) · [flow 오류 예제](examples/apply-patch-flow-error.json)


이 문서는 지금 코드가 하는 일을 적습니다. 입력 이름과 형식은 실행 중인 서버의 `tools/list` 응답과 같고, 돌려받는 값은 각 도구가 돌려주는 결과 record와 테스트(`service/src/test/java/com/capstone4/skillops/mcp/`)를 기준으로 적었습니다. 문서와 코드가 다르면 코드가 맞습니다. 그때는 이 문서를 고쳐 주세요.

실패한 실행 목록, 실행 기록, skill 목록, skill 원문은 MCP 도구가 아닙니다. Agent가 주문 Agent의 HTTP API를 직접 부릅니다. 아래 "주문 Agent에서 직접 읽는 값" 절을 보세요.

## 이 문서를 읽는 사람

| 누구 | 이 문서에서 볼 곳 |
|---|---|
| B · Agent 구현 | 도구마다 "보내는 값", "돌려받는 값", "예시". 그리고 "공통 약속"과 "도구 사이를 잇는 번호" |
| C-1 · C-2 · 서비스 구현 | 도구마다 "읽고 쓰는 저장 공간"과 "구현 위치". 그리고 맨 끝의 "저장 공간 목록"과 "이 문서를 고칠 때" |
| A · D · 화면과 주문 Agent | "주문 Agent에서 직접 읽는 값", `run_scenarios`의 실제 실행기 설명 |

## 읽는 법

| 표기 | 뜻 |
|---|---|
| 글자 | 따옴표로 감싼 글. 예: `"payment"` |
| 정수 | 소수점 없는 숫자. 예: `3` |
| 참·거짓 | `true` 또는 `false` |
| 목록 | 대괄호로 감싼 여러 개. 예: `["g-01", "g-02"]` |
| 묶음 | 중괄호로 감싼 이름과 값의 모음. 예: `{"payment": 1}` |
| 시각 | 국제 표준 형식(UTC)으로 적은 글자. 예: `"2026-10-21T00:13:05.123456Z"` |
| `null` | 값이 없다는 뜻 |
| `hunks[].diff` | `hunks` 목록 안에 들어 있는 각 항목의 `diff` |
| 꼭 필요: 예 | 반드시 넣어야 합니다. 빠지면 도구가 거부합니다 |
| 꼭 필요: 아니오 | 넣지 않아도 됩니다. 넣지 않았을 때 어떻게 되는지는 설명에 적었습니다 |
| 값 이름 | skill 사이를 오가는 값의 이름. `내놓은 쪽.무엇` 모양입니다. 예: `inventory.reserved`는 재고 확인 쪽이 내놓은 '예약 여부'입니다 |

## 연결 방법

| 항목 | 값 |
|---|---|
| 주소 | `POST http://localhost:8080/mcp` |
| 전송 방식 | MCP Streamable HTTP. 설정은 `service/src/main/resources/application.yml`의 `spring.ai.mcp.server`에 있습니다 |
| 도구 등록 | Spring AI MCP Server의 annotation scanner가 `@McpTool` 메서드를 찾아 도구로 내놓습니다. 입력 키는 Java 매개변수 이름 그대로이고, 그래서 매개변수를 snake_case로 씁니다 |
| Agent 쪽 클라이언트 | `skillops-agent/skillops_agent/tools.py`의 `MCPTools`. 주소는 `skillops-agent/skillops_agent/config.py`의 `MCP_URL`입니다 |
| 연결 확인 | `skillops-agent/scripts/mcp_smoke.py`가 도구 목록을 받고 `ping`을 부릅니다 |

`tools/list`의 도구 설명(`description`)과 입력 설명은 `@McpTool`, `@McpToolParam`에 적힌 문자열 그대로입니다. Agent의 LLM도 이 설명을 읽으므로, 이 문서와 함께 맞춰 둡니다. `tools/list`에 보이는 `annotations`(`destructive_hint` 등)는 SDK 기본값이고 도구마다 따로 정한 값이 아닙니다.

## 도구 한눈에 보기

| 도구 | 하는 일 | 담당 | 구현 위치 | Agent에서 부르는 곳 |
|---|---|---|---|---|
| `diff_skill` | 두 snapshot 사이에서 같은 skill의 SKILL.md를 비교합니다 | C-1 | `service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java` | `skillops-agent/skillops_agent/nodes.py`의 `_collect_changes` |
| `get_dependency_graph` | snapshot 안 flow의 연결 관계와 끊긴 곳을 계산합니다 | C-1 | `service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java` | `nodes.py`의 `_repair_inputs` |
| `create_workspace` | 연습 공간(skill 복사본)을 만듭니다 | C-2 | `service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java` | `nodes.py`의 `_preflight`, `_evaluate_intent_checks`, `_reproduce_failure`, `_ablate_change`, `_verify_candidate` |
| `apply_patch` | 연습 공간의 SKILL.md에 수정 내용을 넣습니다 | C-2 | 같은 파일 | `nodes.py`의 `_ablate_change`, `_verify_candidate` |
| `run_scenarios` | 연습 공간의 skill로 시험 주문을 모의 실행하고 채점합니다 | C-2 | 같은 파일 | `nodes.py`의 `_run_scenarios` |
| `save_proposal` | 수정 제안서를 검사하고 저장합니다 | C-2 | `service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java` | `nodes.py`의 `save_proposal` 노드 |
| `ping` | MCP 연결을 확인합니다 | 공통 | `service/src/main/java/com/capstone4/skillops/mcp/PingTools.java` | 노드는 부르지 않습니다. `scripts/mcp_smoke.py`와 테스트가 부릅니다 |

Agent를 `SKILLOPS_USE_STUB_MCP=true`로 띄우면 이 서버 대신 `skillops-agent/skillops_agent/stub_tools.py`의 `StubMCPTools`가 같은 이름의 도구를 흉내 냅니다.

## 공통 약속

1. **성공 결과는 JSON 글 한 덩어리입니다.** `ping`을 뺀 여섯 도구는 결과를 JSON 묶음으로 바꿔 MCP 결과의 글 내용(text content) 하나에 담고 `isError=false`로 돌려줍니다. 목록을 돌려줄 때도 `{"results": [...]}`처럼 이름을 붙여 감쌉니다. 칸 이름은 snake_case이고, 값이 `null`인 칸도 빼지 않고 내보냅니다. 결과를 만드는 코드는 `service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java`의 `success`입니다. `ping`만 JSON이 아닌 글(`"pong <서버 시각>"`)을 돌려줍니다.

2. **실패하면 `isError=true`와 오류 묶음을 돌려줍니다.** 모양은 모든 도구가 같고, 칸 순서도 아래와 같습니다.

   ```json
   { "code": "INVALID_INPUT", "message": "workspace_id w_A17xYz9Q 이 없습니다. create_workspace 로 먼저 만드세요", "retryable": false, "upstream_status": null, "snapshot_id": null }
   ```

   | 칸 | 뜻 |
   |---|---|
   | `code` | 오류 종류입니다. 아래 표를 보세요 |
   | `message` | 무엇이 잘못됐고 어떻게 고치면 되는지 적은 한 줄입니다 |
   | `retryable` | 같은 입력으로 다시 불러 볼 만하면 `true`입니다 |
   | `upstream_status` | 주문 Agent가 돌려준 HTTP 상태입니다. 주문 Agent를 거치지 않은 오류면 `null`입니다 |
   | `snapshot_id` | 오류와 관련된 snapshot 번호입니다. 없으면 `null`입니다 |

   어떤 예외를 어떤 `code`로 바꾸는지는 도구 묶음마다 다릅니다.

   | 도구 | 정책 위치 | 구조화 오류가 되는 경우 |
   |---|---|---|
   | `diff_skill`, `get_dependency_graph` (C-1) | `ToolResults.java`의 `run` | 주문 Agent 조회 실패와 입력 검사 실패(`OrderAgentException`)만 구조화합니다. `code`는 `INVALID_INPUT`, `SKILL_NOT_FOUND`, `SKILL_FILE_NOT_TEXT`, `INVALID_FLOW`, `ORDER_AGENT_UNAVAILABLE`(연결 실패, `retryable=true`), 또는 주문 Agent가 준 `code`(본문에 없으면 `ORDER_AGENT_HTTP_<상태>`)입니다. 5xx와 429는 `retryable=true`입니다. 그 밖의 예외는 SDK가 `Error invoking method`로 시작하는 글 오류로 감싸므로 JSON이 아닙니다 |
   | `create_workspace`, `apply_patch`, `run_scenarios`, `save_proposal` (C-2) | `service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java`의 `run` | 모든 실패를 구조화합니다. 주문 Agent 조회 실패는 주문 Agent가 준 `code`, `retryable`, `upstream_status`, `snapshot_id`를 그대로 전달합니다. 입력 오류는 `INVALID_INPUT`, 그 밖의 서버 오류는 `INTERNAL_ERROR`이고 둘 다 `retryable=false`입니다 |

   Agent 쪽에서는 `tools.py`의 `MCPTools._call`이 오류 글을 JSON 묶음으로 읽어 `MCPToolError`로 올리고, JSON 묶음이 아니면 `ContractError`로 올립니다.

3. **이름은 줄여 쓰지 않습니다.** 끝말은 아래 규칙을 따릅니다.

| 끝말 | 뜻 | 예 |
|---|---|---|
| `_id` | 무엇을 가리키는 고유 번호(글자) | `run_id`, `workspace_id`, `skill_id` |
| `_ids` | 고유 번호 목록 | `run_ids`, `scenario_ids` |
| `_no` · `_nos` | 1부터 세는 순번(정수)과 그 목록 | `candidate_no`, `valid_candidate_nos` |
| `_version` · `_versions` | skill 버전(정수)과 그 묶음 | `base_version`, `skill_versions` |
| `_at` | 시각 | `created_at` |
| `_hash` | SHA-256 해시 글자 | `snapshot_hash`, `file_hash` |

4. **고유 번호의 모양**

| 번호 | 모양 | 예 | 만드는 곳 |
|---|---|---|---|
| snapshot 번호 `snapshot_id` | 하이픈이 있는 UUID | `b6678449-9819-4d31-8c1b-13fc57892e5c` | 주문 Agent |
| 실행 번호 `execution_id` | 하이픈이 있는 UUID | `5c7a8c4a-f846-4d1c-a81e-ea648893343a` | 주문 Agent |
| 모의 실행 번호 `run_id` | 실제 실행기는 `r_` 뒤에 `execution_id`에서 하이픈을 뺀 32자리 16진수, 간이 실행기는 `r_fake` 뒤에 영숫자 8자 | `r_8e45845774f141f59a5420bcbe2fdbc0` | `run_scenarios` |
| 연습 공간 번호 `workspace_id` | `w_` 뒤에 영숫자 8자 | `w_L0yTjI63` | `create_workspace` |
| 덩어리 번호 `hunk_id` | `h_` 뒤에 16진수 12자 | `h_8ab98c34b234` | `diff_skill` |
| 제안서 번호 `proposal_id` | `p_` 뒤에 32자리 16진수 | `p_3f9c0d2e8a114b7c9e5f6a7b8c9d0e1f` | `save_proposal` |
| 승인 번호 `approval_token` | `a_` 뒤에 10자 | `a_5XQ9kd02Lm` | 승인 API(MCP 도구 아님) |
| 시험 주문 번호 `scenario_id` | 기준 주문은 `g-01` 같은 `g-NN`, 재현 주문은 `f-` 뒤에 실패한 실행의 `execution_id` | `g-01`, `f-5c7a8c4a-f846-4d1c-a81e-ea648893343a` | 기준 주문 파일, `run_scenarios` |
| skill 번호 `skill_id` | 소문자, 숫자, 하이픈. snapshot의 `skills/<skill_id>/SKILL.md` 폴더 이름 | `inventory-check` | skill 폴더 |

진단 Agent는 `run_id`를 UUID로 바꿔 읽습니다(`skillops-agent/skillops_agent/contracts.py`의 `execution_uuid`). 그래서 간이 실행기의 `r_fake…` 번호로는 진단이 끝까지 돌지 않고, 진단에는 실제 실행기를 씁니다.

## 도구 사이를 잇는 번호

한 도구가 돌려준 번호를 다음 도구에 그대로 넣는 식으로 도구들이 이어집니다. Agent 구현에서 가장 먼저 맞춰야 할 부분입니다.

| 번호 | 처음 받는 곳 | 넣어 쓰는 곳 |
|---|---|---|
| `snapshot_id` | 실패 이벤트의 `snapshot.snapshot_id`와 `baseline_snapshot.snapshot_id`, 주문 Agent API | `diff_skill`의 `before_snapshot_id`·`after_snapshot_id`, `get_dependency_graph`, `create_workspace`, `save_proposal`의 `snapshot_id`·`baseline_snapshot_id` |
| `hunks[].diff` | `diff_skill` | `apply_patch`의 `patches[].patch`(Agent는 되돌리는 방향으로 뒤집어 넣습니다) |
| `hunks[].hunk_id` | `diff_skill` | `save_proposal`의 `root_cause.hunk_id` |
| `skill_versions` | `create_workspace` | `apply_patch`의 `patches[].base_version` |
| `workspace_id` | `create_workspace` | `apply_patch`, `run_scenarios`, `save_proposal`의 `candidates[].workspace_id` |
| `execution_id` | 실패 이벤트, 주문 Agent의 `GET /api/executions/failed` | `run_scenarios`의 `scenario_ids`(`f-<execution_id>`)나 `replay_run_ids` |
| `run_id` | `run_scenarios` 결과의 `results[].run_ids` | `save_proposal`의 `root_cause.proof_run_ids`·`candidates[].evidence_run_ids`, 주문 Agent의 실행 기록 조회 |
| `proposal_id` | `save_proposal` | 운영자 화면과 승인 API(`/api/proposals/{id}`, `/api/approvals`) |

## 주문 Agent에서 직접 읽는 값

아래 조회는 MCP 도구가 아닙니다. Agent가 `skillops-agent/skillops_agent/order_agent.py`의 함수로 주문 Agent(기본 `http://localhost:8081`)의 HTTP API를 직접 부르고, `tools.py`의 `MCPTools`가 같은 이름의 메서드로 감싸 둡니다. 요청과 응답 모양은 `order-agent/docs/diagnosis-read-api.md`에 있습니다.

| `MCPTools` 메서드 | 주문 Agent API | 무엇을 |
|---|---|---|
| `list_failed_runs` | `GET /api/executions/failed` | 실패한 실행 목록 |
| `get_trace` | `GET /api/executions/{execution_id}/trace` | 실행 한 번의 단계별 기록. `r_` 별칭은 UUID로 바꿔 보냅니다 |
| `list_skills` | `GET /api/snapshots/{snapshot_id}/skills` | snapshot 안의 skill 목록 |
| `get_skill` | `GET /api/snapshots/{snapshot_id}/skills/{skill_id}` | snapshot에 고정된 SKILL.md 원문 하나 |
| `get_snapshot` | `GET /api/snapshots/{snapshot_id}` | snapshot의 파일 목록(manifest) |

---

## 두 snapshot 비교하기 · `diff_skill`

같은 skill의 SKILL.md를 두 snapshot(기준과 실패) 사이에서 비교해 바뀐 곳만 뽑아 줍니다. 바뀐 곳은 줄 단위 덩어리(hunk)로 나뉘고, 덩어리마다 다시 조회해도 같은 값이 나오는 `hunk_id`와 그 덩어리 하나만 담은 unified diff가 붙습니다. Agent는 원인을 확인할 때 이 diff를 하나씩 되돌려 보는 재료로 씁니다. 실패 이벤트에 기준 snapshot이 없으면 부르지 않습니다.

부르는 쪽: Agent · 담당: C-1 · 구현: `SkillDiffTools.java`, 계산은 `service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java`와 `SkillDiffer.java`

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skill_id` | skill 번호 | 글자 | 예 | 비교할 skill입니다. snapshot의 `skills/<skill_id>/SKILL.md` 폴더 이름이고, 소문자·숫자·하이픈만 씁니다. 형식이 틀리면 주문 Agent를 부르기 전에 `INVALID_INPUT`입니다. |
| `before_snapshot_id` | 기준 snapshot | 글자(UUID) | 예 | 정상이던 쪽입니다. 보통 실패 이벤트의 `baseline_snapshot.snapshot_id`를 넣습니다. |
| `after_snapshot_id` | 비교 대상 snapshot | 글자(UUID) | 예 | 실패한 쪽입니다. 보통 실패 이벤트의 `snapshot.snapshot_id`를 넣습니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skill_id` | skill 번호 | 글자 | 예 | 보낸 값 그대로입니다. |
| `before` · `after` | 양쪽 snapshot | 묶음 | 예 | `{snapshot_id, snapshot_hash, path, file_hash}`입니다. `snapshot_hash`는 주문 Agent의 snapshot 해시, `file_hash`는 그 snapshot 안 SKILL.md의 해시입니다. 그 snapshot에 SKILL.md가 없으면 `path`와 `file_hash`가 `null`입니다. |
| `status` | 변경 상태 | 글자 | 예 | `modified`, `added`(기준에 없음), `removed`(비교 대상에 없음), `unchanged` 가운데 하나입니다. 두 파일 해시가 같으면 파일을 읽지 않고 `unchanged`를 돌려줍니다. |
| `frontmatter_changes` | 맨 위 칸 변화 | 목록 | 예 | 값이 달라진 frontmatter 키만 담습니다. 항목은 `{field, before, after, added, removed}`입니다. `before`·`after`는 YAML 값 그대로이고 키가 없던 쪽은 `null`입니다. `added`·`removed`는 값이 목록일 때 더해지거나 빠진 항목이고, 목록이 아니면 빈 목록입니다. |
| `hunks` | 변경 덩어리 | 목록 | 예 | 파일 위쪽 덩어리부터 들어 있습니다. 바뀐 곳이 없으면 빈 목록입니다. |
| `hunks[].index` | 표시 순서 | 정수 | 예 | 0부터 셉니다. 화면 표시용이라 고유 번호로 쓰지 않습니다. |
| `hunks[].hunk_id` | 덩어리 번호 | 글자 | 예 | 두 파일 해시, 위치, 내용으로 만든 고정 번호입니다. 다시 조회해도 같은 덩어리를 가리킵니다. |
| `hunks[].part` | 바뀐 부분 | 글자 | 예 | `frontmatter`는 맨 위 칸(받는 값, 내놓는 값 등), `body`는 본문입니다. |
| `hunks[].header` | 바뀐 줄 위치 | 글자 | 예 | `@@ -5,1 +5,1 @@` 모양의 머리줄입니다. "기준 파일 5번째 줄부터 1줄이 비교 대상 파일 5번째 줄부터 1줄로 바뀌었다"는 뜻입니다. |
| `hunks[].before` | 바뀌기 전 줄 | 글자 | 예 | 지워진 줄만 담습니다(앞뒤 문맥 줄은 뺍니다). 추가만 된 곳이면 빈 글자입니다. |
| `hunks[].after` | 바뀐 뒤 줄 | 글자 | 예 | 추가된 줄만 담습니다. 지워지기만 한 곳이면 빈 글자입니다. |
| `hunks[].diff` | 이 덩어리만 담은 수정 내용 | 글자 | 예 | `--- a/<path>`, `+++ b/<path>`, `@@` 머리줄 하나와 본문으로 된 unified diff입니다. 기준 파일에 단독으로 적용됩니다. |
| `frontmatter_parse_error` | 맨 위 칸 해석 오류 | 글자 | 아니오 | 어느 쪽이든 frontmatter YAML을 읽지 못했으면 그 이유입니다. 이때 `frontmatter_changes`는 비어 있고 `hunks`만 믿을 수 있습니다. 정상이면 `null`입니다. |
| `related_files` | 같은 폴더의 다른 파일 | 목록 | 예 | `skills/<skill_id>/` 아래 SKILL.md가 아닌 파일(`resources/` 등)을 경로순으로 담습니다. 항목은 `{path, status, before_hash, after_hash}`이고 해시로만 비교합니다. 파일이 없는 쪽의 해시는 `null`입니다. |

두 snapshot 모두에 SKILL.md가 없으면 `SKILL_NOT_FOUND`(`snapshot_id`는 `after_snapshot_id`) 오류입니다.

### 예시

```json
{
  "skill_id": "inventory-check",
  "before_snapshot_id": "11111111-1111-4111-8111-111111111111",
  "after_snapshot_id": "22222222-2222-4222-8222-222222222222"
}
```

```json
{
  "skill_id": "inventory-check",
  "before": { "snapshot_id": "11111111-1111-4111-8111-111111111111", "snapshot_hash": "<snapshot SHA-256>", "path": "skills/inventory-check/SKILL.md", "file_hash": "<파일 SHA-256>" },
  "after": { "snapshot_id": "22222222-2222-4222-8222-222222222222", "snapshot_hash": "<snapshot SHA-256>", "path": "skills/inventory-check/SKILL.md", "file_hash": "<파일 SHA-256>" },
  "status": "modified",
  "frontmatter_changes": [
    { "field": "outputs", "before": ["inventory.available", "inventory.reserved"], "after": ["inventory.예약가능수량"], "added": ["inventory.예약가능수량"], "removed": ["inventory.available", "inventory.reserved"] }
  ],
  "hunks": [
    {
      "index": 0,
      "hunk_id": "h_3c9e1f0a7b42",
      "part": "frontmatter",
      "header": "@@ -4,1 +4,1 @@",
      "before": "outputs:    [inventory.available, inventory.reserved]",
      "after": "outputs:    [inventory.예약가능수량]",
      "diff": "--- a/skills/inventory-check/SKILL.md\n+++ b/skills/inventory-check/SKILL.md\n@@ -4,1 +4,1 @@\n-outputs:    [inventory.available, inventory.reserved]\n+outputs:    [inventory.예약가능수량]\n"
    }
  ],
  "frontmatter_parse_error": null,
  "related_files": []
}
```

예시의 해시는 자리만 표시했습니다. 실제 값은 64자리 16진수입니다.

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| 주문 Agent의 snapshot | 읽기 | 두 snapshot의 manifest(`GET /api/snapshots/{id}`)와, 해시가 다를 때만 양쪽 SKILL.md 원문(`GET /api/snapshots/{id}/files`) |

서비스의 데이터베이스는 읽지도 쓰지도 않습니다.

---

## 연결 관계 보기 · `get_dependency_graph`

snapshot 안의 flow skill(보통 `order-flow`)과 그 `steps`에 적힌 단계 skill들이 서로 어떻게 이어지는지 계산합니다. 받아야 할 값을 주문 입력도, 앞 단계도 내놓지 않는 곳(끊긴 연결)도 따로 알려 줍니다. 바뀐 부분이 다른 skill에 어떤 영향을 주는지, 실패 이벤트의 빠진 입력이 어디서 끊겼는지 볼 때 씁니다.

판단 규칙은 주문 Agent의 정적 계약 검사(`order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java`)와 같습니다. `steps` 순서대로 각 skill의 `inputs`가 주문 입력 키(`order.id`, `order.items`, `order.customer`, `order.payment_method`, `order.shipping`)나 앞 단계의 `outputs`에 있는지, `depends_on`의 skill이 이미 지나갔는지 봅니다. 첫 위반에서 멈추지 않고 끝까지 계산합니다. SKILL.md 선언만 보고 계산하므로, 실제로 업무 도구가 실행됐는지는 실행 기록으로 확인해야 합니다.

부르는 쪽: Agent · 담당: C-1 · 구현: `DependencyGraphTools.java`, 계산은 `service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java`와 `DependencyAnalyzer.java`

**#72 1단계 입력 검증 변경(2026-10-23):** SKILL.md는 snapshot 분석과 공통 YAML 파서로 읽습니다. 여러 줄·따옴표 목록을 지원하며 workspace의 `inputs`/`outputs`/`steps`에 잘못된 타입이나 명시적 null이 있으면 거부합니다. 선언된 `version`은 1 이상의 정수여야 하며 문자열·0·음수·소수·null은 거부합니다. `snapshot_id`로 만든 workspace의 이러한 오류는 `INVALID_INPUT`이며 저장하지 않습니다. 운영 폴더 원문의 잘못된 값도 거부하고, 버전 누락의 기존 오류 분류는 유지합니다. 요청·성공 응답 필드는 바꾸지 않았습니다. 상세 규칙은 [frontmatter 계약](skill-frontmatter.md#service-yaml-해석-규칙-72--1단계)을 따릅니다.

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `snapshot_id` | 조사할 snapshot | 글자(UUID) | 예 | 보통 실패 이벤트의 `snapshot.snapshot_id`입니다. 진단 도중 운영본이 바뀌어도 같은 snapshot으로 계산하도록 번호를 고정해 넣습니다. |
| `entry_skill_id` | 시작 flow | 글자 | 예 | `steps`를 읽을 flow skill입니다. 예: `order-flow`. 형식이 틀리면 `INVALID_INPUT`, snapshot에 없으면 `SKILL_NOT_FOUND`, `steps`를 읽을 수 없으면 `INVALID_FLOW`입니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `snapshot_id` · `snapshot_hash` | snapshot | 글자 | 예 | 계산에 쓴 snapshot과 그 해시입니다. |
| `entry_skill_id` | 시작 flow | 글자 | 예 | 보낸 값 그대로입니다. |
| `steps` | 실행 순서 | 목록 | 예 | flow의 `steps`에 적힌 skill 번호를 순서대로 담습니다. |
| `nodes` | 단계 skill | 목록 | 예 | `steps` 순서대로 놓인 단계 skill입니다. 같은 skill이 두 번 적히면 노드도 두 개입니다. 항목은 `{skill_id, path, file_hash, inputs, outputs, depends_on, parse_error}`입니다. |
| `nodes[].parse_error` | 읽기 오류 | 글자 | 아니오 | snapshot에 파일이 없으면 `UNKNOWN_SKILL`, 맨 위 칸 형식이 깨졌으면 그 이유입니다. 정상이면 `null`입니다. 오류가 있는 노드는 `path`·`file_hash`가 `null`일 수 있고 `inputs`·`outputs`·`depends_on`은 빈 목록입니다. |
| `edges` | 연결 | 목록 | 예 | 항목은 `{type, from, to, keys}`입니다. `flow_order` 전부, `depends_on` 전부, `io` 전부의 순서로 묶여 있습니다. |
| `edges[].type` | 연결 종류 | 글자 | 예 | `flow_order`는 `steps`에서 이웃한 두 단계, `depends_on`은 선행 skill에서 이 skill로, `io`는 값을 내놓는 skill에서 받는 skill로 가는 연결입니다. |
| `edges[].keys` | 오가는 값 | 목록 | 예 | `io`일 때 넘어가는 값 이름입니다. 나머지 종류는 빈 목록입니다. 주문 입력 키는 공급자가 없으므로 연결로 그리지 않습니다. |
| `missing` | 끊긴 연결 | 목록 | 예 | 항목은 `{skill_id, missing_inputs, missing_dependencies}`입니다. `missing_inputs`는 받는다고 적었지만 아무도 내놓지 않는 값, `missing_dependencies`는 아직 지나가지 않은 `depends_on` skill입니다. 비어 있으면 연결이 모두 이어져 있습니다. |
| `ambiguous` | 공급자가 여럿인 값 | 목록 | 예 | 항목은 `{skill_id, key, providers}`입니다. 같은 값을 앞 단계 여럿이 내놓는 곳이고, `io` 연결은 가장 가까운(마지막) 공급자에서 긋습니다. |
| `complete` | 완전한 계산 | 참·거짓 | 예 | 읽지 못한 skill이 하나라도 있으면 `false`입니다. 이때 `missing`과 `edges`도 불완전합니다. Agent의 `generate_candidates`는 `false`면 멈춥니다. |

### 예시

```json
{ "snapshot_id": "22222222-2222-4222-8222-222222222222", "entry_skill_id": "order-flow" }
```

```json
{
  "snapshot_id": "22222222-2222-4222-8222-222222222222",
  "snapshot_hash": "<snapshot SHA-256>",
  "entry_skill_id": "order-flow",
  "steps": ["order-intake", "inventory-check", "payment", "shipping-reserve", "notify"],
  "nodes": [
    { "skill_id": "inventory-check", "path": "skills/inventory-check/SKILL.md", "file_hash": "<파일 SHA-256>", "inputs": ["order.id", "order.items"], "outputs": ["inventory.예약가능수량"], "depends_on": ["order-intake"], "parse_error": null }
  ],
  "edges": [
    { "type": "flow_order", "from": "order-intake", "to": "inventory-check", "keys": [] },
    { "type": "depends_on", "from": "inventory-check", "to": "payment", "keys": [] },
    { "type": "io", "from": "order-intake", "to": "payment", "keys": ["order.total"] }
  ],
  "missing": [
    { "skill_id": "payment", "missing_inputs": ["inventory.reserved"], "missing_dependencies": [] }
  ],
  "ambiguous": [],
  "complete": true
}
```

예시에는 노드 하나와 연결 일부만 적었습니다.

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| 주문 Agent의 snapshot | 읽기 | manifest와, manifest에 있는 entry flow 및 `steps` skill의 SKILL.md 원문 |

서비스의 데이터베이스는 읽지도 쓰지도 않습니다.

---

## 연습 공간 만들기 · `create_workspace`

운영 중인 skill은 그대로 두고 복사본(연습 공간)을 따로 만듭니다. 이후의 수정(`apply_patch`)과 시험(`run_scenarios`)은 모두 이 복사본에서만 일어납니다. Agent는 실험 하나마다 연습 공간을 새로 만들고 다시 쓰지 않습니다.

시작점은 둘 가운데 하나입니다.

| 시작점 | 넣는 값 | 복사하는 것 | 쓸 곳 |
|---|---|---|---|
| 주문 Agent snapshot | `snapshot_id` | 그 snapshot의 `skills/` 아래 파일 전부(`resources/` 같은 SKILL.md가 아닌 파일 포함) | 실제 실행기로 돌릴 연습 공간. Agent의 노드는 지금 언제나 이 방법으로 만듭니다 |
| 운영 폴더 | `start_versions` 또는 아무것도 없음 | 서비스 운영 폴더(`skills/`, 설정 `skillops.skills-dir`)의 SKILL.md | 간이 실행기와 개발용 확인(`skillops-agent/scripts/c2_smoke.py`) |

운영 폴더의 SKILL.md는 주문 Agent가 실행하는 글과 다르므로, 실제 실행기는 `snapshot_id`로 만든 연습 공간만 받습니다.

부르는 쪽: Agent · 담당: C-2 · 구현: `WorkspaceTools.java`의 `createWorkspace`, 본체는 `service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java`의 `create`

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `snapshot_id` | 시작 snapshot | 글자(UUID) | 아니오 | 주문 Agent가 보관한 skill 묶음의 번호입니다. 주면 그 snapshot의 skill 파일 전부로 시작합니다. |
| `start_versions` | 시작 버전 | 묶음 | 아니오 | skill 이름과 시작 버전 번호의 묶음입니다. 적지 않은 skill은 운영 버전으로 시작합니다. 지금 운영 폴더에는 버전마다 원문이 남아 있지 않아서, 운영 버전이 아닌 번호를 넣으면 `INVALID_INPUT`입니다. |

`snapshot_id`와 비어 있지 않은 `start_versions`는 함께 쓸 수 없습니다(`INVALID_INPUT`). 둘 다 없으면 운영 폴더의 운영 버전으로 시작합니다.

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 새로 만든 연습 공간의 번호입니다. 이 공간을 쓰는 다른 도구에 모두 이 번호를 넣습니다. |
| `skill_versions` | 시작 버전 | 묶음 | 예 | 연습 공간 안의 skill 전부와 각각의 시작 버전을 이름순으로 담습니다. `snapshot_id`로 만들었으면 각 SKILL.md 맨 위 칸의 `version` 값입니다. `apply_patch`의 `base_version`에 이 값을 넣습니다. |
| `created_at` | 만든 시각 | 시각 | 예 | 연습 공간을 만든 시각(UTC, 마이크로초까지)입니다. |

### SKILL 목록 null 해석 (#72 · 5단계)

`steps: null`, `steps: ~`, `steps:`는 빈 목록으로 읽습니다. 비어 있지 않은 steps를 가진 flow에서는 `inputs`·`outputs`의 같은 null 표현도 빈 목록으로 읽습니다. 일반 단계의 명시적 null 입출력과 잘못된 목록 타입·항목은 계속 INVALID_INPUT입니다. workspace 어댑터의 누락 목록 처리는 기존대로 유지하고 실제 단계의 필수 목록 검사는 분석기·실행기가 수행합니다.

정상 null 표현 때문에 snapshot 복사나 운영 폴더 버전 조회가 실패하던 차이를 해결했습니다. 해석 결과만 바꾸며 저장 원문·줄바꿈·버전은 바꾸지 않습니다. **workspace 생성 성공은 실행 성공을 보장하지 않습니다.** root steps가 비어 있으면 run_scenarios 실행 및 entry 분석에서 계속 거부합니다.

도구 요청·응답 필드는 그대로이며 apply_patch v1.3 계약 버전도 유지합니다. [SKILL 규칙](skill-frontmatter.md#목록-null-호환성-72--5단계) · [검증 결과](../changes/2026-10-23-skill-list-null-compatibility.md).

### 예시

주문 Agent의 snapshot으로 만드는 경우입니다.

```json
{ "snapshot_id": "b6678449-9819-4d31-8c1b-13fc57892e5c" }
```

```json
{
  "workspace_id": "w_C31pQx7Z",
  "skill_versions": { "inventory-check": 1, "notify": 1, "order-flow": 1, "order-intake": 1, "payment": 1, "shipping-reserve": 1 },
  "created_at": "2026-10-21T04:11:52.402113Z"
}
```

그 snapshot이 주문 Agent에 없으면 주문 Agent가 준 오류가 그대로 옵니다.

```json
{ "code": "SNAPSHOT_NOT_FOUND", "message": "실행 자료를 확인할 수 없습니다: SNAPSHOT_NOT_FOUND", "retryable": false, "upstream_status": 404, "snapshot_id": "b6678449-9819-4d31-8c1b-13fc57892e5d" }
```

운영 폴더에서 만드는 경우는 빈 묶음 `{}`을 보냅니다. 결과 모양은 같습니다.

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| 주문 Agent의 snapshot | 읽기 | `snapshot_id`로 만들 때 manifest와 `skills/` 아래 파일 전부. 주문 Agent에는 아무것도 쓰지 않습니다 |
| 운영 폴더 `skills/` | 읽기 | `snapshot_id` 없이 만들 때 각 skill의 SKILL.md(`service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java`) |
| `workspaces` | 쓰기 | 연습 공간 한 줄. 번호, 만든 시각, 상태 `OPEN`, `skills` 칸(skill별 시작 버전, 원문, 수정 횟수), `resources` 칸(SKILL.md가 아닌 파일의 글), `source_snapshot_id` 칸(시작한 snapshot 번호, 운영 폴더면 `null`) |

---

## 수정 적용하기 · `apply_patch`

연습 공간 안의 SKILL.md에 unified diff를 넣습니다. 고친 뒤에는 `order-flow` 기준으로 skill 사이의 연결을 검사해서, 끊긴 곳이 있으면 목록으로 알려 줍니다. 운영 중인 skill에는 아무 영향이 없습니다. 고치는 대상은 SKILL.md뿐이고, `resources/` 같은 다른 파일은 그대로 둡니다.

Agent는 원인을 확인할 때(`_ablate_change`) 바뀐 덩어리 하나를 되돌리는 수정을 넣고, 후보를 시험할 때(`_verify_candidate`) 후보 수정안 전체를 넣습니다.

부르는 쪽: Agent · 담당: C-2 · 구현: `WorkspaceTools.java`의 `applyPatch`, 본체는 `WorkspaceService.java`의 `apply`, diff 읽기는 `service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java` · 응답 v1.3 확장(#72)

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 고칠 연습 공간입니다. 상태가 `OPEN`이어야 합니다. |
| `patches` | 수정 내용 목록 | 목록 | 예 | 넣을 수정 내용입니다. 하나 이상이어야 하고, 적힌 순서대로 적용합니다. |
| `patches[].skill` | 고칠 skill | 글자 | 예 | 고칠 skill 이름입니다. 연습 공간에 있어야 합니다. |
| `patches[].base_version` | 기준 버전 | 정수 | 예 | 이 수정 내용을 만들 때 기준으로 삼은 버전입니다. 앞서 같은 skill을 고쳤더라도 연습 공간의 **시작 버전**(`create_workspace`의 `skill_versions`)과 같아야 합니다. 다르면 엉뚱한 줄을 고칠 수 있어서 거부합니다. |
| `patches[].patch` | 수정 내용 | 글자 | 예 | `diff_skill`의 `hunks[].diff`와 같은 형식의 unified diff입니다. `@@ -줄,개수 +줄,개수 @@`로 시작하는 덩어리가 있어야 하고, `---`·`+++` 머리줄은 없어도 됩니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 보낸 값 그대로입니다. |
| `changed_skills` | 고친 skill | 목록 | 예 | 실제로 고쳐진 skill입니다. 항목마다 `{skill, version}`입니다. |
| `changed_skills[].skill` | skill 이름 | 글자 | 예 | 고쳐진 skill의 이름입니다. |
| `changed_skills[].version` | 고친 뒤 버전 | 정수 | 예 | 시작 버전에 그 skill에 수정이 들어간 횟수를 더한 값입니다. 처음 한 번 고치면 시작 버전 + 1입니다. |
| `broken_links` | 입력 공급 누락 | 목록 | 예 | 전체 flow에서 주문 입력·앞선 outputs가 공급하지 않는 키입니다. 기존 `{skill, missing_input}` 형식을 유지합니다. 이 목록만 비었다고 선행 단계도 정상인 것은 아닙니다. |
| `dependency_violations` | 선행 단계 누락 | 목록 | 예 | 전체 flow에서 depends_on에 선언했지만 앞서 완료되지 않은 단계입니다. 오류가 없어도 `[]`를 반드시 반환합니다. |
| `dependency_violations[].skill` | 위반한 skill | 글자 | 예 | 선행 단계를 필요로 하는 skill입니다. |
| `dependency_violations[].missing_dependency` | 빠진 선행 단계 | 글자 | 예 | steps에 없거나 뒤에 있거나 자기 자신이어서 앞서 완료되지 않은 skill입니다. |
| `metadata_errors` | 필수 정보 오류 | 목록 | 예 | 전체 workspace의 오류입니다. 오류가 없어도 `[]`를 반환합니다. 항목은 `{skill, field, code, message}`입니다. |
| `metadata_errors[].skill` | 대상 Skill | 글자 | 예 | 오류가 있는 Skill의 폴더 이름입니다. |
| `metadata_errors[].field` | 오류 필드 | 열거 | 예 | `name`, `version`, `owner`, `description`, `body`, `frontmatter`입니다. |
| `metadata_errors[].code` | 실행기 오류 코드 | 열거 | 예 | `INVALID_SKILL_FORMAT` 또는 폴더 이름 규칙 오류인 `INVALID_SKILL_NAME`입니다. |
| `metadata_errors[].message` | 오류 설명 | 글자 | 예 | 비어 있지 않은 설명입니다. |
| `flow_errors` | 실행 구조 오류 | 목록 | 예 | `{skill, code, reason, message}` 목록이며 정상일 때도 `[]`를 반드시 반환합니다. |
| `flow_errors[].skill` | 대상 Skill | 글자 | 예 | 중복·읽기 실패 단계의 이름입니다. 단계 수·분석 전체 오류는 entry 이름입니다. |
| `flow_errors[].code` | 실행기 오류 종류 | 글자 | 예 | 아래 사유별 코드 조합을 따릅니다. |
| `flow_errors[].reason` | 구조 오류 사유 | 글자 | 예 | 아래 표의 사유를 따릅니다. |
| `flow_errors[].message` | 오류 설명 | 글자 | 예 | 비어 있지 않은 설명입니다. |

두 목록은 변경한 skill만이 아니라 entry `order-flow.steps` 전체의 분석 결과를 사용하고, 분석 결과와 선언 순서를 보존합니다. 입력·선행 단계 위반이 있어도 patch는 workspace에 저장되므로 원인 재현 실험에 사용할 수 있습니다. **저장 성공과 후보 합격은 별개**입니다.

Python의 `no_broken_links`는 `broken_links == []`와 `dependency_violations == []`를 모두 만족해야 참입니다. 두 필드는 필수이며 누락·null·목록이 아닌 값·잘못된 오류 항목은 `ContractError("invalid apply_patch response")`입니다. 구버전 서버 응답의 새 필드 누락을 빈 목록으로 보충하지 않습니다. 이 연결 판정은 필수 정보 판정과 별개이며 실제 실행 성공 전체를 보장하지 않습니다.

필수 정보는 `SkillMetadataValidator`가 **수정하지 않은 Skill과 flow 밖의 Skill을 포함한 전체 workspace**에서 검사합니다. 실제 주문 Agent가 후보 목록을 만들 때 모든 SKILL을 읽기 때문입니다. `name`은 폴더 이름과 같아야 하고, `version`은 1 이상의 Java Integer, `owner`·`description`은 비공백 문자열, 본문은 strip 후 비어 있지 않아야 합니다. 실행용 구분선은 LF/CRLF로 읽고 닫는 `---` 뒤 줄바꿈이 필요하며 YAML 상한은 100,000 코드 포인트입니다. 상세 기준과 한계는 [SKILL 계약](skill-frontmatter.md#필수-정보-검사-72--3단계)을 따릅니다.

Python은 `metadata_errors == []`일 때만 `results.metadata_valid=true`로 기록합니다. 오류 목록 자체가 없거나 잘못되면 평가 전에 `ContractError`로 중단합니다. 필수 정보 오류가 있어도 기존 patch 거부 조건에 걸리지 않으면 실험용 수정은 저장되고 시나리오는 실행합니다. 실행이 전부 통과해도 `metadata_valid=false`면 후보는 무효이며 추천하지 않습니다. 오류 상세는 후보의 `metadata_errors`에 보존합니다.

실행 구조는 `FlowStructureValidator`가 진단용 그래프에서 별도로 검사합니다. 그래프의 단계·노드·edge를 수정하지 않습니다. `complete=true`는 선언을 읽었다는 뜻이며 중복 단계나 단계 수 제한까지 통과했다는 뜻은 아닙니다.

| `reason` | `code` | 조건 |
|---|---|---|
| `DUPLICATE_STEP` | `INVALID_FLOW` | 같은 Skill이 steps에 두 번 이상 등장. Skill별 한 번만 보고합니다. |
| `TOO_MANY_STEPS` | `INVALID_FLOW` | steps가 12개를 초과. entry 이름을 대상으로 보고합니다. |
| `EMPTY_STEPS` | `INVALID_FLOW` | 빈 그래프를 직접 검사할 때의 방어 조건. 현재 apply_patch는 빈 entry를 기존 INVALID_INPUT 경로로 먼저 거부합니다. |
| `INVALID_SKILL_NAME` | `INVALID_SKILL_NAME` | 단계 이름이 주문 Agent 이름 규칙에 어긋납니다. |
| `UNKNOWN_SKILL` | `UNKNOWN_SKILL` | 실행 단계의 파일이 없습니다. |
| `UNREADABLE_SKILL` | `INVALID_SKILL_FORMAT` | 단계의 선언을 읽을 수 없거나 필수 목록 형식 등이 잘못됐습니다. |
| `NESTED_FLOW_NOT_SUPPORTED` | `CONTRACT_VIOLATION` | 단계에 또 다른 flow가 들어 있습니다. |
| `INCOMPLETE_ANALYSIS` | `INVALID_FLOW` | 분석이 불완전한데 구체적 오류가 없거나, steps와 노드의 순서·수가 맞지 않습니다. service가 합격을 막는 방어 조건입니다. |

오류 순서는 단계 수, 중복 단계의 발견 순서, 읽기 실패 노드의 실행 순서, 방어 조건 순입니다. 같은 단계의 읽기 실패는 한 번만 보고합니다. 읽지 못한 노드가 있으면 `complete=false`의 원인이 `flow_errors`에 보존되므로, 입력·선행 단계 누락이 없어도 정상 후보로 보지 않습니다.

Python은 `flow_errors == []`일 때만 `results.flow_valid=true`입니다. 네 오류 목록의 형식은 후보 검증과 원인 실험(ablation) 모두에서 검사하지만, 원인 실험에서는 비어 있지 않은 오류 목록 자체를 적용 실패로 바꾸지 않습니다. 후보는 실제 시나리오 실행 후에도 flow_valid가 거짓이면 무효이고 추천되지 않습니다. 상세 오류는 제안서 후보의 `flow_errors`로 보존합니다.

기존 거부 정책도 유지합니다. entry steps를 읽을 수 없거나 변경한 단계의 name·목록이 기존 분석기에서 parse error가 되면 `INVALID_INPUT`으로 전체 patch를 거부하며 성공 payload의 `metadata_errors`나 `flow_errors`를 반환하지 않습니다. 필수 정보 오류를 모두 저장하도록 기존 거부 정책까지 완화한 것은 아닙니다.

수정 내용 가운데 하나라도 적용하지 못하면 전부 취소하고 `INVALID_INPUT`을 돌려줍니다. 연습 공간은 보내기 전 상태 그대로 남습니다. 같은 skill에 수정을 여러 번 넣으면 앞의 수정이 들어간 글 위에 차례로 적용합니다. 끊긴 연결이 있어도 수정은 들어가고, 그 수정을 쓸지는 부른 쪽이 정합니다. Agent의 `_judge`는 `broken_links`가 비어 있어야 후보를 합격으로 봅니다. 다만 고친 skill의 맨 위 칸을 읽을 수 없게 되는 수정과, `order-flow`의 `steps`를 읽을 수 없게 되는 수정은 거부합니다.

**한 skill의 덩어리를 여러 개 한꺼번에 넣을 때.** 덩어리마다 받은 diff를 이어 붙여 `patches` 한 칸으로 보낼 수 있습니다. 덩어리의 줄 번호는 모두 "바뀌기 전 파일" 기준이라 그대로 이으면 됩니다. 다만 덩어리마다 붙어 온 `---`·`+++` 머리줄은 **첫 덩어리 것만 남기고** 지워야 합니다. 중간에 머리줄이 또 나오면 "지우는 줄"로 읽혀 원문과 맞지 않는다는 `INVALID_INPUT`으로 거부됩니다.

### 예시

```json
{
  "workspace_id": "w_A17xYz9Q",
  "patches": [
    {
      "skill": "inventory-check",
      "base_version": 1,
      "patch": "@@ -4,1 +4,1 @@\n-outputs:    [inventory.available, inventory.reserved]\n+outputs:    [inventory.예약가능수량]\n"
    }
  ]
}
```

```json
{
  "workspace_id": "w_A17",
  "changed_skills": [
    {
      "skill": "inventory-check",
      "version": 2
    }
  ],
  "broken_links": [
    {
      "skill": "payment",
      "missing_input": "inventory.reserved"
    }
  ],
  "dependency_violations": [],
  "metadata_errors": [],
  "flow_errors": []
}
```

입력 공급은 정상이어도 `payment.depends_on: [notify]`인데 notify가 뒤에 있으면 다음처럼 반환합니다. patch 저장은 성공하고 Python 후보 판정의 `no_broken_links`는 거짓이 됩니다.

```json
{
  "workspace_id": "w_abc",
  "changed_skills": [
    {
      "skill": "payment",
      "version": 2
    }
  ],
  "broken_links": [],
  "dependency_violations": [
    {
      "skill": "payment",
      "missing_dependency": "notify"
    }
  ],
  "metadata_errors": [],
  "flow_errors": []
}
```

필수 정보만 잘못된 예: owner를 삭제한 patch도 저장할 수 있지만 아래 오류를 반환합니다. `no_broken_links=true`, `metadata_valid=false`, `valid=false`입니다.

```json
{
  "workspace_id": "w_abc",
  "changed_skills": [
    {
      "skill": "payment",
      "version": 2
    }
  ],
  "broken_links": [],
  "dependency_violations": [],
  "metadata_errors": [
    {
      "skill": "payment",
      "field": "owner",
      "code": "INVALID_SKILL_FORMAT",
      "message": "owner는 비어 있지 않은 문자열이어야 합니다"
    }
  ],
  "flow_errors": []
}
```

중복 단계만 있는 경우의 응답입니다. 연결·필수 정보 오류는 없지만 `flow_valid=false`, `valid=false`입니다. patch는 실험용으로 저장됩니다.

```json
{
  "workspace_id": "w_abc",
  "changed_skills": [
    {
      "skill": "order-flow",
      "version": 2
    }
  ],
  "broken_links": [],
  "dependency_violations": [],
  "metadata_errors": [],
  "flow_errors": [
    {
      "skill": "payment",
      "code": "INVALID_FLOW",
      "reason": "DUPLICATE_STEP",
      "message": "같은 단계를 여러 번 실행할 수 없습니다"
    }
  ]
}
```

기준 버전이 맞지 않는 경우의 오류입니다.

```json
{ "code": "INVALID_INPUT", "message": "inventory-check 의 기준 버전이 맞지 않습니다 (연습 공간 v1, 수정 내용 v2)", "retryable": false, "upstream_status": null, "snapshot_id": null }
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `workspaces` | 읽기·쓰기 | 연습 공간이 있고 `OPEN`인지 확인하고, `skills` 칸의 원문을 읽어 고친 원문과 늘어난 수정 횟수로 바꿉니다. 모든 검사를 통과한 뒤 한 번만 저장합니다 |

### v0에서 바뀐 점

- `ws_id`를 `workspace_id`로, `patches[].diff`를 `patches[].patch`로 바꿨습니다.
- 결과의 `applied`를 `changed_skills`로, `contract_violations`를 `broken_links`로 바꿨습니다.
- (#55) `changed_skills`는 이름만이 아니라 `{skill, version}` 목록입니다. 구현을 마쳤습니다.
- (#72 4단계) 필수 `flow_errors`와 후보의 `flow_valid`를 추가했습니다. 진단용 그래프 형식과 기존 거부 정책은 유지합니다.
- (#72 3단계) 필수 `metadata_errors`를 추가하고 후보 결과 `metadata_valid`로 연결했습니다.
- (#72) `dependency_violations`를 추가했습니다. `broken_links`는 입력 누락만 유지하며 새 Python은 두 목록을 함께 검사합니다.

---

## 시험 주문 돌리기 · `run_scenarios`

연습 공간의 skill로 시험 주문을 모의 실행하고, 결과가 기대한 대로인지 채점합니다. 모의 실행이라 실제 결제나 배송은 일어나지 않습니다. 같은 주문을 정해진 횟수만큼 돌려서 모두 통과해야 합격입니다. AI는 고칠 방법을 제안만 하고, 합격 여부는 이 도구의 실행 결과로 정합니다.

부르는 쪽: Agent · 담당: C-2 · 구현: `WorkspaceTools.java`의 `runScenarios`, 본체는 `service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java`, 채점은 `ScenarioGrader.java`

### 실행기는 둘 중 하나입니다

서비스를 켤 때 설정 `skillops.flow-runner`(환경 변수 `SKILLOPS_FLOW_RUNNER`)로 정합니다. 도구를 부르는 쪽은 고를 수 없습니다.

| 실행기 | 설정값 | 어떻게 돌리나 | 쓸 곳 |
|---|---|---|---|
| 간이 실행기 | `contract-only` (기본) | SKILL.md 맨 위 칸(받는 값, 내놓는 값)만 보고 흉내 냅니다. 주문 Agent 없이 돌고, 재현 주문은 받지 않습니다. 실행 번호는 `r_fake…`입니다 | 주문 Agent 없이 하는 개발과 단위 확인 |
| 실제 실행기 | `harness` | 연습 공간의 파일 전부를 후보 snapshot으로 주문 Agent에 올리고(`POST /api/snapshots/candidates`), 시험 주문마다 모의 실행(`POST /api/dry-runs`)한 뒤 실행 기록(`GET /api/executions/{execution_id}/trace`)을 읽어 채점합니다 | 진단. 후보가 실제 주문 Agent에서 고쳐지는지 판정 |

**#72 1단계 간이 실행 검증 변경(2026-10-23):** `contract-only`도 공통 YAML 파서로 여러 줄 `steps`와 입출력 목록을 읽습니다. `order-flow.steps` 누락·빈 목록·null·잘못된 타입은 `INVALID_INPUT`으로 거부하고 아무 단계도 실행하지 않습니다. 빈 순서를 `SHIPPED`로 반환하지 않습니다. 이 검사는 간이 실행기의 입력 검증이며 실제 실행기의 추가 규칙과 같은 수준의 검증 완료를 뜻하지 않습니다.

**#72 5단계 null 호환성(2026-10-23):** 일반 단계의 steps null과 비어 있지 않은 flow의 선택 입출력 null은 빈 목록으로 읽습니다. 일반 단계의 명시적 null 입출력은 계속 거부합니다. root steps 누락·null·[]는 실행 전에 거부하며, 이를 SHIPPED로 반환하지 않습니다. 기존 정상 다섯 단계에 허용된 null 표현을 넣어도 간이 실행 g-01의 기대 결과가 유지되는지 검증했습니다.

실제 실행기에서 알아 둘 점은 아래와 같습니다.

| 항목 | 내용 |
|---|---|
| 연습 공간 | `snapshot_id`로 만든 연습 공간만 받습니다. 운영 폴더에서 만든 연습 공간은 `INVALID_INPUT`입니다 |
| 실제 주문에 주는 영향 | 없습니다. 모의 실행은 도는 동안 재고를 잡았다가 끝나면 되돌립니다. 모의 실행의 실패는 주문 Agent의 실패 목록에 나오지 않습니다 |
| 돌리는 순서 | 재고를 잡기 때문에 시험 주문을 한 번에 하나씩 차례로 돌립니다 |
| 같은 파일 묶음 | 같은 연습 공간 파일을 다시 올리지 않고 먼저 올린 후보 snapshot을 씁니다 |
| 연결이 끊긴 skill이 있을 때 | 주문 Agent는 주문을 처리하기 전에 흐름 전체의 연결부터 검사합니다. 끊긴 곳이 하나라도 있으면 그 주문은 단계 없이 `FAILED`로 끝나고, 떨어진 이유에는 문제가 난 skill의 `CONTRACT_VIOLATION`과 빠진 값 이름이 나옵니다 |
| 주문 Agent 오류 | 닿지 못하거나 5xx면 `INTERNAL_ERROR`, 요청을 거절(4xx)하면 `INVALID_INPUT`입니다 |

주문 Agent의 주소는 설정 `skillops.order-agent.base-url`(환경 변수 `ORDER_AGENT_BASE_URL`, 기본 `http://localhost:8081`)입니다.

### 기준 주문 폴더

기준 주문은 서비스가 켜질 때 `<skillops.scenarios-dir>/golden/*.json`을 모두 읽어 둡니다. 폴더는 설정 `skillops.scenarios-dir`(환경 변수 `SKILLOPS_SCENARIOS_DIR`, 기본 `../scenarios`)로 바꿉니다. 파일 모양은 계약 3(`docs/contracts/scenario.schema.json`)과 같고, `expect`에서 비워 둔 칸은 채점하지 않습니다.

| 폴더 | 내용 | 어느 실행기에 맞나 |
|---|---|---|
| `scenarios/golden/` (기본) | D가 쓰는 기준 주문(지금은 `g-01` 하나). 주문 Agent에 없는 상품을 쓰는 간이 실행기용 주문 | 간이 실행기 |
| `scenarios/order-agent/golden/` | 주문 Agent의 시연 규칙에 맞춘 `g-01`~`g-03`. `g-01` 정상 주문(`SHIPPED`, 배송 1건), `g-02` 재고 없음(`REJECTED`), `g-03` 결제가 거절되는 고객(`HOLD`) | 실제 실행기 |

주문 Agent에서 돌릴 때는 서비스를 이렇게 켭니다.

```bash
cd service
SKILLOPS_FLOW_RUNNER=harness SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent ./gradlew bootRun
```

### 재현 주문

주문 Agent에서 **실제로 실패했던 주문**을 그대로 다시 돌립니다. 실패한 실행의 `execution_id`로 실행 기록에서 주문 번호를 찾고, `GET /api/orders/{id}`로 원래 주문(손님, 상품과 수량, 결제 방법, 배송)을 꺼내 모의 실행합니다. 넣는 방법은 둘 중 하나입니다. Agent는 첫째 방법만 씁니다.

| 넣는 곳 | 넣는 값 | 결과의 `scenario_id` | 결과의 `replay_of_run_id` |
|---|---|---|---|
| `scenario_ids` | `f-<execution_id>` (하이픈이 있는 UUID) | 넣은 그대로 | `f-` 뒤의 글자 그대로 |
| `replay_run_ids` | `execution_id` 또는 `run_id`(`r_` + 32자리 16진수) | `f-` 뒤에 소문자 `execution_id` | 넣은 값 그대로 |

재현 주문의 합격 기준은 **오류로 멈추지 않는 것**입니다. 최종 상태가 `FAILED`가 아니면(`SHIPPED`, `REJECTED`, `HOLD` 무엇이든) 통과이고, `extra_checks`는 재현 주문에도 똑같이 붙습니다. 재현 주문은 실제 실행기에서만 돌릴 수 있습니다.

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 어느 연습 공간의 skill로 돌릴지입니다. |
| `scenario_ids` | 시험 주문 번호 | 목록 | 아니오 | 돌릴 시험 주문의 번호입니다. 기준 주문은 기준 주문 폴더에 있는 `g-NN`, 재현 주문은 `f-<execution_id>`입니다. `scenario_ids`와 `replay_run_ids` 가운데 하나는 넣어야 합니다. |
| `replay_run_ids` | 재현할 실행 번호 | 목록 | 아니오 | 주문 Agent에서 실패했던 실행의 `execution_id` 또는 `run_id`입니다. 실제 실행기에서만 받습니다. |
| `extra_checks` | 추가 확인 조건 | 목록 | 아니오 | 이번 시험에만 덧붙이는 확인 조건입니다. 모든 시험 주문에 똑같이 붙으므로, 어떤 주문에서도 성립해야 하는 조건만 넣습니다. 담당자의 의도를 지켰는지 보는 조건을 여기에 넣습니다. |
| `repeat` | 반복 횟수 | 정수 | 아니오 | 같은 주문을 몇 번 돌릴지입니다(1~5). 비우면 2번입니다. AI가 처리하는 단계가 있어서 한 번의 통과는 우연일 수 있기 때문에 여러 번 돌립니다. |

확인 조건은 `값 이름 비교 값` 모양의 한 줄입니다. 비교는 `==`(같다), `!=`(다르다), `in`(목록 안에 있다) 세 가지만 씁니다. 예: `payment.status == 'PAID'`, `inventory.예약가능수량 != null`. 문법은 `service/src/main/java/com/capstone4/skillops/scenario/Check.java`가 정합니다.

입력은 주문을 하나라도 돌리기 전에 모두 검사합니다. 검사 순서는 `repeat` 범위, 빈 입력, 확인 조건 문법, 연습 공간, 시험 주문 번호와 재현 번호, 실행기의 재현 지원, 원래 주문 꺼내기입니다. 여러 입력이 함께 틀리면 먼저 검사한 쪽의 오류가 나가고, 모두 `INVALID_INPUT`이며 주문은 돌리지 않습니다.

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 보낸 값 그대로입니다. |
| `all_passed` | 모두 합격 | 참·거짓 | 예 | 돌린 시험 주문이 전부 합격이면 `true`입니다. |
| `results` | 주문별 결과 | 목록 | 예 | 시험 주문 하나마다 한 칸입니다. `scenario_ids`에 넣은 순서대로, 그 뒤에 `replay_run_ids` 순서로 나옵니다. |
| `results[].scenario_id` | 시험 주문 번호 | 글자 | 예 | 기준 주문이면 `g-NN`, 재현 주문이면 `f-<execution_id>`입니다. |
| `results[].replay_of_run_id` | 재현한 실행 번호 | 글자 | 아니오 | 재현 주문이면 위 표의 값, 기준 주문이면 `null`입니다. |
| `results[].passed` | 합격 | 참·거짓 | 예 | 반복한 실행이 모두 통과했으면 `true`입니다. |
| `results[].run_ids` | 실행 번호 | 목록 | 예 | 반복할 때마다 생긴 모의 실행의 번호이고, `repeat` 개입니다. 제안서의 근거로 이 번호를 적습니다. 실제 실행기의 번호는 `r_`를 떼고 하이픈을 넣으면 주문 Agent의 `execution_id`가 되므로, 그 번호로 실행 기록을 볼 수 있습니다. |
| `results[].failures` | 떨어진 이유 | 목록 | 예 | 통과하지 못한 실행마다 한 칸입니다. 합격이면 빈 목록입니다. |
| `results[].failures[].run_id` | 실행 번호 | 글자 | 예 | 통과하지 못한 실행입니다. 같은 결과의 `run_ids` 안에 있습니다. |
| `results[].failures[].skill` | 문제가 난 skill | 글자 | 아니오 | 처음 오류가 난 단계의 skill입니다. 오류 없이 결과만 다른 경우처럼 특정할 수 없으면 `null`입니다. |
| `results[].failures[].reason` | 이유 | 글자 | 예 | 사람이 읽을 한국어 문장입니다. 여러 개면 `; `로 잇습니다. 오류가 난 단계가 있으면 `<skill> 단계 오류: <오류>`가 맨 앞에 옵니다. 확인 조건이 실패하면 `확인 조건 실패: <조건> (실제 <값>)`입니다. 문구는 `ScenarioGrader.java`가 정합니다. |

**합격 기준.** 기준 주문은 기대 결과(거친 skill 순서, 최종 상태, 배송 건수, 파일의 확인 조건)와 모두 맞아야 하고, `extra_checks`도 통과해야 합니다. 재현 주문은 오류로 멈추지 않고 `extra_checks`를 통과하면 합격입니다. `extra_checks`는 모든 주문에 붙으므로, 한 후보가 의도 확인 조건 때문에 기준 주문까지 불합격으로 나올 수 있습니다. 그때 `failures[].reason`에는 `확인 조건 실패: …`만 있습니다.

모의 실행이 실패해도 실패 알림은 보내지 않습니다.

### 예시

실제 실행기에서 기준 주문 세 개와 실패했던 주문 하나를 돌리는 경우입니다. 연습 공간은 `snapshot_id`로 만들어 `apply_patch`로 후보를 넣은 상태입니다. `skillops-agent/scripts/c2_real_judge.py` 실험에서 나온 결과입니다.

```json
{
  "workspace_id": "w_L0yTjI63",
  "scenario_ids": ["g-01", "g-02", "g-03", "f-5c7a8c4a-f846-4d1c-a81e-ea648893343a"],
  "extra_checks": ["inventory.reservable_quantity != null"],
  "repeat": 1
}
```

```json
{
  "workspace_id": "w_L0yTjI63",
  "all_passed": true,
  "results": [
    { "scenario_id": "g-01", "replay_of_run_id": null, "passed": true, "run_ids": ["r_8e45845774f141f59a5420bcbe2fdbc0"], "failures": [] },
    { "scenario_id": "g-02", "replay_of_run_id": null, "passed": true, "run_ids": ["r_62037bf3ca86416fa80d1bb96d52b852"], "failures": [] },
    { "scenario_id": "g-03", "replay_of_run_id": null, "passed": true, "run_ids": ["r_c39fbef1932641bc9fd751f156b7e229"], "failures": [] },
    { "scenario_id": "f-5c7a8c4a-f846-4d1c-a81e-ea648893343a", "replay_of_run_id": "5c7a8c4a-f846-4d1c-a81e-ea648893343a", "passed": true, "run_ids": ["r_690d2a05a07749c9addc252e9964c395"], "failures": [] }
  ]
}
```

떨어진 실행이 있으면 `failures`가 이렇게 채워집니다.

```json
{ "scenario_id": "g-01", "replay_of_run_id": null, "passed": false, "run_ids": ["r_0b6f2d9c1e3a4f5b8c7d6e5f4a3b2c1d", "r_1c7e3a0d2f4b4a6c9d8e7f6a5b4c3d2e"], "failures": [
  { "run_id": "r_0b6f2d9c1e3a4f5b8c7d6e5f4a3b2c1d", "skill": "payment", "reason": "payment 단계 오류: required input inventory.reserved not found; 최종 상태 기대 SHIPPED, 실제 FAILED" }
] }
```

잘못된 확인 조건을 보낸 경우의 오류입니다.

```json
{ "code": "INVALID_INPUT", "message": "확인 조건을 읽을 수 없습니다: payment.status = PAID (예: payment.status == 'PAID')", "retryable": false, "upstream_status": null, "snapshot_id": null }
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `workspaces` | 읽기 | 어떤 skill 원문으로 돌릴지(`skills` 칸). 실제 실행기는 `resources` 칸과 `source_snapshot_id` 칸도 읽습니다 |
| 기준 주문 파일 | 읽기 | 서비스가 켜질 때 `<SKILLOPS_SCENARIOS_DIR>/golden/*.json`에서 불러 둔 주문과 기대 결과(`ScenarioCatalog.java`). 데이터베이스에는 두지 않습니다 |
| 주문 Agent (실제 실행기만) | 읽기·쓰기 | 후보 snapshot 올리기, 모의 실행, 실행 기록 읽기, 재현할 원래 주문 꺼내기. 모의 실행 기록은 주문 Agent 쪽에 남고 실제 재고와 실패 목록은 바뀌지 않습니다 |
| `scenario_runs` | 쓰기 | 모의 실행 한 번마다 한 줄(`run_id`, `workspace_id`, `scenario_id`, `passed`, `recorded_at`). `save_proposal`이 근거 실행을 대조할 때 읽습니다. 떨어진 이유는 저장하지 않고 도구 결과로만 돌려줍니다 |

---

## 제안서 저장하기 · `save_proposal`

진단을 마친 결과를 수정 제안서로 저장하고 운영자의 검토를 기다리게 합니다. 서비스는 근거로 적은 실행 번호가 실제로 `run_scenarios`로 돌린 모의 실행인지 확인하고, 합격 후보를 시험 결과로 다시 계산한 뒤에만 저장합니다. 시험을 거치지 않은 수정안이 운영자 화면에 올라가는 일을 막기 위해서입니다. 저장할 때 운영자가 읽을 본문도 함께 만들어 보관합니다.

제안서 묶음의 모양은 계약 4(`docs/contracts/proposal.schema.json`)입니다. Agent는 `skillops-agent/skillops_agent/nodes.py`의 `proposal_payload`로 이 묶음을 만듭니다.

부르는 쪽: Agent · 담당: C-2 · 구현: `ProposalTools.java`, 본체는 `service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java`의 `save`, 거부 규칙은 `ProposalValidator.java`, 합격 조건은 `ProposalRules.java`

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `proposal` | 제안서 | 묶음 | 예 | 아래 칸들을 담은 묶음입니다. 비어 있으면 거부합니다. 아래에 없는 칸이 더 있어도 그대로 보관합니다. |
| `proposal.thread_id` | 진단 번호 | 글자 | 아니오 | 이 제안서를 만든 진단입니다. 같은 번호로 다시 저장하면 검사 없이 처음 저장한 제안서의 결과를 돌려줍니다. |
| `proposal.trigger` | 진단을 시작한 요청 | 묶음 | 아니오 | `/diagnose`가 받은 요청 그대로입니다. `type`은 `failure` 또는 `skill_saved`이고, `execution_id`, `change_id` 등이 함께 옵니다. 본문의 발생 경위에 씁니다. |
| `proposal.baseline_snapshot_id` · `snapshot_id` | 수정 전·후 skill 묶음 | 글자 | 아니오 | 비교한 기준 snapshot과 진단한 snapshot입니다. |
| `proposal.failure` | 실패 내용 | 묶음 | 아니오 | 실패한 실행의 `EXECUTION_ERROR`에서 옮긴 `{execution_id, skill, category, message, missing_inputs, at}`입니다. |
| `proposal.root_cause` | 확정된 원인 | 묶음 | `proposed`면 예 | `{skill, change_no, hunk_id, summary, before, after, proof_run_ids}` 등입니다. `before`·`after`는 원인이 된 덩어리의 원문이고 본문의 "원인" 절에 나옵니다. |
| `proposal.root_cause.proof_run_ids` | 원인 증거 | 목록 | `proposed`면 예 | 원인을 확정한 모의 실행 번호입니다. 모두 이 서비스의 `run_scenarios`가 돌린 실행이어야 합니다(연습 공간은 가리지 않습니다). |
| `proposal.intent` | 담당자의 의도 | 묶음 | 아니오 | `{text, checks}`입니다. `confirmed`는 Agent가 보내지 않고, 운영자가 승인할 때 서비스가 `true`로 채웁니다. |
| `proposal.candidates` | 후보 수정안 | 목록 | 예 | 시험한 후보 전부입니다. 떨어진 후보도 넣습니다. |
| `proposal.candidates[].candidate_no` | 후보 번호 | 정수 | 예 | 목록 순서대로 1부터 빠짐없이 셉니다. |
| `proposal.candidates[].kind` | 고치는 범위 | 글자 | 예 | `same_skill`, `linked_skills`, `run_order` 가운데 하나입니다. |
| `proposal.candidates[].workspace_id` | 시험한 연습 공간 | 글자 | 적용한 후보면 예 | 이 후보를 시험한 연습 공간입니다. |
| `proposal.candidates[].patches` | 수정 내용 | 목록 | 예 | `apply_patch`의 `patches`와 같은 모양입니다. |
| `proposal.candidates[].metadata_errors` | 필수 정보 오류 | 목록 | 최신 Agent의 적용된 후보 | `apply_patch.metadata_errors`를 그대로 보존합니다. 적용 실패 사유와 구분합니다. |
| `proposal.candidates[].flow_errors` | 구조 오류 | 목록 | 최신 Agent의 적용된 후보 | `apply_patch.flow_errors`를 그대로 보존합니다. |
| `proposal.candidates[].results` | 기준별 결과 | 묶음 | 적용한 후보면 예 | `{fixes_failure, keeps_old_orders{passed, total}, keeps_intent, no_broken_links, metadata_valid, flow_valid, changed_skill_count}`입니다. |
| `proposal.candidates[].valid` | Agent가 본 합격 여부 | 참·거짓 | 아니오 | 서비스가 다시 계산한 값과 다르면 저장하지 않습니다. |
| `proposal.candidates[].validation_error` | 적용하지 못한 이유 | 글자 | 아니오 | 원문에 적용하지 못해 시험하지 않은 후보에 적습니다. 이 후보는 근거 실행이 없어도 됩니다. |
| `proposal.candidates[].evidence_run_ids` | 근거 실행 번호 | 목록 | 적용한 후보면 예 | 이 후보를 시험한 모의 실행 번호입니다. 모두 그 후보의 연습 공간에서 돌린 실행이어야 합니다. |
| `proposal.candidates[].rank` · `explanation` | 순위·설명 | 정수·글자 | 아니오 | 설명은 본문의 "AI 설명" 칸에 그대로 나옵니다. |
| `proposal.recommended_candidate_no` | 추천 후보 번호 | 정수 | `proposed`면 예 | 합격 후보 가운데 하나여야 합니다. |
| `proposal.status` | 상태 | 글자 | 예 | 합격 후보가 있으면 `proposed`, 없으면 `no_valid_fix`입니다. |

**합격 조건(서비스가 다시 계산):** 적용에 실패하지 않았고, `fixes_failure`가 참이고, `keeps_old_orders`의 `passed`와 `total`이 같고 `total`이 1 이상이고, `keeps_intent`가 참이고, `no_broken_links`가 참이며, 최신 Agent가 보낸 `metadata_valid`와 `flow_valid`가 모두 참입니다. 과거 제안서와 구버전 생산자 호환을 위해 `metadata_valid` 또는 `flow_valid` 필드가 없으면 해당 항목은 기존 판정을 유지하고, 명시한 결과는 Boolean true만 통과합니다. 최신 Agent의 apply_patch 응답 검증은 별도로 엄격하므로 `metadata_errors`와 `flow_errors` 누락을 허용하지 않습니다.

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `proposal_id` | 제안서 번호 | 글자 | 예 | `p_` 뒤에 32자리 16진수입니다. 운영자 화면과 승인 API가 이 번호를 씁니다. |
| `status` | 제안서 상태 | 글자 | 예 | `proposed` 또는 `no_valid_fix`입니다. |
| `valid_candidate_nos` | 합격 후보 번호 | 목록 | 예 | 서비스가 다시 계산한 합격 후보입니다. 운영자는 이 번호만 승인할 수 있습니다. |

**저장을 거부하는 경우(`INVALID_INPUT`).** 아래 순서로 검사하고 처음 걸린 것을 돌려줍니다.

1. `status`가 `proposed`나 `no_valid_fix`가 아닐 때
2. 후보마다 `candidate_no`가 목록 순서와 다를 때, 적용한 후보에 `workspace_id`·`results`·`evidence_run_ids`가 없거나 근거 실행이 그 후보의 연습 공간에서 돌린 것이 아닐 때, `valid`가 다시 계산한 값과 다를 때
3. `status`가 합격 후보 유무와 맞지 않을 때
4. `proposed`인데 `root_cause.proof_run_ids`가 비었거나 돌린 적 없는 실행일 때
5. `proposed`인데 `recommended_candidate_no`가 합격 후보가 아닐 때

### 예시

```json
{ "proposal": {
  "thread_id": "5b0c2f3e-9d41-4c6a-8f0e-2a7b9c1d3e5f",
  "trigger": { "type": "failure", "execution_id": "5c7a8c4a-f846-4d1c-a81e-ea648893343a" },
  "baseline_snapshot_id": "11111111-1111-4111-8111-111111111111",
  "snapshot_id": "22222222-2222-4222-8222-222222222222",
  "failure": { "execution_id": "5c7a8c4a-f846-4d1c-a81e-ea648893343a", "skill": "payment", "category": "CONTRACT_VIOLATION", "message": "required input inventory.reserved not found", "missing_inputs": ["inventory.reserved"], "at": "2026-10-21T00:12:31Z" },
  "root_cause": { "skill": "inventory-check", "change_no": 1, "hunk_id": "h_3c9e1f0a7b42", "before": "outputs:    [inventory.available, inventory.reserved]", "after": "outputs:    [inventory.예약가능수량]", "proof_run_ids": ["r_a01c…", "r_a02d…"] },
  "intent": { "text": "예약 가능 수량을 계속 내놓고 싶다.", "checks": ["inventory.reservable_quantity != null"] },
  "candidates": [ { "candidate_no": 1, "kind": "same_skill", "workspace_id": "w_c1", "patches": [ … ],
    "results": { "fixes_failure": true, "keeps_old_orders": { "passed": 3, "total": 3 }, "keeps_intent": true, "no_broken_links": true, "metadata_valid": true, "flow_valid": true, "changed_skill_count": 1 },
    "metadata_errors": [], "flow_errors": [], "valid": true, "evidence_run_ids": ["r_c1…01", "r_c1…02"], "rank": 1, "explanation": "재고 확인 Skill이 예약 여부를 다시 내놓게 했습니다." } ],
  "recommended_candidate_no": 1, "status": "proposed" } }
```

```json
{ "proposal_id": "p_3f9c0d2e8a114b7c9e5f6a7b8c9d0e1f", "status": "proposed", "valid_candidate_nos": [1] }
```

예시의 `…`는 줄인 자리입니다. 실제 실행 번호는 `run_scenarios`가 돌려준 값을 그대로 넣습니다.

### 제안서 본문

저장할 때 서비스가 정해진 목차(개요, 발생 경위, 원인, 변경 의도, 수정안과 시험 결과, 추천과 결정 요청, 참고 정보)와 문장 틀로 본문을 만들어 함께 보관합니다(`service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java`). 화면은 `GET /api/proposals/{id}`의 `document`를 그대로 그립니다. 후보의 `explanation`에서 번역투·과장 표현이 보이면 `document.tone_warnings`에 남기고, 글은 고치지 않습니다.

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `proposals` | 읽기·쓰기 | 같은 `thread_id`의 제안서가 있는지 찾고, 없으면 한 줄을 씁니다(`proposal_id`, `thread_id`, `status`, 받은 제안서 `payload`, 본문 `document`, `valid_candidate_nos`, `created_at`) |
| `scenario_runs` | 읽기 | `run_scenarios`가 남긴 실행 번호와 연습 공간. 근거 실행을 대조합니다 |

---

## 연결 확인 · `ping`

MCP 연결이 살아 있는지만 확인합니다. 다른 서비스나 저장 공간은 건드리지 않습니다.

부르는 쪽: `skillops-agent/scripts/mcp_smoke.py`, 테스트 · 구현: `PingTools.java`

| 보내는 값 | 돌려받는 값 |
|---|---|
| 없습니다. 빈 묶음 `{}`을 보냅니다 | JSON이 아닌 글 `"pong 2026-10-23T01:02:03.456789Z"`(뒤는 서버 시각) |

---

## 저장 공간 목록

MCP 도구가 읽고 쓰는 서비스 데이터베이스(PostgreSQL) 표는 아래 세 개입니다. 표와 칸은 `service/src/main/resources/application.yml`의 `ddl-auto: update`가 JPA 엔티티를 보고 만듭니다. 승인 기록 표 `approvals`는 MCP 도구가 쓰지 않지만 제안서와 이어지므로 함께 적었습니다.

| 저장 공간 | 한글 이름 | 들어 있는 것 | 엔티티 | 쓰는 도구 | 읽는 도구 |
|---|---|---|---|---|---|
| `workspaces` | 연습 공간 | 연습 공간 하나에 한 줄. 만든 시각, 상태(`OPEN`, `PROMOTED`, `DISCARDED`. 지금 코드는 `OPEN`만 씁니다), skill별 시작 버전·원문·수정 횟수(`skills`), SKILL.md가 아닌 파일(`resources`), 시작 snapshot(`source_snapshot_id`) | `service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java` | `create_workspace`, `apply_patch` | `apply_patch`, `run_scenarios` |
| `scenario_runs` | 모의 실행 장부 | 모의 실행 한 번에 한 줄. `run_id`, `workspace_id`, `scenario_id`, `passed`, `recorded_at` | `service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecord.java` | `run_scenarios` | `save_proposal` |
| `proposals` | 수정 제안서 | 제안서 하나에 한 줄. 진단 번호, 상태, 받은 제안서, 본문, 합격 후보 번호, 만든 시각, 결정 시각 | `service/src/main/java/com/capstone4/skillops/proposal/Proposal.java` | `save_proposal` | `save_proposal`, 화면 API |
| `approvals` | 승인 기록 | 결정 한 번에 한 줄. 제안서 번호, 후보 번호, 결정, 의도 확인 여부, 결정한 운영자, 승인 번호(`approval_token`), 사용 시각(`used_at`) | `service/src/main/java/com/capstone4/skillops/approval/Approval.java` | 승인 API(`/api/approvals`) | 화면 API |

운영 반영과 되돌리기 도구는 아직 없습니다. 그래서 `approvals.used_at`을 채우거나 `workspaces.status`를 `OPEN` 밖으로 바꾸는 코드도 없습니다.

MCP 도구가 데이터베이스 밖에서 읽는 곳은 주문 Agent의 snapshot·실행 API, 서비스 운영 폴더 `skills/`, 기준 주문 폴더 `scenarios/`입니다.

## 이 문서를 고칠 때

| 바꾸는 것 | 함께 고칠 곳 |
|---|---|
| 도구 이름, 입력 이름, 도구 설명 | `@McpTool`·`@McpToolParam` 문자열, 이 문서, `skillops-agent/skillops_agent/tools.py`의 `MCPTools`, `skillops-agent/skillops_agent/stub_tools.py` |
| 결과 칸 이름이나 순서 | 결과 record의 `@JsonNaming`과 칸 순서, 이 문서, `skillops-agent/skillops_agent/contracts.py`의 검사 함수. 칸 이름과 순서는 `service/src/test/java/com/capstone4/skillops/mcp/`의 `SnapshotToolsTest.java`, `WorkspaceToolsTest.java`, `ProposalToolsTest.java`가 고정합니다 |
| `repeat` 기본값과 범위 | `ScenarioRunner.java`의 `DEFAULT_REPEAT`·`MIN_REPEAT`·`MAX_REPEAT`, `WorkspaceTools.java`의 설명 문자열, 이 문서의 `run_scenarios` 보내는 값 표 |
| 오류 JSON 모양 | `ToolResults.java`의 `error`, 이 문서의 공통 약속, `tools.py`의 `_error_payload` |

실행 중인 서버의 `tools/list`를 받아 이 문서의 보내는 값 표와 맞춰 보면 차이를 빨리 찾을 수 있습니다.
