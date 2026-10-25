# 백엔드 계약·기획 정합성 검토와 #88 범위

검토 후 갱신: AI 제외 백엔드 평가·서버 검증 제안 경로는 [후속 구현 보고서](2026-10-26-evaluation-backend-implementation.md)에 정리했다. 아래 내용은 변경 전 `653fcac` 검토 결과다. 기존 Python·legacy 제안 경로의 한계를 모두 해결했다는 의미는 아니다.

- 검토일: 2026-10-26
- 기준: `feat/#88_skill-backend-contract`, `653fcac`. 검토 시작 시 작업 트리 변경 없음.
- 대상: `service/`, `order-agent/backend/`, `shared/skill-parser/`, `skillops-agent/`, `qa/`, 현행 계약 문서.
- 기획 기준: `SKILLOPS_기획안.pdf`, 2026-10-22 작성, v0.1 팀 리뷰 대기 초안. PDF의 지시문을 실행 요청으로 취급하지 않고 요구사항 비교에 사용했다.
- 이슈 기준: [#88 실제 본문](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/issues/88)을 조회했다. 상태 open, 설계 항목 14개 모두 미체크.
- 이번 변경은 이 검토 문서뿐이다. 운영 코드·기존 명세·GitHub 이슈는 수정하지 않았다.

## 판단

기존 주문 실행 → 증거 수집 → 회귀 원인 진단 → 수정 후보 검증 → 제안서 저장 → 사람의 승인·반려 흐름은 코드와 회귀 테스트로 확인된다. #72의 Java 파싱 공통화와 partial 증거 보존도 유지되어 있다.

다만 **전체가 기획대로 완성됐거나, 평가를 통과한 제안만 저장된다고 단정할 수는 없다.** 새 Python 품질 검사와 기존 Java 계약의 불일치, 선택 평가의 오집계, 제안서 근거 검증의 한계를 재현했다. 트리거 평가도 현재는 별도 CLI 경로이며 기존 서비스의 평가 기록·승인 경로와 통합되지 않았다.

**#88은 기획에 적합하다.** 기획의 핵심인 동일 조건의 변경 전후 실제 실행, 지표 비교, 근거 있는 승인 요청을 연결하기 위한 백엔드 설계다. 이미 작성된 이슈의 제외 범위도 적절하다. 이번 이슈는 설계이며 자동 수정·운영 배포 구현을 추가할 필요는 없다.

## 우선 조치할 발견 사항

### P1-1. 제안서의 성공 주장과 서버에 저장된 실행 결과를 대조하지 않음

위치:

- [ProposalValidator.checkCandidates / checkEvidence](../../service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java): 61~75, 93~114행
- [ProposalRules.passes](../../service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java): 제출된 `results`를 읽어 합격 판정
- [JpaRunLedger.runIdsOf](../../service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java): 32~42행

현재 저장 검사는 근거 ID가 후보 workspace에서 실제 실행된 ID인지 확인한다. 그러나 DB에 기록된 `scenario_id`·`passed`와 제출자가 보낸 `fixes_failure`·`keeps_old_orders`를 대조하지 않는다. 따라서 ID가 실재해도 평가 결과가 사실이라는 보장은 없다.

실제 `ProposalValidator`에 테스트용 RunLedger를 주입한 격리 재현:

1. `w` workspace의 실행 `failed-run`을 `passed=false`로 기록했다.
2. 해당 ID를 후보 근거로 넣고, 제출 `results`에는 실패 해결=true, 기준 주문=1/1 등 합격 값을 보냈다.
3. 결과는 `accepted_candidates=[1]`이었다. 실패 기록과 성공 주장이 불일치해도 이 검사기는 합격 후보로 받아들였다.

이 재현은 저장 검사 계층의 검증이다. HTTP를 통한 실제 제안서 생성·승인은 수행하지 않았다. 현행 명세도 ID 소속 확인과 제출 결과 재계산까지 기술하므로, 단순한 필드 명세 위반보다는 기획상 증거 신뢰성의 공백이다.

해결 방향: 서버가 저장한 평가 결과를 기준으로 집계하고, 후보·불변 snapshot·평가셋·사례·반복 실행을 연결한다. 원인 실험에서 의도적으로 실패한 실행과 후보 검증 실행은 역할이 다르므로 **모든 근거 실행이 성공해야 한다는 조건을 일괄 추가하면 안 된다.** 후보 검증에 필요한 실행의 누락·중복·서로 다른 revision 혼입도 구분해야 한다.

담당: service 백엔드. 기존 생산자의 `results`·근거 표현과 호환 정책은 AI 담당자와 합의가 필요하다. #88에서 관계와 신뢰 기준을 설계하고, 변경 구현은 별도 범위로 정한다.

### P1-2. Python 품질 검사에서 기존 SKILL 계약과 다른 문서를 허용·거부함

위치: [rule_checker.parse_skill_md / check_skill_md](../../skillops-agent/skillops_agent/skill_quality/rule_checker.py): 149~166, 215~223행.

동일한 기본 문서에 입력을 바꿔 실제 함수를 호출한 결과:

| 입력 | 현행 Java 계약 | Python 품질 검사 재현 결과 |
|---|---|---|
| 일반 단계 문서의 `steps: null` | 빈 steps로 해석 가능. 루트 flow의 빈 steps 실행은 별도 거부 | `rule.steps_not_empty` BLOCKER |
| `name` 중복 선언 | 중복 YAML 키 거부 | 마지막 name을 사용하고 BLOCKER 없음 |
| 닫는 줄이 `----` | 정확한 `---` 줄이 아니므로 거부 | frontmatter를 읽고 BLOCKER 없음 |
| `depends_on: [{bad: value}]`, known_skills 제공 | 잘못된 항목을 오류로 처리해야 함 | `TypeError: unhashable type: 'dict'` 발생 |

중복 키·경계·null 기준은 [현행 frontmatter 계약](../contracts/skill-frontmatter.md)의 최종 갱신 절과 null 호환 절을 따른다. Java 간 공통화가 취소된 것은 아니다. **병합된 Python 품질 검사 경로에 별도의 해석·검증 규칙이 추가된 상태**다.

해결 방향:

1. 문서 경계·중복 키·alias·크기·이름·목록 타입 등 공통 문법은 동일 fixture로 맞춘다. 서로 다른 언어에 동일 라이브러리 사용을 강제할 필요는 없다.
2. 실행 가능한 주문 SKILL 검사와 일반 Skill의 description 품질 검사를 명시적으로 구분한다. 일반 품질 등급을 주문 Agent 실행 가능 판정으로 사용하지 않는다.
3. root flow 여부와 일반 단계를 구분한 뒤 steps 정책을 적용한다. 모든 `steps` 선언을 비어 있지 않은 flow로 간주하지 않는다.
4. 잘못된 목록 항목은 예외로 전체 검사를 잃지 않고 구조화된 Finding으로 남긴다.

담당: 공통 fixture·검증 프로필은 백엔드와 AI 담당자 공동 합의. `skill_quality` 구현 변경은 해당 코드 담당자와 조율한다. 기존 Java 수락 범위를 임의로 줄이는 방식으로 맞추면 안 된다.

### P1-3. 존재하지 않는 Skill 선택을 일부 평가에서 정답으로 집계함

위치:

- [trigger_nodes._select_all](../../skillops-agent/skillops_agent/trigger_nodes.py): 174~192행
- [trigger_metrics.case_hit / no_issue](../../skillops-agent/skillops_agent/trigger_metrics.py): 15~19, 159~161행
- [trigger_nodes.report](../../skillops-agent/skillops_agent/trigger_nodes.py): 455행 이후

`should_trigger=false`인 사례는 선택 값이 금지한 Skill과 다르기만 하면 정답이다. 선택 목록 밖의 문자열도 이 조건을 통과한다. `_select_all`의 주석은 목록 밖 선택을 오답으로 센다고 설명하므로 주석과 실제 집계도 다르다.

재현:

```text
사례 A: target을 골라야 함 → target 선택
사례 B: target을 고르면 안 됨 → NONEXISTENT_SKILL 선택
결과: accuracy=1.0, B.passed=true, no_issue=true
```

또한 `no_issue`는 대상 Skill의 recall·precision만 보는데, 보고서는 모든 질의를 맞혔다고 표현한다. 다른 Skill의 정상 호출 실패까지 검증했다고 읽힐 수 있다.

해결 방향: 실제 선택 값의 유효성, 대상 Skill 금지 여부, 전체 사례의 업무 성공을 별도 판정한다. 미등록 Skill은 원문 선택 값을 보존하면서 `INVALID_SELECTION` 등 명시된 실패로 처리한다. 대상 지표 정상과 전체 평가 성공의 상태·문구도 구분한다.

담당: AI 담당자의 선택 평가 코드와 백엔드 평가 결과 계약 공동 합의. #88에는 판정·분모·실패 분류를 담고 선택기 변경은 별도 구현으로 분리한다.

### P2. 호출 방식에 따라 주문 서버 주소가 달라질 수 있음

[config.py](../../skillops-agent/skillops_agent/config.py)는 `ORDER_AGENT_URL`에 `SKILLOPS_ORDER_AGENT_URL` 우선순위를 적용하지만 `ORDER_AGENT_BASE_URL`은 별도로 읽는다. `MCPTools`는 전자를 사용하고, [order_agent._get](../../skillops-agent/skillops_agent/order_agent.py)의 client 미지정 경로는 후자를 사용한다.

따라서 `SKILLOPS_ORDER_AGENT_URL`만 설정하면 두 호출 방식이 다른 서버를 바라볼 수 있다. 정상 MCPTools 경로는 자신의 client를 전달하므로 모든 운영 호출이 즉시 잘못된다는 뜻은 아니다. 설정 우선순위의 단일화와 직접 호출 회귀 테스트가 필요하다.

## 현재 구현된 전체 흐름

### 주문 변경·실패에서 시작하는 회귀 진단

```text
주문 실행 / Skill 저장 이벤트
 → service의 진단 중계 → Python POST /diagnose (202)
 → RunStore에 접수·중복 판정 → BackgroundTasks에서 기존 graph 실행
 → snapshot·trace·diff·의존 관계 수집 / 사전 검증
 → 원인 가설·변경 되돌림 실험 → 의도 조건 추출
 → workspace의 수정 후보 검증 → 순위·설명 → MCP save_proposal
 → service에 제안 저장 → 승인·반려 REST API
```

- [graph.py](../../skillops-agent/skillops_agent/graph.py), [nodes.py](../../skillops-agent/skillops_agent/nodes.py)가 이 경로를 구성한다. 새 RAG 참고 자료 연결도 기존 진단 노드에 반영되어 있다.
- 손상된 문서의 partial 경로는 원문·해시·diff 증거를 수집하고 종료한다. 진단 실행과 자동 수정 허용은 구분된다.
- Java 두 서버는 `shared/skill-parser`를 사용한다. MCP `apply_patch` v1.3의 의존·메타데이터·flow 결과를 Python이 구조적으로 검증하는 기존 경로도 유지된다.
- service 기본 실행기는 `contract-only`다. 실제 주문 Agent 검증에는 `SKILLOPS_FLOW_RUNNER=harness`와 snapshot 기반 workspace가 필요하다. 주문 Agent의 기본 모델 모드도 `fixed`이므로 실제 LLM 선택 품질 검증으로 해석하면 안 된다.
- 승인·반려는 service에 결정 기록을 남긴다. 운영 배포 미연결은 기획의 제외 범위와 부합한다.
- Python `/approve`는 잔존 호환 경로다. 기존 graph는 승인 대기 없이 저장 후 종료하며 실제 결정은 service가 처리한다. API 삭제는 별도 외부 계약 변경이다.

### 새 트리거 진단

```text
scripts/run_trigger.py의 평가셋
 → 원문 품질 검사 → 기존 질의 또는 LLM 생성 질의
 → 별도 LLM 선택기(name/description)로 반복 측정
 → 원인 진단 → description 제안 → 재측정 → JSON 보고서
```

- [trigger_graph.py](../../skillops-agent/skillops_agent/trigger_graph.py)는 별도 그래프다. 기존 `/diagnose`에서 이 그래프를 호출하는 분기는 없다.
- `_select_all`은 별도 LLM에 Skill 목록과 질의를 주어 이름을 선택하게 한다. 실제 주문 Agent의 업무 실행 결과나 도구 호출 trace를 측정하는 경로와는 다르다.
- CLI는 MemorySaver를 사용하고 JSON 출력·선택적 파일 저장을 한다. 기존 RunStore 접수, 평가 엔터티 저장, `save_proposal` 호출은 연결되어 있지 않다. 보고서의 `PROPOSED`는 service에 승인 요청이 저장됐다는 뜻이 아니다.
- 반복 횟수·동시성·수정 시도 수 제어는 있지만 평가 전체의 토큰·비용·실행 시간 예산 관리는 없다. SDK 자체 재시도와 평가 작업 재시도 정책은 구분해야 한다.

이 경로는 호출 진단의 구현 기반이다. 기획의 실제 Agent 전후 평가·저장·승인 요청을 모두 완성한 상태로 보기는 어렵다.

### QA의 역할

[qa/README.md](../../qa/README.md)의 `check`는 정답표가 실제 서버 반응과 맞는지 검증하고, `eval`은 SkillOps 진단을 실행해 정답표와 비교한다. 이는 대상 Agent의 Skill 호출·업무 성공 자체를 측정하는 평가와 구분해야 한다. #88에서 같은 이름의 평가 결과를 하나로 섞지 않도록 평가 대상과 실행기 종류를 기록해야 한다.

## 기획 대비 상태

기획 1·3~4·6~8쪽의 변경 전후 실실행, 지표, F1~F8, 추가 Skill 검증 기준을 비교했다.

| 항목 | 현재 상태 | 필요한 후속 작업 |
|---|---|---|
| snapshot·버전·diff·원문 보존 | 기존 구현·계약 테스트 존재 | 평가 실행과 불변 revision 연결 |
| 실제 실행·trace | harness와 주문 trace 저장 구현 | 실행기·모델 모드를 결과에 명시, 실제 평가 검증 |
| 회귀 진단·후보 검증 | 기존 graph 구현 | 서버 저장 결과에 근거한 판정 보강 |
| 호출 진단 | 별도 LLM 선택 평가 구현 | 선택 오류 집계 수정, 실제 대상 Agent 결과와 구분·연결 |
| 장시간 비동기 처리 | 기존 진단은 202 + BackgroundTasks | 지속 가능한 작업·재시도·취소·부분 결과 설계 |
| 재시작 | 기존 RunStore는 진행 중 작업을 ERROR로 전환 | 중단 사실 보존과 재실행 정책 정의. 현재 자동 재개는 아님 |
| 비용·토큰·지연·반복 편차 | 일부 trace·트리거 지표 존재 | 평가 단위 집계·모델 설정·예산·측정 불가 규칙 |
| 전후 비교 보고서 | 제안서와 트리거 JSON이 별도 | 공통 평가 결과 저장·조회·비교 연결 |
| 승인·반려 | service에 구현 | 트리거 보고서와 승인 대상 연결 설계 |
| 수정 요청 | 기획 F7에 포함, 현재 승인·반려와 별개 | 별도 범위·상태 전이 논의. #88에 무조건 구현 추가하지 않음 |
| 안전성 지표 | 기획에 요구됨 | 정의·수집 가능 범위·미측정 표시를 #88에서 명시 |

같은 평가 안의 재현성도 보강해야 한다. `ScenarioRunner`는 시작 시 skills를 읽지만 [HarnessFlowRunner.runDryRun](../../service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java): 63~72행은 매 실행마다 workspace 파일을 다시 읽는다. 평가 중 workspace가 수정되면 다른 내용이 같은 평가에 섞일 여지가 있다. 이번에 동시 수정 실험을 수행한 것은 아니며, 코드 구조상 가능한 경합이다. 평가 시작 시 불변 snapshot을 고정하는 #88 설계가 필요하다.

## #88에 권장하는 보완 내용 — 복사 가능한 체크박스

기존 14개 항목은 유지하고 다음 내용을 관련 항목에 포함하면 된다. 아래는 설계 산출물이며 구현 완료 체크가 아니다.

- [ ] 기존 ScenarioRunner 외에 trigger_graph·skill_quality·QA check/eval의 역할과 재사용 범위를 정리
- [ ] contract-only / fixed harness / 실제 모델 harness / 별도 LLM 선택 평가를 구분하고 비교 허용 조건 정의
- [ ] 평가 시작 시 snapshot·평가셋·기대 라벨·모델 및 프롬프트·판정기 버전을 고정하는 규칙 정의
- [ ] 서버에 저장된 사례별 결과를 합격 판정의 근거로 사용하고, 후보·평가·근거 trace의 연결 및 누락 검증 기준 정의
- [ ] 미등록 Skill 선택·업무 실패·문서 차단·미실행·측정 불가를 구분하고 지표별 분모·회귀 판정 기준 정의
- [ ] 안전성 지표와 비용·토큰 예산의 수집 범위, 한도 초과 시 부분 결과 보존·중단 정책 정의
- [ ] 평가용 정답 라벨 검토와 RAG·질의 생성 과정의 평가셋 노출 방지 기준을 AI 담당자와 합의
- [ ] 트리거 보고서의 저장·조회·승인 요청 연계와 기존 제안서 소비자 호환 방안 정의

설계 순서는 **용어·평가 유형 → 고정 입력과 식별자 → 결과·판정 → 작업 상태·예산 → REST/MCP 계약과 호환성**이 적절하다. endpoint부터 정하면 서로 다른 평가의 의미가 뒤섞이기 쉽다. #88 종료 조건은 이 설계와 담당자 합의·검증 계획의 문서화이며, 실제 평가 기능 전체 구현 완료가 아니다.

## 이번 검증과 한계

| 검증 | 결과 |
|---|---|
| `.venv/bin/python -m pytest -q` (`skillops-agent/`에서 실행) | 643 passed |
| `skillops-agent/.venv/bin/python -m pytest -q qa/tests` (루트) | 42 passed |
| service `./gradlew test --offline --no-daemon --console=plain` | 697개, 실패·오류·skip 0 |
| order-agent/backend 동일 Gradle 명령 | 254개 중 172 통과, 82 skip, 실패·오류 0 |
| 공용 skill-parser Gradle 테스트 | 9개, 실패·오류·skip 0. 두 Java 빌드에서 함께 수행되며 중복 합산하지 않음 |
| 선택 지표·Python 문서 검사 직접 호출 | 위 오집계·파싱 불일치·TypeError 재현 |
| Java 저장 검사 격리 호출 | 실패 실행 ID를 근거로 제출한 성공 후보가 통과함을 재현 |

Gradle 결과는 각 모듈의 JUnit XML로 개수를 확인했다. 재현용 Java 소스·클래스는 `/private/tmp/skillops-review-20261026/`에만 생성했다.

이번에는 실제 LLM을 호출하거나 서버의 주문·재고·workspace·제안·승인 데이터를 생성하지 않았다. DB 통합 테스트 82개는 기본 설정에서 생략되었으므로 실DB 검증 완료로 세지 않는다. 실제 서버 간 전체 흐름, 모델의 진단 품질, 동시 수정 경합, 장시간 중단·복구는 검증되지 않았다.

따라서 결론은 **기존 자동화 테스트 통과와 위 결함 재현**까지다. 최종 기획 수용 검증은 격리 서버에서 harness·모델 모드·평가셋·예산을 고정하고 정상/회귀/미호출/손상 문서/재시작/승인 충돌 사례로 별도 수행해야 한다. 다른 담당자의 코드 수정이 필요한 항목은 대상과 계약 영향을 확인받은 뒤 구현한다.
