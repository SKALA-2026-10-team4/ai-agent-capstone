# 함수·클래스·파일 역할 안내

> **과거 검토 기록 — 기준 `ec39713`.** 이 문서의 “현재”, 미완료 항목, 테스트 수와 줄 번호는 작성 당시 기준입니다. 최신 구현·계약·#72 완료 상태는 [리뷰 문서 안내](../README.md)를 우선합니다. 아래 제안을 현재 API나 새 작업 승인으로 간주하지 않습니다.

기준: 2026-10-22, `ec39713`. [전체 선언·원본 위치 색인](source-index.md) · [API 계약](backend-api.md) · [MCP 계약](mcp-spec.md).

이 문서는 ‘어떤 상황에 어떤 함수를 따라가면 되는지’를 설명드립니다. 정확한 인자·반환 타입·오버로드·private helper·생성자·접근자는 전체 선언 색인에 파일별로 빠짐없이 수록했습니다. 데이터 전달용 getter는 공통 규칙으로 설명하고, 업무 판단·부작용·분기 함수는 아래에서 각각 구분했습니다. 소스에 선언되지 않은 프레임워크 생성 메서드와 이름 없는 콜백은 별도 API가 아닙니다.

## 1. 진입점을 찾는 방법

| 확인하려는 현상 | 먼저 보실 함수 | 그다음 경계 |
|---|---|---|
| 주문이 두 번 생성됨 | OrderService.createOrder, NormalizedOrder.from | OrderRepository request_id·claim, 프런트 동일 키 복구 |
| 재고 부족/결제 거절 | OrderToolService.execute | InventoryMockAdapter.reserve / PaymentMockAdapter.pay |
| Skill 입력이 끊김 | SkillLoader.validateFlow/validateInputs | DependencyAnalyzer.analyze, trace EXECUTION_ERROR |
| 파일이 과거 실행과 다름 | SnapshotService.get/bundleHash | SnapshotRepository.get, trace snapshot_id/hash |
| 저장 후 진단이 안 시작됨 | SnapshotService.saveEdits | SkillChangeDispatcher.dispatch → DiagnosisController.changed → RunStore.accept |
| 진단 ERROR | graph.guarded, nodes.fetch_evidence | 원인 예외·hash·파싱·fixture 설정·MCP 응답 |
| 원인 확정이 이상함 | nodes.ablate/check_ablation | Expectation.replay → ScenarioGrader.grade |
| 후보 생성/적용 실패 | nodes.generate_candidates/verify_candidates | WorkspaceService.apply → PatchApplier.apply |
| 제안서 저장 거절 | ProposalService.save/checkCandidates | checkEvidence/checkRootCause·RunLedger |
| 승인 권한/상태 오류 | SecurityConfig.securityFilterChain | ApprovalController.approve → ApprovalService.approve → markDecided |
| 화면이 이전 결과로 바뀜 | 해당 View의 load/watch | HTTP 완료 순서·generation·선택 snapshot |

## 2. SkillOps Java: HTTP·인증

기본 경로 `service/src/main/java/com/capstone4/skillops/`. [전체 선언](source-index.md).

| 클래스·함수 | 호출 상황·역할 |
|---|---|
| SkillopsServiceApplication.main | Spring 시작, Bean·설정·MCP scanner 구성 |
| HealthController.health | 단순 프로세스 health JSON |
| AuthController.login | 로그인 입력 검증 후 설정 계정 탐색·BCrypt 비교·JWT 발급. 없는 사용자도 dummy hash 비교 |
| AuthController.me | 검증된 JWT에서 사용자 정보를 조회 |
| TokenService.issue | issuer/sub/name/roles/iat/exp와 HS256 header로 JWT 생성 |
| TokenService.userOf | JWT claim을 AuthUser로 변환; me·승인자 기록에서 공유 |
| AuthProperties compact constructor | tokenTtl 기본 2시간, users 기본 빈 목록·불변 복사 |
| SecurityConfig.securityFilterChain | 경로별 인증·OPERATOR 권한, stateless, CSRF 비활성, Bearer 검증 설정 |
| SecurityConfig.jwtAuthenticationConverter | roles 배열을 ROLE_ 권한으로 변환 |
| SecurityConfig.jwtSigningKey | 32바이트 이상 비밀 키 또는 기동 시 임시 난수 키 생성 |
| SecurityConfig.jwtEncoder/jwtDecoder | 같은 HS256 키로 발급·검증 Bean 구성 |
| SecurityConfig.passwordEncoder | BCrypt 비교기 Bean |
| SecurityConfig.unauthorized/forbidden/writeError | 401/403을 code/message JSON으로 출력; 401 Bearer challenge |
| ProposalController.list | 최근 제안서를 summary로 변환 |
| ProposalController.detail | 제안서·고정 본문·가장 최근 결정을 함께 조회; 없으면 404 |
| ProposalSummary.of / ProposalDetail.of | Entity와 payload/document를 REST DTO로 투영 |
| ProposalController.text/number/mapOf | Map 값을 표시 문자열·정수·Map으로 변환하는 보조 함수 |
| ApprovalController.approve | candidate_no·의도 확인과 JWT 사용자로 승인 서비스 호출 |
| ApprovalController.reject/recent | 제안서 거절 / 최근 결정 이력 조회 |
| ApprovalController.proposalRefused | ProposalException의 코드·HTTP status 보존 |
| ApprovalController.decidedConcurrently | 낙관적 잠금 충돌을 409 PROPOSAL_ALREADY_DECIDED로 변환 |
| ApprovalView.of | DB 승인 행에서 브라우저 공개 필드만 구성 |
| DiagnosisController.changed | 내부 서버 토큰·skill_saved/change_id 검사 후 Python /diagnose 중계 |
| DiagnosisController.list/status | 로그인 사용자의 진단 목록·상세를 서버 토큰으로 조회 |
| DiagnosisController.upstream/unavailable | downstream HTTP 오류 / 통신 오류를 404·502로 변환 |

생성자는 의존성(Repository·클라이언트·Clock·설정)을 주입합니다. 테스트용 생성자 오버로드는 실제 HTTP 클라이언트나 Clock을 대체하기 위해 사용하며 별도 공개 HTTP API가 아닙니다. AuthUser·LoginRequest/Response·ApproveRequest는 전달 객체이며 직접 업무 처리를 하지 않습니다.

