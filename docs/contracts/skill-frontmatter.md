# 계약 1 · SKILL.md frontmatter

버전: v0 (초안) · 동결 예정: 1일차 09시

변경 기록: 2026-10-23 · #72 1단계 — service의 workspace/snapshot YAML 해석 공통화, 목록·버전 타입 검사, 간이 실행의 빈 steps 거부.

추가 기록: 2026-10-23 · #72 2단계 — patch 결과에 `depends_on` 선행 단계 누락을 `dependency_violations`로 보존하고 Python 후보 판정에 연결했다. [apply_patch 명세](mcp-tools-input-output.md#8-수정-적용하기--apply_patch)를 참고한다.

## service YAML 해석 규칙 (#72 · 1단계)

service의 `SkillHeader`와 snapshot 분석용 `Frontmatter`는 `skill/SkillFrontmatter`를 공유한다. 이 절은 service 내부의 변경이며 주문 Agent의 `SkillLoader`와 Python 진단 로직까지 통합한 상태는 아니다.

| 항목 | 규칙 |
|---|---|
| YAML 목록 | `[a, b]`와 여러 줄 `- a` 목록을 동일하게 해석한다. 따옴표는 YAML 문법으로 처리하고 항목 문자열에 남기지 않는다. |
| workspace 선택 목록 | `inputs`, `outputs`, `steps`가 없으면 빈 목록이다. 명시적 `null`, scalar, 숫자 항목, 비어 있거나 공백뿐인 문자열 항목은 입력 오류다. `[]`는 빈 목록으로 읽는다. |
| workspace version | 선언하면 1 이상의 Java Integer여야 한다. 문자열 `"1"`, 0·음수·소수·null·Integer 범위 초과는 거부한다. version이 없으면 어댑터는 null을 반환하고 workspace 생성/운영 저장소가 필수값 오류를 처리한다. |
| workspace name | 선언하면 비공백 문자열이어야 한다. 누락 시 기존처럼 null이며 폴더 이름 일치 검사는 이번 단계에 추가하지 않는다. |
| 문서 경계 | 첫 줄 `---`, 닫는 줄 `---`를 정확히 사용한다. 구분선 앞뒤 공백·선행 빈 줄·BOM은 허용하지 않는다. LF/CRLF와 닫는 구분선 EOF는 지원한다. |
| YAML 오류 | 중복 키·collection alias·안전하지 않은 태그·깨진 YAML을 거부한다. workspace 어댑터는 입력 오류로 바꾸고, snapshot 분석은 기존 YAML 예외 정책을 유지한다. |
| 간이 실행 flow | `steps` 누락·빈 목록은 실행 전 거부한다. 명시적 null이나 잘못된 목록도 거부하며 0단계를 실행 성공으로 반환하지 않는다. |
| 원문 증거 | 원본 문자열·줄바꿈·파일 해시는 변경하지 않는다. 파싱은 해석 결과만 만든다. |

snapshot diff의 빈 맵 반환, 깨진 YAML의 hunk 보존, hunk ID·위치 계산 규칙은 유지한다. `closingLine`은 원문 줄 위치 계산이므로 빈 frontmatter에서도 닫는 구분선 위치를 반환할 수 있다.

1단계는 owner/description/depends_on 검증과 주문 Agent와의 라이브러리 연결을 완료하지 않았다. 이후 2단계에서 기존 graph의 선행 단계 검증 결과를 patch와 Python에 전달했다. owner/description 등 전체 실행 계약과 파서의 서버 간 통합은 후속 범위다. [1단계 변경 내역](../changes/2026-10-23-skill-parser-phase1.md).

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
- 로더는 frontmatter 만 파싱한다. 본문은 검사하지 않고 system prompt 에 그대로 주입한다.
  그래서 "본문은 바꿨는데 frontmatter 는 그대로"가 장애 유형 F1 이 된다.

## 위치

| 모드 | 경로 |
|---|---|
| live | `skills/<name>/SKILL.md` (저장소 루트, 서비스가 읽음) |
| sandbox | `<workspace 루트>/<ws_id>/skills/<name>/SKILL.md` |

## 상태 값 (final_status · payment.status 공용)

`SHIPPED` 정상 완료 · `REJECTED` 업무 규칙으로 거절(재고 부족 등) · `HOLD` 보류(F3 에서 신설) · `FAILED` harness/skill 오류
