# partial Skill 진단 증거 조회 계약

버전: v1 · 2026-10-23 · #72

## 범위와 종료 의미

후보 snapshot의 Skill 목록이 `partial: true`이고 유효한 `parse_errors`가 있으면 Python 진단기는 **증거 수집만** 수행한다. 이 경로에서는 LLM, 의존성 그래프 분석, workspace 생성, patch, 시나리오 실행, 제안 저장을 호출하지 않는다. 기존 완전한 목록의 진단 경로는 유지한다.

정상 항목과 오류 항목이 manifest의 SKILL.md 전체를 중복 없이 설명해야 한다. 목록·snapshot 출처 불일치, 모순된 partial 값, 항목 누락·중복은 계약 오류다. 오류 파일의 버전은 추측하지 않는다.

## REST 조회

| 제공자 | 경로 | 변경 |
|---|---|---|
| Python | `GET /runs/{thread_id}/events` | 상세 응답에 `evidence` 추가. 정상 경로에서는 null, 기존 저장 기록에는 없을 수 있음 |
| service | `GET /api/diagnoses/{thread_id}` | 기존 Python 상세 응답 중계로 같은 필드 제공 |
| Python/service | `GET /runs`, `GET /api/diagnoses` | 새 원문 증거 `evidence`는 목록에 포함하지 않음. 나머지 기존 필드는 유지 |

Python 조회의 기존 `X-Service-Token` 검사와 service의 기존 인증을 유지한다. 신규 endpoint나 요청 필드는 없다. 이 변경은 진단 조회 REST 응답 확장이며 MCP 도구 입출력 변경이 아니다. MCP `apply_patch`는 v1.3을 유지한다.

## 상세 응답의 evidence

| 필드 | 타입 | 의미 |
|---|---|---|
| `snapshots.before`, `snapshots.after` | object | 처음 고정한 기준·후보 manifest. snapshot_id/hash/files와 원래 메타데이터 포함 |
| `metadata` | object | 검증한 목록 응답. snapshot_id/snapshot_hash/items/partial/parse_errors를 보존 |
| `sources` | array | 양쪽 snapshot의 SKILL.md 원문. 변경되지 않은 오류 파일도 포함 |
| `collection_errors` | array | 원문 조회 실패, 지원 밖 diff, 형식·출처 오류 |
| `limitations` | string[] | 현재 `PARTIAL_METADATA`, `AUTOMATIC_REPAIR_DISABLED` |
| `collection_status` | string | 아래 수집 단계/결과 |

`sources` 항목은 `{snapshot_id, path, hash, size, encoding, content}`다. hash는 원본 바이트의 SHA-256, size는 바이트 수다. encoding은 `utf-8` 또는 `base64`다. 원문을 다시 직렬화하거나 LF/CRLF를 바꾸지 않는다. base64는 엄격히 복원해 해시를 검증하며 텍스트 diff에는 보내지 않는다. snapshot ID·경로·해시·크기와 실제 content 바이트를 manifest에 대조한 항목만 저장한다.

`collection_errors` 항목은 다음과 같다.

```json
{
  "stage": "source",
  "snapshot_id": "22222222-2222-4222-8222-222222222222",
  "path": "skills/payment/SKILL.md",
  "code": "ORDER_AGENT_UNAVAILABLE",
  "message": "주문 Agent에 연결할 수 없습니다"
}
```

stage는 `source` 또는 `diff`, path는 특정 파일을 지칭하지 않는 오류에서 null이다. code는 조회/MCP 오류 코드를 보존하며 증거 계약 위반·지원 밖 형식에는 `EVIDENCE_CONTRACT_ERROR`를 사용한다. 깨진 원문 응답 JSON은 `ORDER_AGENT_INVALID_RESPONSE`다. 목록 파싱 오류는 collection_errors로 이동하지 않고 `metadata.parse_errors`에 원래 path/code/message 그대로 남긴다.

