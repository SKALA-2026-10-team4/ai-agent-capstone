# 계약 1 · SKILL.md frontmatter

버전: v0 (초안) · 동결 예정: 1일차 09시

## #72 최종 갱신 — 서버 공용 파싱 모듈 (2026-10-23)

아래 1~5단계 설명은 적용 이력이다. 현재 파싱 공통화·안전 설정·크기 제한은 이 절을 우선한다.

service와 주문 Agent는 이제 `shared/skill-parser`의 **동일한 Java SkillParser 모듈**을 Gradle 의존성으로 사용한다. service의 SkillFrontmatter/Frontmatter/SkillHeader와 주문 Agent의 SkillLoader가 같은 YAML 해석기를 호출한다. Java Skill 이름 규칙도 공통 모듈에 위임한다. [모듈 구조·빌드 방법](../../shared/skill-parser/README.md)

| 공통 규칙 | 현재 계약 |
|---|---|
| YAML 구현 | SnakeYAML 2.6, 호출별 SafeConstructor 생성 |
| 중복 키 | 최상위·중첩 객체 모두 거부 |
| alias·anchor | 컬렉션 alias 거부; 스칼라 alias·미사용 anchor는 기존처럼 허용 |
| 안전하지 않은 태그 | Java 임의 객체 생성 태그 거부 |
| 중첩 깊이 | 50 |
| YAML 크기 | 모든 경로에서 frontmatter 최대 100,000 Unicode 코드 포인트. 정확히 상한은 허용, 초과는 거부 |
| 줄바꿈·예산 | 해석 시 CRLF를 LF로 맞춘 뒤 동일 예산 적용. UTF-16 길이·UTF-8 바이트 수와 구분 |
| 문서 본문 | YAML 예산에 포함하지 않음. 기존 snapshot의 파일·묶음 바이트 한도는 별도 유지 |
| 원문 증거 | 문자열·bytes·해시를 수정하지 않음. 초과·깨진 YAML도 diff hunk는 보존 |

실행과 진단의 **검증 목적은 계속 구분**한다. 실행용 닫는 구분선 뒤 줄바꿈 요구, 분석용 EOF 경계 허용, 분석의 빈 맵 처리, workspace의 기존 필드 정책을 유지한다. 실행 로더의 필수 정보·목록·flow 검증도 유지한다.

크기 초과는 분석에서 YAML 파싱 오류, workspace에서 기존 INVALID_INPUT, 실제 로더에서 기존 INVALID_SKILL_FORMAT으로 처리한다. Skill 조회는 기존 SKILL_METADATA_INVALID로 감싸며 목록은 partial/parse_errors에 오류를 남긴다. diff는 구조화된 frontmatter 해석 실패를 frontmatter_parse_error로 남기고 원문 hunks를 유지한다. 오류 코드나 REST/MCP 필드를 새로 추가하지 않는다. apply_patch v1.3과 Python 요청·응답 계약은 유지한다.

**수락 범위 변경:** 기존 service 분석/workspace가 기본 파서 한도로 읽던 100,000 초과 frontmatter를 이제 실행기와 동일하게 거부한다. 정상 문서·기존 6개 원문·20개 정상 문법의 수락과 해시 보존은 회귀 테스트로 확인한다. 본문을 이 한도로 잘라내지 않는다.

공유 [YAML 안전 fixture](examples/skill-yaml-safety-cases.json)와 두 서버의 SharedYamlSafetyContractTest가 중복 키·alias·태그·Unicode·LF/CRLF·크기 경계를 비교한다. 이로써 #72의 공용 모듈 구조·로더/분석기 적용·YAML 안전 및 크기 제한 통일 항목을 구현했다.

변경 기록: 2026-10-23 · #72 1단계 — service의 workspace/snapshot YAML 해석 공통화, 목록·버전 타입 검사, 간이 실행의 빈 steps 거부.

추가 기록: 2026-10-23 · #72 2단계 — patch 결과에 `depends_on` 선행 단계 누락을 `dependency_violations`로 보존하고 Python 후보 판정에 연결했다. [apply_patch 명세](mcp-tools-input-output.md#8-수정-적용하기--apply_patch)를 참고한다.

## service YAML 해석 규칙 (#72 · 1단계 도입, 5단계 갱신)

service의 `SkillHeader`와 snapshot 분석용 `Frontmatter`는 `skill/SkillFrontmatter`를 공유한다. 이 절은 service 내부의 변경이며 주문 Agent의 `SkillLoader`와 Python 진단 로직까지 통합한 상태는 아니다.