## 3. SkillOps Java: MCP·snapshot 분석

| 클래스·함수 | 호출 상황·역할 |
|---|---|
| PingTools.ping | MCP 연결 점검용 pong+시각 |
| SkillDiffTools.diffSkill | MCP 입력을 diffService로 전달하고 ToolResults로 감쌈 |
| DependencyGraphTools.getDependencyGraph | 고정 snapshot/entry flow 정적 분석 요청 |
| WorkspaceTools.createWorkspace/applyPatch/runScenarios | 각각 workspace 생성·수정·시험의 MCP 진입점 |
| ProposalTools.saveProposal | proposal Map 저장의 MCP 진입점 |
| ToolResults.run | C-1 성공 JSON 및 OrderAgentException의 구조화 오류 |
| WorkspaceToolResults.run/error | C-2 성공 JSON과 입력·내부·upstream 오류를 공통 content로 구성 |
| OrderAgentClient.getSnapshot/readFile/readText | 조회 전용 HTTP. Manifest·경로 파일·UTF-8 본문. readText는 base64를 거부 |
| OrderAgentClient.call | 최대 3회 제한 GET 재시도 |
| OrderAgentClient.fromResponse | 주문 오류 code/message·retryable·status를 OrderAgentException으로 매핑 |
| OrderAgentClient.requireUuid/requestFactory/sleep | 참조 검증, connect/read timeout, 제한 backoff·interrupt 처리 |
| SkillDiffService.diffSkill | 양 snapshot Manifest에서 대상 파일 확인, 본문 diff·관련 resource hash 비교 |
| SkillDiffService.filesUnder/side/relatedFiles | Skill 디렉터리 목록 추출·양쪽 참조·resource 상태 구성 |
| SkillDiffer.diff | 순수 문자열 비교. modified/added/removed/unchanged, YAML 변경과 줄 hunk 생성 |
| SkillDiffer.toHunk | hunk 좌표·변경 줄·적용 가능한 diff·안정 hunk_id 생성 |
| SkillDiffer.frontmatterEndLine | 첫 변경이 header/body 어디인지 나눌 기준 줄 탐색 |
| SkillDiffer.frontmatterChanges/minus | YAML key별 차이와 목록 added/removed 계산 |
| SkillDiffer.lines/sha256 | 줄 목록 변환·식별 hash 계산 |
| Frontmatter.parse | 중복 키·alias를 제한한 Safe YAML로 --- 블록 해석 |
| DependencyGraphService.dependencyGraph | entry+steps 파일을 필요한 만큼 조회하고 순수 Analyzer 호출 |
| DependencyGraphService.fetch | Manifest에 있는 경로만 UTF-8로 조회 |
| DependencyAnalyzer.analyze | 순서·입출력·depends_on·모호한 공급자·불완전 노드 전체 계산 |
| DependencyAnalyzer.flowSteps | entry의 steps 조회; 없음/불량은 도구 오류 |
| DependencyAnalyzer.declaration/strings | name·중첩 flow·필수 문자열 목록 검증 |
| DependencyGraphResult.of | Manifest 식별자/hash와 순수 계산 결과를 MCP 응답으로 결합 |
| OrderAgentException의 접근자 | code/retryable/upstreamStatus/snapshotId를 오류 래퍼에 제공 |

DiffSkillResult/Side/RelatedFile, SkillFileDiff/FrontmatterChange/Hunk, DependencyGraph/Node/Edge/Missing/Ambiguous, SnapshotManifest/FileEntry, SnapshotFile, SkillSource는 계산·통신 결과 타입입니다. 생성되는 record accessor는 상태 변경을 하지 않습니다. 자세한 필드는 MCP 명세에 수록했습니다.

## 4. SkillOps Java: workspace·patch

| 함수 | 호출 상황·역할 |
|---|---|
| WorkspaceService.create(startVersions) | live 폴더 기반 생성의 단축 오버로드 |
| WorkspaceService.create(startVersions,snapshotId) | 시작점 충돌 검사, live 또는 snapshot 복사 후 workspaces 저장 |
| createFromSnapshot | skills/의 Skill·resource를 전부 읽어 source_snapshot_id와 함께 저장 |
| versionOf | SkillHeader에서 시작 선언 version을 추출; 없으면 거부 |
| skillsOf | 해당 workspace의 Skill 원문 Map 조회 |
| sourceSnapshotIdOf | harness가 snapshot 기반 workspace인지 확인할 때 사용 |
| filesOf | 현재 Skill 원문과 시작 시 복사한 resource를 경로→문자열 전체 묶음으로 export |
| apply | OPEN·시작 버전 검사 → 복사본에 순차 patch → 그래프 분석 → 전체 저장 |
| analyze | 고정 entry order-flow와 각 파일 hash로 DependencyAnalyzer 호출 |
| sha256/find/newId | 분석용 원문 hash / 없는 workspace 오류 / 난수 workspace ID |
| WorkspaceRecord.open | OPEN Entity 생성; live형·snapshot/resource형 오버로드 |
| WorkspaceRecord.replaceSkills | 성공적으로 수정한 전체 Skill Map 교체; 저장은 호출자 책임 |
| WorkspaceRecord.getResources | 과거 null 필드를 빈 Map으로 보완 |
| WorkspaceRecord의 나머지 getter | 저장된 ID·시각·상태·원문·source snapshot 반환 |
| WorkspaceSkill의 2인자 생성자 | baseVersion/fullText에 revision=0을 부여 |
| LiveFolderSkillVersionStore.liveVersions | 실제 폴더별 SKILL.md의 현재 version 목록 |
| LiveFolderSkillVersionStore.fullText | 이름·존재·요청 version 일치 후 현재 원문 반환; 역사 저장소 아님 |
| LiveFolderSkillVersionStore.versionOf/read | 현재 version 파싱·파일 읽기 예외 설명 |
| SkillHeader.parse | 단순 줄 파서로 name/version/inputs/outputs/steps 추출 |
| SkillHeader.indexOfClosing/list | --- 끝 탐색 / 대괄호 한 줄 목록 처리 |
| PatchApplier.apply | unified diff 적용, 원문의 끝 줄바꿈 여부 유지 |
| PatchApplier.hunkLines | 파일 헤더 제거·보완, 줄 접두어·빈 context 검사 |
| PatchApplier.checkHunkHeader/shiftInsertionHeaders | hunk 문법 검사, 삽입 좌표 라이브러리 차이 보정 |
| PatchApplier.firstMisfitLine | 적용 실패 때 원문과 맞지 않는 줄 근처를 설명 |

