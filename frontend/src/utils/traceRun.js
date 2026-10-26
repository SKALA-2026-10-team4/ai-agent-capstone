// order-agent trace 의 events 를 Skill 별 실행 상태로 계산한다.
// frontend/src/views/ExecutionView.vue 의 단계별 결과가 traceRun 과 RUN_LABEL 을 쓴다.
// 실행 목록 한 줄 라벨(executionLabel)은 frontend/src/views/ExecutionListView.vue 가, 실행 번호를
// 실행 화면 주소로 바꾸는 executionPath 는 frontend/src/components/ProposalFacts.vue 와
// frontend/src/views/DiagnosisView.vue 가 쓴다. 이벤트 종류와 data 모양은
// order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java,
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java,
// order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 가
// 남기는 모양을 따른다. 이벤트 종류를 더할 때 화면 문장은 frontend/src/utils/eventView.js 의
// EVENT_KINDS 에, 단계 상태에 주는 영향은 applyEvent 에 더한다.
// frontend/unit/traceRun.test.js 가 지금 동작을 고정한다.
import { UUID_RE, shortId } from './format.js'

// 단계 상태를 담당자가 읽는 말로 바꾸는 표다. 상태마다 쓰는 아이콘은
// frontend/src/views/ExecutionView.vue 의 STEP_ICON 에 있다.
export const RUN_LABEL = {
  ok: '완료',
  stopped: '업무 규칙으로 멈춤',
  error: '오류',
  running: '실행 중',
  blocked: '검사에서 막혀 실행 안 됨',
  skipped: '실행 안 됨',
}

/**
 * 이벤트 하나를 그 단계(skill)의 상태 s 에 반영한다. skill_id 가 없는 이벤트면
 * s 는 null 이고 아무것도 바꾸지 않는다. case 가 없는 종류도 무시한다. 단계
 * 상태에 영향을 주는 이벤트 종류를 더하려면 여기에 case 를 더한다.
 */
function applyEvent(s, e) {
  switch (e.kind) {
    case 'SKILL_LOADED':
      if (s) s.loaded = e.data
      break
    // 단계의 실제 입력, 출력, 상태, 시간은 #39 이후 실행에만 있다. 이전 실행에는 이 이벤트가 없다.
    case 'SKILL_STARTED':
      if (s) s.started = e.data
      break
    // 단계의 실행 규칙(rules)이 맞아 도구 없이 주문을 멈췄다. 결제 거절(HOLD) 같은 업무 종료와 같게
    // stopped 로 두고, hold 는 HOLD, reject 는 REJECTED 를 final_status 로 미리 둔다. 뒤이은
    // SKILL_FINISHED 와 BUSINESS_STOP 이 서버가 정한 값으로 다시 채운다.
    case 'RULE_MATCHED':
      if (!s) break
      s.rule = e.data
      s.status = 'stopped'
      s.final_status = e.data.then === 'reject' ? 'REJECTED' : 'HOLD'
      break
    // 단계 끝 이벤트다. 오류면 먼저 남은 오류 내용을 유지하고,
    // output 이 없으면 앞서 전달한 값을 둔다.
    case 'SKILL_FINISHED':
      if (!s) break
      s.duration_ms = e.data.duration_ms ?? null
      s.declared_output = e.data.output ?? s.declared_output
      if (e.data.status === 'error') {
        s.status = 'error'
        s.error = s.error ?? { category: e.data.category }
      } else if (e.data.business_stop) {
        s.status = 'stopped'
        s.final_status = e.data.final_status
      } else {
        s.status = 'ok'
      }
      break
    case 'TOOL_REQUEST':
      if (s) s.tools.push({ tool: e.data.tool, arguments: e.data.arguments, reason: e.data.reason ?? null, result: null })
      break
    // 결과는 같은 도구의 결과 없는 마지막 요청과 짝짓고, 짝이 없으면 새 항목을
    // 만든다. 결과 값이 없는 TOOL_REPLAY 는 replayed 표시를 결과로 남긴다.
    case 'TOOL_RESULT':
    case 'TOOL_REPLAY': {
      if (!s) break
      const open = s.tools.findLast((t) => t.tool === e.data.tool && !t.result)
      const entry = open ?? s.tools[s.tools.push({ tool: e.data.tool, arguments: e.data.arguments }) - 1]
      entry.result = e.data.result ?? { replayed: true }
      entry.elapsed_ms = e.data.elapsed_ms ?? null
      break
    }
    case 'TOOL_ERROR':
      if (s) {
        s.status = 'error'
        s.error = e.data
      }
      break
    case 'SKILL_COMPLETED':
      if (s) {
        s.status = 'ok'
        s.declared_output = e.data.declared_output
      }
      break
    case 'BUSINESS_STOP':
      if (s) {
        s.status = 'stopped'
        s.final_status = e.data.final_status
      }
      break
    case 'EXECUTION_ERROR':
      if (s) {
        s.status = 'error'
        s.error = e.data
      }
      break
  }
}

