// 검증·진단 기록 화면(frontend/src/views/DiagnosisView.vue)의 상태 라벨과 판단
// 규칙이다. skillops-agent 가 진단 상태를 늘리면 여기만 고친다. 상태 값의 원본은
// skillops-agent/skillops_agent/state.py 의 DiagnosisStatus 다. 화면 컴포넌트
// 밖에 두어 frontend/unit/diagnosisView.test.js 가 Node 에서 바로 검사한다.
import { stampOf } from './format.js'

// 진단 상태(diagnosis_status)마다 화면 제목이다.
// frontend/tests/diagnosis.spec.js 가 '기본 회귀 검증 통과' 와 '기준 주문
// 검증 불가' 를 화면에서 그대로 찾으므로 이 둘을 바꾸면 e2e 테스트도 고친다.
export const DIAGNOSIS_LABEL = {
  BASELINE_FAILED: '기준 주문 검증 불가',
  VALIDATION_FAILED: '기대 결과 불일치',
  QUEUED: '검증 대기',
  RUNNING: '검증·진단 중',
  VALIDATED: '기본 회귀 검증 통과',
  EVALUATED: '진단·후보 평가 완료',
  INTENT_VIOLATION: '변경 의도 위반 발견',
  ERROR: '검증·진단 오류',
  NO_SKILL_CHANGES: 'Skill 변경 없음',
}

// 상태의 화면 제목을 돌려준다. 라벨이 없는 상태는 원래 이름 그대로
// 보여 준다. 진단 상세의 제목과 목록의 각 줄이 쓴다.
export const diagnosisLabel = (status) => DIAGNOSIS_LABEL[status] ?? status

// 진단 결과(diagnosis.finding)마다 화면 이름과 설명이다. 진단 상태를 늘리지 않고 이 칸으로 무엇을
// 찾았는지 알린다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.5 절). INTENT_NOT_REALIZED 는
// 수정본이 오류 없이 돌지만 담당자 의도 범위의 기준 주문이 의도대로 처리되지 않은 경우다. NEEDS_TOOL 은
// 담당자가 새로 넣은 값을 어떤 업무 도구도 만들지 않아 SKILL.md 수정만으로는 고칠 수 없는 경우이고,
// SkillOps 가 후보 대신 개발 요청서와 임시 대안을 낸다(2.11 절).
export const FINDING = {
  INTENT_NOT_REALIZED: {
    label: '의도가 아직 실행되지 않음',
    note: '수정본은 오류 없이 돌았지만 의도한 주문이 아직 의도대로 처리되지 않아요. 본문 문장만으로는 주문 Agent 가 정책을 실행하지 않으므로, SkillOps 가 실행 규칙(rules)으로 옮기는 수정안을 시험해요.',
  },
  NEEDS_TOOL: {
    label: '도구 개발 필요',
    note: '수정본이 쓰는 값을 지금 어떤 업무 도구도 만들지 않아 SKILL.md 수정만으로는 고칠 수 없어요. SkillOps 가 수정안 대신 개발 요청서와 임시 대안을 만들어요.',
  },
}
// 진단 결과의 화면 이름을 돌려준다. 표에 없으면 원래 값, 값이 없으면 null 이다.
export const findingLabel = (finding) => (finding ? (FINDING[finding]?.label ?? finding) : null)
// 진단 결과의 설명을 돌려준다. 표에 없거나 값이 없으면 null 이다.
export const findingNote = (finding) => (finding ? (FINDING[finding]?.note ?? null) : null)

// 담당자 의도(intent)를 고른다. 저장된 제안서의 intent(출처와 기대 결과 포함)를 먼저 쓰고, 없으면
// 진단 결과의 intent, 그것도 없으면 접수 요청(trigger)에 담당자가 보낸 intent 를 쓴다. 없으면 null 이다.
export const intentOf = (run) => run?.proposal?.intent ?? run?.intent ?? run?.trigger?.intent ?? null