WorkspaceRepository는 JpaRepository를 상속하며 별도 업무 판단은 없습니다. SkillVersionStore는 liveVersions/fullText의 저장소 교체 경계입니다. DTO CreatedWorkspace/PatchInput/ChangedSkill/BrokenLink/PatchResult는 MCP 입출력용입니다.

## 5. SkillOps Java: Runner·Judge·원장

| 함수 | 호출 상황·역할 |
|---|---|
| ScenarioCatalog 생성자/get/ids | 기동 시 golden JSON을 읽고 중복 ID 검사; ID 조회·정렬된 가능 목록 |
| ScenarioRunner.run | 모든 입력·재현 주문 사전 검증 → scenario×repeat 실행 → Judge → ledger → 결과 집계 |
| ScenarioRunner.plan | catalog ID와 f-UUID/replay 별칭을 실제 계획으로 변환 |
| ScenarioRunner.expectationOf | catalog 조건 문법 오류에 시나리오 ID를 붙임 |
| Planned.replay/isReplay/withOrder | 재현 계획 생성·구분·원본 주문 결합 |
| FlowRunner.runDryRun/supportsReplay | 실행기 인터페이스. 기본 replay 미지원 |
| ContractOnlyFlowRunner.runDryRun | 선언 inputs/outputs만 순회, canned 출력과 fake ID 생성 |
| ContractOnlyFlowRunner.newRunId | contract-only의 r_fake 난수 ID |
| HarnessFlowRunner.runDryRun | snapshot 기반 확인, 파일 hash 캐시, 후보 발행, 직렬 dry-run, trace 변환 |
| HarnessFlowRunner.supportsReplay | 실제 harness 실행기로 재현 지원 |
| HarnessFlowRunner.publish | 후보 발행 결과를 파일 묶음 cache에 등록 |
| HarnessFlowRunner.bundleHash/update | 정렬된 경로·내용의 길이 prefix 포함 hash; 주문 snapshot hash와 다름 |
| OrderAgentRunClient.publishCandidate/dryRun/trace | C-2 HTTP 쓰기·실행·조회; 자동 POST 재시도 없음 |
| OrderAgentRunClient.originalOrder | trace.order_id에서 원주문을 조회해 실행 입력 4개만 재구성 |
| executionIdOf/notFound | UUID·r_별칭 정규화 / 재현 원본 없음 오류 |
| call | HTTP 4xx를 Rejected, 5xx·통신 오류를 IllegalStateException으로 변환 |
| isReadTimeout/slow/unreachable/causeOf | 연결 timeout과 read timeout 구분·설명 구성 |
| rejectedMessage/errorBody/text/required | 오류 JSON 필드 및 정상 응답의 필수 ID 검증 |
| listOfMaps/toStringKeys | 재현 주문의 Map/List를 null 보존하며 정규화 |
| requestFactory | connect 2초/read 60초 클라이언트 |
| Rejected.status/code/snapshotNotFound | upstream 거절 정보를 보존, 캐시 snapshot 재발행 조건 판단 |
| OrderAgentTraceConverter.toOutcome | SKILL_STARTED/FINISHED·EXECUTION_ERROR에서 Judge Step 생성 |
| attachError | 기존 마지막 오류 Step에 오류 부착 또는 정적 차단용 합성 Step 생성 |
| errorText/joined | category/message/누락 입력·의존성의 설명 문자열 |
| stepNo/mapOf/listOfMaps | trace 구조 변환·필수 step_no 확인 |
| StepBuilder.build | 시작했지만 끝나지 않은 Step을 error로 취급 |
| Expectation.of/replay | catalog 기대값 또는 FAILED 방지의 약한 재현 기준 생성 |
| ScenarioGrader.grade | 성공 Skill 순서·최종 상태·배송 수·조건을 비교하고 실패 이유 집계 |
| ScenarioGrader.contextOf | Step input/output을 순서대로 합쳐 조건 평가 문맥 구성 |
| Check.parse/literal/splitOutsideQuotes | ==/!=/in 제한 문법과 리터럴·목록 파싱 |
| Check.holds/same/actualText | flat key 비교·숫자 값 비교·실패 안내용 실제 값 |
| Check.unreadable | 지원하지 않는 조건 오류 구성 |
| JpaRunLedger.record/runIdsOf/known | trial 저장 / workspace의 ID 집합 / 전역 존재 확인 |

ScenarioRunRecord의 getter는 저장 trial 조회용이며 실제 판정 근거를 재계산하지 않습니다. ScenarioRunRecordRepository는 workspace별 검색, ScenarioRunReport/ScenarioResult/Failure·RunOutcome/Step·GradeResult는 내부·MCP 결과 데이터입니다. Scenario의 k 필드는 보존되지만 현재 Runner가 사용하는 반복값은 요청 repeat 또는 기본 2입니다.

## 6. SkillOps Java: 제안서·승인 저장

