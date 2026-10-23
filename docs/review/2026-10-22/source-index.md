# 전체 소스 선언 색인

> **과거 검토 기록 — 기준 `ec39713`.** 이 문서의 “현재”, 미완료 항목, 테스트 수와 줄 번호는 작성 당시 기준입니다. 최신 구현·계약·#72 완료 상태는 [리뷰 문서 안내](../README.md)를 우선합니다. 아래 제안을 현재 API나 새 작업 승인으로 간주하지 않습니다.

기준: 2026-10-22, `ec39713`. [함수 역할 안내](function-reference.md) · [문서 안내](README.md).

Java는 JDK Compiler Tree API, Python은 ast, JavaScript/Vue script는 Babel parser로 선언을 추출했습니다. 정규식으로 호출문을 함수 선언으로 추정하지 않았습니다. Java 생성자·오버로드·private helper·인터페이스 메서드, Python 중첩 함수, JS 이름이 붙은 화살표 함수도 포함합니다. 라이브러리 상속 메서드·record의 자동 생성 accessor·이름 없는 callback·Vue template 식은 별도 함수 항목으로 열거하지 않았습니다. 람다와 template의 실행 문맥은 함수 역할 안내를 참고해 주세요. 줄 번호는 분석 시점 원본 기준이며, 추적 소스 링크를 ec39713의 GitHub 원본에 고정했습니다.

각 항목은 선언 시그니처이며 동작의 전체 보장은 아닙니다. 입력 검증·저장 효과·트랜잭션·예외·호출 시점은 API/MCP 명세와 함수 역할 안내를 함께 읽어 주세요.

## 1. SkillOps Java 운영 소스

78개 파일, 명시적 함수·메서드·생성자 294개.

### service/src/main/java/com/capstone4/skillops/SkillopsServiceApplication.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/SkillopsServiceApplication.java) · 타입: `SkillopsServiceApplication`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/SkillopsServiceApplication.java#L9) | `public static void main(String[] args)` |

### service/src/main/java/com/capstone4/skillops/adapter/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/adapter/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/adapter/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/api/ApprovalController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ApprovalController.java) · 타입: `ApprovalController`, `ApproveRequest`, `ApprovalView`

| 위치 | 선언 |
|---|---|
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ApprovalController.java#L45) | `public ApprovalController(ApprovalService approvals)` |
| [L49](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ApprovalController.java#L49) | `public ResponseEntity<?> approve(String proposalId, ApproveRequest request, Jwt jwt)` |
| [L61](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ApprovalController.java#L61) | `public ApprovalView reject(String proposalId, Jwt jwt)` |
| [L67](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ApprovalController.java#L67) | `public Map<String, List<ApprovalView>> recent()` |
| [L72](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ApprovalController.java#L72) | `ResponseEntity<Map<String, String>> proposalRefused(ProposalException e)` |
| [L78](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ApprovalController.java#L78) | `ResponseEntity<Map<String, String>> decidedConcurrently()` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ApprovalController.java#L92) | `public static ApprovalView of(Approval a)` |

### service/src/main/java/com/capstone4/skillops/api/HealthController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/HealthController.java) · 타입: `HealthController`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/HealthController.java#L13) | `public Map<String, String> health()` |

### service/src/main/java/com/capstone4/skillops/api/ProposalController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java) · 타입: `ProposalController`, `ProposalSummary`, `ProposalDetail`

| 위치 | 선언 |
|---|---|
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java#L35) | `public ProposalController(ProposalService proposals, ApprovalService approvals)` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java#L40) | `public Map<String, List<ProposalSummary>> list()` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java#L45) | `public ResponseEntity<?> detail(String proposalId)` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java#L59) | `static ProposalSummary of(Proposal p)` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java#L75) | `static ProposalDetail of(Proposal p, ApprovalView decision)` |
| [L82](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java#L82) | `private static String text(Object value)` |
| [L86](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java#L86) | `private static Integer number(Object value)` |
| [L90](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/api/ProposalController.java#L90) | `private static Map<String, Object> mapOf(Object value)` |

### service/src/main/java/com/capstone4/skillops/approval/Approval.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java) · 타입: `Approval`

| 위치 | 선언 |
|---|---|
| [L60](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L60) | `protected Approval()` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L63) | `public Approval(String proposalId, Integer candidateNo, String decision, boolean intentConfirmed, String approvedBy, String approverName, String approvalToken, Instant decidedAt)` |
| [L77](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L77) | `public UUID getId()` |
| [L81](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L81) | `public String getProposalId()` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L85) | `public Integer getCandidateNo()` |
| [L89](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L89) | `public String getDecision()` |
| [L93](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L93) | `public boolean isIntentConfirmed()` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L97) | `public String getApprovedBy()` |
| [L101](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L101) | `public String getApproverName()` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L105) | `public String getApprovalToken()` |
| [L109](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L109) | `public Instant getDecidedAt()` |
| [L113](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L113) | `public Instant getUsedAt()` |
| [L117](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L117) | `public String getAgentResult()` |
| [L121](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/Approval.java#L121) | `public String getAgentMessage()` |

### service/src/main/java/com/capstone4/skillops/approval/ApprovalRepository.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalRepository.java) · 타입: `ApprovalRepository`

| 위치 | 선언 |
|---|---|
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalRepository.java#L11) | `List<Approval> findTop50ByOrderByDecidedAtDesc()` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalRepository.java#L13) | `Optional<Approval> findTopByProposalIdOrderByDecidedAtDesc(String proposalId)` |

### service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java) · 타입: `ApprovalService`

| 위치 | 선언 |
|---|---|
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java#L29) | `public ApprovalService(ApprovalRepository approvals, ProposalService proposals)` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java#L34) | `ApprovalService(ApprovalRepository approvals, ProposalService proposals, Clock clock)` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java#L41) | `public Approval approve(String proposalId, int candidateNo, boolean intentConfirmed, AuthUser user)` |
| [L49](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java#L49) | `public Approval reject(String proposalId, AuthUser user)` |
| [L56](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java#L56) | `public List<Approval> recent()` |
| [L61](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java#L61) | `public Optional<Approval> latestFor(String proposalId)` |
| [L66](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java#L66) | `private String newToken()` |

### service/src/main/java/com/capstone4/skillops/approval/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/approval/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/auth/AuthController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/AuthController.java) · 타입: `AuthController`, `LoginRequest`, `LoginResponse`

| 위치 | 선언 |
|---|---|
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/AuthController.java#L33) | `public AuthController(AuthProperties properties, PasswordEncoder passwords, TokenService tokens)` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/AuthController.java#L39) | `public ResponseEntity<?> login(LoginRequest request)` |
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/AuthController.java#L55) | `public AuthUser me(Jwt jwt)` |

### service/src/main/java/com/capstone4/skillops/auth/AuthProperties.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/AuthProperties.java) · 타입: `AuthProperties`, `User`

| 위치 | 선언 |
|---|---|
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/AuthProperties.java#L12) | `public AuthProperties(String jwtSecret, Duration tokenTtl, List<User> users)` |

### service/src/main/java/com/capstone4/skillops/auth/AuthUser.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/AuthUser.java) · 타입: `AuthUser`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java) · 타입: `SecurityConfig`

| 위치 | 선언 |
|---|---|
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L48) | `SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception` |
| [L74](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L74) | `JwtAuthenticationConverter jwtAuthenticationConverter()` |
| [L84](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L84) | `SecretKey jwtSigningKey(AuthProperties properties)` |
| [L100](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L100) | `JwtEncoder jwtEncoder(SecretKey key)` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L105) | `JwtDecoder jwtDecoder(SecretKey key)` |
| [L110](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L110) | `PasswordEncoder passwordEncoder()` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L116) | `private static AuthenticationEntryPoint unauthorized()` |
| [L123](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L123) | `private static AccessDeniedHandler forbidden()` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java#L128) | `private static void writeError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException` |

### service/src/main/java/com/capstone4/skillops/auth/TokenService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/TokenService.java) · 타입: `TokenService`, `Issued`

| 위치 | 선언 |
|---|---|
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/TokenService.java#L28) | `public TokenService(JwtEncoder encoder, AuthProperties properties)` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/TokenService.java#L33) | `TokenService(JwtEncoder encoder, AuthProperties properties, Clock clock)` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/TokenService.java#L39) | `public Issued issue(AuthProperties.User user)` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/TokenService.java#L59) | `public static AuthUser userOf(Jwt jwt)` |

### service/src/main/java/com/capstone4/skillops/auth/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/auth/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java) · 타입: `DiagnosisController`

| 위치 | 선언 |
|---|---|
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java#L17) | `public DiagnosisController(String url, String token)` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java#L26) | `DiagnosisController(RestClient agent, String token)` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java#L27) | `public Map<?, ?> changed(String token, Map<String, Object> event)` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java#L34) | `public Map<?, ?> list()` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java#L36) | `public Map<?, ?> status(UUID threadId)` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java#L40) | `public ResponseEntity<Map<String, String>> upstream(RestClientResponseException e)` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java#L45) | `public ResponseEntity<Map<String, String>> unavailable(ResourceAccessException e)` |

### service/src/main/java/com/capstone4/skillops/domain/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/domain/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/domain/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/harness/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/harness/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/harness/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java) · 타입: `DependencyGraphTools`

| 위치 | 선언 |
|---|---|
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java#L17) | `public DependencyGraphTools(DependencyGraphService graphService)` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java#L24) | `public CallToolResult getDependencyGraph(String snapshot_id, String entry_skill_id)` |

### service/src/main/java/com/capstone4/skillops/mcp/PingTools.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/PingTools.java) · 타입: `PingTools`

| 위치 | 선언 |
|---|---|
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/PingTools.java#L19) | `public String ping()` |

### service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java) · 타입: `ProposalTools`

| 위치 | 선언 |
|---|---|
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java#L22) | `public ProposalTools(ProposalService proposals)` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java#L26) | `public CallToolResult saveProposal(Map<String, Object> proposal)` |

### service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java) · 타입: `SkillDiffTools`

| 위치 | 선언 |
|---|---|
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java#L17) | `public SkillDiffTools(SkillDiffService diffService)` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java#L25) | `public CallToolResult diffSkill(String skill_id, String before_snapshot_id, String after_snapshot_id)` |

### service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java) · 타입: `ToolResults`

| 위치 | 선언 |
|---|---|
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java#L21) | `private ToolResults()` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java#L25) | `static CallToolResult run(Supplier<Object> body)` |

### service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java) · 타입: `WorkspaceToolResults`

| 위치 | 선언 |
|---|---|
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java#L27) | `private WorkspaceToolResults()` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java#L31) | `static CallToolResult run(Supplier<Object> body)` |
| [L43](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java#L43) | `private static CallToolResult error(String code, String message, boolean retryable, Integer upstreamStatus, String snapshotId)` |

### service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java) · 타입: `WorkspaceTools`

| 위치 | 선언 |
|---|---|
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java#L28) | `public WorkspaceTools(WorkspaceService workspaces, ScenarioRunner scenarioRunner)` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java#L33) | `public CallToolResult createWorkspace(Map<String, Integer> start_versions, String snapshot_id)` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java#L48) | `public CallToolResult applyPatch(String workspace_id, List<PatchInput> patches)` |
| [L64](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java#L64) | `public CallToolResult runScenarios(String workspace_id, List<String> scenario_ids, List<String> replay_run_ids, List<String> extra_checks, Integer repeat)` |

### service/src/main/java/com/capstone4/skillops/proposal/Proposal.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java) · 타입: `Proposal`

| 위치 | 선언 |
|---|---|
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L62) | `protected Proposal()` |
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L65) | `public static Proposal create(String proposalId, String threadId, String status, Map<String, Object> payload, Map<String, Object> document, List<Integer> validCandidateNos, Instant createdAt)` |
| [L82](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L82) | `void decide(String decision, Instant at)` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L97) | `public String getProposalId()` |
| [L101](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L101) | `public String getThreadId()` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L105) | `public String getStatus()` |
| [L109](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L109) | `public Map<String, Object> getPayload()` |
| [L113](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L113) | `public Map<String, Object> getDocument()` |
| [L117](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L117) | `public List<Integer> getValidCandidateNos()` |
| [L121](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L121) | `public Instant getCreatedAt()` |
| [L125](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/Proposal.java#L125) | `public Instant getDecidedAt()` |

### service/src/main/java/com/capstone4/skillops/proposal/ProposalDocument.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocument.java) · 타입: `ProposalDocument`, `Section`, `Fact`, `ToneWarning`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java) · 타입: `ProposalDocumentWriter`, `Writer`

| 위치 | 선언 |
|---|---|
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L39) | `private ProposalDocumentWriter()` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L42) | `public static ProposalDocument write(String proposalId, Map<String, Object> proposal, Instant createdAt)` |
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L65) | `Writer(String proposalId, Map<String, Object> proposal, Instant createdAt)` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L85) | `private Instant failedAt()` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L97) | `ProposalDocument document()` |
| [L112](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L112) | `private String title()` |
| [L120](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L120) | `private List<String> summarySentences()` |
| [L152](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L152) | `private Section whatHappened()` |
| [L181](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L181) | `private Section cause()` |
| [L203](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L203) | `private Section intentSection()` |
| [L213](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L213) | `private Section candidatesSection()` |
| [L237](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L237) | `private Section candidatePart(Map<String, Object> candidate)` |
| [L280](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L280) | `private String nothingTested()` |
| [L284](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L284) | `private Section decision()` |
| [L303](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L303) | `private Section reference()` |
| [L313](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L313) | `private List<ToneWarning> toneWarnings()` |
| [L323](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L323) | `private static Section section(String key, String heading, List<String> paragraphs, List<Fact> facts)` |
| [L327](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L327) | `private static String categoryLabel(String category)` |
| [L331](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L331) | `private static String joined(Object values)` |
| [L337](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java#L337) | `private static void add(List<Fact> facts, String label, String value)` |

### service/src/main/java/com/capstone4/skillops/proposal/ProposalException.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalException.java) · 타입: `ProposalException`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalException.java#L9) | `public ProposalException(String code, String message, int httpStatus)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalException.java#L15) | `public String code()` |
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalException.java#L19) | `public int httpStatus()` |

### service/src/main/java/com/capstone4/skillops/proposal/ProposalRepository.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRepository.java) · 타입: `ProposalRepository`

| 위치 | 선언 |
|---|---|
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRepository.java#L10) | `Optional<Proposal> findByThreadId(String threadId)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRepository.java#L12) | `List<Proposal> findTop100ByOrderByCreatedAtDesc()` |

### service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java) · 타입: `ProposalRules`

| 위치 | 선언 |
|---|---|
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L12) | `private ProposalRules()` |
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L19) | `static boolean passes(Map<String, Object> candidate)` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L34) | `static Map<String, Object> map(Object value)` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L40) | `static List<Object> list(Object value)` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L45) | `static List<Map<String, Object>> maps(Object value)` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L50) | `static List<String> strings(Object value)` |
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L55) | `static String str(Object value)` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L59) | `static Integer integer(Object value)` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java#L63) | `static boolean bool(Object value)` |

### service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java) · 타입: `ProposalService`, `SavedProposal`

