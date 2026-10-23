# #72 · 남은 파싱 통합 3개 항목 완료

작성일: 2026-10-23 · 상태: 구현·검증 완료, 미배포. 커밋·푸시 상태는 Git 이력을 따른다.

## 완료한 범위

- [x] 중복 YAML 키, alias, 문서 크기 제한 통일
- [x] service와 주문 Agent가 공유할 파싱 모듈 구조 결정
- [x] SkillLoader와 Frontmatter에 공통 규칙 적용

사용자 요청에 따라 남은 세 항목을 구현했다. 기존 #72의 다른 완료 항목과 합하면 **19개 구현 항목이 완료된 상태**다. 원격 이슈의 체크박스·상태는 변경하지 않았다. 별도의 평가·비교 계약 설계 이슈와 자동 수정 기능은 이번 작업에 포함하지 않았다.

## 변경 내용과 이유

`shared/skill-parser`에 Spring·DB·HTTP와 무관한 Java 21 라이브러리를 추가했다. 두 독립 Gradle 빌드가 같은 디렉터리의 모듈에 의존하며 각 bootJar에 공용 JAR를 포함한다. 소스 복사·개별 YAML 옵션 재정의를 제거했다.

```text
주문 Agent SkillLoader ────────────┐
                                 ├→ shared/skill-parser / SkillParser
service SkillFrontmatter ─────────┘
  ├ Frontmatter → diff·의존성 분석
  ├ SkillHeader → workspace·간이 실행
  └ SkillMetadataValidator → 실행용 필수 정보 검사
```

Java Skill 이름 검증도 공용 모듈에 위임했다. service의 기존 어댑터 타입·메서드를 남겨 호출 코드와 오류 처리 계약을 보존했다. 주문 Agent의 중복 UTF-8 디코딩·frontmatter 분리·YAML 옵션 생성 코드를 제거했다.

공통 안전 정책:

| 항목 | 정책 |
|---|---|
| 파서 | SnakeYAML 2.6, 호출별 SafeConstructor |
| 중복 키 | 최상위·중첩 모두 거부 |
| 컬렉션 alias | 거부 |
| 스칼라 alias·미사용 anchor | 기존처럼 허용 |
| 임의 Java 객체 태그 | 거부 |
| 중첩 깊이 | 50, 기존 기본값 명시 |
| YAML 크기 | 모든 경로에서 최대 100,000 Unicode 코드 포인트 |
| 줄바꿈 | CRLF를 LF로 해석한 후 크기 계산 |
| 본문 | YAML 크기 예산에서 제외 |

## 동작 변화와 유지 범위

**변화:** service의 diff/workspace는 이전에 실행기보다 큰 YAML 기본 상한을 사용했다. 이제 100,000 코드 포인트 초과 frontmatter를 거부한다. 정확히 상한은 허용하고 Unicode 보조 평면 문자는 한 코드 포인트로 센다.

**유지:** 실행의 엄격한 구분선·UTF-8, 분석의 EOF 경계와 빈 맵 처리, workspace의 기존 필드 정책, 실제 실행기의 필수 정보·목록·flow 검증은 유지한다. 목적별 정책을 동일한 성공/실패 판정으로 억지로 바꾸지 않았다.

원문 bytes·문자열·CRLF·파일 해시·hunk ID 생성 규칙은 바꾸지 않았다. YAML 한도 초과나 오류가 있어도 diff는 원문 hunks와 frontmatter_parse_error를 보존한다. 파일·묶음의 바이트 한도는 기존 snapshot 계층의 책임이다.

## API/MCP 및 빌드 영향

- REST/MCP 입력·응답 필드, 기존 오류 코드와 apply_patch v1.3은 유지한다. Python 코드 변경은 필요 없다.
- 파싱 규칙과 한도 변경은 [SKILL 명세](../contracts/skill-frontmatter.md), MCP 입출력 안내와 주문 Agent 조회 명세에 반영했다.
- 두 Java 서버를 빌드할 때 저장소의 shared/skill-parser 디렉터리가 필요하다.
- 주문 Agent Docker 빌드 context를 저장소 루트로 바꿨다. 기존 order-agent 디렉터리에서의 docker compose 명령은 유지한다.
- 루트 .dockerignore에 필요한 소스만 허용해 .env·.git·로컬 빌드/cache 전송을 막았다. 실제 전송 context는 약 685 KB였다.
- 기존 실행 컨테이너를 교체하거나 재시작하지 않았다. 별도 태그로 이미지 빌드만 검증했다.

## 검증 결과