| 함수 | 호출 상황·역할 |
|---|---|
| ProposalService.save | thread 중복 확인·후보/상태/근거 검사·문서 작성·DB 저장 |
| checkCandidates | candidate_no 연속성과 valid 재계산·근거 검사 |
| checkEvidence | evidence가 후보 workspace의 ledger ID에 속하는지 확인 |
| checkRootCause | proposed의 원인 proof ID가 전역 ledger에 존재하는지 확인 |
| markDecided | 아직 결정 전인지·후보가 합격인지 확인하고 approved/rejected 저장 |
| find/recent/savedOf | 상세 조회·최근 100개·저장 결과 DTO 변환 |
| ProposalRules.passes | 제출 results의 합격 boolean·회귀 집계 논리식 |
| ProposalRules.map/list/maps/strings/str/integer/bool | 느슨한 Map payload를 해석하는 공통 값 변환. 타입 전체를 강제하는 schema가 아님 |
| Proposal.create/decide | Entity 생성 / 결정 시각·상태·승인 intent.confirmed 변경 |
| Proposal getter | payload/document/valid 목록·상태·시각 조회; 과거 null은 빈 document/목록으로 보완 |
| ProposalDocumentWriter.write | 고정 본문 작성 진입점 |
| Writer.document/title/summarySentences | 목차·제목·전체 요약 문장 조합 |
| Writer.failedAt | 오류 시각 파싱, 없거나 불량이면 제안서 생성 시각 사용 |
| whatHappened/cause | 실패 경위와 실제 멈춘 Skill/변경 원인 Skill 구분 |
| intentSection | 의도 인용·검증 조건·미검증 안내 |
| candidatesSection/candidatePart | 후보별 검증 결과·변경 Skill·근거 설명 |
| nothingTested/decision/reference | 시험 못 한 경우 안내·추천/승인 경계·참조 ID·시각 |
| toneWarnings | 후보 AI 설명에서 ToneCheck 위반 표현 수집 |
| section/categoryLabel/joined/add | 고정 절 생성·오류 라벨·ID 연결·빈 값 제외 |
| ToneCheck.findIn | 금지·과장·번역투 표현을 처음 등장한 순서로 반환; 본문 자동수정 없음 |
| ApprovalService.approve/reject | 제안서 결정+승인 행을 한 트랜잭션으로 저장 |
| recent/latestFor/newToken | 최근 50개·제안서 마지막 결정·승인 난수 토큰 생성 |
| Approval 생성자 및 getter | 승인자·결정·후보·token·used_at·이전 agent 전달 상태 보존/조회 |
| ProposalException.code/httpStatus | REST 결정 오류의 업무 코드·상태 제공 |

ProposalRepository는 thread_id·최근 생성 조회, ApprovalRepository는 최근 결정·제안서별 마지막 결정 조회입니다. ProposalDocument와 중첩 Section/Fact/ToneWarning은 고정 본문 구조이며 추가 모델 호출을 하지 않습니다.

## 7. 주문 Java: Controller·Service·Entity

기본 경로 `order-agent/backend/src/main/java/com/capstone4/orderagent/`.

| 함수 | 호출 상황·역할 |
|---|---|
| OrderAgentApplication.main | 주문 Spring 기동, scheduler 활성화 |
| ClockConfig.clock | UTC Clock 주입; 배송일·DB 기록에서 사용 |
| InterruptedOrderRecovery.run | readiness 수락 전 미완료 주문 복구 |
| HealthController.health | readiness에 따라 200/503 |
| ProductController.list / ProductService.getProducts | 활성 상품 DTO 목록 |
| OrderController.create/get/byRequest/uuid | 주문 접수·조회·멱등 키 조회·엄격 UUID 형식 검사 |
| EvidenceController.legacyTrace/recent/trace/failed | 별칭·최근 실행·상세 원문·live 실패 목록 |
| EvidenceController.skills/skill | 고정 snapshot 메타데이터·원문 조회 |
| changeDelivery/saveEdits | outbox 상태 조회 / 편집 snapshot 저장과 접수 참조 응답 |
| publish/publishCandidate | 시연 fixture / 후보 전체 파일 발행 |
| snapshot/file/manifest | Manifest·파일 조회·응답 조립; 파일 UTF-8 불가면 base64 |
| DryRunController.run | demo flag·입력 검증 후 dry-run 서비스 호출 |
| NormalizedOrder.from | 고객 strip, 같은 SKU 합산·정렬, 수량 상한, 요청 hash 생성 |
| OrderService.createOrder | 기존 키 복구·접수 충돌 복구·claim·실행·오류와 저장 결과 연결 |
| OrderService.getOrder/getByRequestId | lifecycle 조회로 위임 |
| OrderService.hasDatabaseCause | 원인 체인의 DataAccessException 여부로 500/503 분류 |
| OrderLifecycleService.existing | 요청 키 존재 시 해시 일치 검사 |
| accept | 상품 활성/존재·가격 확정, INTAKE 단계와 새 주문 저장 |
| claim | CREATED 주문을 조건부 UPDATE로 한 실행자가 PROCESSING으로 전환 |
| get/byRequest/find | Entity를 API 응답으로 변환, 없는 주문 404 |
| fail | 미완료 주문을 FAILED로 바꾸고 실패/이후 SKIPPED 단계 기록 |
| recoverInterrupted | 미완료 전체 주문의 미결제 예약 해제·실행 INTERRUPTED·주문 FAILED |
| OrderProcessingService.process | live/dry-run 공통 Harness 진입점; 장시간 트랜잭션 없음 |
| DryRunService.run | snapshot 지정 새 dry_run 주문·동일 Harness·최종 cleanup·실행 ID 반환 |
| DryRunService.cleanup | 남은 예약 전량 반환·RELEASE 기록; 업무 결과와 trace는 보존 |
| InventoryRecovery.releaseUnpaid | 기동 복구 트랜잭션 안에서 미결제 예약만 해제·증거 기록 |
| OrderResponse.from | 확정 Entity·Item·Step을 snake_case API DTO로 투영하고 status별 안내 생성 |
| GlobalExceptionHandler.agent/business/validation/invalidInput/malformed/database/unexpected/response | 각 예외를 오류 DTO·HTTP status에 대응; AgentException의 NOT_FOUND suffix 판단 |
| BusinessException.getCode/getOrderId | 오류 응답의 업무 코드·주문 참조 제공 |
| ProcessingFailure.getStep | 롤백 경계 바깥에 실패 단계만 전달 |

Order Entity의 상태 변경은 다음과 같이 보시면 됩니다.

| 함수군 | 역할·선행 조건 |
|---|---|
| 생성자, addItem | CREATED 주문 생성·상품명/단가 고정·금액 합산 |
| addStep | 업무 기록 번호를 붙여 추가하고 updated_at 갱신 |
| requireProcessing | 업무 변경 전 PROCESSING 강제 |
| reject/hold/noSelection | 재고 거절·결제 보류·선택 없음으로 종료 |
| paid/shipped/notified/complete | 결제·배송·알림 참조 기록 후 완료. complete는 배송과 알림을 요구 |
| fail | CREATED/PROCESSING만 FAILED로 전환; 이미 종료된 결과 보존 |
| useSnapshot/markDryRun | CREATED일 때만 요청 snapshot·실행 모드 지정 |
| reserved/released | 재고 예약 소유 플래그 설정·해제 |
| get*/is* | 저장된 필드·읽기 전용 items/steps 반환. 새로운 업무 실행 없음 |

