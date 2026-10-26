// 개발 요청서(development_request)를 화면 글과 마크다운으로 바꾼다. 담당자 수정이 어떤 업무 도구도
// 만들지 않는 값을 요구하면 SKILL.md 수정만으로는 고칠 수 없으므로, SkillOps 가 후보 대신 개발 요청서와
// 임시 대안을 담은 no_valid_fix 제안서를 낸다. 모양은 docs/plans/2026-10-26-behavior-edit-root-fix.md
// 2.11 절과 docs/contracts/proposal.schema.json 의 development_request 칸이다.
// frontend/src/components/DevelopmentRequestCard.vue 가 카드와 '마크다운으로 복사' 에 쓰고,
// frontend/src/views/ProposalView.vue 와 frontend/src/views/DiagnosisView.vue 가 개발 요청서를 고를 때
// 쓴다. 화면 컴포넌트 밖에 두어 frontend/unit/developmentRequest.test.js 가 Node 에서 바로 검사한다.

// 빈틈 종류(gaps[].kind)의 화면 이름이다. missing_value 는 새 inputs·rules 키를 시작 입력과 어떤 도구의
// 출력도 덮지 못하는 경우, tool_output_missing 은 새 outputs 키를 그 Skill 의 도구도 다른 도구도 내지
// 않는 경우다.
export const GAP_KIND_LABEL = {
  missing_value: '어떤 도구도 만들지 않는 값',
  tool_output_missing: '도구가 내지 않는 출력',
}
// 빈틈 종류의 화면 이름을 돌려준다. 표에 없으면 원래 값, 값이 없으면 빈 글이다.
export const gapKindLabel = (kind) => (kind ? (GAP_KIND_LABEL[kind] ?? kind) : '')

// 빈틈이 나온 SKILL.md frontmatter 칸(gaps[].field)의 화면 이름이다. 표에 없으면 원래 값이다.
export const GAP_FIELD_LABEL = {
  inputs: '입력(inputs)',
  rules: '실행 규칙(rules)',
  outputs: '출력(outputs)',
}
export const gapFieldLabel = (field) => (field ? (GAP_FIELD_LABEL[field] ?? field) : '')

// 제안 방법 종류(options[].type)의 짧은 이름이다. 카드의 꼬리표로 쓴다.
export const OPTION_TYPE_LABEL = {
  extend_tool: '기존 도구 확장',
  new_tool: '새 도구 추가',
}
export const optionTypeLabel = (type) => (type ? (OPTION_TYPE_LABEL[type] ?? type) : '')

/**
 * 개발 요청서를 고른다. service 상세 응답의 맨 위 칸을 먼저 보고, 없으면 Agent 원본(proposal) 칸을
 * 본다. 진단 결과(run)도 같은 모양이라 그대로 넘기면 된다. 없거나 빈틈(gaps)이 하나도 없으면 null 이다.
 */
export function developmentRequestOf(detail) {
  const request = detail?.development_request ?? detail?.proposal?.development_request ?? null
  return request?.gaps?.length ? request : null
}

// 이름 목록을 ', ' 로 잇는다. 각 이름은 fmt 로 감싸고, 목록이 비면 '없음' 이다.
const listOf = (items, fmt) => (items?.length ? items.map(fmt).join(', ') : '없음')

/**
 * 제안 방법 하나를 한 줄 글로 바꾼다. extend_tool 은 '기존 도구 <tool>(<skill>)가 <add_produces> 를
 * 내도록 확장', new_tool 은 '새 도구 <suggested_name>(예시 이름)을 <place_before> 앞에 추가 — 쓸 수 있는
 * 입력: …' 이다. new_tool 의 이름은 SkillOps 가 붙인 예시 이름이라 그렇게 밝힌다(2.11 절). code 는 값·도구
 * 이름을 감쌀 함수이고, 카드는 그대로, 마크다운은 백틱으로 감싼다. 모르는 종류는 종류 이름 그대로다.
 */
export function optionText(option, code = (s) => s) {
  if (option?.type === 'extend_tool') {
    return `기존 도구 ${code(option.tool)}(${option.skill})가 ${listOf(option.add_produces, code)} 를 내도록 확장`
  }
  if (option?.type === 'new_tool') {
    return (
      `새 도구 ${code(option.suggested_name)}(예시 이름)을 ${option.place_before} 앞에 추가 — ` +
      `쓸 수 있는 입력: ${listOf(option.available_inputs, code)}`
    )
  }
  return String(option?.type ?? '')
}

/**
 * 제안 방법이 새로 내야 할 값 목록을 돌려준다. extend_tool 은 add_produces, new_tool 은 produces 다.
 * 없으면 빈 목록이다.
 */
export const optionProduces = (option) =>
  (option?.type === 'extend_tool' ? option.add_produces : option?.produces) ?? []

/**
 * 마크다운 문서에 넣을 맥락을 service 상세 응답에서 고른다. 개발 요청서만으로는 알 수 없는 제안서 번호,
 * 담당자 의도 글, 원인, 수정 전·후 Skill 세트 id 다. 없는 칸은 null 이다.
 * frontend/src/views/ProposalView.vue 가 쓴다.
 */
