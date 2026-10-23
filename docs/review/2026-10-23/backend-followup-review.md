# 다른 담당자의 후속 정리 내용 검토

> **과거 검토 기록 — 기준 `dc261ba`.** 이 문서의 “현재”, 미완료 항목, 테스트 수와 줄 번호는 작성 당시 기준입니다. 최신 구현·계약·#72 완료 상태는 [리뷰 문서 안내](../README.md)를 우선합니다. 아래 제안을 현재 API나 새 작업 승인으로 간주하지 않습니다.

기준일: 2026-10-23 · 로컬 HEAD: `dc261ba01ec6a13e6c7f7b488be4dfc17e270781`

사용자가 전달한 “일부 해결·미해결·후속 작업”을 현재 백엔드 소스와 대조했다. 별도 원격 브랜치는 지정되지 않아 현재 체크아웃만 검토했다. 기존 코드·공통 명세는 수정하지 않았다. 서버·DB·유료 모델을 실행하지 않았으며 아래 판단은 정적 검토 결과다.

## 1. 결론

해결 여부에 관한 설명은 대체로 현재 코드와 일치한다. 다만 후속 작업 분류는 조정해야 한다. **Python 주문 서버 주소 통합은 설정 동작 변경**이며, **HTTP 클라이언트 공통화는 대상별 정책을 보존하는 조건에서 진행**해야 한다. 모든 Map을 DTO로 바꾸거나 모든 서버의 파서를 동일하게 묶는 방식도 권장하지 않는다.

## 2. 항목별 대조

| 항목 | 판정 | 코드 근거와 해석 |
|---|---|---|
| 그래프의 승인 대기 노드 제거 | 확인 | `graph.py` FLOW는 `save_proposal: END`. Python `/approve`, `_resume_pipeline`, `_pending_approvals`는 남아 있다. Spring 승인·반려와 별개의 잔존 경로다. |
| 최근 실행 목록의 N+1 | 확인 | `ExecutionRepository.recent()`가 ID 최대 20개 조회 후 `summary(id)`를 각각 호출한다. 이벤트 조회는 제거됐지만 목록 크기 N에 대해 SQL은 1+N회, 20건이면 21회다. |
| Python RunStore 목록 단일 SELECT | 확인 | `RunStore.list()`는 `_query_runs()`의 SELECT 한 번으로 목록을 가져온다. 이 판단은 목록 조회 대상이며 `recover()`의 행별 UPDATE까지 한 쿼리가 됐다는 뜻은 아니다. |
| MCP TODO는 수정, 계약은 과거 상태 | 확인 | `SkillDiffTools`, `DependencyGraphTools` 주석은 현재 Agent가 snapshot 인자를 보낸다고 적는다. 계약 README는 여전히 12개, diff 계약은 정수 버전 입력이다. |
| timeout 상수화, HTTP 생성 중복 | 확인 | OrderAgentClient 2초/5초, OrderAgentRunClient 2초/60초, DiagnosisController 2초/5초. 세 클래스가 각각 클라이언트를 구성한다. |
| Python 주문 서버 주소 두 경로 | 확인, 영향 범위 보완 필요 | tools.py는 ORDER_AGENT_URL, order_agent.py의 자체 client는 ORDER_AGENT_BASE_URL을 쓴다. 다만 tools.py의 조회 네 개는 자신이 만든 client를 주입하므로 그 호출들은 주입된 주소를 사용한다. |
| Skill 해석 경계 세 개 | 확인 | SkillLoader, Frontmatter/DependencyAnalyzer, SkillHeader가 별도 규칙을 가진다. SkillHeader의 여러 줄 목록 처리 외에도 필수 필드·구분선·중복 키·길이 제한이 다르다. |
| 문자열 키로 읽는 Map/dict 잔존 | 확인 | ProposalValidator 등의 고정 계약 처리에도 남아 있다. 기존 DiffSkillResult, SnapshotManifest, ScenarioRunReport, PatchInput 등 typed record도 있으므로 “DTO가 전혀 없다”로 해석하면 안 된다. |
| Java 한 줄 몰아쓰기·짧은 변수명 | 확인 | OrderToolService, Order, OrderHarness에 다중 실행문과 짧은 이름이 남아 있다. 가독성 작업이며 기능 정확성과 우선순위를 구분한다. |
| 컨트롤러 책임 분리 미완료 | 확인 | DiagnosisController가 client 생성·헤더·원격 호출·오류 매핑을 맡는다. EvidenceController는 UTF-8/base64 변환과 manifest 조립을 한다. |