| 항목 | 규칙 |
|---|---|
| YAML 목록 | `[a, b]`와 여러 줄 `- a` 목록을 동일하게 해석한다. 따옴표는 YAML 문법으로 처리하고 항목 문자열에 남기지 않는다. |
| workspace 선택 목록 | 누락 목록은 기존처럼 빈 목록이다. steps의 null은 빈 목록으로 읽고, steps가 비어 있지 않은 flow의 inputs/outputs null도 빈 목록으로 읽는다. 일반 단계 inputs/outputs null, scalar, 잘못된 항목은 거부한다. 자세한 범위는 아래 5단계를 따른다. |
| workspace version | 선언하면 1 이상의 Java Integer여야 한다. 문자열 `"1"`, 0·음수·소수·null·Integer 범위 초과는 거부한다. version이 없으면 어댑터는 null을 반환하고 workspace 생성/운영 저장소가 필수값 오류를 처리한다. |
| workspace name | 선언하면 비공백 문자열이어야 한다. 누락 시 기존처럼 null이며 폴더 이름 일치 검사는 이번 단계에 추가하지 않는다. |
| 문서 경계 | 첫 줄 `---`, 닫는 줄 `---`를 정확히 사용한다. 구분선 앞뒤 공백·선행 빈 줄·BOM은 허용하지 않는다. LF/CRLF와 닫는 구분선 EOF는 지원한다. |
| YAML 오류 | 중복 키·collection alias·안전하지 않은 태그·깨진 YAML을 거부한다. workspace 어댑터는 입력 오류로 바꾸고, snapshot 분석은 기존 YAML 예외 정책을 유지한다. |
| 간이 실행 flow | root의 steps 누락·null은 빈 목록으로 해석한 뒤 실행 전 거부한다. 빈 목록·잘못된 목록도 실행할 수 없으며 0단계를 실행 성공으로 반환하지 않는다. |
| 원문 증거 | 원본 문자열·줄바꿈·파일 해시는 변경하지 않는다. 파싱은 해석 결과만 만든다. |

snapshot diff의 빈 맵 반환, 깨진 YAML의 hunk 보존, hunk ID·위치 계산 규칙은 유지한다. `closingLine`은 원문 줄 위치 계산이므로 빈 frontmatter에서도 닫는 구분선 위치를 반환할 수 있다.

1단계는 owner/description/depends_on 검증과 주문 Agent와의 라이브러리 연결을 완료하지 않았다. 이후 2단계에서 기존 graph의 선행 단계 검증 결과를 patch와 Python에 전달했고, 3단계에서 아래 필수 정보 검사를 추가했다. 서버 간 라이브러리 통합과 전체 실행 계약의 통일은 아직 완료하지 않았다. [1단계 변경 내역](../changes/2026-10-23-skill-parser-phase1.md).

## 필수 정보 검사 (#72 · 3단계)

service의 `SkillMetadataValidator`는 실제 주문 Agent `SkillLoader.load`의 필수 정보 기준을 따른다. `SkillFrontmatter.readForExecution`으로 실행용 문서 경계를 읽으며 diff용의 느슨한 경계 규칙은 유지한다. 각 서버가 하나의 라이브러리를 쓰는 구조는 아니며, [공통 문서 fixture](examples/skill-metadata-cases.json)를 두 서버의 테스트에서 실제로 읽어 기준이 어긋나는 것을 방지한다.

| 항목 | 실행용 필수 정보 기준 |
|---|---|
| 폴더 이름 | 64자 이하, `[a-z][a-z0-9]*(?:-[a-z0-9]+)*`. 위반하면 `INVALID_SKILL_NAME`. |
| `name` | 폴더 이름과 같은 문자열. 누락·다른 값·잘못된 타입은 오류. |
| `version` | 1 이상의 Java Integer. 누락·null·문자열·실수·Integer 범위 초과는 오류. workspace revision과의 일치 검사는 이번 범위에 포함하지 않는다. |
| `owner`, `description` | 공백뿐이지 않은 문자열. 누락·null·목록·숫자·불리언은 오류. |
| 본문 | 닫는 구분선 이후 `strip()` 후 비어 있지 않은 텍스트. 업무 내용의 타당성은 검사하지 않는다. |
| 경계 | 첫 `---`와 닫는 `---` 뒤 줄바꿈이 필요. CRLF를 LF로 해석한다. diff에서 지원하는 닫는 구분선 EOF를 실행용으로는 허용하지 않는다. 원문은 변경하지 않는다. |
| YAML | SafeConstructor, 중복 키 금지, 컬렉션 별칭 0개, 100,000 코드 포인트 제한. YAML 해석 실패는 `frontmatter` 오류 하나로 반환. |
| 오류 | `{skill, field, code, message}`. 폴더 이름 이외의 오류 코드는 `INVALID_SKILL_FORMAT`. 읽을 수 있는 문서는 여러 필드 오류를 모은다. |