export function markdownContextOf(detail) {
  const p = detail?.proposal ?? {}
  return {
    proposalId: detail?.proposal_id ?? null,
    intentText: p.intent?.text ?? null,
    rootCause: p.root_cause ?? null,
    baselineSnapshotId: p.baseline_snapshot_id ?? p.trigger?.baseline_snapshot_id ?? null,
    snapshotId: p.snapshot_id ?? p.trigger?.candidate_snapshot_id ?? null,
  }
}

// 마크다운 인라인 코드다. 값 속 백틱은 따옴표로 바꿔 코드가 끊기지 않게 한다.
const md = (s) => `\`${String(s ?? '').replaceAll('`', "'")}\``
// 표 칸 글이다. 줄바꿈은 빈칸으로, 세로 막대는 이스케이프한다.
const cell = (s) => String(s ?? '').replace(/\r?\n/g, ' ').replaceAll('|', '\\|')

/**
 * patch 를 감쌀 코드 울타리를 만든다. 글 속 가장 긴 백틱 줄보다 한 개 더 길게(최소 3개) 해서 patch 에
 * 백틱이 있어도 코드 블록이 끊기지 않게 한다.
 */
function fenceFor(text) {
  const longest = Math.max(0, ...(String(text).match(/`+/g) ?? []).map((m) => m.length))
  return '`'.repeat(Math.max(3, longest + 1))
}

/**
 * 개발 요청서를 개발팀에 그대로 보낼 수 있는 한국어 마크다운 문서로 만든다. 절 순서는 제목, 배경, 필요한 값,
 * 제안 방법, 바꿀 곳, 임시 대안, 근거 실행이다. context 는 markdownContextOf 의 모양이고 비어도 된다.
 * 칸이 비면 그 절에 '없습니다' 를 적어 절 순서는 늘 같다. 끝은 줄바꿈 하나로 맺는다.
 * frontend/src/components/DevelopmentRequestCard.vue 의 '마크다운으로 복사' 버튼이 부른다.
 */
export function developmentRequestMarkdown(request, context = {}) {
  const r = request ?? {}
  const gaps = r.gaps ?? []
  const options = r.options ?? []
  const changePoints = r.change_points ?? []
  const interim = r.interim ?? null
  const evidence = r.evidence_run_ids ?? []
  const skills = [...new Set(gaps.map((g) => g.needed_by).filter(Boolean))]
  const lines = []
  const push = (...xs) => lines.push(...xs)

  push(`# ${r.title || '개발 요청'}`, '')

  push('## 배경', '')
  push(
    `담당자가 ${skills.length ? skills.map((s) => `${s} Skill`).join(', ') : 'Skill'} 에 새로 넣은 값을 지금 어떤 업무 도구도 만들지 않습니다. ` +
      'SKILL.md 수정만으로는 해결할 수 없어 SkillOps 가 이 개발 요청서를 만들었습니다.',
    '',
  )
  const facts = []
  if (context.intentText) facts.push(`- 담당자 의도: ${context.intentText}`)
  if (context.rootCause?.skill) {
    const summary = context.rootCause.summary ? ` — ${context.rootCause.summary}` : ''
    facts.push(`- 원인: ${context.rootCause.skill}${summary}`)
  }
  if (context.proposalId) facts.push(`- 제안서 번호: ${md(context.proposalId)}`)
  if (context.baselineSnapshotId) facts.push(`- 수정 전 Skill 세트: ${md(context.baselineSnapshotId)}`)
  if (context.snapshotId) facts.push(`- 수정 후 Skill 세트: ${md(context.snapshotId)}`)
  if (facts.length) push(...facts, '')

  push('## 필요한 값', '')
  if (gaps.length) {
    push('| 값 | 필요로 하는 Skill | 칸 | 종류 |', '| --- | --- | --- | --- |')
    for (const g of gaps) {
      push(`| ${md(cell(g.key))} | ${cell(g.needed_by)} | ${md(cell(g.field))} | ${cell(gapKindLabel(g.kind))} |`)
    }
  } else {
    push('적힌 값이 없습니다.')
  }
  push('')

  push('## 제안 방법', '')
  if (options.length) {
    push('아래 가운데 하나를 골라 개발해 주세요.', '')
    options.forEach((o, i) => {
      push(`${i + 1}. ${optionText(o, md)}`)
      const produces = optionProduces(o)
      if (o.type === 'new_tool' && produces.length) push(`   - 내야 할 값: ${produces.map(md).join(', ')}`)
    })
  } else {
    push('적힌 방법이 없습니다.')
  }
  push('')

  push('## 바꿀 곳', '')
  if (changePoints.length) push(...changePoints.map((p) => `- ${md(p)}`))
  else push('적힌 곳이 없습니다.')
  push('')

  push('## 임시 대안', '')
  if (interim && (interim.summary || interim.patch || interim.proof_run_ids?.length)) {
    if (interim.summary) push(interim.summary, '')
    if (interim.patch) {
      const fence = fenceFor(interim.patch)
      push(`${fence}diff`, interim.patch.replace(/\n$/, ''), fence, '')
    }
    if (interim.proof_run_ids?.length) {
      push(`증명 실행(이 변경을 되돌리면 통과한 원인 실험): ${interim.proof_run_ids.map(md).join(', ')}`, '')
    }
  } else {
    push('임시 대안이 없습니다.', '')
  }

  push('## 근거 실행', '')
  if (evidence.length) push(...evidence.map((id) => `- ${md(id)}`))
  else push('적힌 실행이 없습니다.')

  return `${lines.join('\n')}\n`
}
