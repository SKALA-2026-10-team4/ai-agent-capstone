// trace 이벤트를 사람이 읽는 문장과 항목으로 바꾼다. 원본 data 는 화면에서 접어 둔 채로 따로
// 보여 준다. 이벤트별 data 모양은 order-agent 가 남기는 모양을 따르고, 이벤트를 남기는 곳은
// order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java,
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java,
// order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 다.
// 이벤트 이름, 순서, data 키가 계약이라는 설명은 OrderHarness 의 클래스 주석에 있다.
// frontend/src/components/EventTimeline.vue 가 eventLabel, isNoisy, groupBySkill 을 쓴다.
// Node 단위 테스트(frontend/unit/eventView.test.js, frontend/unit/eventKinds.test.js)가
// 불러올 수 있도록 상대 경로로만 import 한다.
//
// 확장 지점은 EVENT_KINDS 표다. 이벤트 종류를 더하거나 라벨, 숨김, 문장을 바꿀 때는 이 표만
// 고친다. 그 종류가 단계 상태에도 영향을 주면 frontend/src/utils/traceRun.js 의 applyEvent 에
// case 를 더한다. 새 컨텍스트 키나 도구의 화면 이름은 KEY_LABELS 와 TOOL_LABELS 에 더한다.
import { shortUuid, shortenUuidsIn, won } from './format.js'

// 컨텍스트 키와 도구 인자 이름을 화면 이름으로 바꾸는 표다. 표에 없는 키는 원래 이름으로 보인다.
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

// 업무 도구 이름을 한글 이름으로 바꾸는 표다. 도구 목록은
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 에
// 있고, 그 클래스 주석이 새 도구를 더할 때 이 표도 고치라고 안내한다.
const TOOL_LABELS = {
  'order.inspect': '주문 확인',
  'inventory.reserve': '재고 예약',
  'inventory.release': '재고 예약 해제',
  'payment.charge': '결제',
  'shipping.reserve': '배송 예약',
  'notify.send': '고객 알림',
}

// 컨텍스트 키의 화면 이름을 돌려준다.
export const keyLabel = (key) => KEY_LABELS[key] ?? key
// 도구의 화면 이름을 '한글 이름 (원래 이름)' 모양으로 돌려준다. 표에 없으면 원래 이름 그대로다.
export const toolLabel = (tool) => (TOOL_LABELS[tool] ? `${TOOL_LABELS[tool]} (${tool})` : tool)

/**
 * 컨텍스트 값 하나를 짧은 글로 바꾼다. 없는 값은 '없음', 참과 거짓은 '예' 와
 * '아니오', 상품 목록은 'SKU × 수량', 배송은 '국가 · 배송 방식', 금액은 원 표기로
 * 쓴다. 그 밖의 글은 안에 든 UUID 를 줄이고, 객체와 목록은 JSON 글로 보여 준다.
 */