Product.reserve/release는 단일 상품 수량을 변경하며 전량 검증·잠금은 상위 adapter/service가 담당합니다. OrderItem 생성자·getLineTotal 등은 상품·가격·수량 snapshot을 보존합니다. OrderProcessingStep 생성자·getter는 단계 기록을 보존합니다. enum 및 DTO record는 API 명세의 값 범위를 따릅니다.

OrderRepository.lockById·ProductRepository.lockBySkus는 비관적 쓰기 잠금, OrderRepository.claim은 조건부 상태 전환, findByRequestId/findByStatusIn/상품 검색 메서드는 해당 조회입니다. JpaRepository 상속 API는 프레임워크 기본 저장·검색 경계입니다.

## 8. 주문 Java: Harness·업무 도구·Mock

| 함수 | 호출 상황·역할 |
|---|---|
| OrderHarness.execute | 실행 한도·trace 시작, snapshot 고정, flow 선택·정적 검사, 단계 입력·도구·출력 관찰, 업무 종료·보상 |
| finishedStep | 실제 step_no/version/status/output/duration과 추가 상태를 trace에 기록 |
| present/project | 선언 outputs 중 존재하는 것만 / 전부를 추출 |
| loaded/stepType | 파일 로드 관찰·Skill을 업무 StepType에 대응 |
| SkillLoader.load | 이름·UTF-8·--- 경계·Safe YAML·필수 metadata/body·문자열 목록 검사 후 Skill 생성 |
| validateName/strings | 경로 탈출 방지 이름 문법 / 목록 타입·필수 여부 |
| candidates | 실제 고정 파일에서 선택용 name/description/version 제공 |
| validateFlow | 중복·단계 수·중첩 flow·선언 공급과 선행 관계를 업무 효과 전 검사 |
| validateInputs | 정적/런타임 공용 공급·depends_on 검사, 정확한 실패 상세 생성 |
| SkillExecutor.decide | fixed 또는 virtual-thread 모델 호출, timeout·예산·허용 일시 오류 재시도·JSON parse |
| SkillExecutor.close/mode | worker 종료 / provider 모드 확인 |
| DecisionProvider.decide/mode | 모델 선택 구현의 작은 인터페이스; Question·Reply가 입력/응답 경계 |
| FixedDecisionProvider.decide/mode | 본문을 해석하지 않는 order-flow·서버 도구 요청 생성 / fixed |
| OpenAiDecisionProvider.decide/mode | 제한 시스템 지침·Question으로 실제 모델 JSON 호출, 토큰 관측·예외 분류 / openai |
| ExecutionControl.check/remainingMs | 전체 실행 deadline 검사·남은 시간 |
| ExecutionControl.attempt/observe/elapsedMs | API 시도수 제한·관측 토큰 누적 제한·측정 시간 |
| ToolCatalog.toolFor | Skill별 허용 도구 고정 binding |
| ToolCatalog.arguments | 서버 주문 문맥에서 기대 인자 생성 |
| ToolCatalog.definition | 모델에 전달할 해당 도구 이름·설명 |
| ToolDispatcher.execute | 요청 관찰 → tool binding 검사 → 업무 실행 → 오류 관찰 |
| OrderToolService.initial/context/status | 서버 확정 입력·요청 snapshot/dry-run 모드·현재 상태 조회 |
| OrderToolService.execute | 주문 lock·실행 RUNNING·멱등 캐시·인자 hash 검증 후 도구별 업무 처리 |
| OrderToolService.complete | 주문 완료와 실행 완료·종료 증거를 같은 트랜잭션에 기록 |
| compensateUnpaid/release | 미결제 예약 보상, inventory.release 요청·결과 증거 |
| noSelection | REJECTED/NO_SELECTION과 미실행 업무 단계 기록 |
| quantities/step/skip | SKU 수량 Map·업무 성공/거절/해제·후속 SKIPPED 기록 |
| InventoryMockAdapter.reserve | 모든 상품이 충분할 때만 전체 차감; 일부 차감 없음 |
| InventoryMockAdapter.release | 예약 수량 반환; 호출자가 lock/트랜잭션 소유 |
| PaymentMockAdapter.pay | 예약·금액·method 검사, c_declined+card만 거절, Mock 참조 생성 |
| ShippingMockAdapter.reserve | 결제·예약·국가 검사, UTC Clock 기준 국내 +2/해외 +7일·express -1 |
| NotificationMockAdapter.send | 배송 참조 존재 확인; 실제 외부 발송 없음 |
| Json.write/object/hash(byte[])/hash(Object)/canonical | 공통 JSON 변환·원문 SHA256·정렬/숫자 정규화된 JSON hash |
| AgentException.category/details | 실행 오류 범주·선언 위반 상세 보존 |