| collection_status | 의미 |
|---|---|
| `METADATA_COLLECTED` | 목록·manifest·trace 참조 저장, 원문 조회 전 |
| `SOURCES_COLLECTED` | 원문 수집 단계 저장, diff 조회 전. 일부 조회 오류가 있을 수 있으므로 collection_errors도 확인 |
| `COLLECTED` | 수집 가능한 증거를 오류 없이 수집. Skill 문서의 유효성·실행 성공을 뜻하지 않음 |
| `COLLECTED_WITH_ERRORS` | 개별 조회 실패 또는 지원 밖 변경을 기록하고 수집 종료 |
| `STOPPED` | 원문/해시/출처/응답 계약 위반 등으로 추가 수집 중단 |

기존 `trace`, snapshot_id, baseline_snapshot_id, execution_id를 함께 보존한다. `skill_versions`에는 목록의 정상 항목 버전만 들어간다. 기존 `diff.files`에는 검증된 service diff 응답을, `diff.hunks`와 `changes`에는 번호를 붙인 hunks를 저장한다. `frontmatter_parse_error`도 diff 응답 안에 보존한다. 원문 수집 중 중단되면 diff는 null일 수 있다.

## 최종 상태와 실패 보존

partial 수집의 최종 `diagnosis_status`는 기존 `ERROR`, error는 `ContractError: candidate skill metadata is incomplete`다. 별도의 성공·승인 상태를 추가하지 않는다. `diagnosis`에는 다음을 담는다.

```json
{
  "explanation": "Skill 메타데이터가 불완전하여 증거만 수집했습니다. 자동 수정과 실행은 수행하지 않습니다.",
  "evidence_only": true,
  "collection_status": "COLLECTED",
  "collection_error_count": 0
}
```

목록 검사 자체가 실패하거나 예상하지 못한 노드 예외가 발생하면 기존 guarded 오류 처리를 따른다. 이 경우 diagnosis는 없을 수 있고 error는 실제 예외 문구다. 이미 완료된 수집 노드의 증거는 SQLite RunStore에 남는다. 노드 실행 도중 프로세스가 중단되면 그 노드 내부에서 아직 반환하지 않은 수집분까지 보장하지는 않는다. 재시작 시 기존 복구 처리가 ERROR로 바꾸되 저장된 evidence는 유지한다.

## 기존 조회·MCP 재사용 및 한도

- 원문은 기존 주문 Agent `GET /api/snapshots/{id}/files?path=...`로 읽는다. Python 내부 함수 `get_snapshot_file(snapshot_id, path)`를 추가했으며 새 MCP tool은 아니다. 주입한 HTTP client의 주소·timeout과 기존 최대 3회 조회 재시도 규칙을 사용한다.
- snapshot당 256파일·파일당 1,000,000바이트·snapshot 합계 5,000,000바이트를 검사한다. 양쪽 원문 합계는 최대 10,000,000바이트이며 조용히 잘라 저장하지 않는다. 이는 원본 바이트 기준 한도다. JSON/base64와 diff의 직렬화 크기는 별도다.
- 원문 수집 대상은 manifest의 `skills/<폴더>/SKILL.md`다. 리소스 본문은 수집하지 않으며 manifest의 경로·해시는 보존한다.
- diff는 양쪽에 존재하고 원문이 검증된 UTF-8 Skill만 기존 MCP `diff_skill`로 조회한다. Skill ID, 양쪽 snapshot·파일 해시, hunk 구조를 검증한다. 추가/삭제·리소스 변경·잘못된 Skill ID는 제한 사유를 기록한다.
- 인증·통신·5xx 등 조회 실패는 코드와 사유를 남기고 다른 파일을 수집한다. 원문 변조·snapshot 변조·diff 출처 불일치는 검증된 증거만 남기고 추가 수집을 중단한다.
- 자동 수정·후보 생성·버전 복원·실행 검증을 이어가는 기능은 이번 범위에 포함하지 않는다.