// 오류에 skill_id 가 없으면(INVALID_TOOL_ARGUMENTS, MISSING_SKILL_OUTPUT 등) 로드되거나 시작했지만
// 끝나지 못한 마지막 단계를 실패한 단계로 본다. 그런 단계가 없으면 아무 단계에도 붙이지 않는다.
function assignOrphanError(bySkill, steps, error) {
  if (!error || error.skill_id) return
  const last = steps.findLast((id) => (bySkill[id]?.loaded || bySkill[id]?.started) && !bySkill[id].status)
  if (last) {
    bySkill[last].status = 'error'
    bySkill[last].error = error
  }
}

// 아직 상태가 없는 단계를 채운다. 로드됐고 실행이 진행 중(RUNNING)이면
// running, 정적 검사에서 막혔으면 blocked, 아니면 skipped 다.
function settleUnfinished(ensure, steps, trace, error) {
  const blocked = error?.phase === 'static_validation'
  for (const id of steps) {
    const s = ensure(id)
    if (s.status) continue
    if (s.loaded && trace?.completion === 'RUNNING') s.status = 'running'
    else s.status = blocked ? 'blocked' : 'skipped'
  }
}

/**
 * trace 와 단계 순서(steps)로 { bySkill, error } 를 계산한다. bySkill 은 Skill 마다
 * { status, loaded, started, tools, declared_output, duration_ms, error,
 * final_status, rule } 이고, error 는 마지막 EXECUTION_ERROR 의 data 다. status 는 ok(완료),
 * stopped(REJECTED 나 HOLD 로 끝난 정상 업무 종료, 실행 규칙으로 멈춘 경우 포함), error, running,
 * blocked(정적 검사에서 막혀 실행하지 않음), skipped 가운데 하나다. rule 은 그 단계에서 맞은 실행
 * 규칙(RULE_MATCHED 의 data)이고 없으면 null 이다. frontend/src/views/ExecutionView.vue 가
 * FLOW_VALIDATED 의 steps, 없으면 세트의 order-flow 순서로 부른다.
 */
export function traceRun(trace, steps) {
  const events = trace?.events ?? []
  const bySkill = {}
  // Skill 의 상태 객체를 처음 볼 때 만든다. skill_id 가 있는 이벤트는
  // 처리하지 않는 종류여도 항목을 만든다.
  const ensure = (id) =>
    (bySkill[id] ??= {
      status: null,
      loaded: null,
      started: null,
      tools: [],
      declared_output: null,
      duration_ms: null,
      error: null,
      final_status: null,
      rule: null,
    })

  let error = null
  for (const e of events) {
    // RULE_MATCHED 는 data 에도 skill_id 가 있으므로 이벤트 칸이 비면 그 값으로 단계를 찾는다.
    const id = e.skill_id ?? (e.kind === 'RULE_MATCHED' ? e.data?.skill_id : null)
    applyEvent(id ? ensure(id) : null, e)
    if (e.kind === 'EXECUTION_ERROR') error = e.data
  }
  // 순서가 결과를 정한다. skill_id 없는 오류를 먼저 단계에 붙이고, 그 뒤 남은 단계를 채운다.
  assignOrphanError(bySkill, steps, error)
  settleUnfinished(ensure, steps, trace, error)
  return { bySkill, error }
}

// 실행 목록 한 줄 라벨을 만든다. 시작 시각(월-일 시:분), 최종 상태,
// 오류 분류, 실행 번호 앞 8자를 가운뎃점으로 잇고 오류 분류는 있을
// 때만 넣는다. frontend/src/views/ExecutionListView.vue 의 runLabel 이 쓴다.
export const executionLabel = (x) =>
  `${x.started_at?.slice(5, 16).replace('T', ' ')} · ${x.final_status ?? x.completion}` +
  `${x.error_category ? ` · ${x.error_category}` : ''} · ${shortId(x.execution_id)}`

// run_id 모양이다. order-agent 는 r_ 뒤에 하이픈을 뺀 execution_id 를 붙여 만들고, 그 규칙은
// order-agent/backend/src/main/java/com/capstone4/orderagent/trace/RunId.java 에 있다.
const RUN_ID = /^r_[0-9a-f]{32}$/i
/**
 * 실행 번호를 실행 화면 주소(/executions/{uuid})로 바꾼다. 실행 번호는 run_id 이거나
 * UUID 이고, run_id 는 하이픈을 다시 넣어 UUID 로 되돌린다. 실행 번호가 아니면
 * null 이다. frontend/src/components/ProposalFacts.vue 는 제안서 사실 값을 실행 링크로
 * 바꿀 때, frontend/src/views/DiagnosisView.vue 는 근거 실행 링크를 만들 때 쓴다.
 */
export function executionPath(id) {
  if (RUN_ID.test(id)) {
    const h = id.slice(2)
    return `/executions/${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
  }
  return UUID_RE.test(id) ? `/executions/${id}` : null
}