주요 근거:

- [승인 그래프](../../../skillops-agent/skillops_agent/graph.py), [Python 승인 경로](../../../skillops-agent/skillops_agent/main.py)
- [실행 목록 저장소](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java), [진단 저장소](../../../skillops-agent/skillops_agent/run_store.py)
- [Python 설정](../../../skillops-agent/skillops_agent/config.py), [주입 client를 사용하는 조회](../../../skillops-agent/skillops_agent/tools.py), [자체 client 생성](../../../skillops-agent/skillops_agent/order_agent.py)
- [SkillHeader](../../../service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java), [Frontmatter](../../../service/src/main/java/com/capstone4/skillops/snapshot/Frontmatter.java), [SkillLoader](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java)
- [DiagnosisController](../../../service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java), [EvidenceController](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java)

## 3. 후속 제안에서 조정할 부분

### Python 주소 통합은 설정 오류 수정으로 분류

`SKILLOPS_ORDER_AGENT_URL=http://server-a`만 설정하고 `ORDER_AGENT_BASE_URL`은 비워 두면 tools.py는 server-a를 사용하지만, client 없이 직접 호출한 order_agent.py는 localhost:8081을 사용한다. 현재 그래프의 주요 조회는 tools.py의 client를 넘기므로 항상 두 서버를 오간다고 단정할 수는 없다.

권장안은 한 resolver가 기존 환경변수의 우선순위를 결정하고 두 경로가 같은 최종 URL을 읽게 하는 것이다. 기존 변수명은 전환 기간 호환 alias로 유지할 수 있다. 두 변수가 모두 설정됐거나 빈 문자열인 경우의 정책도 정의해야 한다. Python 함수에 client를 직접 넘기는 사용 방식은 유지한다.

변경 전 확인할 사례는 기본값, BASE_URL만 설정, SKILLOPS_ORDER_AGENT_URL만 설정, 둘 다 다르게 설정, 빈 문자열 설정이다. 이 변경은 호출 대상을 바꾸므로 사용자 확인과 설정 문서 갱신 대상이다.

### 클라이언트 공통화는 대상별 설정을 유지

각 클래스가 별도 client를 만든다는 사실만으로 요청마다 새 HttpClient를 생성하는 문제라고 볼 수는 없다. 현재 Spring 빈 생성 시 client를 구성해 필드에 보관한다. 핵심은 설정·생성 책임이 흩어져 있다는 점이다.

- transport 설정과 생성 factory를 공통화하되 조회용 5초와 실행용 60초를 유지한다. 모두 5초로 맞추면 실제 모의 실행이 중단될 수 있다.
- DiagnosisController의 HTTP/1.1 강제, 서비스 토큰 헤더와 오류 응답 매핑을 보존한다.
- 서로 다른 서버에 같은 토큰을 일괄 전송하는 전역 client를 만들지 않는다. 대상별 client 빈 또는 wrapper를 둔다.
- 읽기 GET 재시도와 부작용 있는 POST 정책은 분리한다.

### recent() 단일 쿼리는 응답 호환 확인 후 진행

최근 실행을 `agent_executions`와 `skill_snapshots`의 LEFT JOIN으로 한 번에 읽고, 기존 summary row mapping을 재사용하는 방식이 적절하다. 이벤트를 다시 읽을 필요는 없다.

최대 20건, 최신순, snapshot_hash의 null 처리, run_id 변환, ISO 시각과 반환 필드를 유지해야 한다. 동일 started_at의 순서를 ID로 고정하는 개선은 기존에 정의되지 않은 정렬 정책을 추가하는 별도 선택이다. N+1 제거에는 쿼리 횟수 확인과 결과 호환 검사가 필요하다.

### 파서는 공통 해석과 목적별 검증을 분리

여러 줄 목록을 읽지 못하는 SkillHeader는 실제 의미 불일치다. 예를 들어 `outputs:` 다음 줄의 `- inventory.reserved`를 빈 목록으로 읽을 수 있다. inline 목록의 따옴표 처리 등도 정식 YAML과 다르다.