| 위치 | 선언 |
|---|---|
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L45) | `public ProposalService(ProposalRepository repository, RunLedger ledger)` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L50) | `ProposalService(ProposalRepository repository, RunLedger ledger, Clock clock)` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L62) | `public SavedProposal save(Map<String, Object> proposal)` |
| [L106](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L106) | `public Proposal markDecided(String proposalId, String decision, Integer candidateNo)` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L128) | `public Optional<Proposal> find(String proposalId)` |
| [L133](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L133) | `public List<Proposal> recent()` |
| [L138](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L138) | `private List<Integer> checkCandidates(List<Map<String, Object>> candidates)` |
| [L162](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L162) | `private void checkEvidence(int no, Map<String, Object> candidate)` |
| [L183](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L183) | `private void checkRootCause(Map<String, Object> rootCause)` |
| [L195](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java#L195) | `private static SavedProposal savedOf(Proposal proposal)` |

### service/src/main/java/com/capstone4/skillops/proposal/ToneCheck.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ToneCheck.java) · 타입: `ToneCheck`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ToneCheck.java#L16) | `private ToneCheck()` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/proposal/ToneCheck.java#L20) | `public static List<String> findIn(String text)` |

### service/src/main/java/com/capstone4/skillops/registry/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/registry/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/registry/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/scenario/Check.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Check.java) · 타입: `Check`, `Op`

| 위치 | 선언 |
|---|---|
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Check.java#L25) | `public static Check parse(String text)` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Check.java#L52) | `public boolean holds(Map<String, Object> context)` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Check.java#L62) | `public String actualText(Map<String, Object> context)` |
| [L70](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Check.java#L70) | `private static Object literal(String raw, String text)` |
| [L88](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Check.java#L88) | `private static List<String> splitOutsideQuotes(String inner)` |
| [L110](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Check.java#L110) | `private static boolean same(Object a, Object b)` |
| [L120](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Check.java#L120) | `private static IllegalArgumentException unreadable(String text)` |

### service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java) · 타입: `ContractOnlyFlowRunner`

| 위치 | 선언 |
|---|---|
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java#L34) | `public RunOutcome runDryRun(DryRunRequest request)` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java#L76) | `private String newRunId()` |

### service/src/main/java/com/capstone4/skillops/scenario/Expectation.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Expectation.java) · 타입: `Expectation`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Expectation.java#L13) | `public static Expectation of(Scenario.Expect expect)` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Expectation.java#L21) | `public static Expectation replay()` |

### service/src/main/java/com/capstone4/skillops/scenario/FlowRunner.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/FlowRunner.java) · 타입: `FlowRunner`, `DryRunRequest`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/FlowRunner.java#L14) | `RunOutcome runDryRun(DryRunRequest request)` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/FlowRunner.java#L20) | `default boolean supportsReplay()` |

### service/src/main/java/com/capstone4/skillops/scenario/GradeResult.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/GradeResult.java) · 타입: `GradeResult`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java) · 타입: `HarnessFlowRunner`

| 위치 | 선언 |
|---|---|
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java#L45) | `public HarnessFlowRunner(WorkspaceService workspaces, OrderAgentRunClient orderAgent)` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java#L50) | `public RunOutcome runDryRun(DryRunRequest request)` |
| [L90](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java#L90) | `public boolean supportsReplay()` |
| [L95](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java#L95) | `private String publish(String bundle, Map<String, String> files, Map<String, Object> metadata)` |
| [L102](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java#L102) | `static String bundleHash(Map<String, String> files)` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java#L116) | `private static void update(MessageDigest sha, String text)` |

### service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java) · 타입: `JpaRunLedger`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java#L16) | `public JpaRunLedger(ScenarioRunRecordRepository records)` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java#L20) | `public void record(String workspaceId, String scenarioId, String runId, boolean passed)` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java#L25) | `public Set<String> runIdsOf(String workspaceId)` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java#L31) | `public boolean known(String runId)` |

### service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java) · 타입: `OrderAgentRunClient`, `DryRunStarted`, `Rejected`

| 위치 | 선언 |
|---|---|
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L65) | `Rejected(String message, int status, String code)` |
| [L71](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L71) | `public int status()` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L75) | `public String code()` |
| [L80](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L80) | `boolean snapshotNotFound()` |
| [L89](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L89) | `public OrderAgentRunClient(String baseUrl)` |
| [L95](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L95) | `OrderAgentRunClient(RestClient.Builder builder, String baseUrl)` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L105) | `public String publishCandidate(Map<String, String> files, Map<String, Object> metadata)` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L116) | `public DryRunStarted dryRun(String snapshotId, Map<String, Object> order)` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L128) | `public Map<String, Object> trace(String executionId)` |
| [L140](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L140) | `public Map<String, Object> originalOrder(String executionIdOrRunId)` |
| [L178](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L178) | `static String executionIdOf(String executionIdOrRunId)` |
| [L194](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L194) | `private static IllegalArgumentException notFound(String executionIdOrRunId, Rejected cause)` |
| [L204](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L204) | `private T call(String action, String path, Supplier<T> request)` |
| [L232](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L232) | `private static boolean isReadTimeout(Throwable e)` |
| [L244](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L244) | `private static String slow(String url)` |
| [L248](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L248) | `private static String unreachable(String url, String cause)` |
| [L253](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L253) | `private static String rejectedMessage(String action, int status, String code, JsonNode error)` |
| [L270](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L270) | `private static String causeOf(Throwable e)` |
| [L279](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L279) | `private JsonNode errorBody(RestClientResponseException e)` |
| [L288](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L288) | `private static String text(JsonNode node, String field)` |
| [L292](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L292) | `private static String required(Map<String, Object> body, String field, String path)` |
| [L299](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L299) | `private static List<Map<String, Object>> listOfMaps(Object value)` |
| [L307](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L307) | `private static Map<String, Object> toStringKeys(Map<?, ?> map)` |
| [L313](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java#L313) | `private static JdkClientHttpRequestFactory requestFactory()` |

### service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java) · 타입: `OrderAgentTraceConverter`, `StepBuilder`

| 위치 | 선언 |
|---|---|
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L17) | `private OrderAgentTraceConverter()` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L20) | `public static RunOutcome toOutcome(String runId, Map<String, Object> trace)` |
| [L58](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L58) | `private static void attachError(List<StepBuilder> steps, Map<String, Object> error)` |
| [L73](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L73) | `static String errorText(Map<String, Object> error)` |
| [L89](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L89) | `private static String joined(Object values)` |
| [L96](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L96) | `private static int stepNo(Map<String, Object> data)` |
| [L103](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L103) | `private static Map<String, Object> mapOf(Object value)` |
| [L108](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L108) | `private static List<Map<String, Object>> listOfMaps(Object value)` |
| [L124](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L124) | `StepBuilder(String skill)` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java#L128) | `RunOutcome.Step build()` |

### service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java) · 타입: `RunLedger`

| 위치 | 선언 |
|---|---|
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java#L12) | `void record(String workspaceId, String scenarioId, String runId, boolean passed)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java#L15) | `Set<String> runIdsOf(String workspaceId)` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java#L18) | `boolean known(String runId)` |

### service/src/main/java/com/capstone4/skillops/scenario/RunOutcome.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/RunOutcome.java) · 타입: `RunOutcome`, `Step`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/scenario/Scenario.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/Scenario.java) · 타입: `Scenario`, `Expect`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java) · 타입: `ScenarioCatalog`

| 위치 | 선언 |
|---|---|
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java#L30) | `public ScenarioCatalog(String scenariosDir, JsonMapper mapper)` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java#L63) | `public Scenario get(String id)` |
| [L72](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java#L72) | `public Set<String> ids()` |

### service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java) · 타입: `ScenarioGrader`

| 위치 | 선언 |
|---|---|
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java#L21) | `public GradeResult grade(Expectation expectation, List<Check> extraChecks, RunOutcome run)` |
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java#L65) | `static Map<String, Object> contextOf(RunOutcome run)` |

### service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecord.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecord.java) · 타입: `ScenarioRunRecord`

| 위치 | 선언 |
|---|---|
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecord.java#L32) | `protected ScenarioRunRecord()` |
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecord.java#L35) | `ScenarioRunRecord(String runId, String workspaceId, String scenarioId, boolean passed, Instant recordedAt)` |
| [L43](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecord.java#L43) | `public String getRunId()` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecord.java#L47) | `public String getWorkspaceId()` |

### service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecordRepository.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecordRepository.java) · 타입: `ScenarioRunRecordRepository`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecordRepository.java#L9) | `List<ScenarioRunRecord> findByWorkspaceId(String workspaceId)` |

### service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunReport.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunReport.java) · 타입: `ScenarioRunReport`, `ScenarioResult`, `Failure`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java) · 타입: `ScenarioRunner`, `Planned`

| 위치 | 선언 |
|---|---|
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java#L34) | `public ScenarioRunner(WorkspaceService workspaces, ScenarioCatalog catalog, FlowRunner flowRunner, ScenarioGrader grader, OrderAgentRunClient orderAgent, RunLedger ledger)` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java#L48) | `public ScenarioRunReport run(String workspaceId, List<String> scenarioIds, List<String> replayRunIds, List<String> extraChecks, Integer repeat)` |
| [L102](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java#L102) | `private List<Planned> plan(List<String> ids, List<String> replays)` |
| [L125](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java#L125) | `private static Expectation expectationOf(Scenario scenario)` |
| [L143](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java#L143) | `static Planned replay(String scenarioId, String replayOfRunId, String executionId)` |
| [L147](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java#L147) | `boolean isReplay()` |
| [L151](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java#L151) | `Planned withOrder(Map<String, Object> original)` |

### service/src/main/java/com/capstone4/skillops/scenario/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/scenario/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java) · 타입: `DependencyAnalyzer`, `Declaration`

| 위치 | 선언 |
|---|---|
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java#L23) | `private DependencyAnalyzer()` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java#L27) | `public static DependencyGraph analyze(String entrySkillId, Map<String, SkillSource> sources)` |
| [L100](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java#L100) | `static List<String> flowSteps(String entrySkillId, SkillSource entry)` |
| [L120](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java#L120) | `private static Declaration declaration(String id, String text)` |
| [L134](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java#L134) | `private static List<String> strings(Map<String, Object> fm, String key, boolean required)` |

### service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraph.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraph.java) · 타입: `DependencyGraph`, `Node`, `Edge`, `Missing`, `Ambiguous`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphResult.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphResult.java) · 타입: `DependencyGraphResult`

| 위치 | 선언 |
|---|---|
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphResult.java#L25) | `static DependencyGraphResult of(SnapshotManifest manifest, DependencyGraph g)` |

### service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java) · 타입: `DependencyGraphService`

| 위치 | 선언 |
|---|---|
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java#L22) | `public DependencyGraphService(OrderAgentClient orderAgent)` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java#L26) | `public DependencyGraphResult dependencyGraph(String snapshotId, String entrySkillId)` |
| [L54](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java#L54) | `private SkillSource fetch(String snapshotId, String skillId, Map<String, String> hashByPath)` |

### service/src/main/java/com/capstone4/skillops/snapshot/DiffSkillResult.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/DiffSkillResult.java) · 타입: `DiffSkillResult`, `Side`, `RelatedFile`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/snapshot/Frontmatter.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/Frontmatter.java) · 타입: `Frontmatter`

| 위치 | 선언 |
|---|---|
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/Frontmatter.java#L20) | `private Frontmatter()` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/Frontmatter.java#L24) | `static Map<String, Object> parse(String text)` |

### service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java) · 타입: `OrderAgentClient`

| 위치 | 선언 |
|---|---|
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L32) | `public OrderAgentClient(String baseUrl)` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L37) | `OrderAgentClient(RestClient.Builder builder, Duration backoff)` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L42) | `public SnapshotManifest getSnapshot(String snapshotId)` |
| [L51](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L51) | `public SnapshotFile readFile(String snapshotId, String path)` |
| [L60](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L60) | `public String readText(String snapshotId, String path)` |
| [L69](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L69) | `private T call(String snapshotId, Supplier<T> request)` |
| [L88](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L88) | `private OrderAgentException fromResponse(RestClientResponseException e, String snapshotId)` |
| [L107](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L107) | `private static void requireUuid(String snapshotId)` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L116) | `private static JdkClientHttpRequestFactory requestFactory()` |
| [L123](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java#L123) | `private static void sleep(Duration d)` |

### service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentException.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentException.java) · 타입: `OrderAgentException`

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentException.java#L15) | `public OrderAgentException(String code, String message, boolean retryable, Integer upstreamStatus, String snapshotId)` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentException.java#L23) | `public String code()` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentException.java#L27) | `public boolean retryable()` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentException.java#L32) | `public Integer upstreamStatus()` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentException.java#L36) | `public String snapshotId()` |

### service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java) · 타입: `SkillDiffService`

| 위치 | 선언 |
|---|---|
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java#L25) | `public SkillDiffService(OrderAgentClient orderAgent)` |
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java#L29) | `public DiffSkillResult diffSkill(String skillId, String beforeSnapshotId, String afterSnapshotId)` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java#L62) | `private static Map<String, String> filesUnder(SnapshotManifest manifest, String dir)` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java#L68) | `private static DiffSkillResult.Side side(SnapshotManifest manifest, String path, String fileHash)` |
| [L72](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java#L72) | `private static List<DiffSkillResult.RelatedFile> relatedFiles(Map<String, String> before, Map<String, String> after, String skillMd)` |

### service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java) · 타입: `SkillDiffer`

| 위치 | 선언 |
|---|---|
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java#L30) | `private SkillDiffer()` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java#L39) | `public static SkillFileDiff diff(String path, String before, String after, String beforeHash, String afterHash)` |
| [L74](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java#L74) | `private static SkillFileDiff.Hunk toHunk(int index, String path, List<String> lines, int frontmatterEnd, boolean useNewSide, String beforeHash, String afterHash)` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java#L116) | `private static int frontmatterEndLine(String text)` |
| [L132](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java#L132) | `private static List<SkillFileDiff.FrontmatterChange> frontmatterChanges(String before, String after)` |
| [L149](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java#L149) | `private static List<String> minus(Object left, Object right)` |
| [L157](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java#L157) | `private static List<String> lines(String text)` |
| [L161](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java#L161) | `private static String sha256(String text)` |

### service/src/main/java/com/capstone4/skillops/snapshot/SkillFileDiff.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillFileDiff.java) · 타입: `SkillFileDiff`, `FrontmatterChange`, `Hunk`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/snapshot/SkillSource.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SkillSource.java) · 타입: `SkillSource`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/snapshot/SnapshotFile.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SnapshotFile.java) · 타입: `SnapshotFile`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/snapshot/SnapshotManifest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/snapshot/SnapshotManifest.java) · 타입: `SnapshotManifest`, `FileEntry`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/trace/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/trace/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/trace/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/webhook/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/webhook/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/webhook/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

### service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java) · 타입: `LiveFolderSkillVersionStore`

| 위치 | 선언 |
|---|---|
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java#L29) | `public LiveFolderSkillVersionStore(String skillsDir)` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java#L33) | `public Map<String, Integer> liveVersions()` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java#L48) | `public String fullText(String skill, int version)` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java#L63) | `private static int versionOf(String skill, String text)` |
| [L71](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java#L71) | `private static String read(Path file)` |

### service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java) · 타입: `PatchApplier`

| 위치 | 선언 |
|---|---|
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java#L31) | `private PatchApplier()` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java#L40) | `static String apply(String original, String unifiedDiff)` |
| [L73](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java#L73) | `private static List<String> hunkLines(String unifiedDiff)` |
| [L110](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java#L110) | `private static void checkHunkHeader(String line)` |
| [L117](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java#L117) | `private static void shiftInsertionHeaders(List<String> lines)` |
| [L137](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java#L137) | `private static int firstMisfitLine(List<String> source, Patch<String> patch)` |

### service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java) · 타입: `SkillHeader`

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java#L15) | `public static SkillHeader parse(String fullText)` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java#L46) | `private static int indexOfClosing(List<String> lines)` |
| [L56](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java#L56) | `private static List<String> list(String value)` |

### service/src/main/java/com/capstone4/skillops/workspace/SkillVersionStore.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/SkillVersionStore.java) · 타입: `SkillVersionStore`

| 위치 | 선언 |
|---|---|
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/SkillVersionStore.java#L12) | `Map<String, Integer> liveVersions()` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/SkillVersionStore.java#L15) | `String fullText(String skill, int version)` |

### service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java) · 타입: `WorkspaceRecord`, `Status`

| 위치 | 선언 |
|---|---|
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L55) | `protected WorkspaceRecord()` |
| [L58](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L58) | `public static WorkspaceRecord open(String workspaceId, Instant createdAt, Map<String, WorkspaceSkill> skills)` |
| [L66](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L66) | `public static WorkspaceRecord open(String workspaceId, Instant createdAt, Map<String, WorkspaceSkill> skills, Map<String, String> resources, String sourceSnapshotId)` |
| [L78](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L78) | `public String getWorkspaceId()` |
| [L82](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L82) | `public Instant getCreatedAt()` |
| [L86](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L86) | `public Status getStatus()` |
| [L90](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L90) | `public Map<String, WorkspaceSkill> getSkills()` |
| [L95](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L95) | `public Map<String, String> getResources()` |
| [L100](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L100) | `public void replaceSkills(Map<String, WorkspaceSkill> skills)` |
| [L104](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java#L104) | `public String getSourceSnapshotId()` |

### service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRepository.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRepository.java) · 타입: `WorkspaceRepository`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java) · 타입: `WorkspaceService`, `CreatedWorkspace`, `PatchInput`, `ChangedSkill`, `BrokenLink`, `PatchResult`

| 위치 | 선언 |
|---|---|
| [L84](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L84) | `public WorkspaceService(WorkspaceRepository repository, SkillVersionStore store, OrderAgentClient orderAgent)` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L92) | `public WorkspaceService(WorkspaceRepository repository, SkillVersionStore store)` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L97) | `public CreatedWorkspace create(Map<String, Integer> startVersions)` |
| [L106](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L106) | `public CreatedWorkspace create(Map<String, Integer> startVersions, String snapshotId)` |
| [L139](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L139) | `private CreatedWorkspace createFromSnapshot(String snapshotId)` |
| [L172](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L172) | `private static int versionOf(String path, String text)` |
| [L186](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L186) | `public Map<String, WorkspaceSkill> skillsOf(String workspaceId)` |
| [L194](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L194) | `public String sourceSnapshotIdOf(String workspaceId)` |
| [L202](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L202) | `public Map<String, String> filesOf(String workspaceId)` |
| [L218](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L218) | `public PatchResult apply(String workspaceId, List<PatchInput> patches)` |
| [L275](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L275) | `private static DependencyGraph analyze(Map<String, WorkspaceSkill> skills)` |
| [L288](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L288) | `private static String sha256(String text)` |
| [L296](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L296) | `private WorkspaceRecord find(String workspaceId)` |
| [L302](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java#L302) | `private String newId()` |

### service/src/main/java/com/capstone4/skillops/workspace/WorkspaceSkill.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceSkill.java) · 타입: `WorkspaceSkill`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/WorkspaceSkill.java#L14) | `public WorkspaceSkill(int baseVersion, String fullText)` |

### service/src/main/java/com/capstone4/skillops/workspace/package-info.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/package-info.java)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/java/com/capstone4/skillops/workspace/package-info.java#L1) | `패키지 책임 설명 / 명시적 함수 없음` |

## 2. 주문 Java 운영 소스

56개 파일, 명시적 함수·메서드·생성자 253개.

### order-agent/backend/src/main/java/com/capstone4/orderagent/OrderAgentApplication.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/OrderAgentApplication.java) · 타입: `OrderAgentApplication`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/OrderAgentApplication.java#L9) | `public static void main(String[] args)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/InventoryMockAdapter.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/InventoryMockAdapter.java) · 타입: `InventoryMockAdapter`

| 위치 | 선언 |
|---|---|
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/InventoryMockAdapter.java#L8) | `public boolean reserve(List<Product> products, Map<String, Integer> quantities)` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/InventoryMockAdapter.java#L18) | `public void release(List<Product> products, Map<String, Integer> quantities)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/NotificationMockAdapter.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/NotificationMockAdapter.java) · 타입: `NotificationMockAdapter`

| 위치 | 선언 |
|---|---|
| [L6](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/NotificationMockAdapter.java#L6) | `public void send(UUID orderId, String customer, String shipmentReference)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/PaymentMockAdapter.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/PaymentMockAdapter.java) · 타입: `PaymentMockAdapter`, `Result`

| 위치 | 선언 |
|---|---|
| [L7](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/PaymentMockAdapter.java#L7) | `public Result pay(UUID orderId, String customer, long amount, String method, boolean reserved)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java) · 타입: `ShippingMockAdapter`, `Result`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java#L9) | `public ShippingMockAdapter(Clock clock)` |
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java#L10) | `public Result reserve(UUID orderId, String country, boolean express, boolean paid, boolean reserved)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/config/ClockConfig.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/config/ClockConfig.java) · 타입: `ClockConfig`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/config/ClockConfig.java#L9) | `Clock clock()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/config/InterruptedOrderRecovery.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/config/InterruptedOrderRecovery.java) · 타입: `InterruptedOrderRecovery`

| 위치 | 선언 |
|---|---|
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/config/InterruptedOrderRecovery.java#L12) | `public InterruptedOrderRecovery(OrderLifecycleService lifecycle)` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/config/InterruptedOrderRecovery.java#L13) | `public void run(ApplicationArguments args)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java) · 타입: `DryRunController`, `DryRunRequest`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java#L16) | `public DryRunController(DryRunService service, boolean demo)` |
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java#L19) | `public Map<String, Object> run(DryRunRequest request)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java) · 타입: `EvidenceController`, `PublishRequest`, `EditRequest`, `CandidateRequest`

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L15) | `public EvidenceController(SnapshotService snapshots, TraceService traces, SkillQueryService skills, boolean demo, SkillChangeDispatcher dispatcher)` |
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L16) | `public Map<String, Object> legacyTrace(String runId)` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L21) | `public Map<String, Object> recent()` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L25) | `public Map<String, Object> trace(UUID id)` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L30) | `public Map<String, Object> failed()` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L31) | `public Map<String, Object> skills(UUID id)` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L32) | `public Map<String, Object> skill(UUID id, String skillId)` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L34) | `public Map<String, Object> changeDelivery(UUID id)` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L37) | `public Map<String, Object> saveEdits(EditRequest request)` |
| [L51](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L51) | `public Map<String, Object> publish(PublishRequest request)` |
| [L58](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L58) | `public Map<String, Object> publishCandidate(CandidateRequest request)` |
| [L67](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L67) | `public Map<String, Object> snapshot(UUID id)` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L68) | `public Map<String, Object> file(UUID id, String path)` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java#L75) | `private Map<String, Object> manifest(SnapshotRepository.Stored s)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/controller/HealthController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/HealthController.java) · 타입: `HealthController`

| 위치 | 선언 |
|---|---|
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/HealthController.java#L10) | `public HealthController(ApplicationAvailability availability)` |
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/HealthController.java#L11) | `ResponseEntity<Map<String, String>> health()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java) · 타입: `OrderController`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java#L14) | `public OrderController(OrderService service)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java#L15) | `ResponseEntity<OrderResponse> create(String key, CreateOrderRequest request)` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java#L21) | `OrderResponse get(String id)` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java#L22) | `OrderResponse byRequest(String key)` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java#L23) | `private UUID uuid(String raw)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ProductController.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ProductController.java) · 타입: `ProductController`

| 위치 | 선언 |
|---|---|
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ProductController.java#L10) | `public ProductController(ProductService service)` |
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ProductController.java#L11) | `Map<String, List<ProductResponse>> list()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/dto/common/ErrorResponse.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/dto/common/ErrorResponse.java) · 타입: `ErrorResponse`, `FieldError`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java) · 타입: `CreateOrderRequest`, `Item`, `Shipping`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java#L14) | `public CreateOrderRequest(String customer, List<Item> items, String method, Shipping shipping)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java) · 타입: `OrderResponse`, `Item`, `Shipping`, `Payment`, `Shipment`, `Notification`, `Step`

| 위치 | 선언 |
|---|---|
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java#L21) | `public static OrderResponse from(Order o)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/dto/product/ProductResponse.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/dto/product/ProductResponse.java) · 타입: `ProductResponse`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java) · 타입: `Order`

| 위치 | 선언 |
|---|---|
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L39) | `protected Order()` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L40) | `public Order(UUID requestId, String requestHash, String customer, String method, String country, boolean express, Instant now)` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L48) | `public void addItem(Product product, int quantity)` |
| [L53](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L53) | `public void addStep(ProcessingStepType type, ProcessingStepStatus state, String code, String message, Instant started, Instant completed)` |
| [L58](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L58) | `private void requireProcessing()` |
| [L61](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L61) | `public void reject()` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L62) | `public void hold()` |
| [L66](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L66) | `public void paid(String reference)` |
| [L67](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L67) | `public void shipped(String reference, LocalDate eta)` |
| [L72](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L72) | `public void notified()` |
| [L77](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L77) | `public void complete()` |
| [L82](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L82) | `public void fail(String code, Instant now)` |
| [L86](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L86) | `public void useSnapshot(UUID id)` |
| [L87](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L87) | `public UUID getRequestedSnapshotId()` |
| [L88](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L88) | `public void markDryRun()` |
| [L89](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L89) | `public boolean isDryRun()` |
| [L90](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L90) | `public UUID getExecutionId()` |
| [L91](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L91) | `public boolean isInventoryReserved()` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L92) | `public void reserved()` |
| [L93](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L93) | `public void released()` |
| [L94](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L94) | `public void noSelection()` |
| [L95](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L95) | `public UUID getId()` |
| [L96](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L96) | `public UUID getRequestId()` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L97) | `public String getRequestHash()` |
| [L98](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L98) | `public String getCustomer()` |
| [L99](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L99) | `public String getPaymentMethod()` |
| [L100](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L100) | `public String getShippingCountry()` |
| [L101](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L101) | `public boolean isShippingExpress()` |
| [L102](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L102) | `public long getTotalAmount()` |
| [L103](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L103) | `public OrderStatus getStatus()` |
| [L104](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L104) | `public String getResultCode()` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L105) | `public PaymentStatus getPaymentStatus()` |
| [L106](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L106) | `public String getPaymentReference()` |
| [L107](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L107) | `public String getShipmentReference()` |
| [L108](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L108) | `public LocalDate getEstimatedDeliveryDate()` |
| [L109](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L109) | `public NotificationStatus getNotificationStatus()` |
| [L110](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L110) | `public Instant getCreatedAt()` |
| [L111](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L111) | `public Instant getUpdatedAt()` |
| [L112](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L112) | `public List<OrderItem> getItems()` |
| [L113](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java#L113) | `public List<OrderProcessingStep> getSteps()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java) · 타입: `OrderItem`

| 위치 | 선언 |
|---|---|
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java#L17) | `protected OrderItem()` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java#L18) | `public OrderItem(Order order, Product product, int quantity)` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java#L24) | `public String getSku()` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java#L25) | `public String getProductName()` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java#L26) | `public int getQuantity()` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java#L27) | `public long getUnitPrice()` |
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderItem.java#L28) | `public long getLineTotal()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java) · 타입: `OrderProcessingStep`

| 위치 | 선언 |
|---|---|
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L20) | `protected OrderProcessingStep()` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L21) | `public OrderProcessingStep(Order order, int sequence, ProcessingStepType step, ProcessingStepStatus status, String resultCode, String message, Instant started, Instant ended)` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L26) | `public int getSequence()` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L27) | `public ProcessingStepType getStep()` |
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L28) | `public ProcessingStepStatus getStatus()` |
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L29) | `public String getResultCode()` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L30) | `public String getMessage()` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L31) | `public Instant getStartedAt()` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/OrderProcessingStep.java#L32) | `public Instant getCompletedAt()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java) · 타입: `Product`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L16) | `protected Product()` |
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L17) | `public Long getId()` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L18) | `public String getSku()` |
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L19) | `public String getName()` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L20) | `public long getUnitPrice()` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L21) | `public int getStockQuantity()` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L22) | `public boolean isActive()` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L23) | `public void reserve(int quantity)` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java#L27) | `public void release(int quantity)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/NotificationStatus.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/NotificationStatus.java) · 타입: `NotificationStatus`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/OrderStatus.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/OrderStatus.java) · 타입: `OrderStatus`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/PaymentStatus.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/PaymentStatus.java) · 타입: `PaymentStatus`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepStatus.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepStatus.java) · 타입: `ProcessingStepStatus`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java) · 타입: `ProcessingStepType`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### order-agent/backend/src/main/java/com/capstone4/orderagent/exception/BusinessException.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/BusinessException.java) · 타입: `BusinessException`