먼저 변경 전 `SharedYamlLimitTest` 2개가 실패하는 것을 확인했다. service가 초과 YAML을 해석하고 파싱 오류를 남기지 않던 차이를 재현했다. 공통 모듈 적용 후 두 테스트를 포함해 service 전체가 통과했다.

| 검증 | 결과 |
|---|---|
| service 전체 | **697개 통과** |
| 주문 Agent 기본 전체 실행 | **172개 통과**, DB 통합 82개는 환경 조건으로 skip |
| 주문 Agent integration 패키지, 실제 별도 PostgreSQL | **85개 통과, skip 0**; 위 기본 실행과 3개 중복, 이전 skip 82개 포함 |
| 공용 skill-parser 모듈 | **9개 통과**; 두 Gradle 진입점에서 확인 |
| 주문 Agent bootJar | 공용 skill-parser JAR·SnakeYAML 2.6 포함 |
| service bootJar | 공용 모듈을 포함한 패키징 성공 |
| 주문 Agent Docker build | `skillops-order-agent-parser-check:local` 빌드 성공 |

공통 YAML 안전 fixture 10개와 크기 경계 6개를 각각 LF/CRLF로 만들어 **서버별 32개 계약 사례**를 실제 어댑터/로더에 적용했다. 기존 필수 정보·flow·정상 문서 호환성 테스트도 전체 실행에서 유지했다.

검증 내용:

- 정상 원문 6개와 추가 정상 문법 20개, 원문 해시 유지.
- 최상위·중첩 중복 키, 컬렉션 alias, 스칼라 alias, 미사용 anchor, 안전하지 않은 태그, 깨진 YAML.
- 99,999·100,000·100,001 코드 포인트, ASCII·한글·보조 평면 Unicode, LF/CRLF.
- 엄격한 UTF-8 오류, EOF 분석/실행 정책 차이, YAML보다 큰 본문 허용.
- 초과 YAML에서 diff 원문 hunk·식별자·파싱 오류 보존.
- 실제 DB에 연결한 주문 실행·계약·조회·트레이스·dry-run 통합 테스트.

Python 운영 코드와 공개 입출력 필드를 변경하지 않아 기존 583개 Python 테스트는 이번 단계에서 다시 실행하지 않았다. 실제 유료 LLM이나 새 이미지의 서버 배포는 수행하지 않았다.

## 실행 명령

```bash
cd service
./gradlew test --offline --no-daemon --console=plain
./gradlew bootJar --offline --no-daemon --console=plain
```

```bash
cd order-agent/backend
./gradlew test bootJar --offline --no-daemon --console=plain
```

```bash
# 저장소 루트: 전용 DB만 시작, 운영 order-agent 프로젝트와 분리
docker compose -f order-agent/compose.yml -p skillops-parser-validation --profile test up -d --wait db-test
cd order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew :test --tests 'com.capstone4.orderagent.integration.*' --offline --no-daemon --console=plain
```

```bash
# 저장소 루트
docker build -t skillops-order-agent-parser-check:local -f order-agent/backend/Dockerfile .
docker compose -f order-agent/compose.yml -p skillops-parser-validation --profile test down
```

테스트용 DB·네트워크는 정리했다. 운영 데모의 DB·재고·Skill 파일을 이번 검증에서 수정하지 않았다. 기존 사용자 README 변경과 다른 미커밋 문서는 보존했다.

## 이슈에 복사할 완료 내역

```markdown
### #72 잔여 작업 완료 (2026-10-23)

- [x] 중복 YAML 키, alias, 문서 크기 제한 통일
- [x] service와 주문 Agent가 공유할 파싱 모듈 구조 결정
- [x] SkillLoader와 Frontmatter에 공통 규칙 적용

두 Java 서버가 shared/skill-parser의 동일한 파서를 사용하도록 변경했습니다.
YAML 안전 정책과 frontmatter 100,000 코드 포인트 제한을 공통화하고,
실행·진단·diff의 목적별 검증 정책과 원문 증거는 유지했습니다.

검증: service 697개, 주문 Agent 기본 172개, 별도 PostgreSQL integration 패키지
85개, 공용 파서 9개 통과. 주문 Agent의 두 실행에는 3개 중복이 있습니다.
두 서버 bootJar와 주문 Agent Docker 이미지 빌드도 확인했습니다.

API/MCP 필드와 apply_patch v1.3은 유지하고 해석 규칙·빌드 명세를 갱신했습니다.
기존 체크리스트 19개 구현 항목이 완료됐습니다.
커밋·푸시 여부는 fix/#72_skill-parser-consistency의 Git 이력을 따릅니다.
운영 배포와 원격 이슈의 체크·종료는 수행하지 않았습니다.
```
