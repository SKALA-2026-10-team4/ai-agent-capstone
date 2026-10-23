// 검증·진단 기록 화면(frontend/src/views/DiagnosisView.vue)의 상태 라벨과 판단
// 규칙이다. skillops-agent 가 진단 상태를 늘리면 여기만 고친다. 상태 값의 원본은
// skillops-agent/skillops_agent/state.py 의 DiagnosisStatus 다. 화면 컴포넌트
// 밖에 두어 frontend/unit/diagnosisView.test.js 가 Node 에서 바로 검사한다.

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