`WorkspaceService.apply`는 변경한 문서뿐 아니라 workspace의 모든 Skill을 이름순으로 검사해 `metadata_errors`에 담는다. 실행기가 flow 밖의 문서도 후보 조회에서 읽기 때문이다. 필드 오류 순서는 name, version, owner, description, body다.

**저장과 합격은 별개다.** 기존의 원문 불일치·기준 버전 불일치·변경한 단계 parse error·entry 분석 실패 거부 정책은 유지한다. 이를 통과한 patch는 필수 정보 오류가 있어도 실험용으로 저장한다. Python은 `metadata_valid=false`로 판정하고 오류 상세를 후보에 남겨 추천에서 제외한다. `no_broken_links`는 입력·선행 단계 연결 의미를 유지한다.

3단계의 검사 범위는 위 필수 정보와 문서 형식이다. 이후 4단계에서 아래 flow 구조 검사를 추가했다. 선택 목록의 null 처리와 실행 의미 전체를 완전히 통일한 것은 아니다. 실제 주문 Agent의 운영 로더는 수정하지 않았다. [최신 MCP 명세](mcp-tools-input-output.md#8-수정-적용하기--apply_patch).

## flow 실행 구조 검사 (#72 · 4단계)

service의 `FlowStructureValidator`는 진단용 `DependencyGraph`를 읽어 구조 오류를 모은다. 그래프 생성 규칙·응답 형식은 유지하므로 중복 단계도 그래프에 남고, 읽을 수 없는 단계도 parse_error와 complete=false로 확인할 수 있다. 그래프를 읽을 수 있다는 사실과 실행 구조 합격은 구분한다.

| 조건 | 판정 |
|---|---|
| 중복 단계 | `INVALID_FLOW / DUPLICATE_STEP`, 같은 Skill은 한 번만 보고 |
| 12단계 초과 | `INVALID_FLOW / TOO_MANY_STEPS` |
| 없는 단계 | `UNKNOWN_SKILL / UNKNOWN_SKILL` |
| 잘못된 단계 이름 | `INVALID_SKILL_NAME / INVALID_SKILL_NAME` |
| 읽을 수 없는 단계 선언 | `INVALID_SKILL_FORMAT / UNREADABLE_SKILL` |
| 단계 안에 또 다른 flow | `CONTRACT_VIOLATION / NESTED_FLOW_NOT_SUPPORTED` |
| 원인이 설명되지 않은 불완전한 분석 또는 steps·노드 불일치 | `INVALID_FLOW / INCOMPLETE_ANALYSIS`, service의 방어 조건 |

단계 수 제한은 `FlowStructureValidator.MAX_FLOW_STEPS=12`와 주문 Agent의 `OrderFlowPolicy.MAX_FLOW_STEPS`를 [공통 flow fixture](examples/flow-structure-cases.json)의 같은 값으로 검증한다. 두 서버가 하나의 라이브러리를 공유하지는 않는다. 이름 규칙은 service 안에서 `SkillNames`로 공통화했다.

`apply_patch` v1.3은 필수 `flow_errors:[{skill,code,reason,message}]`를 반환한다. 새 Python은 `flow_valid`를 별도로 계산하고 false인 후보를 추천하지 않는다. 입력·선행 단계·필수 정보 검사도 각각 유지한다. 원인 실험용 patch는 기존 거부 조건에 걸리지 않으면 저장한다. 빈 entry나 깨진 entry steps, 변경한 단계의 parse error는 기존처럼 INVALID_INPUT으로 전체 patch를 거부한다. 빈 그래프를 검사기에 직접 넘길 경우에는 EMPTY_STEPS 오류를 반환한다.

이 검사는 정적 구조와 그래프가 읽는 단계 선언 범위다. entry의 선택 목록 전체, flow 밖 문서의 목록 전체, 필수 마지막 업무 단계의 완료·도구 바인딩·실제 실행 성공을 보장하지 않는다. 5단계에서 확인된 정상 null 문법의 workspace 호환성은 맞췄다. 전체 파서 검증 통합과 손상된 초기 snapshot으로 진단을 시작하는 경로는 여전히 별도 작업이다.

## 목록 null 호환성 (#72 · 5단계)

`SkillHeader.parse`는 name·version을 검사한 뒤 steps를 먼저 읽고, steps가 비어 있지 않을 때만 flow의 선택 입출력에 null을 허용한다. 파일 이름으로 flow 여부를 정하지 않는다. null 정책은 기존 목록 검사 함수에서 공통으로 처리한다.

| 대상 | 누락 | 명시적 null (`null`, `~`, 값 생략) | 빈 배열 | 잘못된 타입·항목 |
|---|---|---|---|---|
| steps | [] | [] | [] | 거부 |
| flow의 inputs/outputs | [] | [] | [] | 거부 |
| 일반 단계 inputs/outputs: workspace 어댑터 | 기존처럼 [] | 거부 | [] | 거부 |
| 일반 단계 inputs/outputs/depends_on: snapshot 선언 검사·실제 실행기 | 거부 | 거부 | 허용 | 거부 |

이 변경으로 이전 workspace가 거부했던 정상 문법 6개를 포함해 공통 정상 문법 20개 모두 snapshot workspace 생성에 성공한다. `depends_on`은 SkillHeader record에 추가하지 않았고 기존 분석기·실행기가 검증한다.

원문의 null을 []로 치환하거나 YAML을 다시 저장하지 않는다. 해석 값만 정규화하고 원문·LF/CRLF·끝 줄바꿈·파일 해시·버전은 보존한다. 문자열 `"null"`과 `[null]`은 정상 null 목록으로 간주하지 않는다. 빈 entry의 실행·분석 거부는 유지하며 workspace 복사 성공은 실행 성공을 뜻하지 않는다.

MCP 요청·응답 필드와 apply_patch v1.3 Schema는 유지한다. 이 변경 자체에 Python이나 주문 Agent 운영 로더 수정은 필요하지 않다. [설계](../skill-list-null-compatibility-design.md) · [적용 및 검증 결과](../changes/2026-10-23-skill-list-null-compatibility.md).

## 단계 skill

```yaml
---
name: payment                       # 디렉토리 이름과 동일. kebab-case
description: 주문 금액을 결제한다. 결제 정책은 여기서 바꾼다.
inputs:     [order.id, order.total, inventory.reserved]
outputs:    [payment.id, payment.status]
depends_on: [inventory-check]
owner: 재무팀                        # 이 skill 을 고치는 업무 담당 조직
version: 4                          # 저장 시 서비스가 +1. 담당자는 건드리지 않는다
---
# 결제 절차                          ← 여기부터 본문. 담당자가 쓰는 자연어.
1. 재고 예약(inventory.reserved)이 true 인 주문만 진행한다.
2. 결제 어댑터를 호출하고 payment.id 를 받는다.
3. 실패하면 payment.status 를 FAILED 로 두고 끝낸다.
```

## orchestrator (`order-flow`)

```yaml
---
name: order-flow
description: 주문 한 건이 거치는 단계와 순서. 순서를 바꾸는 사람은 운영팀.
steps: [order-intake, inventory-check, payment, shipping-reserve, notify]
owner: 운영팀
version: 1
---
# 주문 처리 순서
들어온 주문은 위 steps 순서대로 처리한다. 앞 단계가 FAILED 면 뒤 단계는 실행하지 않는다.
```

## 컨텍스트 키 규칙

- 키는 `<네임스페이스>.<필드>`. 네임스페이스는 skill 이 `outputs` 에 선언한 접두어다.
  `order.*` 는 입력 주문에서 오고, 그 외는 모두 앞선 skill 의 출력이다.
- **계약 일관성 검사**(W1, 기준 4): `steps` 순서대로 각 skill 의 `inputs` ⊆ `order.*` ∪ (앞선 skill 들의 `outputs`).
- 로더는 frontmatter를 파싱하고 빈 본문을 거부한다. 본문 업무 내용은 해석하지 않고 모델에 전달한다.
  그래서 "본문은 바꿨는데 frontmatter 는 그대로"가 장애 유형 F1 이 된다.

## 위치

| 모드 | 경로 |
|---|---|
| live | `skills/<name>/SKILL.md` (저장소 루트, 서비스가 읽음) |
| sandbox | `<workspace 루트>/<ws_id>/skills/<name>/SKILL.md` |

## 상태 값 (final_status · payment.status 공용)

`SHIPPED` 정상 완료 · `REJECTED` 업무 규칙으로 거절(재고 부족 등) · `HOLD` 보류(F3 에서 신설) · `FAILED` harness/skill 오류