| 위치 | 선언 |
|---|---|
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/BusinessException.java#L8) | `public BusinessException(ErrorCode code)` |
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/BusinessException.java#L9) | `public BusinessException(ErrorCode code, UUID orderId)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/BusinessException.java#L12) | `public ErrorCode getCode()` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/BusinessException.java#L13) | `public UUID getOrderId()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/exception/ErrorCode.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/ErrorCode.java) · 타입: `ErrorCode`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/ErrorCode.java#L13) | `ErrorCode(int status, String message)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java) · 타입: `GlobalExceptionHandler`

| 위치 | 선언 |
|---|---|
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java#L19) | `ResponseEntity<ErrorResponse> agent(com.capstone4.orderagent.harness.AgentException e)` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java#L24) | `ResponseEntity<ErrorResponse> business(BusinessException e)` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java#L26) | `ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e)` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java#L32) | `ResponseEntity<ErrorResponse> invalidInput(Exception e)` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java#L34) | `ResponseEntity<ErrorResponse> malformed(Exception e)` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java#L36) | `ResponseEntity<ErrorResponse> database(DataAccessException e)` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java#L41) | `ResponseEntity<ErrorResponse> unexpected(Exception e)` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java#L46) | `private ResponseEntity<ErrorResponse> response(ErrorCode code, UUID id, List<ErrorResponse.FieldError> fields)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/exception/ProcessingFailure.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/ProcessingFailure.java) · 타입: `ProcessingFailure`

| 위치 | 선언 |
|---|---|
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/ProcessingFailure.java#L8) | `public ProcessingFailure(ProcessingStepType step, RuntimeException cause)` |
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/exception/ProcessingFailure.java#L9) | `public ProcessingStepType getStep()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/AgentException.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/AgentException.java) · 타입: `AgentException`

| 위치 | 선언 |
|---|---|
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/AgentException.java#L8) | `public AgentException(String category)` |
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/AgentException.java#L9) | `public AgentException(String category, Map<String, Object> details)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/AgentException.java#L12) | `public String category()` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/AgentException.java#L13) | `public Map<String, Object> details()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/DecisionProvider.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/DecisionProvider.java) · 타입: `DecisionProvider`, `Question`, `Reply`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/DecisionProvider.java#L9) | `Reply decide(Question question)` |
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/DecisionProvider.java#L10) | `String mode()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java) · 타입: `ExecutionControl`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java#L13) | `public ExecutionControl(long timeoutMs, int maxCalls, long maxTokens)` |
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java#L17) | `public void check()` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java#L18) | `public long remainingMs()` |
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java#L19) | `public void attempt()` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java#L20) | `public void observe(Long input, Long output)` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java#L24) | `public long elapsedMs()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/FixedDecisionProvider.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/FixedDecisionProvider.java) · 타입: `FixedDecisionProvider`

| 위치 | 선언 |
|---|---|
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/FixedDecisionProvider.java#L10) | `public Reply decide(Question q)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/FixedDecisionProvider.java#L15) | `public String mode()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java) · 타입: `Json`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java#L14) | `private Json()` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java#L15) | `public static String write(Object value)` |
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java#L16) | `public static Map<String, Object> object(String value)` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java#L18) | `public static String hash(byte[] bytes)` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java#L22) | `public static String hash(Object value)` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java#L23) | `private static Object canonical(Object value)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OpenAiDecisionProvider.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OpenAiDecisionProvider.java) · 타입: `OpenAiDecisionProvider`

| 위치 | 선언 |
|---|---|
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OpenAiDecisionProvider.java#L18) | `public OpenAiDecisionProvider(ChatModel model, long timeout)` |
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OpenAiDecisionProvider.java#L19) | `public Reply decide(Question question)` |
| [L49](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OpenAiDecisionProvider.java#L49) | `public String mode()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java) · 타입: `OrderHarness`

| 위치 | 선언 |
|---|---|
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java#L23) | `public OrderHarness(SnapshotService snapshots, SkillLoader loader, SkillExecutor decisions, ToolDispatcher tools, OrderToolService business, OrderLifecycleService lifecycle, TraceService trace, long timeout, int maxCalls, long maxTokens, String model)` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java#L34) | `public void execute(UUID orderId)` |
| [L117](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java#L117) | `private void finishedStep(UUID execution, SkillLoader.Skill skill, int stepNo, long started, String status, Map<String, Object> output, Map<String, Object> extra)` |
| [L124](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java#L124) | `private Map<String, Object> present(Map<String, Object> source, List<String> keys)` |
| [L129](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java#L129) | `private void loaded(UUID execution, SkillLoader.Skill skill)` |
| [L131](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java#L131) | `private Map<String, Object> project(Map<String, Object> source, List<String> keys)` |
| [L132](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java#L132) | `private ProcessingStepType stepType(String id)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillExecutor.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillExecutor.java) · 타입: `SkillExecutor`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillExecutor.java#L16) | `public SkillExecutor(DecisionProvider provider, TraceService trace, long callTimeout)` |
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillExecutor.java#L17) | `void close()` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillExecutor.java#L18) | `public Map<String, Object> decide(UUID executionId, DecisionProvider.Question question, ExecutionControl control)` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillExecutor.java#L46) | `public String mode()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java) · 타입: `SkillLoader`, `Skill`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java#L16) | `public Skill load(SortedMap<String, byte[]> files, String id)` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java#L36) | `public static void validateName(String id)` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java#L40) | `private List<String> strings(Map<?, ?> m, String key, boolean required)` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java#L45) | `public List<Map<String, Object>> candidates(SortedMap<String, byte[]> files)` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java#L50) | `public List<Skill> validateFlow(SortedMap<String, byte[]> files, Skill flow)` |
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java#L65) | `public void validateInputs(Skill skill, Set<String> available, Set<String> completed, String phase)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java) · 타입: `ToolCatalog`

| 위치 | 선언 |
|---|---|
| [L6](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java#L6) | `private ToolCatalog()` |
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java#L10) | `public static String toolFor(String skill)` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java#L13) | `public static Map<String, Object> arguments(String tool, Map<String, Object> context)` |
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java#L29) | `public static Map<String, Object> definition(String tool)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolDispatcher.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolDispatcher.java) · 타입: `ToolDispatcher`

| 위치 | 선언 |
|---|---|
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolDispatcher.java#L12) | `public ToolDispatcher(OrderToolService business, TraceService trace)` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolDispatcher.java#L13) | `public Map<String, Object> execute(UUID execution, UUID order, SkillLoader.Skill skill, String tool, Map<String, Object> args, ExecutionControl control)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/repository/OrderRepository.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/OrderRepository.java) · 타입: `OrderRepository`

| 위치 | 선언 |
|---|---|
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/OrderRepository.java#L11) | `Optional<Order> lockById(UUID id)` |
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/OrderRepository.java#L14) | `Optional<Order> findByRequestId(UUID requestId)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/OrderRepository.java#L15) | `List<Order> findByStatusIn(Collection<OrderStatus> statuses)` |
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/OrderRepository.java#L16) | `int claim(UUID id, Instant now)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/repository/ProductRepository.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/ProductRepository.java) · 타입: `ProductRepository`

| 위치 | 선언 |
|---|---|
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/ProductRepository.java#L10) | `List<Product> findByActiveTrueOrderBySkuAsc()` |
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/ProductRepository.java#L11) | `List<Product> findBySkuInOrderBySkuAsc(Collection<String> skus)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/repository/ProductRepository.java#L12) | `List<Product> lockBySkus(Collection<String> skus)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java) · 타입: `DryRunService`

| 위치 | 선언 |
|---|---|
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java#L25) | `public DryRunService(OrderLifecycleService lifecycle, OrderProcessingService processor, SnapshotService snapshots, OrderRepository orders, ProductRepository products, InventoryMockAdapter inventory, TraceService traces, PlatformTransactionManager transactions, Clock clock)` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java#L31) | `public Map<String, Object> run(UUID snapshotId, CreateOrderRequest request)` |
| [L53](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java#L53) | `private UUID cleanup(UUID id)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/service/InventoryRecovery.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/InventoryRecovery.java) · 타입: `InventoryRecovery`

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/InventoryRecovery.java#L15) | `public InventoryRecovery(ProductRepository products, InventoryMockAdapter inventory, Clock clock, com.capstone4.orderagent.trace.ExecutionRepository evidence)` |
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/InventoryRecovery.java#L16) | `public void releaseUnpaid(Order order)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/service/NormalizedOrder.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/NormalizedOrder.java) · 타입: `NormalizedOrder`

| 위치 | 선언 |
|---|---|
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/NormalizedOrder.java#L11) | `static NormalizedOrder from(CreateOrderRequest r)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java) · 타입: `OrderLifecycleService`, `Accepted`

| 위치 | 선언 |
|---|---|
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L20) | `public OrderLifecycleService(OrderRepository orders, ProductRepository products, Clock clock, com.capstone4.orderagent.trace.ExecutionRepository evidence, InventoryRecovery inventoryRecovery)` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L25) | `public Optional<Accepted> existing(UUID key, String hash)` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L32) | `public Accepted accept(UUID key, NormalizedOrder input)` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L47) | `public boolean claim(UUID id)` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L50) | `public OrderResponse get(UUID id)` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L52) | `public OrderResponse byRequest(UUID key)` |
| [L56](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L56) | `public void fail(UUID id, ProcessingStepType failedStep, String code)` |
| [L69](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L69) | `public void recoverInterrupted()` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java#L85) | `private Order find(UUID id)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderProcessingService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderProcessingService.java) · 타입: `OrderProcessingService`

| 위치 | 선언 |
|---|---|
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderProcessingService.java#L11) | `public OrderProcessingService(OrderHarness harness)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderProcessingService.java#L12) | `public void process(UUID orderId)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java) · 타입: `OrderService`, `Creation`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java#L16) | `public OrderService(OrderLifecycleService lifecycle, OrderProcessingService processor, com.capstone4.orderagent.workspace.SnapshotService snapshots)` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java#L21) | `public Creation createOrder(UUID key, CreateOrderRequest request)` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java#L46) | `public OrderResponse getOrder(UUID id)` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java#L47) | `public OrderResponse getByRequestId(UUID key)` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java#L48) | `static boolean hasDatabaseCause(Throwable error)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java) · 타입: `OrderToolService`, `Initial`

| 위치 | 선언 |
|---|---|
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L29) | `public OrderToolService(OrderRepository orders, ProductRepository products, InventoryMockAdapter inventory, PaymentMockAdapter payments, ShippingMockAdapter shipping, NotificationMockAdapter notifications, ExecutionRepository evidence, JdbcTemplate jdbc, Clock clock)` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L36) | `public Initial initial(UUID id)` |
| [L38](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L38) | `public String status(UUID id)` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L40) | `private Map<String, Object> context(Order o)` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L46) | `public Map<String, Object> execute(UUID executionId, UUID orderId, String skill, String tool, Map<String, Object> arguments)` |
| [L103](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L103) | `public void complete(UUID orderId, UUID executionId, long elapsed)` |
| [L109](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L109) | `public void compensateUnpaid(UUID orderId, UUID executionId, String reason)` |
| [L114](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L114) | `private void release(Order o, UUID executionId, String skill, String reason)` |
| [L122](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L122) | `public void noSelection(UUID id)` |
| [L123](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L123) | `private SortedMap<String, Integer> quantities(Order o)` |
| [L124](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L124) | `private void step(Order o, ProcessingStepType type, ProcessingStepStatus state, String code, String message, Instant started)` |
| [L125](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java#L125) | `private void skip(Order o, ProcessingStepType[] steps)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/service/ProductService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/ProductService.java) · 타입: `ProductService`

| 위치 | 선언 |
|---|---|
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/ProductService.java#L12) | `public ProductService(ProductRepository products)` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/service/ProductService.java#L13) | `public List<ProductResponse> getProducts()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java) · 타입: `ExecutionRepository`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L13) | `public ExecutionRepository(JdbcTemplate jdbc)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L15) | `public void begin(UUID id, UUID orderId, String runMode, String mode, String model, Instant now)` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L20) | `public void attach(UUID id, UUID snapshotId)` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L21) | `public void selection(UUID id, String raw, String resolved)` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L24) | `public void append(UUID id, String kind, String skill, Object data, Instant now)` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L30) | `public void apiAttempt(UUID id)` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L31) | `public void usage(UUID id, Long input, Long output)` |
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L35) | `public void finish(UUID id, String completion, String finalStatus, String error, long elapsed, Instant now)` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L39) | `public Map<String, Object> get(UUID id)` |
| [L60](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L60) | `public List<Map<String, Object>> failed()` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L92) | `public List<Map<String, Object>> recent()` |
| [L96](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L96) | `private Map<String, Object> summary(UUID id)` |
| [L99](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L99) | `public List<UUID> runningForOrder(UUID orderId)` |
| [L102](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L102) | `public Optional<String> cached(UUID executionId, String tool, String hash)` |
| [L108](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java#L108) | `public void operation(UUID executionId, String tool, String hash, Object result, Instant now)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java) · 타입: `TraceService`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L13) | `public TraceService(ExecutionRepository store, Clock clock)` |
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L14) | `public UUID begin(UUID order, String runMode, String mode, String model)` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L18) | `public void fixSnapshot(UUID id, UUID snapshot, String hash)` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L21) | `public void attach(UUID id, UUID snapshot, String hash, Object candidates)` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L25) | `public void selection(UUID id, String raw, String resolved)` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L30) | `public void event(UUID id, String kind, String skill, Map<String, ?> data)` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L31) | `public void apiAttempt(UUID id, String skill, String purpose, int attempt)` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L34) | `public void modelResult(UUID id, String skill, String raw, Long input, Long output)` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L39) | `public void finish(UUID id, String completion, String status, String error, long elapsed)` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L46) | `public Map<String, Object> get(UUID id)` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L47) | `public List<Map<String, Object>> failed()` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java#L48) | `public List<Map<String, Object>> recent()` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java) · 타입: `SkillChangeDispatcher`

