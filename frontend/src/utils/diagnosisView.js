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

// 진단 결과(diagnosis.finding)마다 화면 이름과 설명이다. 진단 상태를 늘리지 않고 이 칸으로 무엇을
// 찾았는지 알린다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.5 절). INTENT_NOT_REALIZED 는
// 수정본이 오류 없이 돌지만 담당자 의도 범위의 기준 주문이 의도대로 처리되지 않은 경우다. NEEDS_TOOL 은
// 담당자가 새로 넣은 값을 어떤 업무 도구도 만들지 않아 SKILL.md 수정만으로는 고칠 수 없는 경우이고,
// SkillOps 가 후보 대신 개발 요청서와 임시 대안을 낸다(2.11 절). NEEDS_INTENT 는 담당자가 의도를 적었지만
// SkillOps 가 시험 조건(기대 결과)으로 바꾸지 못해 수정안을 만들지 않고 멈춘 경우다(상태는 EVALUATED).
// skillops-agent/skillops_agent/state.py 의 같은 이름 상수와 글자가 같아야 한다.
export const FINDING = {
  INTENT_NOT_REALIZED: {
    label: '의도가 아직 실행되지 않음',
    note: '수정본은 오류 없이 돌았지만 의도한 주문이 아직 의도대로 처리되지 않아요. 본문 문장만으로는 주문 Agent 가 정책을 실행하지 않으므로, SkillOps 가 실행 규칙(rules)으로 옮기는 수정안을 시험해요.',
  },
  NEEDS_TOOL: {
    label: '도구 개발 필요',
    note: '수정본이 쓰는 값을 지금 어떤 업무 도구도 만들지 않아 SKILL.md 수정만으로는 고칠 수 없어요. SkillOps 가 수정안 대신 개발 요청서와 임시 대안을 만들어요.',
  },
  NEEDS_INTENT: {
    label: '의도 확인 필요',
    note: '적어 주신 의도를 SkillOps 가 실제로 시험할 조건으로 바꾸지 못해 수정안을 만들지 않았어요. 기대 결과를 한 줄로 적어 다시 시험해 주세요.',
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

// 담당자가 의도를 적었지만 SkillOps 가 시험 조건으로 바꾸지 못해 멈춘 진단(finding NEEDS_INTENT)인지 본다.
// skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 가 이 finding 을 남긴다. frontend/src/views/DiagnosisView.vue 와
// frontend/src/views/ProposalListView.vue 가 쓴다.
export const needsIntent = (run) => run?.diagnosis?.finding === 'NEEDS_INTENT'

// NEEDS_INTENT 진단을 다시 시험할 때 되살릴 내용이다. 기준 세트 위에 수정 세트의 바뀐 파일을 초안으로
// 다시 얹고, 의도 글을 의도 칸에 채운다. { baselineId, candidateId, paths, intentText } 를 돌려주며
// frontend/src/stores/workspace.js 의 restoreForRetry 가 받는다.
export const retryPlanOf = (run) => ({
  baselineId: baselineSnapshotOf(run),
  candidateId: candidateSnapshotOf(run),
  paths: run.trigger?.changed_paths ?? [],
  intentText: run.trigger?.intent?.text ?? '',
})

// 후보의 검증 결과(valid)를 문구로 바꾼다. true 는 통과, false 는
// 미통과이고, 그 밖의 값은 아직 검증을 마치지 않은 후보로 본다.
export const candidateVerdict = (valid) => (valid === true ? '검증 통과' : valid === false ? '검증 미통과' : '검증 미완료')
