# #72 · 정상 SKILL 문서 호환성 기준

> 수정 전 관찰 기록입니다. 이후 null 호환성 수정을 적용해 정상 문법 20개 모두 workspace 생성에 성공합니다. 현재 fixture의 관찰 필드와 거부 예상 분기는 제거했습니다. 아래 수치와 거부 목록은 수정 전 상태이며, [최신 적용 결과](2026-10-23-skill-list-null-compatibility.md)를 참고하세요.

## 결과

현재 저장소의 정상 SKILL 문서 6개는 service와 실제 주문 Agent 로더에서 모두 읽힌다. 전체 flow의 입력·선행 단계·실행 구조 정적 검증도 통과한다. snapshot에서 workspace를 만들 때 원문과 버전, 줄바꿈을 유지하는 것도 확인했다.

별도로 준비한 정상 목록 문법 20개는 주문 Agent와 snapshot 분석기가 모두 허용한다. workspace의 SkillHeader와 snapshot 기반 workspace 생성은 14개를 허용하고 null 표현을 사용하는 6개를 거부한다. **현재 저장소 문서 6개와 추가 문법의 불일치 사례 6개는 서로 다른 대상이다.**

이번에는 테스트·기준 데이터·이 문서만 추가했다. 운영 파서, 실제 SKILL 원문, REST/MCP 계약과 Python 코드는 수정하지 않았다.

## 고정한 기준

기준 커밋은 `447c770c1b000d17f9c99b79f5317f834cdb6f18`이다.

| 문서 | 버전 | 역할 |
|---|---|---|
| inventory-check | 1 | 재고 확인 단계 |
| notify | 1 | 알림 단계 |
| order-flow | 1 | 5단계 실행 순서 |
| order-intake | 1 | 주문 접수 단계 |
| payment | 1 | 결제 단계 |
| shipping-reserve | 1 | 배송 예약 단계 |

[공통 기준 데이터](../contracts/examples/skill-document-compatibility.json)에 원문, UTF-8 SHA-256, 해석한 이름·버전·담당 조직·설명·입력·출력·선행 단계·실행 순서·본문을 고정했다. 두 Java 모듈이 같은 파일을 읽는다. 생성 시점의 실제 파일 바이트가 기준 원문과 같은지도 확인했다.

고정본과 함께 현재 skills/ 디렉터리의 모든 문서를 따로 검사한다. 이후 문서가 바뀌어도 과거 정상 문서의 호환성과 현재 문서의 유효성을 각각 확인할 수 있다. 현재 문서가 고정본과 영구히 같은 바이트여야 한다는 테스트는 추가하지 않았다.

## 검사 범위

- service: SkillHeader, SkillMetadataValidator, SkillFrontmatter, DependencyAnalyzer, FlowStructureValidator를 실제로 호출한다.
- service: snapshot 기반 WorkspaceService.create를 실제로 호출하고 원문·버전·출처 보존을 검사한다. HTTP 응답과 DB 저장소만 mock으로 대체한다.
- 주문 Agent: SkillLoader.load, candidates, validateFlow를 실제로 호출한다. 고정한 문서의 모든 해석 필드와 원본 파일 해시를 비교한다.
- 추가 문법: 일반 단계의 생략·빈 steps·세 가지 null 표현, 필수 목록의 빈 배열, 따옴표·여러 줄 목록, flow의 선택 목록 생략·빈 배열·null, LF/CRLF를 검사한다.
- 실제 주문·업무 도구·LLM 호출과 DB 통합 실행은 포함하지 않는다. 여기서 정상은 문서 로딩과 정적 계약 검사 기준이며 업무 결과까지 보장한다는 뜻은 아니다.

## 확인된 기존 호환성 차이

| 사례 | 실제 주문 Agent | snapshot 분석 | SkillHeader / snapshot workspace 생성 |
|---|---|---|---|
| 일반 단계 `steps: null` | 빈 steps로 허용 | 허용 | steps 오류로 거부 |
| 일반 단계 `steps: ~` | 빈 steps로 허용 | 허용 | steps 오류로 거부 |
| 일반 단계 `steps:` | 빈 steps로 허용 | 허용 | steps 오류로 거부 |
| flow `inputs: null` | 선택 목록을 비워 허용 | 허용 | inputs 오류로 거부 |
| flow `outputs: null` | 선택 목록을 비워 허용 | 허용 | outputs 오류로 거부 |
| flow의 inputs·outputs·depends_on 모두 null | 선택 목록을 비워 허용 | 허용 | 먼저 inputs 오류로 거부 |

flow의 `depends_on: null` 단독 사례는 현재 두 경로에서 모두 허용된다. SkillHeader는 depends_on을 반환하는 어댑터가 아니므로 다른 선택 목록과 동일하게 거부한다고 가정하지 않았다.

이 여섯 사례는 이번 수정이 새로 만든 오류가 아니다. fixture의 `headerRejectedField`는 **현재 거부되는 동작의 관찰값**이며, 앞으로도 거부해야 한다는 요구사항이 아니다. 현재 테스트는 이 차이를 명시적으로 확인하므로 전체 테스트가 통과하더라도 정상 문법 20개가 모두 workspace와 호환된다는 의미는 아니다.

후속 수정에서는 실제 주문 Agent가 허용하는 범위를 유지하면서 이 여섯 사례를 수락하도록 바꾸고, 해당 관찰값을 null로 갱신한다. 일반 단계의 필수 목록을 일괄 선택 사항으로 바꾸거나 flow의 선택 목록을 일괄 필수로 바꾸면 안 된다. 기존 정상 원본의 해석 값·정적 연결 관계·원문 보존 테스트는 계속 유지한다.

## 실행과 결과

각 Java 모듈에서 다음 명령으로 실행했다.

```sh
./gradlew test --offline --no-daemon --console=plain
```

- service: 새 호환성 검사 29개 포함, 전체 630개 통과.
- order-agent/backend: 새 호환성 검사 28개 포함, 132개 통과. DB 통합 테스트 82개는 기존 환경 변수 조건에 따라 미실행.
- 거부 사례는 현재 거부 동작과 저장하지 않음을 명시적으로 확인하는 검사다. 비활성화하거나 실패를 무시한 테스트는 없다.
- Python과 운영 코드는 이번에 바꾸지 않아 해당 테스트를 다시 실행하지 않았다.
