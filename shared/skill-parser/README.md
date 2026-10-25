# SKILL 공용 파싱 모듈

`service`와 `order-agent/backend`가 같은 `SkillParser`를 사용하는 Java 21 라이브러리다. Spring, HTTP, DB, 업무 실행에 의존하지 않는다.

## 의존 구조

```text
service :skill-parser ──────────────┐
  SkillFrontmatter → SkillParser   │
    Frontmatter / SkillHeader      ├─ shared/skill-parser
    SkillMetadataValidator         │   SnakeYAML 2.6
order-agent/backend :skill-parser ─┘
  SkillLoader → SkillParser
```

두 서버의 settings.gradle이 동일 디렉터리를 Gradle subproject로 등록한다. 소스를 복사하거나 원격 저장소에 라이브러리를 발행하지 않는다. 각각의 bootJar에 동일한 라이브러리 JAR가 포함된다. 서버 패키징과 배포는 계속 독립적으로 수행한다.

## 파싱과 목적별 정책

| 함수 | 책임 |
|---|---|
| parseForAnalysis | 기존 diff/분석 경계 해석, 블록 없음·객체 아님은 빈 맵 |
| requireFields | workspace의 필수 YAML 객체 검사 |
| readForExecution(String/byte[]) | 실행용 구분선·본문 분리, bytes 입력은 엄격한 UTF-8 |
| isValidName | 양쪽 Java 서버의 Skill 이름 규칙 |
| closingLine | 원문 기준 hunk 분류에 필요한 구분선 줄 번호 |

모든 경로가 같은 안전한 YAML 해석기를 쓴다. 중복 키 거부, 컬렉션 alias 0개, 중첩 깊이 50, 임의 Java 객체 태그 거부, frontmatter 100,000 코드 포인트 제한을 적용한다. 스칼라 alias와 사용하지 않은 anchor는 기존처럼 허용한다.

CRLF는 해석할 때 LF로 맞춘 뒤 한도를 계산한다. Unicode 보조 평면 문자도 한 코드 포인트로 센다. YAML 본문과 파일 전체 크기를 혼동하지 않는다. 본문은 YAML 한도에 포함하지 않으며 파일 바이트 한도는 기존 snapshot 계층에서 관리한다.

분석용 닫는 구분선 EOF 허용과 실행용 닫는 구분선 뒤 줄바꿈 요구는 기존 목적별 정책이다. 실행의 owner/description/name/version/body 및 단계 목록 검사는 SkillLoader에, 진단 오류 수집은 service에 남긴다. 파서 공유가 잘못된 문서의 실행 허용을 뜻하지 않는다.

원문·UTF-8 바이트·CRLF·파일 해시는 수정하지 않는다. service의 기존 SkillFrontmatter 타입과 호출 메서드도 어댑터로 유지한다.

## 빌드·검증

```bash
# 저장소 루트에서 두 빌드를 순서대로 실행
./service/gradlew -p service test bootJar
./order-agent/backend/gradlew -p order-agent/backend test bootJar

# 공용 모듈만 검증: 둘 중 어느 Gradle 진입점에서도 가능
./service/gradlew -p service :skill-parser:test
```

공용 모듈은 두 빌드에서 같은 출력 디렉터리를 쓰므로 같은 checkout에서 두 Gradle 빌드를 동시에 실행하지 않는다.

주문 backend Docker context는 저장소 루트다. 기존 `cd order-agent && docker compose up --build` 명령은 유지한다. 직접 빌드하려면 저장소 루트에서 실행한다.

```bash
docker build -f order-agent/backend/Dockerfile -t order-agent:local .
```

루트 .dockerignore는 backend·Skill·공용 모듈만 전송하며 .env, .git, 로컬 build/cache는 제외한다. 이 변경 때문에 기존 컨테이너를 자동으로 재시작하지는 않는다.

## 계약과 테스트

- [SKILL 해석 명세](../../docs/contracts/skill-frontmatter.md)
- [서버 공통 YAML 안전 fixture](../../docs/contracts/examples/skill-yaml-safety-cases.json)
- 기존 필수 정보 37건, flow 구조 15건, 정상 원문 6개와 정상 문법 20개 fixture도 그대로 사용한다.
- 소스와 의존 버전을 바꾸면 두 서버의 전체 테스트와 패키징을 함께 확인한다.
