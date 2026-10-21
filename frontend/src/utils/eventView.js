// trace 이벤트를 사람이 읽는 문장과 항목으로 바꾼다. 원본 data 는 화면에서 접어 둔 채로 따로 보여 준다.
// 이벤트별 data 모양은 order-agent TraceService·OrderHarness·OrderToolService 기준.

// 컨텍스트 키·도구 인자 이름 → 화면 이름
const KEY_LABELS = {
  'order.id': '주문 번호',
  'order.customer': '고객',
  'order.items': '상품',
  'order.payment_method': '결제 방식',
  'order.shipping': '배송',
  'order.total': '주문 금액',
  'inventory.available': '재고 충분',
  'inventory.reservable_quantity': '예약 가능 수량',
  'inventory.reserved': '재고 예약됨',
  'payment.id': '결제 번호',
  'payment.status': '결제 상태',
  'shipping.id': '배송 번호',
  'shipping.eta': '도착 예정일',
  'notify.sent': '알림 보냄',
  'notify.channel': '알림 채널',
  order_id: '주문 번호',
  items: '상품',
  amount: '금액',
  method: '결제 방식',
  country: '배송 국가',
  express: '빠른 배송',
  customer: '고객',
}

const TOOL_LABELS = {
  'order.inspect': '주문 확인',
  'inventory.reserve': '재고 예약',
  'inventory.release': '재고 예약 해제',
  'payment.charge': '결제',
  'shipping.reserve': '배송 예약',
  'notify.send': '고객 알림',
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const short = (id) => (typeof id === 'string' && UUID.test(id) ? `${id.slice(0, 8)}…` : id)

export const keyLabel = (key) => KEY_LABELS[key] ?? key
export const toolLabel = (tool) => (TOOL_LABELS[tool] ? `${TOOL_LABELS[tool]} (${tool})` : tool)

// 값 하나를 짧은 글로
export function formatValue(key, value) {
  if (value === null || value === undefined) return '없음'
  if (typeof value === 'boolean') return value ? '예' : '아니오'
  if (key === 'order.items' || key === 'items') {
    return value.map((i) => `${i.sku} × ${i.qty}`).join(', ')
  }
  if (key === 'order.shipping') return `${value.country} · ${value.express ? '빠른 배송' : '일반 배송'}`
  if ((key === 'order.total' || key === 'amount') && typeof value === 'number') {
    return `${value.toLocaleString()}원`
  }
  if (typeof value === 'string') {
    // pay_mock_<uuid> 같은 참조 번호도 짧게
    return value.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, (m) => short(m))
  }
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

// { key: value } → [{ label, value }]
export const fieldsOf = (obj) =>
  Object.entries(obj ?? {}).map(([k, v]) => ({ label: keyLabel(k), value: formatValue(k, v) }))

function parseModel(raw) {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

// → { title, note?, fields: [{label, value}] }
export function describe(e) {
  const d = e.data ?? {}
  switch (e.kind) {
    case 'EXECUTION_STARTED':
      return { title: `주문 ${short(d.order_id)} 처리를 시작했다`, note: `${d.mode} 모드`, fields: [] }
    case 'SNAPSHOT_FIXED':
      return { title: `Skill 파일 세트 ${short(d.snapshot_id)} 를 고정했다`, fields: [{ label: '해시', value: `${d.hash?.slice(0, 16)}…` }] }
    case 'CANDIDATES_OFFERED':
      return {
        title: `모델에게 Skill 후보 ${d.candidates?.length ?? 0}개를 보여 줬다`,
        fields: (d.candidates ?? []).map((c) => ({ label: c.skill_id, value: `v${c.version} · ${c.description}` })),
      }
    case 'DECISION_INPUT':
      return d.purpose === 'select'
        ? { title: '모델에게 시작할 Skill 을 고르게 했다', fields: fieldsOf(d.inputs) }
        : {
            title: `모델에게 ${toolLabel(d.tool_definition?.name)} 호출을 판단하게 했다`,
            note: '이 단계가 받은 입력',
            fields: fieldsOf(d.inputs),
          }
    case 'MODEL_REQUEST':
      return { title: `모델 API 호출 (${d.attempt}번째 시도)`, fields: [] }
    case 'MODEL_RESPONSE': {
      const m = parseModel(d.raw_response)
      const tokens = d.input_tokens == null ? '토큰 미관측' : `토큰 ${d.input_tokens}/${d.output_tokens}`
      if (m?.skill) return { title: `모델이 '${m.skill}' 를 골랐다`, note: tokens, fields: [] }
      if (m?.tool) return { title: `모델이 ${toolLabel(m.tool)} 호출을 정했다`, note: tokens, fields: fieldsOf(m.arguments) }
      return { title: '모델 원래 응답', note: tokens, fields: [{ label: '응답', value: d.raw_response }] }
    }
    case 'DECISION_ERROR':
      return { title: `모델 응답 오류 ${d.category} (${d.attempt}번째 시도)`, fields: [] }
    case 'SKILL_SELECTED':
      return { title: `선택된 이름: '${d.raw_name}'`, fields: [] }
    case 'SKILL_RESOLVED':
      return { title: `'${d.raw_name}' → ${d.resolved_skill_id} 로 확인했다`, fields: [] }
    case 'SKILL_LOAD_REJECTED':
      return { title: `'${d.requested_name}' 파일을 불러오지 않았다`, note: d.category, fields: [] }
    case 'SKILL_LOADED':
      return { title: `${d.path} (v${d.version}) 를 불러왔다`, fields: [{ label: '파일 해시', value: `${d.hash?.slice(0, 16)}…` }] }
    case 'SKILL_STARTED':
      return {
        title: `${d.step_no}단계를 시작했다 (v${d.version})`,
        note: '이 단계가 받은 입력',
        fields: fieldsOf(d.input),
      }
    case 'SKILL_FINISHED': {
      const time = d.duration_ms != null ? `${d.duration_ms} ms` : null
      if (d.status === 'error') return { title: `${d.step_no}단계가 오류로 끝났다: ${d.category}`, note: time, fields: fieldsOf(d.output) }
      if (d.business_stop) {
        return { title: `${d.step_no}단계에서 업무 규칙대로 멈췄다: ${d.final_status}`, note: time, fields: fieldsOf(d.output) }
      }
      return { title: `${d.step_no}단계를 마쳤다`, note: time, fields: fieldsOf(d.output) }
    }
    case 'FLOW_VALIDATED':
      return { title: '흐름 계약 검사를 통과했다', note: (d.steps ?? []).join(' → '), fields: [] }
    case 'TOOL_REQUEST':
      return {
        title: `${toolLabel(d.tool)} 요청`,
        note: d.reason ? `사유: ${d.reason}` : null,
        fields: fieldsOf(d.arguments),
      }
    case 'TOOL_RESULT': {
      const status = d.result?.final_status
      return {
        title: `${toolLabel(d.tool)} 결과`,
        note: [status && `주문 상태 ${status}`, d.elapsed_ms != null && `${d.elapsed_ms} ms`].filter(Boolean).join(' · '),
        // inventory.release 결과는 context 없이 바로 값이 온다.
        fields: fieldsOf(d.result?.context ?? (status ? {} : d.result)),
      }
    }
    case 'TOOL_ERROR':
      return { title: `${toolLabel(d.tool)} 실패`, note: d.category, fields: fieldsOf(d.arguments) }
    case 'TOOL_REPLAY':
      return { title: `${toolLabel(d.tool)} — 이미 실행한 결과를 다시 썼다`, fields: fieldsOf(d.arguments) }
    case 'SKILL_COMPLETED':
      return { title: '다음 단계로 넘긴 값', fields: fieldsOf(d.declared_output) }
    case 'BUSINESS_STOP':
      return {
        title: `업무 규칙대로 멈췄다: ${d.final_status}`,
        note: d.final_status === 'REJECTED' ? '재고 부족 등 정상적인 거절' : '결제 거절 등 정상적인 보류',
        fields: [],
      }
    case 'EXECUTION_ERROR': {
      const fields = []
      if (d.phase) fields.push({ label: '검사 단계', value: d.phase === 'static_validation' ? '실행 전 정적 검사' : '실행 중 입력 검사' })
      if (d.missing_inputs?.length) fields.push({ label: '못 받은 입력', value: d.missing_inputs.join(', ') })
      if (d.missing_dependencies?.length) fields.push({ label: '끝나지 않은 앞 단계', value: d.missing_dependencies.join(', ') })
      if (d.reason) fields.push({ label: '사유', value: d.reason })
      return { title: `실행 오류: ${d.category}`, note: d.message, fields }
    }
    case 'EXECUTION_INTERRUPTED':
      return { title: '서버 재시작으로 중단된 실행을 정리했다', fields: [] }
    case 'EXECUTION_FINISHED':
      return {
        title: `실행 끝: ${d.final_status ?? '상태 없음'}`,
        note: [d.completion, d.error_category].filter(Boolean).join(' · '),
        fields: [],
      }
    default:
      return { title: e.kind, fields: [] }
  }
}