export function formatValue(key, value) {
  if (value === null || value === undefined) return '없음'
  if (typeof value === 'boolean') return value ? '예' : '아니오'
  if (key === 'order.items' || key === 'items') {
    return value.map((i) => `${i.sku} × ${i.qty}`).join(', ')
  }
  if (key === 'order.shipping') return `${value.country} · ${value.express ? '빠른 배송' : '일반 배송'}`
  if ((key === 'order.total' || key === 'amount') && typeof value === 'number') {
    return won(value)
  }
  if (typeof value === 'string') {
    // pay_mock_<uuid> 같은 참조 번호 안의 UUID 도 짧게 줄인다.
    return shortenUuidsIn(value)
  }
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

// { 키: 값 } 객체를 화면 항목 [{ label, value }] 목록으로 바꾼다. 비었거나 null 이면 빈 목록이다.
// describe 의 fields 를 만들고, frontend/src/components/FieldList.vue 가 그 목록을 그린다.
export const fieldsOf = (obj) =>
  Object.entries(obj ?? {}).map(([k, v]) => ({ label: keyLabel(k), value: formatValue(k, v) }))

// 모델 원래 응답(raw_response)을 JSON 으로 읽는다. JSON 이 아니면 null 이다.
function parseModel(raw) {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

// 이벤트 종류 표다. 이벤트 표시를 바꾸거나 새 종류를 더할 때 이 표만 고치면 된다. 항목마다
// 세 칸이 있다. label 은 목록에 보일 한글 라벨이고, order-agent 화면의 라벨 표인
// order-agent/frontend/src/utils/evidenceFormat.js 의 EVENT_KIND_TEXT 와 같은 말을 쓴다.
// 그 표에 없는 SKILL_STARTED 와 SKILL_FINISHED 도 여기에는 있다. noisy 가 참이면 모델
// 입력과 응답처럼 길어서 기본으로 숨긴다. describe 는 data 를 받아 { title, note,
// fields } 를 만들고, fields 는 { label, value } 목록이며 note 는 없을 수 있다.
//
// 표에 없는 종류는 종류 이름이 그대로 라벨과 제목이 된다.
// frontend/unit/eventView.test.js 와 frontend/unit/eventKinds.test.js 가
// 종류 수(23)와 문장을 고정하므로 표를 바꾸면 두 테스트도 고친다.
const EVENT_KINDS = {
  EXECUTION_STARTED: {
    label: '실행 시작',
    describe: (d) => ({ title: `주문 ${shortUuid(d.order_id)} 처리를 시작했다`, note: `${d.mode} 모드`, fields: [] }),
  },
  SNAPSHOT_FIXED: {
    label: '실행 파일 고정',
    describe: (d) => ({
      title: `Skill 파일 세트 ${shortUuid(d.snapshot_id)} 를 고정했다`,
      fields: [{ label: '해시', value: `${d.hash?.slice(0, 16)}…` }],
    }),
  },
  CANDIDATES_OFFERED: {
    label: '실제 제공한 후보',
    noisy: true,
    describe: (d) => ({
      title: `모델에게 Skill 후보 ${d.candidates?.length ?? 0}개를 보여 줬다`,
      fields: (d.candidates ?? []).map((c) => ({ label: c.skill_id, value: `v${c.version} · ${c.description}` })),
    }),
  },
  // purpose 가 select 면 진입 Skill 선택이고, 그 밖에는 단계의 도구 호출 판단이다.
  DECISION_INPUT: {
    label: '실행기에 제공한 입력',
    noisy: true,
    describe: (d) =>
      d.purpose === 'select'
        ? { title: '모델에게 시작할 Skill 을 고르게 했다', fields: fieldsOf(d.inputs) }
        : {
            title: `모델에게 ${toolLabel(d.tool_definition?.name)} 호출을 판단하게 했다`,
            note: '이 단계가 받은 입력',
            fields: fieldsOf(d.inputs),
          },
  },
  MODEL_REQUEST: {
    label: 'API 호출',
    noisy: true,
    describe: (d) => ({ title: `모델 API 호출 (${d.attempt}번째 시도)`, fields: [] }),
  },
  // 응답이 Skill 선택이면 고른 이름을, 도구 호출이면 도구와 인자를, 둘
  // 다 아니면 원문을 보여 준다. 토큰 수가 없으면 미관측으로 적는다.
  MODEL_RESPONSE: {
    label: '원래 응답',
    noisy: true,
    describe: (d) => {
      const m = parseModel(d.raw_response)
      const tokens = d.input_tokens == null ? '토큰 미관측' : `토큰 ${d.input_tokens}/${d.output_tokens}`
      if (m?.skill) return { title: `모델이 '${m.skill}' 를 골랐다`, note: tokens, fields: [] }
      if (m?.tool) return { title: `모델이 ${toolLabel(m.tool)} 호출을 정했다`, note: tokens, fields: fieldsOf(m.arguments) }
      return { title: '모델 원래 응답', note: tokens, fields: [{ label: '응답', value: d.raw_response }] }
    },
  },
  DECISION_ERROR: {
    label: '응답 오류',
    describe: (d) => ({ title: `모델 응답 오류 ${d.category} (${d.attempt}번째 시도)`, fields: [] }),
  },
  SKILL_SELECTED: {
    label: 'Skill 선택',
    describe: (d) => ({ title: `선택된 이름: '${d.raw_name}'`, fields: [] }),
  },
  SKILL_LOAD_REJECTED: {
    label: '파일 로드 거절',
    describe: (d) => ({ title: `'${d.requested_name}' 파일을 불러오지 않았다`, note: d.category, fields: [] }),
  },
  SKILL_RESOLVED: {
    label: 'Skill 이름 해석',
    describe: (d) => ({ title: `'${d.raw_name}' → ${d.resolved_skill_id} 로 확인했다`, fields: [] }),
  },
  SKILL_LOADED: {
    label: '실제 파일 로드',
    describe: (d) => ({
      title: `${d.path} (v${d.version}) 를 불러왔다`,
      fields: [{ label: '파일 해시', value: `${d.hash?.slice(0, 16)}…` }],
    }),
  },
  SKILL_STARTED: {
    label: '단계 시작',
    describe: (d) => ({
      title: `${d.step_no}단계를 시작했다 (v${d.version})`,
      note: '이 단계가 받은 입력',
      fields: fieldsOf(d.input),
    }),
  },
  // status 가 error 면 오류로, business_stop 이 참이면 업무 규칙에 따른
  // 멈춤으로, 그 밖에는 정상 완료로 쓴다. 걸린 시간이 없으면 note 는 null 이다.
  SKILL_FINISHED: {
    label: '단계 끝',
    describe: (d) => {
      const time = d.duration_ms != null ? `${d.duration_ms} ms` : null
      if (d.status === 'error') return { title: `${d.step_no}단계가 오류로 끝났다: ${d.category}`, note: time, fields: fieldsOf(d.output) }
      if (d.business_stop) {
        return { title: `${d.step_no}단계에서 업무 규칙대로 멈췄다: ${d.final_status}`, note: time, fields: fieldsOf(d.output) }
      }
      return { title: `${d.step_no}단계를 마쳤다`, note: time, fields: fieldsOf(d.output) }
    },
  },
  FLOW_VALIDATED: {
    label: '흐름 계약 검사',
    describe: (d) => ({ title: '흐름 계약 검사를 통과했다', note: (d.steps ?? []).join(' → '), fields: [] }),
  },
  TOOL_REQUEST: {
    label: '도구 요청',
    describe: (d) => ({
      title: `${toolLabel(d.tool)} 요청`,
      note: d.reason ? `사유: ${d.reason}` : null,
      fields: fieldsOf(d.arguments),
    }),
  },
  // 결과의 주문 상태(final_status)와 걸린 시간을 note 로 붙이고, 결과
  // 컨텍스트를 항목으로 보여 준다.
  TOOL_RESULT: {
    label: '도구 실행 결과',
    describe: (d) => {
      const status = d.result?.final_status
      return {
        title: `${toolLabel(d.tool)} 결과`,
        note: [status && `주문 상태 ${status}`, d.elapsed_ms != null && `${d.elapsed_ms} ms`].filter(Boolean).join(' · '),
        // inventory.release 결과는 context 없이 바로 값이 온다.
        fields: fieldsOf(d.result?.context ?? (status ? {} : d.result)),
      }
    },
  },
  TOOL_ERROR: {
    label: '도구 오류',
    describe: (d) => ({ title: `${toolLabel(d.tool)} 실패`, note: d.category, fields: fieldsOf(d.arguments) }),
  },
  TOOL_REPLAY: {
    label: '멱등 결과 재사용',
    describe: (d) => ({ title: `${toolLabel(d.tool)} — 이미 실행한 결과를 다시 썼다`, fields: fieldsOf(d.arguments) }),
  },
  SKILL_COMPLETED: {
    label: '단계 결과 전달',
    describe: (d) => ({ title: '다음 단계로 넘긴 값', fields: fieldsOf(d.declared_output) }),
  },
  // REJECTED 는 재고 부족 같은 정상 거절로, 그 밖의 상태는 결제 거절 같은 정상 보류로 설명한다.
  BUSINESS_STOP: {
    label: '정상 업무 종료',
    describe: (d) => ({
      title: `업무 규칙대로 멈췄다: ${d.final_status}`,
      note: d.final_status === 'REJECTED' ? '재고 부족 등 정상적인 거절' : '결제 거절 등 정상적인 보류',
      fields: [],
    }),
  },
  // 검사 단계, 못 받은 입력, 끝나지 않은 앞 단계, 사유 가운데 있는 것만 항목으로 보여 준다.
  // phase 가 static_validation 이면 실행 전 정적 검사이고, 그 밖에는 실행 중 입력 검사다.
  EXECUTION_ERROR: {
    label: '실행 오류',
    describe: (d) => {
      const fields = []
      if (d.phase) fields.push({ label: '검사 단계', value: d.phase === 'static_validation' ? '실행 전 정적 검사' : '실행 중 입력 검사' })
      if (d.missing_inputs?.length) fields.push({ label: '못 받은 입력', value: d.missing_inputs.join(', ') })
      if (d.missing_dependencies?.length) fields.push({ label: '끝나지 않은 앞 단계', value: d.missing_dependencies.join(', ') })
      if (d.reason) fields.push({ label: '사유', value: d.reason })
      return { title: `실행 오류: ${d.category}`, note: d.message, fields }
    },
  },
  EXECUTION_INTERRUPTED: {
    label: '중단 실행 복구',
    describe: () => ({ title: '서버 재시작으로 중단된 실행을 정리했다', fields: [] }),
  },
  EXECUTION_FINISHED: {
    label: '실행 종료',
    describe: (d) => ({
      title: `실행 끝: ${d.final_status ?? '상태 없음'}`,
      note: [d.completion, d.error_category].filter(Boolean).join(' · '),
      fields: [],
    }),
  },
}

// 이벤트 종류마다 한글 라벨이다. EVENT_KINDS 의 label 로 만든다.
export const EVENT_LABELS = Object.fromEntries(Object.entries(EVENT_KINDS).map(([kind, { label }]) => [kind, label]))
// 기본으로 숨기는 종류의 집합이다. EVENT_KINDS 의 noisy 로 만든다.
const NOISY = new Set(Object.keys(EVENT_KINDS).filter((kind) => EVENT_KINDS[kind].noisy))

// 목록에 보일 이벤트 이름을 돌려준다. 표에 없는 종류는 원래 이름 그대로다.
// frontend/src/components/EventTimeline.vue 가 이벤트마다 부른다.
export const eventLabel = (kind) => EVENT_LABELS[kind] ?? kind

// 모델 입력이나 원래 응답처럼 길어서 기본으로 숨기는 종류인지 본다.
// frontend/src/components/EventTimeline.vue 는 모델 입력과 응답
// 보기를 끄면 이 종류를 목록에서 뺀다.
export const isNoisy = (kind) => NOISY.has(kind)

// 오류 이벤트인지 종류 이름으로 정한다. 이름에 ERROR 나 REJECTED 가 들어 있으면 오류로 보고,
// 화면은 그 이벤트를 강조한다. 표와 따로 이름 규칙을 쓰므로 새 오류 종류도 이름만 맞추면 강조된다.
export const isErrorEvent = (e) => e.kind.includes('ERROR') || e.kind.includes('REJECTED')

/**
 * 이벤트 하나를 { title, note, fields } 문장으로 바꾼다. data 가 없으면 빈 객체로 읽는다. 표에
 * 없는 종류는 종류 이름을 제목으로 쓰고, Object.hasOwn 으로 찾으므로 constructor 같은
 * Object 속성 이름도 표에 없는 종류로 본다. groupBySkill 이 이벤트마다 부른다.
 */
export function describe(e) {
  const d = e.data ?? {}
  return Object.hasOwn(EVENT_KINDS, e.kind) ? EVENT_KINDS[e.kind].describe(d) : { title: e.kind, fields: [] }
}

/**
 * 연속된 같은 skill 의 이벤트를 한 묶음으로 모으고, 이벤트마다 문장(view)과 오류 여부(error)를
 * 붙인다. skill 이 없는 이벤트는 skill 이 null 인 묶음이 되고, 화면은 그 묶음을 실행 준비와
 * 마무리로 보여 준다. frontend/src/components/EventTimeline.vue 가 숨김을 뺀 이벤트로 부른다.
 */
export function groupBySkill(events) {
  const out = []
  for (const e of events) {
    const key = e.skill_id ?? null
    if (!out.length || out.at(-1).skill !== key) out.push({ skill: key, events: [] })
    out.at(-1).events.push({ ...e, view: describe(e), error: isErrorEvent(e) })
  }
  return out
}