// 아직 끝나지 않아 다시 읽어야 하는 진단 상태다.
// skillops-agent/skillops_agent/run_store.py 의 ACTIVE_STATUSES 와 같은 두 값이다.
export const IN_PROGRESS_STATUSES = ['QUEUED', 'RUNNING']

// 다시 조회할지 정한다. 진단 결과가 아직 없거나(목록 화면이거나 전달 대기 중), 진행
// 중이거나, 직전 조회가 실패했으면 다시 읽는다. frontend/src/views/DiagnosisView.vue 의
// refresh 가 이 값이 참이면 frontend/src/config.js 의 DIAGNOSIS_POLL_MS 뒤에 다시 조회한다.
export const shouldPollAgain = (run, error) =>
  !run || IN_PROGRESS_STATUSES.includes(run.diagnosis_status) || !!error

// 후보 목록과 원인은 저장된 제안서(proposal)에 있으면 그것을, 없으면
// 진단 결과의 값을 쓴다. 후보가 없으면 빈 목록이다.
export const candidatesOf = (run) => run?.proposal?.candidates ?? run?.candidates ?? []
export const rootCauseOf = (run) => run?.proposal?.root_cause ?? run?.root_cause

// 기준 세트와 수정 세트의 snapshot id 를 돌려준다. 진단 결과에 없으면
// 접수 요청(trigger)의 값을 쓰고, trigger 도 없으면 TypeError 가 난다.
export const baselineSnapshotOf = (run) => run.baseline_snapshot_id ?? run.trigger.baseline_snapshot_id
export const candidateSnapshotOf = (run) => run.snapshot_id ?? run.trigger.candidate_snapshot_id

// 후보의 검증 결과(valid)를 문구로 바꾼다. true 는 통과, false 는
// 미통과이고, 그 밖의 값은 아직 검증을 마치지 않은 후보로 본다.
export const candidateVerdict = (valid) => (valid === true ? '검증 통과' : valid === false ? '검증 미통과' : '검증 미완료')

// 진단 상태마다 배지 색 톤이다. 통과는 ok, 진행 중은 info, 의도 위반과 기대 불일치는 warn, 검증 불가와 오류는 err
// 이고, 표에 없는 상태는 빈 톤(흐린 배지)이다. 진단 목록과 상세의 상태 배지가 쓴다.
export const DIAGNOSIS_TONE = {
  VALIDATED: 'ok',
  EVALUATED: 'ok',
  QUEUED: 'info',
  RUNNING: 'info',
  VALIDATION_FAILED: 'warn',
  INTENT_VIOLATION: 'warn',
  BASELINE_FAILED: 'err',
  ERROR: 'err',
}
export const diagnosisTone = (status) => DIAGNOSIS_TONE[status] ?? ''

// 바뀐 파일 경로(skills/<id>/SKILL.md)에서 Skill 이름만 뽑는다. Skill 파일이 아니면 경로 그대로 쓴다.
export const changedSkillsOf = (run) =>
  (run?.trigger?.changed_paths ?? []).map((p) => p.match(/^skills\/([^/]+)\/SKILL\.md$/)?.[1] ?? p)

/**
 * 진단 목록 한 줄의 제목이다. 저장한 Skill 이름과 저장 시각으로 'notify 수정 · 10월 26일 06:40 저장' 처럼 쓴다.
 * 바뀐 파일을 모르면 '수정본 저장 · 10월 26일 06:40', 시각도 모르면 '수정본 저장' 이다. 세트 id 는 쓰지 않는다.
 */
export function diagnosisTitleOf(run) {
  const skills = changedSkillsOf(run)
  const stamp = stampOf(run?.created_at)
  if (skills.length) return `${skills.join(', ')} 수정${stamp ? ` · ${stamp} 저장` : ''}`
  return stamp ? `수정본 저장 · ${stamp}` : '수정본 저장'
}