실제 도구 목록과 정확한 인자·출력은 [MCP 문서 10절](mcp-spec.md#10-harness-내부-업무-도구-전체)에 있습니다. 모델이 ‘했다’고 쓴 응답이 실제 도구 성공을 대체하지 않도록 현재 관찰 경계를 유지해 주세요.

## 9. 주문 Java: snapshot·trace·outbox

| 함수 | 호출 상황·역할 |
|---|---|
| SnapshotService.readPublishedBundle | bundle.json 경로·파일 집합·hash와 전후 manifest 일치, symlink 금지 |
| capture | 게시 bundle을 source/release metadata와 snapshot으로 저장 |
| saveEdits | baseline hash·경로·크기 검사, 바뀐 파일 병합, 새 snapshot+outbox 원자 저장 |
| publish | 전체 경로·수/크기 검사, byte 복제·UUID/hash 후 snapshot 저장 |
| get | DB snapshot을 읽어 전체 hash 재검증 |
| demo | baseline/broken-contract/repaired 준비 파일 변형 후 발행 |
| bundleHash/validatePath | metadata 포함 hash / 경로 segment 제한 |
| SnapshotRepository.insert/get | Manifest·파일 바이트 저장 / 각 파일 hash 검사 후 전체 복원 |
| SkillQueryService.list | 실제 실행 로더로 메타데이터 해석, 실패 파일은 partial/parse_errors |
| SkillQueryService.get/metadata | Skill 원문+meta / 공통 메타데이터 필드 구성 |
| SkillChangeDispatcher.dispatch/status | 잠금 outbox 전송·thread 연결·실패 기록 / 사용자 상태 조회 |
| TraceService.begin/fixSnapshot/attach/selection | 실행 시작·파일 고정·후보 관찰·원문/해석 선택 기록 |
| event/apiAttempt/modelResult | 일반 사건·실제 API 시도 수·원문 모델 응답/토큰 관측 |
| finish/get/failed/recent | 실행 종료 및 종료 이벤트 / 상세·live 실패·최근 조회 |
| ExecutionRepository.begin/attach/selection | agent_executions 행·주문 execution 연결·선택 상태 저장 |
| append | 행의 event_sequence를 증가시키고 사건 삽입 |
| apiAttempt/usage/finish | 호출·관측 토큰 누적 / RUNNING일 때만 종료 상태 반영 |
| get | 실행과 snapshot hash·sequence 순 전체 events 조회 |
| failed | live FAILED/INTERRUPTED와 마지막 실제 오류 상세 조회 |
| recent/summary | 최근 20개 ID를 get으로 읽고 events 제거 |
| runningForOrder | 기동 복구 대상 실행 찾기 |
| cached/operation | 실행·도구의 확정 결과 캐시 조회·인자 충돌 검사 / 업무 결과 저장 |

snapshot_id는 불변 참조이고 version은 설명 값입니다. 파일·snapshot hash 검증과 원문 trace 보존은 진단의 근거이므로 성능 개선 중에도 제거하지 않으시는 것이 좋겠습니다.

## 10. Python: HTTP·상태·도구 연결

기본 경로 `skillops-agent/skillops_agent/`.

| 파일·함수 | 호출 상황·역할 |
|---|---|
| main.lifespan | 서버 시작 시 RunStore.recover로 미완료 표시 |
| DiagnoseRequest.validate_references | failure·saved·diagnosis_only별 참조, UUID와 실행 별칭 일치 검사 |
| verify_token | 설정 시 X-Service-Token 비교 |
| _run_pipeline | thread 설정·초기 state로 그래프 실행, 최종 SQLite 갱신·예외 기록·이전 승인 매핑 |
| _resume_pipeline | 이전 승인 interrupt용 Command 재개; 현행 graph 경계와 불일치 |
| health/get_run_events/list_runs/diagnose/approve | 각각 FastAPI 라우트. 상세 계약은 API 문서 참조 |
| RunStore.__init__/connect | DB 디렉터리·테이블 준비 / timeout 10초 SQLite 연결 |
| RunStore.accept | BEGIN IMMEDIATE로 change_id·fingerprint 멱등 검사 후 QUEUED 저장 |
| update/get/list/list_all/recover | 부분 JSON merge·상세·최근 100·전체·미완료 ERROR 처리 |
| graph.error_text | ExceptionGroup을 하위 메시지로 펼침 |
| graph.guarded/run | node 예외를 ERROR로 변환하고 node별 결과를 RunStore에 저장 |
| graph.next_node/route | error가 있으면 END, 아니면 다음 node |
| evidence_route/ablation_route/valid_route | 증거·원인/재시도·합격/재시도의 조건부 그래프 분기 |
| tools.MCPTools.__aenter__/__aexit__ | MCP 세션+공유 주문 httpx 연결 수명 관리·종료 |
| MCPTools._call | 도구 호출·isError 검사·단일 JSON text 해석 |
| MCPTools.list_failed_runs/get_trace/list_skills/get_skill | 주문 REST wrapper. MCP 등록 도구가 아님 |
| MCPTools.get_snapshot | Manifest 직접 REST GET |
| MCPTools.ping/diff_skill/get_dependency_graph/create_workspace/apply_patch/run_scenarios/save_proposal | 현재 Java 매개변수 이름으로 arguments 구성하여 MCP 호출 |
| MCPToolError.__init__ | 도구명·구조화 payload·code를 계약 예외로 보존 |
| get_tools | 환경변수 USE_STUB_MCP에 따라 실제/Stub 구현 선택 |
| mcp_client.mcp_client | mcp.Client Streamable HTTP 클라이언트 생성 |
| order_agent.get_trace/list_failed_runs/list_skills/get_skill | ID 검증 후 raw 주문 응답 그대로 조회 |
| order_agent._get/_get_with_retry | 독립/공유 httpx 사용, 최대 3회 제한 GET 재시도 |
| _from_response/_require_uuid | upstream 오류 구조 보존 / 보내기 전 UUID 검사 |
| OrderAgentError.__init__ | code·retryable·status·execution/snapshot 참조 보존 |
| llm.make_llm/make_structured_llm | ChatOpenAI 생성 / Pydantic structured output 구성 |
| prompts.load | 이름에 대응하는 prompts/*.md UTF-8 로드, 없으면 FileNotFoundError |

config.py는 dotenv·주소·모델·서버 토큰·stub 여부·golden ID를 읽습니다. state.py의 TypedDict는 타입 안내이며 런타임 전체 JSON 검증기가 아닙니다. `__init__.py`는 패키지 경계입니다.

### 계약 보조 함수 전체

| contracts.py 함수 | 역할 |
|---|---|
| execution_uuid | UUID/r_별칭을 소문자 정식 UUID로 변환 |
| event_data | trace event.data가 dict인지 검사 |
| trace_context | SKILL_FINISHED 출력 문맥만 합침; 현재 핵심 검증 경로에서는 별도 Judge 사용 |
| context_keys | SKILL_STARTED input과 SKILL_FINISHED output의 관측 flat key 수집 |
| _is_literal/_split_outside_quotes | C Check와 같은 리터럴·쉼표 목록 인식 |
| check_problem | 조건 문법과 관측된 키 여부를 검사, 제거 이유 또는 None 반환 |
| tools_called | TOOL_REQUEST에서 도구명을 추출. 실제 성공 도구만의 목록이 아님 |
| validate_report | workspace·시나리오·반복·run ID·failure·all_passed 계약 검사 |
| reverse_hunk | 단일 unified hunk의 old/new 좌표와 +/-를 뒤집음 |

## 11. Python: 진단 node 전체

| node·helper | 상황과 동작 |
|---|---|
| init | 각 state 필드를 초기값으로 구성, RUNNING 시작 |
| fetch_evidence | 실제 snapshot/trace/Manifest hash·변경 경로·Skill listing·diff 검증. 저장 이벤트는 기준/후보 사전 시험, diagnosis_only는 의도 재현 |
| hypothesize | trace·변경 hunk·이전 실패로 structured 가설 생성 |
| ablate | 원본 실패 대조군 replay 후 가설별 새 workspace에서 단일 hunk 역패치·재실행 |
| check_ablation | 첫 확인된 실험을 root_cause/proof로 변환하거나 재시도 수 증가 |
| route_ablation | 원인 확인·3회·step 한도에 따라 다음 경로 선택 |
| _known_values | 복원 성공 trace의 입력·출력 키, 없으면 기존 trace 키 |
| extract_intent | 모델의 의도와 checks를 받고 C 문법·실제 키에 안 맞는 조건을 이유와 함께 제거 |
| generate_candidates | 그래프·모든 현재 원문을 모델에 제공, 전체 수정 파일을 받아 difflib patch 생성 |
| verify_candidates | 후보별 독립 workspace 적용, 회귀·재현·선택적 의도 시험, 실제 도구 요청 목록·근거 ID 수집 |
| check_valid/route_valid | 합격 후보 유무·후보 재시도 3회·단계 한도로 분기 |
| rank_and_explain / 중첩 sort_key | 합격 후보만 연결 일관성·변경 Skill 수 순서 정렬, 설명은 모델 생성 |
| _failure_of | 원본 EXECUTION_ERROR를 proposal.failure로 투영 |
| proposal_payload | workspace 필드명을 workspace_id로 바꾸고 추천 인덱스를 candidate_no로 변환 |
| save_proposal | Java에 제안서 저장 후 SAVED/id/valid 후보와 EVALUATED 상태 기록 |
| wait_for_approval/route_approval | 이전 interrupt/승인 분기 함수. 현행 graph에는 등록되지 않아 일반 진단에서 호출되지 않음 |
| _error_event | 첫 EXECUTION_ERROR 조회 |
| _run | run_scenarios를 호출하고 계획한 모든 결과를 validate_report로 검증 |
| _now/_event/_tick | UTC 진행 시각·이벤트·단계 수 업데이트 |
| _is_limit_exceeded | step_count와 max_steps 비교; node 내부 반복 총량 제한은 아님 |

_HypothesisItem/_HypothesesOutput/_IntentOutput/_PatchSpec/_CandidateSpec/_CandidatesOutput/_ExplanationItem/_RankExplanationsOutput은 모델 출력 형식입니다. confidence 범위·후보 수·kind enum 등을 모든 곳에서 강제하지는 않습니다. stub_tools.py는 같은 메서드 경계의 가짜 snapshot·trace·workspace·도구 결과를 만들어 unit/demo에서 사용합니다. 실제 네트워크·업무 효과의 증거가 아닙니다.

## 12. Vue/JavaScript 두 화면

### 통합 frontend

| 파일·함수군 | 사용자 상황·역할 |
|---|---|
| api/client.request/get/post/connectAuth/needsToken | 프록시 HTTP 공통 입구, /service 토큰, timeout, 오류 변환, mock 분기·인증 연결 |
| api/auth.login/me | 사용자 로그인·토큰 사용자 조회 |
| api/diagnoses.listDiagnoses/getDiagnosis/getChangeDelivery | 진단 목록·실행 상태·outbox 접수 추적 |
| api/orderAgent.createOrder/recover/sleep | 새 주문 키 생성, 500의 order_id 조회 또는 응답 유실 시 같은 키 polling |
| api/orderAgent의 나머지 조회·pinFixture/saveSkillEdits | 주문·상품·실행·snapshot·Skill·file 조회, 시연 세트 발행·편집 저장 |
| api/proposals.listProposals/getProposal/approveProposal/rejectProposal/listApprovals | 저장 제안서·결정 경계 |
| stores/auth.restore/persist/token/login/logout/hasRole | 탭 sessionStorage·만료·로그인·클라이언트 역할 표시 |
| stores/workspace.restore/saveDrafts | snapshot별 브라우저 초안 복원 / 명시적 서버 저장. 저장 중 추가 편집 보존 |
| ensureSet/select/refreshExecutions/pin | 서버 Skill 세트 로드·선택·최근 실행·시연 후보 |
| fixtureOf/labelOf | snapshot metadata/고정 이력에 맞는 사람용 라벨 |
| setDraft/removeDraft/clearDrafts | 브라우저 초안만 변경 |
| applyPatchesToDraft | 후보 patch를 현재 초안에 전부 적용 가능할 때만 반영; fuzz=1 |
| workspace.init | 저장 선택 복구, 없으면 최근 실행의 snapshot 선택 |
| stores/toast.show/dismiss | 결과 안내를 일정 시간 표시·제거 |
| utils/frontmatter.splitSkillMd/parseSkillMd/replaceSteps | 초안 YAML·본문 분리와 파싱 / steps 줄 편집 |
| utils/skillSet.formatProblem/toSkill/fromServer/loadSkillSet | 초안 검사·UI 모델 변환·서버 metadata와 원문 결합, partial 파일 오류 보존 |
| skillPath/flowSteps | 고정 Skill 경로·order-flow 순서 |
| utils/flowGraph.buildGraph/problemCount | 편집 중 그래프·누락 공급·선행 관계 미리보기와 문제 수 |
| utils/traceRun.traceRun/isErrorEvent/executionLabel/executionPath | 원본 관찰 사건을 단계 overlay·오류·실행 링크로 변환 |
| utils/eventView.keyLabel/toolLabel/formatValue/fieldsOf/parseModel/describe | 원본 data를 읽기 쉬운 한국어 항목·이벤트 안내로 표시 |
| utils/proposalView.awaitsDecision | proposed/no_valid_fix를 아직 결정 전으로 분류 |
| router.beforeEach | 로그인 이외 화면에 로그인 이동 적용; 서버 권한 검증은 별도 |
| LoginView의 submit 계열 | 로그인 후 next 경로로 이동 |
| SkillDraftView의 편집·저장·이동 함수 | Skill 원문/흐름 초안, 저장 후 change_id로 진단 상태 화면 연결 |
| SnapshotCompareView의 로드·비교 함수 | 선택한 두 불변 snapshot 비교, 브라우저 diff 표시 |
| OrderView의 실행 함수 | 선택 snapshot을 지정하여 주문하고 실행 상세 연결 |
| ExecutionView의 조회 함수 | trace·실행 snapshot의 실제 파일과 단계 관찰 표시 |
| DiagnosisView.refresh/watch/unmount | change_id→thread 전환, 2초 polling, generation으로 늦은 응답 차단 |
| ProposalListView의 조회·필터 | 검토 대기·결정 상태·후보 수 목록 |
| ProposalView.load/decide/applyToDraft | 저장 본문·후보 선택, 역할/의도 확인 후 결정, 후보 내용을 브라우저 초안으로 가져오기 |
| AppSidebar·SnapshotPicker 등의 함수 | 탐색·검토 건수·snapshot 선택·새 시연 세트 고정 |
| DiffView·EventTimeline·ProposalDocument 등의 함수 | diff 행·관찰 순서·고정 문서 sections/parts 표시 |
| mocks.handleMock 및 helper | 서버 없는 화면 개발용 응답·상태, 실제 검증 결과와 분리 |

각 View/component 내부의 정확한 선언명과 인자는 전체 선언 색인 4절에 수록했습니다. computed/watch/onMounted 등의 이름 없는 콜백은 해당 화면의 표시·수명주기 문맥으로 읽으시면 됩니다. CSS·template만 있는 컴포넌트도 별도로 표시했습니다.

### 독립 주문 frontend

| 파일·함수군 | 사용자 상황·역할 |
|---|---|
| api/client.request / ApiError 생성자 | AbortController·timeout·외부 signal·JSON·HTTP/네트워크 오류 보존 |
| api/orders.createOrder/getOrder/getByRequest | 같은 request key·body로 주문/조회 |
| api/products.listProducts | 현재 상품 목록 |
| api/evidence.listExecutions/getTrace/getSnapshot/getSnapshotFile/publishSnapshot | 독립 주문 화면의 증거 패널·시연 snapshot |
| useOrder.loadProducts | generation 기반 최신 상품 로드·오류 표시 |
| useOrder.accept | 응답 ID·알려진 상태 검증, 완료 시 입력 해제·상품 새로고침 |
| useOrder.failed | 접수 전 검증 오류와 접수 불확실 네트워크 오류 구분 |
| useOrder.finish/wait | CREATED/PROCESSING 조회, polling 기한 이후 수동 확인 상태 |
| useOrder.dispatch/submit | 입력을 복사·UUID 고정해 전송; double submit 방지 |
| useOrder.recover/resend | known order 또는 같은 키로 조회, 확실한 미접수일 때만 동일 요청 재전송 허용 |
| useOrder.preset/newOrder | 데모 입력 채우기 / 완료 뒤 새 주문 상태 생성 |
| useOrder.dispose/active | 페이지 이탈 시 취소, 세대가 지난 비동기 결과 무시 |
| utils/validation.defaultForm/validate 및 format 함수 | 기본 주문·입력 검사·서버 가격 기반 예상액·시각/금액 표시 |
| ExecutionInspector의 로드·선택 함수 | 최근 실행·원본 trace·snapshot 파일·모델/도구 증거 표시 |
| OrderForm·DemoPresets·OrderResult·OrderSteps | 입력/emit·예시·업무 상태·확정 steps 표시 |

두 frontend의 request/복구 함수는 별도 구현입니다. 기능을 통합하실 때 타임아웃 뒤 같은 키·같은 입력을 유지하는 보장과 late response 방어를 함께 보존해 주세요.

## 13. 프롬프트·스크립트·테스트 읽는 순서

prompts/hypothesize는 hunk 가설, extract_intent는 의도+관측 키 조건, generate_candidates는 현재 전체 파일 기반 후보, rank_and_explain은 이미 정한 순위의 설명, style은 공손한 한국어 표현 규칙입니다. 자연어 prompt는 서버 권한·도구 검증·Judge의 대체 수단이 아닙니다.

| 파일 | 실행 목적·주의점 |
|---|---|
| scripts/mcp_smoke.py | 도구 목록+ping; 전체 파이프라인 성공 검사 아님 |
| scripts/mcp_call.py | --list 또는 도구 한 번 호출; docstring의 ws_id 예시는 이전 계약 |
| scripts/c2_smoke.py | live폴더/기본 contract-only에서 workspace·g-01·잘못된 조건 오류 검사 |
| scripts/c2_real_judge.py | 실제 C MCP·주문 API로 준비 후보 판정, 일부 실행 방식은 live 실패 1건 생성 |
| scripts/b_c_contract_smoke.py | 실제 서버 경계+가짜 진단 LLM, snapshot·dry-run·edits/제안서 저장 및 불변량 확인. edits는 dispatch가 켜져 있으면 실제 자동 진단을 유발할 수 있음 |
| scripts/run_demo.py | 이전 계약의 가짜 LLM 데모; 현재 필드·lint·승인 END와 불일치하여 정비 필요 |
| order-agent/scripts/publish-skills.py | 게시 Skill 묶음의 hash manifest 작성 경계 |
| order-agent/scripts/smoke.py | 실제 정상 주문을 생성하며 재고 소비 |
| order-agent/scripts/check-evidence.py | 실제 실행·snapshot 증거 검증; 실행 옵션·서버 전제 먼저 확인 |
| docs/reports/b-c-contract-probe.py | 이전 통합 감사의 계약 점검 도구·결과와 함께 해석 |

테스트는 다음 순서로 읽으시면 경계를 이해하기 쉽습니다.

1. 주문 MockPolicy/SkillBoundary/DecisionLimit: 허용 정책·선언·예산.
2. 주문 OrderApi/AgentEvidence/DiagnosisReadApi/CandidateSnapshot/DryRun Integration: DB 효과와 증거. 전용 DB opt-in 필요.
3. service Workspace/Patch/ScenarioGrader/TraceConverter/Runner/HarnessFlowRunner: 후보 적용·실제 실행 변환·판정 경계.
4. service ProposalService/DocumentWriter/Approval/Auth/Controller: 근거 검증·고정 본문·권한·결정 저장.
5. Python test_scenarios/test_checks/test_graph/test_run_store: 계약 변환·의도 조건·분기·중복 접수·재시작 상태.
6. frontend/tests 및 order frontend tests: 진단 화면·동일 키 복구·사용자 흐름.

개별 Java/Python 테스트 함수는 [전체 선언 색인 7절](source-index.md#7-javapython-테스트-선언)에, 나머지 프런트 테스트와 설정·Skill·시나리오 파일은 같은 문서 8절에 연결했습니다. 이번 실행 결과와 미검증 범위는 [구조·개선 리뷰 10절](architecture-review.md#10-이번에-실행한-검증)에서 확인하실 수 있습니다.