먼저 공통 fixture로 YAML 해석·정규화 규칙을 맞추고, 실행기와 진단 분석기의 목적 차이는 유지한다. 실행기는 잘못된 입력을 거부해야 하지만, diff는 깨진 YAML의 원문 변경도 보여 줘야 한다. 따라서 세 경계의 모든 허용·거부 결과를 강제로 같게 만들면 진단 기능을 약화시킬 수 있다. 잘못된 원문은 byte/text diff를 보존하고 파싱 실패를 별도 필드로 보고하는 식으로 설계한다.

### DTO는 경계부터 점진적으로 도입

고정 구조인 진단 요청, 제안서 후보·판정, 원격 실행 요약부터 타입을 적용한다. Skill 입력·출력 context나 도구별 가변 JSON 전체를 고정 DTO로 바꾸지는 않는다. DTO 전환 과정에서 누락/null, 숫자 변환, 알 수 없는 키, snake_case 이름이 달라지면 기존 계약이 바뀔 수 있으므로 직렬화·검증 호환을 확인한다.

`/approve` 삭제는 저장소 내부의 production 호출이 없다는 것만으로 외부 소비자 부재까지 입증되지 않는다. 사용 현황과 계약 소유자를 확인하고, 필요하면 폐기 예고와 전환 기간을 정한 뒤 제거한다. 주석만으로 사용 중단을 강제할 수는 없다.

## 4. 제시한 감소 수치의 확인 범위

전달된 Map/dict 건수는 검색 범위·패턴·비교 커밋이 없어 동일 지표로 확정하지 않았다. 단순 get 호출 수에는 Python HTTP client의 `.get()`이나 문자열·중첩 접근이 섞일 수 있어 DTO 전환의 완료 지표로 쓰기 어렵다.

참고로 `ec39713 → HEAD`에서 운영 소스만 대상으로 `.get(` 다음 문자열 리터럴을 찾으면 service는 호출 수 112→101, Python은 140→127, 주문 Agent는 33→30이다. 이는 전달된 수치와 집계 방식이 다른 별도 참고값이며 Map 접근을 정확히 분류한 AST 분석이 아니다.

세미콜론을 한 줄에 세 개 이상 찾는 단순 검색은 주문 Agent 55→51줄이며, 주석·문자열·for문의 세미콜론까지 셀 수 있다. 다만 지적한 세 클래스의 현재 줄 수는 OrderToolService 8, Order 7, OrderHarness 6으로 일치했다. 최종 완료 기준은 검색 건수보다 고정 계약의 타입 검증, 읽기 쉬운 제어 흐름과 기존 응답 호환으로 정한다.

## 5. 백엔드 후속 작업 순서

1. **설정·계약 불일치 정리:** Python 주소 단일 해석, 실제 MCP 도구 7개와 snapshot 기반 계약 문서 정합화. 주소 변경은 동작 수정으로 분리한다.
2. **작은 호환 변경:** recent 단일 쿼리, DiagnosisAgentClient와 Evidence 응답 mapper/codec 추출. 기존 timeout·헤더·오류·인코딩·필드 보존.
3. **가독성 변경:** 한 줄 실행문과 모호한 지역 변수명을 정리한다. 직렬화 DTO 필드명과 MCP 파라미터명은 일반 변수명처럼 변경하지 않는다.
4. **별도 합의 작업:** Skill 파싱 규칙, `/approve` 폐기, 고정 경계 DTO 도입을 각각 범위가 작은 변경으로 진행한다.

이 목록은 앞서 작성한 [평가 원장·비동기 작업·예산·격리 실행 계획](backend-work-items.md)을 대체하지 않는다. N+1·가독성·클라이언트 공통화가 끝나도 실제 평가 판정 검증과 영속 작업 기능은 별도로 남는다. MCP 상세 정리는 [MCP 정리 문서](mcp-cleanup.md)를 참조한다.

다른 담당자의 코드·공동 명세에 손대기 전에는 파일별 변경 범위와 API/설정 영향을 사용자에게 확인받는다. 구현 시 새로 만들거나 수정하는 함수에는 짧은 기능 주석을 남기고, API·공통 동작 변경은 관련 명세와 테스트 결과를 함께 보고한다.