| 위치 | 선언 |
|---|---|
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java#L19) | `public SkillChangeDispatcher(JdbcTemplate jdbc, String serviceUrl, String token, boolean enabled)` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java#L31) | `SkillChangeDispatcher(JdbcTemplate jdbc, RestClient http, boolean enabled)` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java#L32) | `public void dispatch()` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java#L50) | `public Map<String, Object> status(UUID id)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java) · 타입: `SkillQueryService`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java#L14) | `public SkillQueryService(SnapshotService snapshots, SkillLoader loader)` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java#L23) | `public Map<String, Object> list(UUID id)` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java#L45) | `public Map<String, Object> get(UUID id, String skillId)` |
| [L67](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java#L67) | `private Map<String, Object> metadata(SkillLoader.Skill skill)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotRepository.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotRepository.java) · 타입: `SnapshotRepository`, `Stored`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotRepository.java#L13) | `public SnapshotRepository(JdbcTemplate jdbc)` |
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotRepository.java#L14) | `public void insert(UUID id, String hash, Map<String, Object> metadata, SortedMap<String, byte[]> files, Instant now)` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotRepository.java#L21) | `public Stored get(UUID id)` |

### order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java) · 타입: `SnapshotService`

| 위치 | 선언 |
|---|---|
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L18) | `public SnapshotService(SnapshotRepository store, String root, Clock clock, org.springframework.jdbc.core.JdbcTemplate jdbc)` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L22) | `public SortedMap<String, byte[]> readPublishedBundle()` |
| [L51](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L51) | `public SnapshotRepository.Stored capture()` |
| [L56](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L56) | `public SnapshotRepository.Stored saveEdits(UUID baselineId, String baselineHash, Map<String, String> changes)` |
| [L90](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L90) | `public SnapshotRepository.Stored publish(SortedMap<String, byte[]> files, Map<String, Object> metadata)` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L105) | `public SnapshotRepository.Stored get(UUID id)` |
| [L111](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L111) | `public SnapshotRepository.Stored demo(String fixture)` |
| [L125](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L125) | `public static String bundleHash(SortedMap<String, byte[]> files, Map<String, Object> metadata)` |
| [L129](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java#L129) | `private void validatePath(String path)` |

## 3. Python 운영 소스

14개 파일, 명시적 함수·메서드·생성자 106개.

### skillops-agent/skillops_agent/__init__.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/__init__.py)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/__init__.py#L1) | `설정·상태 타입·모듈 선언` |

### skillops-agent/skillops_agent/config.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/config.py)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/config.py#L1) | `설정·상태 타입·모듈 선언` |

### skillops-agent/skillops_agent/contracts.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py) · 타입: `ContractError`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L13) | `execution_uuid(value: str) -> str` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L21) | `event_data(event: dict) -> dict` |
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L28) | `trace_context(trace: dict) -> dict` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L37) | `context_keys(trace: dict) -> list[str]` |
| [L58](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L58) | `_is_literal(raw: str) -> bool` |
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L65) | `_split_outside_quotes(inner: str) -> list[str]` |
| [L81](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L81) | `check_problem(check: str, known_keys: set[str]) -> str \| None` |
| [L99](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L99) | `tools_called(trace: dict) -> list[str]` |
| [L107](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L107) | `validate_report(report: dict, workspace_id: str, scenario_ids: list[str], repeat: int) -> list[dict]` |
| [L135](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/contracts.py#L135) | `reverse_hunk(diff: str) -> str` |

### skillops-agent/skillops_agent/graph.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py)

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py#L14) | `error_text(exc)` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py#L20) | `guarded(fn)` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py#L22) | `async run(state)` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py#L37) | `next_node(target)` |
| [L38](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py#L38) | `route(state)` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py#L63) | `evidence_route(state)` |
| [L83](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py#L83) | `ablation_route(state)` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/graph.py#L92) | `valid_route(state)` |

### skillops-agent/skillops_agent/llm.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/llm.py)

| 위치 | 선언 |
|---|---|
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/llm.py#L10) | `make_llm(temperature: float=0.0) -> ChatOpenAI` |
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/llm.py#L14) | `make_structured_llm(schema: Any) -> Any` |

### skillops-agent/skillops_agent/main.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py) · 타입: `DiagnoseRequest`, `ApproveRequest`

| 위치 | 선언 |
|---|---|
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L18) | `async lifespan(app)` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L45) | `validate_references(self)` |
| [L73](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L73) | `async verify_token(x_service_token: str=Header(default='')) -> None` |
| [L79](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L79) | `async _run_pipeline(req: DiagnoseRequest, thread_id: str) -> None` |
| [L96](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L96) | `async _resume_pipeline(req: ApproveRequest, thread_id: str) -> None` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L105) | `health() -> dict[str, str]` |
| [L110](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L110) | `async get_run_events(thread_id: str) -> dict` |
| [L118](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L118) | `async list_runs()` |
| [L123](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L123) | `async diagnose(req: DiagnoseRequest, background_tasks: BackgroundTasks) -> dict` |
| [L139](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/main.py#L139) | `async approve(req: ApproveRequest, background_tasks: BackgroundTasks) -> dict` |

### skillops-agent/skillops_agent/mcp_client.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/mcp_client.py)

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/mcp_client.py#L15) | `mcp_client(url: str=MCP_URL) -> Client` |

### skillops-agent/skillops_agent/nodes.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py) · 타입: `_HypothesisItem`, `_HypothesesOutput`, `_IntentOutput`, `_PatchSpec`, `_CandidateSpec`, `_CandidatesOutput`, `_ExplanationItem`, `_RankExplanationsOutput`

| 위치 | 선언 |
|---|---|
| [L90](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L90) | `_error_event(trace: dict) -> dict` |
| [L94](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L94) | `async _run(tools, workspace_id, ids, repeat=2, checks=None)` |
| [L104](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L104) | `_now() -> str` |
| [L108](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L108) | `_event(node: str, msg: str, run_id: str \| None=None) -> ProgressEvent` |
| [L112](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L112) | `_tick(state: AgentState, node: str, msg: str, run_id: str \| None=None) -> dict` |
| [L120](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L120) | `_is_limit_exceeded(state: AgentState) -> bool` |
| [L130](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L130) | `init(state: AgentState) -> dict` |
| [L164](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L164) | `async fetch_evidence(state: AgentState) -> dict` |
| [L327](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L327) | `async hypothesize(state: AgentState) -> dict` |
| [L365](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L365) | `async ablate(state: AgentState) -> dict` |
| [L413](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L413) | `check_ablation(state: AgentState) -> dict` |
| [L435](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L435) | `route_ablation(state: AgentState) -> str` |
| [L448](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L448) | `async _known_values(state: AgentState) -> list[str]` |
| [L462](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L462) | `async extract_intent(state: AgentState) -> dict` |
| [L503](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L503) | `async generate_candidates(state: AgentState) -> dict` |
| [L610](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L610) | `async verify_candidates(state: AgentState) -> dict` |
| [L691](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L691) | `check_valid(state: AgentState) -> dict` |
| [L702](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L702) | `route_valid(state: AgentState) -> str` |
| [L715](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L715) | `async rank_and_explain(state: AgentState) -> dict` |
| [L719](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L719) | `sort_key(c: Candidate)` |
| [L760](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L760) | `_failure_of(trace: dict \| None) -> dict \| None` |
| [L776](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L776) | `proposal_payload(state: AgentState) -> dict` |
| [L808](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L808) | `async save_proposal(state: AgentState) -> dict` |
| [L830](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L830) | `wait_for_approval(state: AgentState) -> dict` |
| [L843](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/nodes.py#L843) | `route_approval(state: AgentState) -> str` |

### skillops-agent/skillops_agent/order_agent.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py) · 타입: `OrderAgentError`

| 위치 | 선언 |
|---|---|
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L37) | `__init__(self, code: str, message: str, *, retryable: bool, upstream_status: int \| None=None, execution_id: str \| None=None, snapshot_id: str \| None=None) -> None` |
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L55) | `async get_trace(execution_id: str, *, client: httpx.AsyncClient \| None=None) -> dict` |
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L65) | `async list_failed_runs(*, client: httpx.AsyncClient \| None=None) -> dict` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L75) | `async list_skills(snapshot_id: str, *, client: httpx.AsyncClient \| None=None) -> dict` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L85) | `async get_skill(snapshot_id: str, skill_id: str, *, client: httpx.AsyncClient \| None=None) -> dict` |
| [L106](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L106) | `async _get(path: str, client: httpx.AsyncClient \| None, **ref: str) -> Any` |
| [L113](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L113) | `async _get_with_retry(client: httpx.AsyncClient, path: str, ref: dict) -> Any` |
| [L133](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L133) | `_from_response(response: httpx.Response, ref: dict) -> OrderAgentError` |
| [L149](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/order_agent.py#L149) | `_require_uuid(value: str, name: str, **ref: str) -> None` |

### skillops-agent/skillops_agent/prompts.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/prompts.py)

| 위치 | 선언 |
|---|---|
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/prompts.py#L8) | `load(name: str) -> str` |

### skillops-agent/skillops_agent/run_store.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py) · 타입: `RunStore`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py#L13) | `__init__(self, path=None)` |
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py#L28) | `connect(self)` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py#L33) | `accept(self, trigger)` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py#L62) | `update(self, thread, updates)` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py#L76) | `get(self, thread)` |
| [L89](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py#L89) | `list(self)` |
| [L99](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py#L99) | `recover(self)` |
| [L110](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/run_store.py#L110) | `list_all(self)` |

### skillops-agent/skillops_agent/state.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/state.py) · 타입: `Hypothesis`, `AblationRun`, `RootCause`, `Intent`, `Patch`, `CandidateResults`, `Candidate`, `ProgressEvent`, `AgentState`

명시적 함수가 없는 데이터 타입·enum·선언입니다. record 필드는 API/MCP 명세 또는 원본 선언을 확인해 주세요.

### skillops-agent/skillops_agent/stub_tools.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py) · 타입: `StubMCPTools`

| 위치 | 선언 |
|---|---|
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L20) | `__init__(self)` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L24) | `async __aenter__(self)` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L27) | `async __aexit__(self, *args)` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L30) | `async ping(self)` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L33) | `async list_failed_runs(self)` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L36) | `async get_snapshot(self, snapshot_id)` |
| [L51](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L51) | `async list_skills(self, snapshot_id)` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L62) | `async get_skill(self, snapshot_id, skill_id)` |
| [L66](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L66) | `async get_trace(self, execution_id)` |
| [L96](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L96) | `async diff_skill(self, skill_id, before_snapshot_id, after_snapshot_id)` |
| [L123](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L123) | `async get_dependency_graph(self, snapshot_id, entry_skill_id)` |
| [L137](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L137) | `async create_workspace(self, start_versions=None, snapshot_id=None)` |
| [L147](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L147) | `async apply_patch(self, workspace_id, patches)` |
| [L159](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L159) | `async run_scenarios(self, workspace_id, scenario_ids, repeat=2, replay_run_ids=None, extra_checks=None)` |
| [L209](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/stub_tools.py#L209) | `async save_proposal(self, proposal)` |

### skillops-agent/skillops_agent/tools.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py) · 타입: `MCPToolError`, `MCPTools`

| 위치 | 선언 |
|---|---|
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L26) | `__init__(self, tool: str, payload: dict)` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L36) | `async __aenter__(self) -> 'MCPTools'` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L42) | `async __aexit__(self, *args: Any) -> None` |
| [L49](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L49) | `async _call(self, name: str, args: dict) -> Any` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L75) | `async list_failed_runs(self) -> dict` |
| [L78](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L78) | `async get_snapshot(self, snapshot_id: str) -> dict` |
| [L83](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L83) | `async get_trace(self, execution_id: str) -> dict` |
| [L86](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L86) | `async list_skills(self, snapshot_id: str) -> dict` |
| [L89](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L89) | `async get_skill(self, snapshot_id: str, skill_id: str) -> dict` |
| [L94](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L94) | `async ping(self) -> str` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L97) | `async diff_skill(self, skill_id: str, before_snapshot_id: str, after_snapshot_id: str) -> dict` |
| [L109](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L109) | `async get_dependency_graph(self, snapshot_id: str, entry_skill_id: str) -> dict` |
| [L118](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L118) | `async create_workspace(self, start_versions: dict \| None=None, snapshot_id: str \| None=None) -> dict` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L128) | `async apply_patch(self, workspace_id: str, patches: list[dict]) -> dict` |
| [L137](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L137) | `async run_scenarios(self, workspace_id: str, scenario_ids: list[str], repeat: int=2, replay_run_ids: list[str] \| None=None, extra_checks: list \| None=None) -> dict` |
| [L156](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L156) | `async save_proposal(self, proposal: dict) -> dict` |
| [L170](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/skillops_agent/tools.py#L170) | `get_tools() -> MCPTools \| StubMCPTools` |

## 4. 통합 Vue/JavaScript 운영 소스

38개 파일, 명시적 함수·메서드·생성자 137개.

### frontend/src/App.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/App.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/App.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/api/auth.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/auth.js)

| 위치 | 선언 |
|---|---|
| [L4](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/auth.js#L4) | `login(username, password)` |
| [L6](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/auth.js#L6) | `me()` |

### frontend/src/api/client.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/client.js)

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/client.js#L13) | `connectAuth(handlers)` |
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/client.js#L14) | `needsToken(url)` |
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/client.js#L16) | `async request(method, url, { body, headers = {} } = {})` |
| [L69](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/client.js#L69) | `get(url)` |
| [L70](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/client.js#L70) | `post(url, body, headers)` |

### frontend/src/api/diagnoses.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/diagnoses.js)

| 위치 | 선언 |
|---|---|
| [L3](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/diagnoses.js#L3) | `listDiagnoses()` |
| [L4](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/diagnoses.js#L4) | `getDiagnosis(threadId)` |
| [L5](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/diagnoses.js#L5) | `getChangeDelivery(changeId)` |

### frontend/src/api/error.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/error.js)

| 위치 | 선언 |
|---|---|
| [L3](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/error.js#L3) | `constructor(status, message, { code = null, orderId = null, fieldErrors = [], body = null } = {})` |

### frontend/src/api/orderAgent.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js)

| 위치 | 선언 |
|---|---|
| [L5](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L5) | `listProducts()` |
| [L7](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L7) | `getOrder(orderId)` |
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L9) | `getOrderByRequest(requestId)` |
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L11) | `sleep(ms)` |
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L16) | `async recover(requestId, deadlineMs = 60000)` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L34) | `async createOrder(order, { snapshotId = null } = {})` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L47) | `listExecutions()` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L50) | `listFailedExecutions()` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L52) | `getTrace(executionId)` |
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L55) | `getSnapshot(snapshotId)` |
| [L57](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L57) | `getSnapshotFile(snapshotId, path)` |
| [L61](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L61) | `listSnapshotSkills(snapshotId)` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L63) | `getSnapshotSkill(snapshotId, skillId)` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L68) | `pinFixture(fixture)` |
| [L70](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/orderAgent.js#L70) | `saveSkillEdits(baselineSnapshotId, baselineHash, changes)` |

### frontend/src/api/proposals.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/proposals.js)

| 위치 | 선언 |
|---|---|
| [L7](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/proposals.js#L7) | `listProposals()` |
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/proposals.js#L11) | `getProposal(id)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/proposals.js#L15) | `approveProposal(proposalId, candidateNo, intentConfirmed)` |
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/proposals.js#L21) | `rejectProposal(proposalId)` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/api/proposals.js#L24) | `listApprovals()` |

### frontend/src/components/AppSidebar.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/AppSidebar.vue)

| 위치 | 선언 |
|---|---|
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/AppSidebar.vue#L30) | `logout()` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/AppSidebar.vue#L36) | `async countPending()` |

### frontend/src/components/DiffView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/DiffView.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/DiffView.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/components/EmptyState.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/EmptyState.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/EmptyState.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/components/EventTimeline.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/EventTimeline.vue)

| 위치 | 선언 |
|---|---|
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/EventTimeline.vue#L34) | `toggleRaw(seq)` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/EventTimeline.vue#L40) | `pretty(v)` |

### frontend/src/components/FieldList.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/FieldList.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/FieldList.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/components/PageHeader.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/PageHeader.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/PageHeader.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/components/ProposalDocument.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/ProposalDocument.vue)

| 위치 | 선언 |
|---|---|
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/ProposalDocument.vue#L8) | `runLinks(value)` |

### frontend/src/components/SnapshotPicker.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/SnapshotPicker.vue)

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/SnapshotPicker.vue#L15) | `async pin(fixture)` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/SnapshotPicker.vue#L27) | `async pick(id)` |

### frontend/src/components/StatusBadge.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/StatusBadge.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/StatusBadge.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/components/ToastHost.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/ToastHost.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/components/ToastHost.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/main.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/main.js)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/main.js#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/mocks/index.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js)

| 위치 | 선언 |
|---|---|
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L10) | `clone(v)` |
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L11) | `sleep(ms)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L12) | `now()` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L13) | `uuid()` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L15) | `async sha256(text)` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L31) | `async publish(files, metadata)` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L41) | `byteSize(text)` |
| [L43](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L43) | `manifest(s)` |
| [L71](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L71) | `runTool(order, tool, ctx)` |
| [L110](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L110) | `toolArgs(tool, ctx)` |
| [L121](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L121) | `async execute(order, snapshot)` |
| [L125](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L125) | `ev(kind, skill_id, data)` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L128) | `step(name, status, result_code, message)` |
| [L130](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L130) | `release(skillId, reason)` |
| [L171](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L171) | `fail(category, details)` |
| [L201](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L201) | `finished(extra)` |
| [L304](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L304) | `async createOrder(body, headers)` |
| [L359](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L359) | `publicOrder(o)` |
| [L364](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L364) | `summary({ events: _events, ...rest })` |
| [L367](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L367) | `serverSkills(snapshot)` |
| [L392](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L392) | `mockLogin({ username, password })` |
| [L405](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L405) | `mockUser(headers)` |
| [L411](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L411) | `notFound(code)` |
| [L414](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L414) | `summaryOf(p)` |
| [L594](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L594) | `async seed()` |
| [L607](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/index.js#L607) | `async handleMock(method, url, body, headers = {})` |

### frontend/src/mocks/skillFiles.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/skillFiles.js)

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/mocks/skillFiles.js#L14) | `fixtureFiles(fixture)` |

### frontend/src/router/index.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/router/index.js)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/router/index.js#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### frontend/src/stores/auth.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/auth.js)

| 위치 | 선언 |
|---|---|
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/auth.js#L8) | `restore()` |
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/auth.js#L17) | `persist(value)` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/auth.js#L31) | `hasRole(role)` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/auth.js#L33) | `token()` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/auth.js#L42) | `async login(username, password)` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/auth.js#L52) | `logout()` |

### frontend/src/stores/toast.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/toast.js)

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/toast.js#L9) | `show(message, tone = 'ok', ms = 3500)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/toast.js#L15) | `dismiss(id)` |

