// order-agent trace(events) → skill 별 실행 상태. 캔버스 overlay 와 실행 화면이 쓴다.
// 이벤트 종류와 data 모양은 order-agent TraceService·OrderHarness·OrderToolService 기준.

// order-agent 화면(ExecutionInspector)과 같은 한글 라벨
export const EVENT_LABELS = {
  EXECUTION_STARTED: '실행 시작',
  SNAPSHOT_FIXED: '실행 파일 고정',
  CANDIDATES_OFFERED: '실제 제공한 후보',
  DECISION_INPUT: '실행기에 제공한 입력',
  MODEL_REQUEST: 'API 호출',
  MODEL_RESPONSE: '원래 응답',
  DECISION_ERROR: '응답 오류',
  SKILL_SELECTED: 'Skill 선택',
  SKILL_LOAD_REJECTED: '파일 로드 거절',
  SKILL_RESOLVED: 'Skill 이름 해석',
  SKILL_LOADED: '실제 파일 로드',
  SKILL_STARTED: '단계 시작',
  SKILL_FINISHED: '단계 끝',
  FLOW_VALIDATED: '흐름 계약 검사',
  TOOL_REQUEST: '도구 요청',
  TOOL_RESULT: '도구 실행 결과',
  TOOL_ERROR: '도구 오류',
  TOOL_REPLAY: '멱등 결과 재사용',
  SKILL_COMPLETED: '단계 결과 전달',
  BUSINESS_STOP: '정상 업무 종료',
  EXECUTION_ERROR: '실행 오류',
  EXECUTION_INTERRUPTED: '중단 실행 복구',
  EXECUTION_FINISHED: '실행 종료',
}

// 단계 상태 → 담당자가 읽는 말
export const RUN_LABEL = {
  ok: '완료',
  stopped: '업무 규칙으로 멈춤',
  error: '오류',
  running: '실행 중',
  blocked: '검사에서 막혀 실행 안 됨',
  skipped: '실행 안 됨',
}

export const isErrorEvent = (e) => e.kind.includes('ERROR') || e.kind.includes('REJECTED')

// 단계 상태: ok(완료) · stopped(정상 업무 종료 REJECTED/HOLD) · error · running · blocked(정적 검사에서 차단돼 미실행) · skipped
export function traceRun(trace, steps) {
  const events = trace?.events ?? []
  const bySkill = {}
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
    })

  let error = null
  for (const e of events) {
    const s = e.skill_id ? ensure(e.skill_id) : null
    switch (e.kind) {
      case 'SKILL_LOADED':
        if (s) s.loaded = e.data
        break
      // #39 이후 실행: 단계의 실제 입력·출력·상태·시간. 이전 실행에는 없다.
      case 'SKILL_STARTED':
        if (s) s.started = e.data
        break
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
        error = e.data
        if (s) {
          s.status = 'error'
          s.error = e.data
        }
        break
    }
  }

  // 오류에 skill_id 가 없으면(INVALID_TOOL_ARGUMENTS·MISSING_SKILL_OUTPUT 등) 끝나지 못한 마지막 단계가 실패한 단계다.
  if (error && !error.skill_id) {
    const last = steps.findLast((id) => (bySkill[id]?.loaded || bySkill[id]?.started) && !bySkill[id].status)
    if (last) {
      bySkill[last].status = 'error'
      bySkill[last].error = error
    }
  }

  const blocked = error?.phase === 'static_validation'
  for (const id of steps) {
    const s = ensure(id)
    if (s.status) continue
    if (s.loaded && trace?.completion === 'RUNNING') s.status = 'running'
    else s.status = blocked ? 'blocked' : 'skipped'
  }
  return { bySkill, error }
}

// 실행 목록 한 줄 라벨
export const executionLabel = (x) =>
  `${x.started_at?.slice(5, 16).replace('T', ' ')} · ${x.final_status ?? x.completion}` +
  `${x.error_category ? ` · ${x.error_category}` : ''} · ${x.execution_id.slice(0, 8)}`