### frontend/src/stores/workspace.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js)

| 위치 | 선언 |
|---|---|
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L22) | `restore()` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L42) | `async saveDrafts()` |
| [L79](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L79) | `async ensureSet(id)` |
| [L93](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L93) | `async select(id)` |
| [L98](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L98) | `async refreshExecutions()` |
| [L106](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L106) | `async pin(fixture)` |
| [L131](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L131) | `fixtureOf(id)` |
| [L134](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L134) | `labelOf(id)` |
| [L157](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L157) | `setDraft(path, text)` |
| [L168](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L168) | `applyPatchesToDraft(patches)` |
| [L182](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L182) | `async init()` |
| [L191](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L191) | `removeDraft(path)` |
| [L198](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/stores/workspace.js#L198) | `clearDrafts()` |

### frontend/src/utils/eventView.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/eventView.js)

| 위치 | 선언 |
|---|---|
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/eventView.js#L40) | `short(id)` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/eventView.js#L42) | `keyLabel(key)` |
| [L43](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/eventView.js#L43) | `toolLabel(tool)` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/eventView.js#L46) | `formatValue(key, value)` |
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/eventView.js#L65) | `fieldsOf(obj)` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/eventView.js#L68) | `parseModel(raw)` |
| [L77](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/eventView.js#L77) | `describe(e)` |

### frontend/src/utils/flowGraph.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/flowGraph.js)

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/flowGraph.js#L16) | `buildGraph(skills, steps, entrySkillId = 'order-flow')` |
| [L70](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/flowGraph.js#L70) | `problemCount(graph)` |

### frontend/src/utils/frontmatter.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/frontmatter.js)

| 위치 | 선언 |
|---|---|
| [L4](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/frontmatter.js#L4) | `splitSkillMd(text)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/frontmatter.js#L12) | `list(v)` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/frontmatter.js#L15) | `parseSkillMd(text)` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/frontmatter.js#L42) | `replaceSteps(text, steps)` |

### frontend/src/utils/proposalView.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/proposalView.js)

| 위치 | 선언 |
|---|---|
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/proposalView.js#L18) | `awaitsDecision(p)` |

### frontend/src/utils/skillSet.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/skillSet.js)

| 위치 | 선언 |
|---|---|
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/skillSet.js#L10) | `skillPath(id)` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/skillSet.js#L13) | `formatProblem(p, skillId)` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/skillSet.js#L26) | `toSkill(path, text, fileHash = null)` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/skillSet.js#L46) | `fromServer(item, content)` |
| [L67](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/skillSet.js#L67) | `async loadSkillSet(snapshotId)` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/skillSet.js#L92) | `flowSteps(skills)` |

### frontend/src/utils/traceRun.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/traceRun.js)

| 위치 | 선언 |
|---|---|
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/traceRun.js#L41) | `isErrorEvent(e)` |
| [L44](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/traceRun.js#L44) | `traceRun(trace, steps)` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/traceRun.js#L47) | `ensure(id)` |
| [L144](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/traceRun.js#L144) | `executionLabel(x)` |
| [L151](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/utils/traceRun.js#L151) | `executionPath(id)` |

### frontend/src/views/DiagnosisView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/DiagnosisView.vue)

| 위치 | 선언 |
|---|---|
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/DiagnosisView.vue#L22) | `executionLink(id)` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/DiagnosisView.vue#L23) | `async refresh(epoch)` |

### frontend/src/views/ExecutionView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/ExecutionView.vue)

| 위치 | 선언 |
|---|---|
| [L64](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/ExecutionView.vue#L64) | `statusOf(id)` |

### frontend/src/views/LoginView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/LoginView.vue)

| 위치 | 선언 |
|---|---|
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/LoginView.vue#L17) | `async submit()` |

### frontend/src/views/OrderView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/OrderView.vue)

| 위치 | 선언 |
|---|---|
| [L38](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/OrderView.vue#L38) | `won(n)` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/OrderView.vue#L45) | `async showFailed()` |
| [L54](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/OrderView.vue#L54) | `runLabel(x)` |
| [L57](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/OrderView.vue#L57) | `preset(p)` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/OrderView.vue#L62) | `async loadProducts()` |
| [L70](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/OrderView.vue#L70) | `async submit()` |

### frontend/src/views/ProposalListView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/ProposalListView.vue)

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/ProposalListView.vue#L14) | `pendingOnly(items)` |

### frontend/src/views/ProposalView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/ProposalView.vue)

| 위치 | 선언 |
|---|---|
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/ProposalView.vue#L41) | `async load(id)` |
| [L64](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/ProposalView.vue#L64) | `async decide(action)` |
| [L82](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/ProposalView.vue#L82) | `applyToDraft()` |

### frontend/src/views/SkillDraftView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SkillDraftView.vue)

| 위치 | 선언 |
|---|---|
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SkillDraftView.vue#L23) | `async save()` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SkillDraftView.vue#L48) | `stateOf(s)` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SkillDraftView.vue#L62) | `revert()` |
| [L67](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SkillDraftView.vue#L67) | `removeNew()` |
| [L90](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SkillDraftView.vue#L90) | `edit(text)` |
| [L94](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SkillDraftView.vue#L94) | `create()` |

### frontend/src/views/SnapshotCompareView.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SnapshotCompareView.vue)

| 위치 | 선언 |
|---|---|
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SnapshotCompareView.vue#L22) | `summary(set)` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SnapshotCompareView.vue#L30) | `async compare()` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SnapshotCompareView.vue#L40) | `async text(id, set, path)` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SnapshotCompareView.vue#L68) | `fieldChanges(l, r)` |
| [L81](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SnapshotCompareView.vue#L81) | `async prepareDemo()` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/src/views/SnapshotCompareView.vue#L97) | `label(id)` |

## 5. 주문 Vue/JavaScript 운영 소스

14개 파일, 명시적 함수·메서드·생성자 42개.

### order-agent/frontend/src/App.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/App.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/App.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### order-agent/frontend/src/api/client.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/client.js)

| 위치 | 선언 |
|---|---|
| [L2](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/client.js#L2) | `constructor(message, { status = 0, code = "NETWORK_ERROR", order_id = null, field_errors = [], } = {})` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/client.js#L22) | `async request(path, { timeout = 5000, signal, ...options } = {})` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/client.js#L27) | `abort()` |

### order-agent/frontend/src/api/evidence.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/evidence.js)

| 위치 | 선언 |
|---|---|
| [L4](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/evidence.js#L4) | `listExecutions(options)` |
| [L5](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/evidence.js#L5) | `getTrace(id, options)` |
| [L6](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/evidence.js#L6) | `getSnapshot(id, options)` |
| [L7](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/evidence.js#L7) | `getSnapshotFile(id, path, options)` |
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/evidence.js#L8) | `publishSnapshot(fixture, options)` |

### order-agent/frontend/src/api/orders.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/orders.js)

| 위치 | 선언 |
|---|---|
| [L2](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/orders.js#L2) | `createOrder(key, body, options)` |
| [L10](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/orders.js#L10) | `getOrder(id, options)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/orders.js#L12) | `getByRequest(key, options)` |

### order-agent/frontend/src/api/products.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/products.js)

| 위치 | 선언 |
|---|---|
| [L2](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/api/products.js#L2) | `listProducts(options)` |

### order-agent/frontend/src/components/DemoPresets.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/DemoPresets.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/DemoPresets.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### order-agent/frontend/src/components/ExecutionInspector.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/ExecutionInspector.vue)

| 위치 | 선언 |
|---|---|
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/ExecutionInspector.vue#L11) | `options()` |
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/ExecutionInspector.vue#L17) | `pretty(value)` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/ExecutionInspector.vue#L18) | `async inspect(id)` |
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/ExecutionInspector.vue#L35) | `async refresh()` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/ExecutionInspector.vue#L41) | `async readFile()` |
| [L49](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/ExecutionInspector.vue#L49) | `async publish(fixture)` |

### order-agent/frontend/src/components/OrderForm.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderForm.vue)

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderForm.vue#L15) | `update(change)` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderForm.vue#L18) | `itemChange(index, field, value)` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderForm.vue#L25) | `add()` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderForm.vue#L32) | `product(sku)` |

### order-agent/frontend/src/components/OrderResult.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderResult.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderResult.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### order-agent/frontend/src/components/OrderSteps.vue

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderSteps.vue)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/components/OrderSteps.vue#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### order-agent/frontend/src/composables/useOrder.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js)

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L9) | `copy(value)` |
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L11) | `useOrder(api = { listProducts, createOrder, getOrder, getByRequest }, options = {})` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L40) | `active(id)` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L42) | `async loadProducts()` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L59) | `wait(ms)` |
| [L67](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L67) | `accept(result)` |
| [L81](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L81) | `failed(error, id)` |
| [L104](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L104) | `async finish(result, id)` |
| [L124](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L124) | `async dispatch(id)` |
| [L135](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L135) | `async submit()` |
| [L147](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L147) | `async recover()` |
| [L180](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L180) | `async resend()` |
| [L187](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L187) | `preset(name)` |
| [L196](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L196) | `newOrder()` |
| [L207](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/composables/useOrder.js#L207) | `dispose()` |

### order-agent/frontend/src/main.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/main.js)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/main.js#L1) | `모듈 초기화·상수·반응형 선언 또는 Vue 표시 구성` |

### order-agent/frontend/src/utils/format.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/utils/format.js)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/utils/format.js#L1) | `money(value)` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/utils/format.js#L30) | `timestamp(value)` |

### order-agent/frontend/src/utils/validation.js

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/utils/validation.js)

| 위치 | 선언 |
|---|---|
| [L1](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/utils/validation.js#L1) | `defaultForm()` |
| [L7](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/utils/validation.js#L7) | `validate(form)` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/src/utils/validation.js#L30) | `estimate(form, products)` |

## 6. Python 스크립트

6개 파일, 명시적 함수·메서드·생성자 47개.

### skillops-agent/scripts/b_c_contract_smoke.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/b_c_contract_smoke.py) · 타입: `main.Chain`

| 위치 | 선언 |
|---|---|
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/b_c_contract_smoke.py#L25) | `async main()` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/b_c_contract_smoke.py#L30) | `async get(path)` |
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/b_c_contract_smoke.py#L35) | `async post(path, body)` |
| [L73](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/b_c_contract_smoke.py#L73) | `__init__(self, output)` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/b_c_contract_smoke.py#L76) | `async ainvoke(self, *args)` |
| [L79](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/b_c_contract_smoke.py#L79) | `fake_llm(schema)` |
| [L136](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/b_c_contract_smoke.py#L136) | `async edited_snapshot(path, transform)` |

### skillops-agent/scripts/c2_real_judge.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py) · 타입: `ToolError`, `Candidate`, `Verdict`, `OrderAgent`

| 위치 | 선언 |
|---|---|
| [L83](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L83) | `__init__(self, tool: str, payload)` |
| [L106](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L106) | `text_of(result) -> str` |
| [L114](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L114) | `async call(client, tool: str, arguments: dict) -> dict` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L128) | `execution_id_of(run_id: str) -> str` |
| [L136](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L136) | `unified(path: str, before: str, after: str) -> str \| None` |
| [L151](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L151) | `join_hunks(diffs: list[str]) -> str` |
| [L170](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L170) | `swap_payment_input(text: str) -> str` |
| [L191](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L191) | `__init__(self, http: httpx.AsyncClient)` |
| [L194](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L194) | `async fixture(self, name: str) -> dict` |
| [L199](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L199) | `async file_text(self, snapshot_id: str, path: str) -> str` |
| [L204](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L204) | `async lantern_stock(self) -> int` |
| [L209](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L209) | `async failed(self) -> list[dict]` |
| [L214](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L214) | `async live_order(self, snapshot_id: str) -> httpx.Response` |
| [L221](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L221) | `async trace(self, execution_id: str) -> dict` |
| [L227](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L227) | `trace_line(trace: dict) -> str` |
| [L250](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L250) | `split_reason(reason: str) -> list[str]` |
| [L254](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L254) | `intent_state(result: dict) -> str` |
| [L268](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L268) | `candidate_intent(results: dict[str, dict]) -> str` |
| [L277](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L277) | `base_passed(result: dict) -> bool` |
| [L286](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L286) | `is_valid(v: Verdict, replay_id: str) -> bool` |
| [L293](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L293) | `mark(ok: bool) -> str` |
| [L297](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L297) | `table(verdicts: list[Verdict], replay_id: str) -> str` |
| [L316](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L316) | `section(title: str) -> None` |
| [L320](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L320) | `async build_candidates(client, agent: OrderAgent, snaps: dict[str, str], skills: list[str]) -> list[Candidate]` |
| [L360](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L360) | `async judge(client, candidate: Candidate, broken: str, replay_id: str) -> Verdict` |
| [L396](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L396) | `async evidence(agent: OrderAgent, verdicts: list[Verdict], replay_id: str) -> None` |
| [L422](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_real_judge.py#L422) | `async main(reuse_failed: str \| None) -> int` |

### skillops-agent/scripts/c2_smoke.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_smoke.py)

| 위치 | 선언 |
|---|---|
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_smoke.py#L24) | `_text_of(result) -> str \| None` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_smoke.py#L32) | `_fail(step: str, detail) -> int` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/c2_smoke.py#L37) | `async main() -> int` |

### skillops-agent/scripts/mcp_call.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/mcp_call.py)

| 위치 | 선언 |
|---|---|
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/mcp_call.py#L22) | `_text_of(result) -> str` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/mcp_call.py#L30) | `_pretty(text: str) -> str` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/mcp_call.py#L37) | `async main(argv: list[str]) -> int` |

### skillops-agent/scripts/mcp_smoke.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/mcp_smoke.py)

| 위치 | 선언 |
|---|---|
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/mcp_smoke.py#L18) | `_text_of(result) -> str \| None` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/mcp_smoke.py#L26) | `async main() -> int` |

### skillops-agent/scripts/run_demo.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/run_demo.py) · 타입: `_FakeChain`

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/run_demo.py#L15) | `__init__(self, val)` |
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/run_demo.py#L17) | `async ainvoke(self, *a, **kw)` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/run_demo.py#L20) | `_fake_llm(schema)` |
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/run_demo.py#L55) | `c(color, text)` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/scripts/run_demo.py#L59) | `async main()` |

## 7. Java/Python 테스트 선언

47개 파일, 명시적 함수·메서드·생성자 477개.

### order-agent/backend/src/test/java/com/capstone4/orderagent/adapter/MockPolicyTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/adapter/MockPolicyTest.java) · 타입: `MockPolicyTest`

| 위치 | 선언 |
|---|---|
| [L7](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/adapter/MockPolicyTest.java#L7) | `void paymentDeclineIsAnExplicitBusinessResult()` |
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/adapter/MockPolicyTest.java#L13) | `void shippingGuardsPaymentAndCalculatesDeterministicEta()` |

### order-agent/backend/src/test/java/com/capstone4/orderagent/harness/DecisionLimitTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/DecisionLimitTest.java) · 타입: `DecisionLimitTest`

| 위치 | 선언 |
|---|---|
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/DecisionLimitTest.java#L11) | `DecisionProvider.Question question()` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/DecisionLimitTest.java#L12) | `void transientApiRetryCountsEveryCallAndUsesSameQuestion()` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/DecisionLimitTest.java#L15) | `public String mode()` |
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/DecisionLimitTest.java#L16) | `public Reply decide(Question q)` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/DecisionLimitTest.java#L24) | `void authenticationAndWrongChoicesAreNeverRetried()` |
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/DecisionLimitTest.java#L35) | `void retryCannotBypassOverallApiBudget()` |

### order-agent/backend/src/test/java/com/capstone4/orderagent/harness/SkillBoundaryTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/SkillBoundaryTest.java) · 타입: `SkillBoundaryTest`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/SkillBoundaryTest.java#L9) | `void loaderRejectsTraversalInvalidYamlAndDuplicateMetadata()` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/SkillBoundaryTest.java#L18) | `void allAttemptsShareBudgetAndObservedTokensDoNotBypassIt()` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/harness/SkillBoundaryTest.java#L23) | `void runtimeContractIncludesMissingInputsAndDependencies()` |

### order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java) · 타입: `AgentEvidenceIntegrationTest`

| 위치 | 선언 |
|---|---|
| [L38](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L38) | `void reset()` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L39) | `CreateOrderRequest input(UUID snapshot)` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L40) | `int stock()` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L41) | `UUID execution()` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L42) | `List<Map<String, Object>> events(Map<String, Object> trace)` |
| [L44](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L44) | `void editorApiSavesActualEditsAndExecutesReturnedSnapshot() throws Exception` |
| [L79](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L79) | `void editorApiRejectsInvalidOrUnchangedRequestsWithoutPartialSave() throws Exception` |
| [L98](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L98) | `void normalExecutionActuallyLoadsSixSkillsAndSharesSnapshotReferences() throws Exception` |
| [L117](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L117) | `void descriptionOnlyEditPreservesBusinessResultsAndLoadsEditedBytes(String customer, int initialStock, String expectedStatus, int remainingStock) throws Exception` |
| [L177](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L177) | `void deletedInventoryOutputFailsAtPaymentBeforeBusinessToolsAndRestorationPasses() throws Exception` |
| [L233](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L233) | `void shippingBeforePaymentFailsStaticValidationAndRestoredFlowPasses() throws Exception` |
| [L303](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L303) | `void bodyOnlyPrepaidPolicyIsDeliveredButNotEnforcedByFixedMode(String method) throws Exception` |
| [L365](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L365) | `void declaredButUnproducedInventoryOutputFailsAtRuntimeAndReleasesReservation() throws Exception` |
| [L435](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L435) | `void brokenContractFailsBeforeBusinessToolsAndRepairedSnapshotPasses()` |
| [L453](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L453) | `void snapshotsPreserveMetadataRelatedBytesAndNeverOverwritePreviousCandidate()` |
| [L463](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L463) | `void tamperedSnapshotsAreRejectedAndNeverUsed()` |
| [L470](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L470) | `void unknownSkillRetainsOriginalNameAndRefusesLoad()` |
| [L477](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L477) | `void explicitNoSelectionIsNotAnUnknownSkill()` |
| [L483](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L483) | `void invalidModelArgumentsNeverChargeAndCompensateCommittedReservation()` |
| [L496](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java#L496) | `void incompleteFlowIsNeverMarkedShipped() throws Exception` |

### order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java) · 타입: `CandidateSnapshotIntegrationTest`

| 위치 | 선언 |
|---|---|
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java#L29) | `void reset()` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java#L34) | `Map<String, String> baselineFiles()` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java#L39) | `String publish(Object files, int status) throws Exception` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java#L45) | `void publishesCandidateFiles() throws Exception` |
| [L56](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java#L56) | `void orderRunsWithCandidateSnapshot() throws Exception` |
| [L66](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java#L66) | `void rejectsPathOutsideSkills() throws Exception` |

### order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java) · 타입: `DiagnosisReadApiIntegrationTest`

| 위치 | 선언 |
|---|---|
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L35) | `void reset()` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L40) | `CreateOrderRequest input(UUID snapshot, String customer, String sku)` |
| [L44](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L44) | `Map<String, Object> read(String path) throws Exception` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L47) | `List<Map<String, Object>> items(Map<String, Object> body, String key)` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L50) | `void emptyAndNormalBusinessRejectionsAreNotExecutionFailures() throws Exception` |
| [L56](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L56) | `void allLiveFailuresAreReturnedInFailureTimeOrderIncludingMissingSnapshots() throws Exception` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L75) | `void insertExecution(UUID id, UUID order, String mode, String completion, Instant at)` |
| [L81](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L81) | `void contractFailureKeepsExactEvidenceWithoutInventingExecutedSteps() throws Exception` |
| [L95](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L95) | `void successfulExecutionRecordsActualStepInputsOutputsAndMeasuredDuration() throws Exception` |
| [L113](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L113) | `void skillMetadataAndOriginalBytesShareTheSnapshotAndManifestHashes() throws Exception` |
| [L132](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L132) | `void malformedSkillIsReportedAsPartialButOriginalFileRemainsReadable() throws Exception` |
| [L142](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L142) | `void invalidUtf8AndMissingRequiredMetadataAreNotSilentlyNormalized() throws Exception` |
| [L153](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java#L153) | `void missingInvalidAndTamperedResourcesHaveExplicitHttpErrors() throws Exception` |

### order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java) · 타입: `DryRunIntegrationTest`

| 위치 | 선언 |
|---|---|
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L33) | `void reset()` |
| [L38](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L38) | `Map<String, Object> dryRun(UUID snapshot, int status) throws Exception` |
| [L43](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L43) | `Map<String, Object> read(String path) throws Exception` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L46) | `List<Map<String, Object>> items(Map<String, Object> body, String key)` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L47) | `int lanternStock() throws Exception` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L52) | `void dryRunShipsWithBaseline() throws Exception` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L59) | `void dryRunLeavesStockUnchanged() throws Exception` |
| [L69](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L69) | `void dryRunFailureIsNotListedAsFailed() throws Exception` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L76) | `void liveOrderStillLive() throws Exception` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java#L85) | `void unknownSnapshotFailsBeforeAnyOrderIsCreated() throws Exception` |

### order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java) · 타입: `OrderApiIntegrationTest`

| 위치 | 선언 |
|---|---|
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L41) | `void reset()` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L45) | `String body(String customer, String sku, int qty)` |
| [L51](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L51) | `String create(String key, String body, int status) throws Exception` |
| [L56](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L56) | `int stock()` |
| [L58](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L58) | `void normalOrderPersistsAndReturnsActualSteps() throws Exception` |
| [L72](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L72) | `void stockRejectionNeverPaysShipsOrNotifies() throws Exception` |
| [L80](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L80) | `void declineRestoresStockAndRecordsRelease() throws Exception` |
| [L88](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L88) | `void multiItemReservationIsAllOrNothing() throws Exception` |
| [L94](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L94) | `void sameKeyIsIdempotentAndChangedBodyConflicts() throws Exception` |
| [L103](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L103) | `void duplicateSkusNormalizeBeforeHashAndStockCheck() throws Exception` |
| [L112](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L112) | `void invalidInputAndMissingResourcesAreSafeErrors() throws Exception` |
| [L123](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L123) | `void failurePreservesCommittedPaymentAndTrace() throws Exception` |
| [L136](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L136) | `CreateOrderRequest input()` |
| [L140](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L140) | `void concurrentOrdersNeverOversellLastStock() throws Exception` |
| [L151](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L151) | `void concurrentSameKeyProducesOneOrderAndOneCharge() throws Exception` |
| [L162](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/integration/OrderApiIntegrationTest.java#L162) | `void restartRecoveryDoesNotReplayIncompleteOrders() throws Exception` |

### order-agent/backend/src/test/java/com/capstone4/orderagent/workspace/SkillChangeDispatcherTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/workspace/SkillChangeDispatcherTest.java) · 타입: `SkillChangeDispatcherTest`

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/test/java/com/capstone4/orderagent/workspace/SkillChangeDispatcherTest.java#L15) | `void failedDeliveryRemainsPendingAndSuccessfulRetryRecordsThread()` |

### service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java) · 타입: `ApprovalControllerTest`

| 위치 | 선언 |
|---|---|
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L68) | `private String tokenFor(String username)` |
| [L73](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L73) | `private MockHttpServletRequestBuilder approve(String token)` |
| [L78](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L78) | `void approval_requires_login() throws Exception` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L85) | `void owner_cannot_approve() throws Exception` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L92) | `void operator_approves_and_is_recorded_as_approver() throws Exception` |
| [L107](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L107) | `void proposalErrorKeepsCodeAndStatus() throws Exception` |
| [L118](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L118) | `void optimisticLockFailureBecomesConflict() throws Exception` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L128) | `void candidateNoBelowOneIsBadRequest() throws Exception` |
| [L136](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L136) | `void approval_without_intent_confirmation_is_refused() throws Exception` |
| [L145](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L145) | `void missing_candidate_is_rejected() throws Exception` |
| [L152](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L152) | `void operator_can_reject_and_owner_cannot() throws Exception` |
| [L165](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L165) | `void other_api_paths_stay_open_for_teammates() throws Exception` |
| [L173](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L173) | `void owner_can_read_approval_history() throws Exception` |
| [L182](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L182) | `void forged_token_is_rejected() throws Exception` |
| [L192](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L192) | `void expired_token_is_rejected() throws Exception` |
| [L200](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L200) | `void mcp_endpoint_is_not_behind_login() throws Exception` |
| [L208](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java#L208) | `private static JwtClaimsSet claims(Instant expiresAt)` |

### service/src/test/java/com/capstone4/skillops/api/HealthControllerTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/HealthControllerTest.java) · 타입: `HealthControllerTest`

| 위치 | 선언 |
|---|---|
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/HealthControllerTest.java#L18) | `void health_returns_ok() throws Exception` |

### service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java) · 타입: `ProposalControllerTest`

| 위치 | 선언 |
|---|---|
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java#L48) | `private String tokenFor(String username)` |
| [L53](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java#L53) | `static Proposal proposal()` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java#L68) | `void listRequiresLogin() throws Exception` |
| [L73](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java#L73) | `void ownerCanReadList() throws Exception` |
| [L89](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java#L89) | `void detailCarriesDocumentAndDecision() throws Exception` |
| [L107](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java#L107) | `void undecidedDetailHasNullDecision() throws Exception` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java#L116) | `void unknownProposalIs404() throws Exception` |

### service/src/test/java/com/capstone4/skillops/approval/ApprovalServiceTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/approval/ApprovalServiceTest.java) · 타입: `ApprovalServiceTest`

| 위치 | 선언 |
|---|---|
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/approval/ApprovalServiceTest.java#L36) | `void approvalRecordsCandidateNumberAndMarksProposal()` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/approval/ApprovalServiceTest.java#L52) | `void rejectHasNoTokenAndNoCandidate()` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/approval/ApprovalServiceTest.java#L62) | `void rejectsCandidateThatDidNotPass()` |
| [L71](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/approval/ApprovalServiceTest.java#L71) | `void latestDecisionIsReadFromRepository()` |

### service/src/test/java/com/capstone4/skillops/auth/AuthControllerTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/auth/AuthControllerTest.java) · 타입: `AuthControllerTest`

| 위치 | 선언 |
|---|---|
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/auth/AuthControllerTest.java#L33) | `private String login(String username, String password) throws Exception` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/auth/AuthControllerTest.java#L39) | `void login_issues_signed_token_with_roles() throws Exception` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/auth/AuthControllerTest.java#L52) | `void wrong_password_and_unknown_user_get_the_same_401() throws Exception` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/auth/AuthControllerTest.java#L62) | `void blank_fields_are_rejected() throws Exception` |
| [L69](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/auth/AuthControllerTest.java#L69) | `void me_returns_token_user_and_requires_login() throws Exception` |

### service/src/test/java/com/capstone4/skillops/diagnosis/DiagnosisControllerTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/diagnosis/DiagnosisControllerTest.java) · 타입: `DiagnosisControllerTest`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/diagnosis/DiagnosisControllerTest.java#L14) | `void forwardsChangeAndServerTokenToAgent()` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/diagnosis/DiagnosisControllerTest.java#L25) | `void wrongInternalTokenIsRejectedBeforeForwarding()` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/diagnosis/DiagnosisControllerTest.java#L30) | `void progressIsReadWithoutExposingToken()` |

### service/src/test/java/com/capstone4/skillops/mcp/ProposalToolsTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/ProposalToolsTest.java) · 타입: `ProposalToolsTest`

| 위치 | 선언 |
|---|---|
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/ProposalToolsTest.java#L28) | `void returnsProposalIdStatusAndValidNumbers()` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/ProposalToolsTest.java#L40) | `void invalidProposalBecomesInvalidInputError()` |
| [L51](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/ProposalToolsTest.java#L51) | `private static Map<String, Object> mapOf(String json)` |
| [L56](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/ProposalToolsTest.java#L56) | `private static String textOf(CallToolResult result)` |

### service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java) · 타입: `WorkspaceToolsTest`

| 위치 | 선언 |
|---|---|
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L40) | `void createWorkspaceReturnsWorkspaceId()` |
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L55) | `void createWorkspacePassesSnapshotId()` |
| [L70](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L70) | `void baseAndSnapshotTogetherIsInvalidInput()` |
| [L84](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L84) | `void orderAgentFailureKeepsItsCodeAndRetryable()` |
| [L102](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L102) | `void orderAgentNotFoundKeepsUpstreamStatus()` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L116) | `void runScenariosReturnsV1Report()` |
| [L138](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L138) | `void applyPatchReturnsV1Shape()` |
| [L158](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L158) | `void applyPatchFailureBecomesInvalidInput()` |
| [L173](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L173) | `void applyPatchTakesSnakeCaseInputThroughMcp()` |
| [L197](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L197) | `void runScenariosTakesReplayRunIdsThroughMcp()` |
| [L225](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L225) | `void invalidInputBecomesStructuredError()` |
| [L243](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L243) | `void unexpectedFailureBecomesInternalError()` |
| [L256](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L256) | `private static Map<String, Object> mapOf(String json)` |
| [L261](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java#L261) | `private static String textOf(CallToolResult result)` |

### service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java) · 타입: `ProposalDocumentWriterTest`

| 위치 | 선언 |
|---|---|
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L24) | `static Map<String, Object> fixture(String name)` |
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L29) | `static ProposalDocument write()` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L33) | `static Section section(ProposalDocument doc, String key)` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L37) | `static Section candidates(ProposalDocument doc)` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L41) | `static Map<String, Object> withoutExplanations(Map<String, Object> proposal)` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L48) | `static List<String> allTexts(ProposalDocument doc)` |
| [L54](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L54) | `static void collect(Section s, List<String> texts)` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L68) | `void summaryFollowsTheFixedSentences()` |
| [L79](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L79) | `void sectionsFollowTheTableOfContents()` |
| [L87](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L87) | `void whatHappenedListsWhenWhereAndWhichValue()` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L105) | `void causeSeparatesWhereItStoppedFromWhatChanged()` |
| [L119](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L119) | `void intentQuotesTheTextAndListsChecks()` |
| [L130](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L130) | `void passingCandidateIsJudgedApplicable()` |
| [L147](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L147) | `void failedCandidateExplainsEachCriterion()` |
| [L161](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L161) | `void candidatesSectionIntroducesTheTrials()` |
| [L168](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L168) | `void decisionNamesTheRecommendationAndWhatApprovalDoes()` |
| [L180](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L180) | `void referenceCarriesIdsAndCreationTime()` |
| [L189](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L189) | `void noRootCauseStillProducesAReadableDocument()` |
| [L205](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L205) | `void templatesNeverUseBannedPhrases()` |
| [L214](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L214) | `void explanationPhrasesAreRecordedNotRewritten()` |
| [L224](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L224) | `static Map<String, Object> withUntestedSecondCandidate()` |
| [L234](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L234) | `static Map<String, Object> withoutIntentChecks()` |
| [L241](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L241) | `void untestedCandidateIsNotCountedAsTried()` |
| [L250](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L250) | `void nothingTestedIsSaidPlainly()` |
| [L264](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L264) | `void noIntentChecksAreNotClaimed()` |
| [L274](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java#L274) | `static Map<String, Object> candidate(Map<String, Object> proposal, int index)` |

### service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java) · 타입: `ProposalServiceTest`

| 위치 | 선언 |
|---|---|
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L36) | `void setUp()` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L48) | `static Map<String, Object> f1()` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L52) | `static Map<String, Object> candidate(Map<String, Object> proposal, int index)` |
| [L57](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L57) | `static Map<String, Object> results(Map<String, Object> proposal, int index)` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L62) | `Proposal captured()` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L68) | `void savesAndReturnsContractShape()` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L85) | `void sameThreadReturnsTheFirstProposal()` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L97) | `void unknownEvidenceRunIsRejected()` |
| [L105](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L105) | `void evidenceFromAnotherWorkspaceIsRejected()` |
| [L112](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L112) | `void unknownProofRunIsRejected()` |
| [L121](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L121) | `void agentValidMustMatchRecomputedResult()` |
| [L128](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L128) | `void proposedWithoutPassingCandidateIsRejected()` |
| [L136](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L136) | `void noValidFixWithPassingCandidateIsRejected()` |
| [L143](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L143) | `void recommendedMustBePassingCandidate()` |
| [L150](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L150) | `void candidateNumbersMustStartAtOneWithoutGaps()` |
| [L157](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L157) | `void unknownStatusIsRejected()` |
| [L164](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L164) | `void noValidFixWithoutRootCauseIsAccepted()` |
| [L172](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L172) | `void rejectedCandidateWithValidationErrorNeedsNoEvidence()` |
| [L182](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L182) | `void emptyProposalIsRejected()` |
| [L189](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L189) | `Proposal stored(String status)` |
| [L196](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L196) | `static void assertProblem(Throwable thrown, String code, int httpStatus)` |
| [L203](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L203) | `void approvingFillsIntentConfirmed()` |
| [L217](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L217) | `void rejectingKeepsIntentUnconfirmed()` |
| [L229](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L229) | `void secondDecisionIsRejected()` |
| [L238](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L238) | `void noValidFixCannotBeApproved()` |
| [L245](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L245) | `void unknownCandidateNumberIsRejected()` |
| [L254](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceTest.java#L254) | `void unknownProposalIsNotFound()` |

### service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceWiringTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceWiringTest.java) · 타입: `ProposalServiceWiringTest`

| 위치 | 선언 |
|---|---|
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceWiringTest.java#L15) | `void springCreatesTheServiceWithItsPublicConstructor()` |

### service/src/test/java/com/capstone4/skillops/proposal/ToneCheckTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ToneCheckTest.java) · 타입: `ToneCheckTest`

| 위치 | 선언 |
|---|---|
| [L9](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ToneCheckTest.java#L9) | `void findsBannedPhrasesInOrder()` |
| [L15](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ToneCheckTest.java#L15) | `void plainSentenceHasNone()` |
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ToneCheckTest.java#L20) | `void nullOrBlankHasNone()` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/proposal/ToneCheckTest.java#L26) | `void findsExaggerationAndAwkwardWords()` |

### service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java) · 타입: `CheckTest`

| 위치 | 선언 |
|---|---|
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java#L12) | `void stringEquality()` |
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java#L19) | `void booleanAndNullWithKoreanKey()` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java#L27) | `void numbersCompareByValue()` |
| [L34](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java#L34) | `void inChecksMembership()` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java#L41) | `void rejectsMalformed()` |
| [L50](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java#L50) | `void actualText()` |

### service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java) · 타입: `ContractOnlyFlowRunnerTest`

| 위치 | 선언 |
|---|---|
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java#L19) | `void liveSkillsShipAndPassG01()` |
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java#L28) | `void f1ChangeBreaksPayment()` |
| [L38](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java#L38) | `void workspaceWithoutOrderFlowIsRejected()` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java#L46) | `void stepSkillMissingFromWorkspaceIsRejected()` |

### service/src/test/java/com/capstone4/skillops/scenario/FlowRunnerSelectionTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/FlowRunnerSelectionTest.java) · 타입: `FlowRunnerSelectionTest`

| 위치 | 선언 |
|---|---|
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/FlowRunnerSelectionTest.java#L19) | `void contractOnlyIsTheDefault()` |
| [L26](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/FlowRunnerSelectionTest.java#L26) | `void harnessSelectsTheOrderAgentRunner()` |

### service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java) · 타입: `HarnessFlowRunnerTest`

| 위치 | 선언 |
|---|---|
| [L49](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L49) | `void setUp()` |
| [L58](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L58) | `void publishesAllWorkspaceFiles()` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L76) | `void reusesSnapshotForSameFiles()` |
| [L94](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L94) | `void changedFilesArePublishedAgain()` |
| [L114](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L114) | `void sendsOnlyOrderFields()` |
| [L136](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L136) | `void runsAndConverts()` |
| [L150](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L150) | `void orderAgentDownIsReported()` |
| [L161](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L161) | `void connectionFailureIsReported()` |
| [L172](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L172) | `void refusesWorkspaceNotStartedFromSnapshot()` |
| [L186](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L186) | `void republishesWhenCachedSnapshotIsGone()` |
| [L210](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L210) | `void freshSnapshotNotFoundIsNotRetried()` |
| [L222](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L222) | `void orderAgentRejectionIsInvalidInputWithItsCode()` |
| [L239](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L239) | `void validationErrorCarriesFieldErrors()` |
| [L255](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L255) | `private static FlowRunner.DryRunRequest request(Map<String, Object> order)` |
| [L259](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L259) | `private static Map<String, Object> g01Order()` |
| [L263](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L263) | `private void expectDryRun(String snapshotId)` |
| [L270](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L270) | `private void expectTrace()` |
| [L277](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L277) | `private static ResponseCreator manifest(String snapshotId)` |
| [L282](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L282) | `private static ResponseCreator dryRunStarted()` |
| [L288](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/HarnessFlowRunnerTest.java#L288) | `private static ResponseCreator snapshotNotFound()` |

### service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java) · 타입: `OrderAgentRunClientTest`

| 위치 | 선언 |
|---|---|
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L41) | `void originalOrderKeepsOnlyOrderFields()` |
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L55) | `void originalOrderAcceptsRunIdAlias()` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L63) | `void unknownExecutionIsInvalidInput()` |
| [L74](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L74) | `void missingOrderIsInvalidInput()` |
| [L87](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L87) | `void badIdIsRejectedWithoutCalling()` |
| [L96](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L96) | `void readTimeoutIsReportedAsSlow()` |
| [L108](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L108) | `void connectTimeoutIsReportedAsUnreachable()` |
| [L118](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L118) | `void refusedConnectionIsReportedAsUnreachable()` |
| [L129](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L129) | `void readTimeoutOutlastsOrderAgentRunLimit()` |
| [L137](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L137) | `void serverErrorIsReportedAsUnreachable()` |
| [L148](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L148) | `void executionIdOfAcceptsUuidAndRunId()` |
| [L158](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L158) | `private void expectTraceAndOrder()` |
| [L167](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java#L167) | `private static String resource(String name)` |

### service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java) · 타입: `OrderAgentTraceConverterTest`

| 위치 | 선언 |
|---|---|
| [L17](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java#L17) | `void shippedTraceBecomesFiveOkSteps()` |
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java#L35) | `void contractViolationBecomesErrorStep()` |
| [L53](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java#L53) | `void runtimeErrorFillsTheFinishedErrorStep()` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java#L75) | `void shippedTraceMeetsG01Expectation()` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java#L85) | `void contractViolationFailsG01AtPayment()` |
| [L96](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java#L96) | `static Map<String, Object> trace(String name)` |
| [L109](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java#L109) | `private static Map<String, Object> event(int sequence, String kind, String skill, Map<String, Object> data)` |

### service/src/test/java/com/capstone4/skillops/scenario/ScenarioCatalogTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioCatalogTest.java) · 타입: `ScenarioCatalogTest`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioCatalogTest.java#L16) | `void loadsG01()` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioCatalogTest.java#L22) | `void brokenJsonNamesTheFile(Path dir) throws Exception` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioCatalogTest.java#L32) | `void missingIdNamesTheFile(Path dir) throws Exception` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioCatalogTest.java#L42) | `void duplicateIdNamesBothFiles(Path dir) throws Exception` |
| [L53](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioCatalogTest.java#L53) | `void unknownIdListsAvailable()` |

### service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java) · 타입: `ScenarioGraderTest`

| 위치 | 선언 |
|---|---|
| [L16](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L16) | `void passesG01()` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L24) | `void f1RunFailsWithAllReasonsInOrder()` |
| [L38](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L38) | `void failsOnWrongValueOnly()` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L46) | `void extraChecksApplyAfterScenarioChecks()` |
| [L53](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L53) | `void replayPassesUnlessFailed()` |
| [L60](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L60) | `void skipsMissingExpectFields()` |
| [L67](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L67) | `private static RunOutcome shippedRun()` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L76) | `private static RunOutcome shippedRunWith(String key, Object value)` |
| [L88](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L88) | `private static RunOutcome runWithFinalStatus(String finalStatus)` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L92) | `private static RunOutcome f1Run()` |
| [L102](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java#L102) | `private static RunOutcome.Step ok(String skill, Map<String, Object> output)` |

### service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java) · 타입: `ScenarioRunnerReplayTest`

| 위치 | 선언 |
|---|---|
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L37) | `public RunOutcome runDryRun(DryRunRequest request)` |
| [L43](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L43) | `public boolean supportsReplay()` |
| [L51](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L51) | `void setUp()` |
| [L57](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L57) | `void replaysFailedExecutionAsF()` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L75) | `void replayRunIdAliasAccepted()` |
| [L87](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L87) | `void replayRunIdsAcceptExecutionUuid()` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L97) | `void replayPassesUnlessFailed()` |
| [L113](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L113) | `void unknownReplayExecutionRejectedBeforeRunning()` |
| [L126](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L126) | `void replayNeedsHarnessRunner()` |
| [L141](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L141) | `void otherFIdsGoToTheCatalog()` |
| [L152](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L152) | `void badReplayRunIdRejected()` |
| [L164](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L164) | `void needsScenarioIdsOrReplayRunIds()` |
| [L173](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java#L173) | `void goldenAndReplayResultsKeepTheGivenOrder()` |

### service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java) · 타입: `ScenarioRunnerTest`

| 위치 | 선언 |
|---|---|
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L27) | `void setUp()` |
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L40) | `void everyRunIsRecordedForItsWorkspace()` |
| [L55](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L55) | `void nothingIsRecordedWhenInputIsRejectedBeforeRunning()` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L62) | `void runsG01TwiceAndPasses()` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L75) | `void f1WorkspaceFailsWithJoinedReasons()` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L85) | `void rejectsBadCheckBeforeRunning()` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L92) | `void rejectsUnknownScenario()` |
| [L98](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L98) | `void rejectsUnknownWorkspaceBeforeRunning()` |
| [L106](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L106) | `void rejectsEmptyScenarioIdsAndBadRepeat()` |
| [L117](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java#L117) | `void rejectsBadScenarioFileCheckBeforeRunningAnyScenario(Path dir) throws Exception` |

### service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java) · 타입: `TestFixtures`, `InMemoryRunLedger`

| 위치 | 선언 |
|---|---|
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L21) | `private TestFixtures()` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L24) | `static Scenario g01()` |
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L28) | `static Map<String, Object> g01Order()` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L33) | `public static Map<String, WorkspaceSkill> liveSkills()` |
| [L46](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L46) | `static Map<String, WorkspaceSkill> f1Skills()` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L59) | `public void record(String workspaceId, String scenarioId, String runId, boolean passed)` |
| [L64](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L64) | `public java.util.Set<String> runIdsOf(String workspaceId)` |
| [L70](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L70) | `public boolean known(String runId)` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java#L75) | `public void clear()` |

### service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java) · 타입: `ApplyPatchTest`

| 위치 | 선언 |
|---|---|
| [L35](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L35) | `void workspaceExists()` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L41) | `void appliesAndReportsNoViolations()` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L52) | `void reportsViolationsForBreakingPatch()` |
| [L65](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L65) | `void reportsBrokenLinksThatWereAlreadyThere()` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L76) | `void contextMismatchRejectsAndKeepsWorkspace()` |
| [L91](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L91) | `void baseVersionMismatchRejects()` |
| [L101](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L101) | `void secondPatchOfSameSkillStillComparesWithStartVersion()` |
| [L117](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L117) | `void versionKeepsCountingAcrossCalls()` |
| [L127](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L127) | `void unknownSkillRejects()` |
| [L137](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L137) | `void unknownWorkspaceFailsLikeOtherTools()` |
| [L146](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L146) | `void emptyPatchesRejects()` |
| [L156](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L156) | `void closedWorkspaceRejects()` |
| [L166](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L166) | `void missingSkillNameOrPatchRejects()` |
| [L177](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L177) | `void rejectsPatchThatBreaksHeader()` |
| [L190](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L190) | `void rejectsPatchThatBreaksEntryFlow()` |
| [L203](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L203) | `void workspaceWithoutEntryFlowCannotBeChecked()` |
| [L217](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L217) | `private void assertWorkspaceUntouched()` |
| [L221](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java#L221) | `private WorkspaceRecord savedRecord()` |

### service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java) · 타입: `F1Texts`

| 위치 | 선언 |
|---|---|
| [L20](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java#L20) | `private F1Texts()` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java#L23) | `static String live()` |
| [L27](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java#L27) | `static String repaired()` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java#L31) | `static String broken()` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java#L36) | `static Map<String, WorkspaceSkill> brokenSkills()` |
| [L43](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java#L43) | `static String repairOutputsPatch()` |
| [L48](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java#L48) | `static String breakOutputsPatch()` |

### service/src/test/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStoreTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStoreTest.java) · 타입: `LiveFolderSkillVersionStoreTest`

| 위치 | 선언 |
|---|---|
| [L21](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStoreTest.java#L21) | `void setUp() throws Exception` |
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStoreTest.java#L28) | `void liveVersionsReadsHeader()` |
| [L33](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStoreTest.java#L33) | `void fullTextOfLiveVersion()` |
| [L38](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStoreTest.java#L38) | `void olderVersionNotInFolder()` |
| [L44](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStoreTest.java#L44) | `void unknownSkill()` |

### service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java) · 타입: `PatchApplierTest`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L13) | `void appliesSingleHunk()` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L18) | `void appliesMultiHunk()` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L37) | `void keepsTrailingNewline()` |
| [L44](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L44) | `void contextMismatchFails()` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L52) | `void reportsTheLineOfTheFirstHunkThatDoesNotFit()` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L59) | `void appliesC1HunkDiff()` |
| [L66](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L66) | `void appliesHeaderlessDiff()` |
| [L72](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L72) | `void ignoresTrailingBlankLinesInDiff()` |
| [L78](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L78) | `void diffWithoutHunkFails()` |
| [L91](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L91) | `void malformedHunkHeaderFails()` |
| [L102](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L102) | `void insertsAtTopWithZeroContext()` |
| [L107](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L107) | `void insertsAfterOldLineWithZeroContext()` |
| [L112](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L112) | `void insertsAtEndWithZeroContext()` |
| [L118](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L118) | `void insertsSeveralZeroContextHunksInOriginalCoordinates()` |
| [L124](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L124) | `void zeroOldCountHeaderWithContextKeepsItsStart()` |
| [L130](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L130) | `void zeroOldCountHeaderWithContextIsPlacedByTheContext()` |
| [L135](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L135) | `void zeroOldCountHeaderWithRemovalKeepsItsStart()` |
| [L140](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L140) | `void insertionPastTheEndFails()` |
| [L150](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L150) | `void contextLineWithoutLeadingSpaceFails()` |
| [L157](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L157) | `void continuationLineInPlusBlockWithoutPlusFails()` |
| [L165](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L165) | `void lineAfterMinusBlockWithoutPrefixFails()` |
| [L174](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L174) | `void emptyLineInsideHunkIsAnEmptyContextLine()` |
| [L179](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L179) | `void emptyContextLineIsVerifiedAgainstTheOriginal()` |
| [L186](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java#L186) | `void emptyLinesBetweenHunksAreSeparators()` |

### service/src/test/java/com/capstone4/skillops/workspace/SkillHeaderTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/SkillHeaderTest.java) · 타입: `SkillHeaderTest`

| 위치 | 선언 |
|---|---|
| [L13](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/SkillHeaderTest.java#L13) | `void readsRealPaymentSkill() throws Exception` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/SkillHeaderTest.java#L22) | `void readsOrderFlowSteps() throws Exception` |
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/SkillHeaderTest.java#L29) | `void rejectsTextWithoutHeader()` |

### service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java) · 타입: `WorkspaceFromSnapshotTest`

| 위치 | 선언 |
|---|---|
| [L40](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L40) | `void saveReturnsItsArgument()` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L45) | `void copiesSkillsFromSnapshot()` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L63) | `void usesTheVersionInTheSnapshotHeaderNotTheLiveOne()` |
| [L71](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L71) | `void copiesResourcesAndTrailingNewline()` |
| [L88](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L88) | `void doesNotReadResourcesAsSkillsOrSkillsAsResources()` |
| [L100](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L100) | `void rejectsBaseWithSnapshot()` |
| [L109](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L109) | `void emptyStartVersionsCountsAsNotGiven()` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L116) | `void nullSnapshotStartsFromLiveVersionsWithoutTouchingOrderAgent()` |
| [L130](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L130) | `void orderAgentFailureSavesNothing()` |
| [L145](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L145) | `void unknownSnapshotFailureIsNotTurnedIntoAnEmptyWorkspace()` |
| [L155](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L155) | `void rejectsSnapshotWithoutAnySkill()` |
| [L166](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L166) | `void rejectsSkillWithoutVersionInHeader()` |
| [L177](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L177) | `void filesOfMergesSkillsAndResources()` |
| [L192](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L192) | `void filesOfTreatsMissingResourcesAsEmpty()` |
| [L202](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L202) | `void filesOfUnknownWorkspaceFails()` |
| [L212](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L212) | `void sourceSnapshotIdOfTellsWhereTheWorkspaceStarted()` |
| [L228](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L228) | `void snapshotStartWithoutOrderAgentConfiguredFails()` |
| [L238](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L238) | `private static String skillText(String name, int version)` |
| [L243](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L243) | `private void serve(String path, String text)` |
| [L248](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L248) | `private void manifest(String path)` |
| [L254](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java#L254) | `private WorkspaceRecord savedRecord()` |

### service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java) · 타입: `WorkspaceServiceTest`, `FakeStore`

| 위치 | 선언 |
|---|---|
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L25) | `public Map<String, Integer> liveVersions()` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L30) | `public String fullText(String skill, int version)` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L42) | `void saveReturnsItsArgument()` |
| [L47](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L47) | `void createDefaultsToLiveVersions()` |
| [L57](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L57) | `void createOverridesStartVersion()` |
| [L62](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L62) | `void createRejectsUnknownSkillAndSavesNothing()` |
| [L69](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L69) | `void createRejectsUnavailableVersion()` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L76) | `void createRejectsMissingVersionInStartVersions()` |
| [L85](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java#L85) | `void skillsOfUnknownWorkspaceFails()` |

### service/src/test/java/com/capstone4/skillops/workspace/WorkspaceSkillJsonTest.java

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceSkillJsonTest.java) · 타입: `WorkspaceSkillJsonTest`

| 위치 | 선언 |
|---|---|
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceSkillJsonTest.java#L28) | `void legacyRowWithoutRevisionLoadsAsRevisionZeroWithJackson2()` |
| [L36](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceSkillJsonTest.java#L36) | `void legacyRowWithoutRevisionLoadsAsRevisionZeroWithJackson3()` |
| [L43](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceSkillJsonTest.java#L43) | `void revisionSurvivesWriteAndRead()` |
| [L54](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceSkillJsonTest.java#L54) | `void twoArgumentConstructorStartsAtRevisionZero()` |
| [L59](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/test/java/com/capstone4/skillops/workspace/WorkspaceSkillJsonTest.java#L59) | `private static Type skillsColumnType()` |

### skillops-agent/tests/conftest.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/conftest.py)

| 위치 | 선언 |
|---|---|
| [L5](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/conftest.py#L5) | `offline_credentials(monkeypatch, tmp_path)` |

### skillops-agent/tests/test_checks.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_checks.py)

| 위치 | 선언 |
|---|---|
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_checks.py#L25) | `test_readable_checks_on_known_values_pass(check)` |
| [L41](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_checks.py#L41) | `test_unreadable_or_unknown_checks_are_explained(check, reason)` |
| [L45](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_checks.py#L45) | `test_context_keys_come_from_skill_inputs_and_outputs()` |

### skillops-agent/tests/test_foundation.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py) · 타입: `test_make_structured_llm_wraps_schema.Echo`

| 위치 | 선언 |
|---|---|
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L19) | `patch_pipeline(monkeypatch)` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L22) | `async _noop(req, thread_id)` |
| [L31](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L31) | `test_make_llm_returns_chat_openai()` |
| [L39](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L39) | `test_make_structured_llm_wraps_schema()` |
| [L52](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L52) | `test_diagnose_no_token_passes_when_token_not_configured(monkeypatch)` |
| [L63](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L63) | `test_diagnose_wrong_token_returns_401(monkeypatch)` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L76) | `test_diagnose_correct_token_returns_202(monkeypatch)` |
| [L92](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L92) | `test_get_tools_returns_stub_when_stub_mode(monkeypatch)` |
| [L98](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L98) | `test_stub_ping()` |
| [L99](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L99) | `async _run()` |
| [L108](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L108) | `test_stub_get_trace_returns_failed_trace()` |
| [L109](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L109) | `async _run()` |
| [L121](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L121) | `test_stub_diff_skill_returns_changes()` |
| [L122](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L122) | `async _run()` |
| [L143](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L143) | `test_prompt_files_exist(name)` |
| [L149](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_foundation.py#L149) | `test_style_rules_reach_operator_facing_prompts(name)` |

### skillops-agent/tests/test_graph.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py) · 타입: `FakeChain`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L14) | `initial(**extra)` |
| [L22](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L22) | `__init__(self, output)` |
| [L25](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L25) | `async ainvoke(self, *args)` |
| [L29](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L29) | `fake_llm(schema)` |
| [L61](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L61) | `tools(monkeypatch)` |
| [L68](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L68) | `async run(state)` |
| [L75](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L75) | `async test_real_event_shape_and_changed_skill_are_used(tools)` |
| [L90](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L90) | `async test_mcp_failure_stops_without_false_confirmation(tools, monkeypatch)` |
| [L91](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L91) | `async fail(*args, **kwargs)` |
| [L102](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L102) | `async test_missing_results_are_not_success(tools, monkeypatch)` |
| [L103](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L103) | `async empty(workspace_id, *args, **kwargs)` |
| [L113](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L113) | `async test_baseline_is_required_without_metadata(tools, monkeypatch)` |
| [L116](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L116) | `async no_metadata(snapshot_id)` |
| [L127](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L127) | `async test_changed_paths_must_match_manifest(tools)` |
| [L133](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L133) | `async test_workspace_always_uses_candidate_snapshot(tools, monkeypatch)` |
| [L137](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L137) | `async create(**kwargs)` |
| [L147](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L147) | `async test_checks_are_scoped_to_replay_and_all_trials_have_evidence(tools, monkeypatch)` |
| [L151](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L151) | `async scenarios(ws, ids, **kwargs)` |
| [L164](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L164) | `async test_broken_contract_cannot_be_valid(tools, monkeypatch)` |
| [L167](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L167) | `async broken(*args)` |
| [L180](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L180) | `async test_normal_saved_change_ends_without_llm(tools, monkeypatch)` |
| [L183](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L183) | `async ready(**kwargs)` |
| [L200](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L200) | `async test_s5_diagnosis_only_reports_check_failure_without_candidates(tools, monkeypatch)` |
| [L203](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L203) | `async violation(*args, **kwargs)` |
| [L227](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L227) | `async test_unmodified_candidate_must_still_reproduce_failure(tools, monkeypatch)` |
| [L230](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L230) | `async already_healthy(**kwargs)` |
| [L243](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L243) | `async test_unknown_hypothesis_never_skips_patch_and_confirms(tools, monkeypatch)` |
| [L244](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L244) | `invalid(schema)` |
| [L261](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L261) | `async test_empty_llm_response_has_no_fake_patch_or_evidence(tools, monkeypatch)` |
| [L272](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L272) | `async test_baseline_fixture_failure_does_not_call_diagnosis_llm(tools, monkeypatch)` |
| [L275](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L275) | `async invalid_environment(**kwargs)` |
| [L293](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L293) | `async test_generated_patch_uses_current_file_not_old_diff(tools, monkeypatch)` |
| [L297](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L297) | `async capture(workspace, patches)` |
| [L312](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L312) | `async test_rejected_candidate_does_not_abort_other_candidates(tools, monkeypatch)` |
| [L318](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L318) | `async reject_first_candidate(workspace, patches)` |
| [L327](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L327) | `two_candidates(schema)` |
| [L343](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L343) | `async test_generation_prompt_contains_real_current_sources(tools, monkeypatch)` |
| [L346](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L346) | `capture(schema)` |
| [L350](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L350) | `async recorded(prompt)` |
| [L366](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L366) | `async test_proposal_is_saved_with_contract_payload(tools)` |
| [L387](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L387) | `async test_rejected_save_ends_in_error(tools, monkeypatch)` |
| [L388](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L388) | `async reject(proposal)` |
| [L399](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L399) | `intent_llm(checks)` |
| [L400](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L400) | `make(schema)` |
| [L410](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L410) | `async test_unreadable_intent_checks_are_dropped_not_sent(tools, monkeypatch)` |
| [L414](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L414) | `async capture(workspace_id, scenario_ids, repeat=2, replay_run_ids=None, extra_checks=None)` |
| [L443](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L443) | `async test_all_checks_dropped_continues_without_intent_check(tools, monkeypatch)` |
| [L447](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L447) | `async capture(workspace_id, scenario_ids, repeat=2, replay_run_ids=None, extra_checks=None)` |
| [L466](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L466) | `async test_intent_prompt_lists_values_seen_in_a_passing_run(tools, monkeypatch)` |
| [L469](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L469) | `capture(schema)` |
| [L474](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_graph.py#L474) | `async ainvoke(prompt, *args)` |

### skillops-agent/tests/test_health.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py)

| 위치 | 선언 |
|---|---|
| [L11](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L11) | `patch_pipeline(monkeypatch)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L12) | `async _noop(req, thread_id)` |
| [L18](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L18) | `test_health_ok()` |
| [L24](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L24) | `test_diagnose_accepts_failure_trigger()` |
| [L32](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L32) | `test_diagnose_rejects_unknown_type()` |
| [L37](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L37) | `test_diagnose_returns_queryable_thread_id()` |
| [L72](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L72) | `test_ambiguous_or_unsupported_trigger_is_rejected(payload)` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L76) | `test_duplicate_change_returns_same_thread_without_rescheduling(monkeypatch)` |
| [L79](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_health.py#L79) | `async start(req, thread_id)` |

### skillops-agent/tests/test_run_store.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_run_store.py)

| 위치 | 선언 |
|---|---|
| [L8](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_run_store.py#L8) | `test_concurrent_duplicate_delivery_starts_only_one_run(tmp_path)` |
| [L12](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_run_store.py#L12) | `accept(_)` |
| [L23](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_run_store.py#L23) | `test_same_change_different_payload_is_rejected(tmp_path)` |
| [L30](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_run_store.py#L30) | `test_progress_survives_restart_and_interruption_is_not_success(tmp_path)` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_run_store.py#L42) | `test_completed_result_is_preserved(tmp_path)` |

### skillops-agent/tests/test_scenarios.py

[원본](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py) · 타입: `test_mcp_errors_are_not_normal_results.Client`, `test_workspace_forwards_snapshot_and_patch_names.Capture`

| 위치 | 선언 |
|---|---|
| [L14](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L14) | `test_invalid_execution_is_rejected(value)` |
| [L19](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L19) | `test_reverse_multiline_hunk_preserves_positions_and_context()` |
| [L28](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L28) | `test_invalid_ablation_hunks_are_rejected(diff)` |
| [L42](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L42) | `async test_mcp_errors_are_not_normal_results(result)` |
| [L44](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L44) | `async call_tool(self, *args)` |
| [L54](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L54) | `async test_http_alias_converts_to_uuid_and_failure_has_no_since()` |
| [L58](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L58) | `respond(req)` |
| [L76](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L76) | `async test_workspace_forwards_snapshot_and_patch_names()` |
| [L80](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L80) | `async call_tool(self, name, args)` |
| [L97](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/tests/test_scenarios.py#L97) | `test_incomplete_reports_are_rejected(mutation)` |

## 8. 추가 실행·검증·설정·계약·Skill 파일

아래 파일은 실행 코드와 함께 검토해야 하는 입력·정책·설정입니다. 기존 설계 문서는 현재 구현과 차이가 있을 수 있으며 차이는 구조·개선 리뷰에 정리했습니다.
- [.env.example](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/.env.example)
- [README.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/README.md)
- [docker-compose.yml](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/docker-compose.yml)
- [docs/contracts/README.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/docs/contracts/README.md)
- [docs/contracts/mcp-tools-and-rest.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/docs/contracts/mcp-tools-and-rest.md)
- [docs/contracts/mcp-tools-input-output.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/docs/contracts/mcp-tools-input-output.md)
- [docs/contracts/proposal.schema.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/docs/contracts/proposal.schema.json)
- [docs/contracts/scenario.schema.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/docs/contracts/scenario.schema.json)
- [docs/contracts/skill-frontmatter.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/docs/contracts/skill-frontmatter.md)
- [docs/contracts/trace.schema.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/docs/contracts/trace.schema.json)
- [frontend/eslint.config.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/eslint.config.js)
- [frontend/package.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/package.json)
- [frontend/playwright.config.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/playwright.config.js)
- [frontend/tests/diagnosis.spec.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/tests/diagnosis.spec.js)
- [frontend/vite.config.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/frontend/vite.config.js)
- [order-agent/.env.example](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/.env.example)
- [order-agent/README.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/README.md)
- [order-agent/backend/Dockerfile](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/Dockerfile)
- [order-agent/backend/build.gradle](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/build.gradle)
- [order-agent/backend/settings.gradle](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/settings.gradle)
- [order-agent/backend/src/main/resources/application.yml](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/resources/application.yml)
- [order-agent/backend/src/main/resources/data.sql](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/resources/data.sql)
- [order-agent/backend/src/main/resources/schema.sql](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/backend/src/main/resources/schema.sql)
- [order-agent/compose.db-port.yml](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/compose.db-port.yml)
- [order-agent/compose.yml](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/compose.yml)
- [order-agent/docs/runner-integration-design.md](../../../order-agent/docs/runner-integration-design.md)
- [order-agent/frontend/Dockerfile](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/Dockerfile)
- [order-agent/frontend/eslint.config.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/eslint.config.js)
- [order-agent/frontend/package.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/package.json)
- [order-agent/frontend/playwright.config.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/playwright.config.js)
- [order-agent/frontend/tests/e2e/evidence.spec.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/tests/e2e/evidence.spec.js)
- [order-agent/frontend/tests/e2e/order.spec.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/tests/e2e/order.spec.js)
- [order-agent/frontend/tests/order.test.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/tests/order.test.js)
- [order-agent/frontend/vite.config.js](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/frontend/vite.config.js)
- [order-agent/scripts/check-evidence.py](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/scripts/check-evidence.py)
- [order-agent/scripts/publish-skills.py](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/scripts/publish-skills.py)
- [order-agent/scripts/smoke.py](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/scripts/smoke.py)
- [order-agent/skills/bundle.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/skills/bundle.json)
- [order-agent/skills/inventory-check/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/skills/inventory-check/SKILL.md)
- [order-agent/skills/inventory-check/resources/policy.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/skills/inventory-check/resources/policy.md)
- [order-agent/skills/notify/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/skills/notify/SKILL.md)
- [order-agent/skills/order-flow/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/skills/order-flow/SKILL.md)
- [order-agent/skills/order-intake/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/skills/order-intake/SKILL.md)
- [order-agent/skills/payment/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/skills/payment/SKILL.md)
- [order-agent/skills/shipping-reserve/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/order-agent/skills/shipping-reserve/SKILL.md)
- [scenarios/README.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/scenarios/README.md)
- [scenarios/failures/F1.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/scenarios/failures/F1.md)
- [scenarios/golden/g-01.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/scenarios/golden/g-01.json)
- [scenarios/order-agent/golden/g-01.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/scenarios/order-agent/golden/g-01.json)
- [scenarios/order-agent/golden/g-02.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/scenarios/order-agent/golden/g-02.json)
- [scenarios/order-agent/golden/g-03.json](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/scenarios/order-agent/golden/g-03.json)
- [service/build.gradle](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/build.gradle)
- [service/gradle/wrapper/gradle-wrapper.properties](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/gradle/wrapper/gradle-wrapper.properties)
- [service/settings.gradle](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/settings.gradle)
- [service/src/main/resources/application.yml](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/service/src/main/resources/application.yml)
- [skillops-agent/prompts/extract_intent.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/prompts/extract_intent.md)
- [skillops-agent/prompts/generate_candidates.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/prompts/generate_candidates.md)
- [skillops-agent/prompts/hypothesize.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/prompts/hypothesize.md)
- [skillops-agent/prompts/rank_and_explain.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/prompts/rank_and_explain.md)
- [skillops-agent/prompts/style.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/prompts/style.md)
- [skillops-agent/pyproject.toml](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skillops-agent/pyproject.toml)
- [skills/README.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skills/README.md)
- [skills/inventory-check/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skills/inventory-check/SKILL.md)
- [skills/notify/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skills/notify/SKILL.md)
- [skills/order-flow/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skills/order-flow/SKILL.md)
- [skills/order-intake/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skills/order-intake/SKILL.md)
- [skills/payment/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skills/payment/SKILL.md)
- [skills/shipping-reserve/SKILL.md](https://github.com/SKALA-2026-10-team4/ai-agent-capstone/blob/ec39713b549d817b8d85b9b881939a1759ccc5ad/skills/shipping-reserve/SKILL.md)
