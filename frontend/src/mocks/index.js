// 서버 없이 화면을 띄우는 메모리 mock 이다. npm run dev:mock 으로 띄우면
// frontend/src/api/client.js 의 requestMock 이 이 파일의 handleMock 을 동적 import 해서
// 부른다. 상태는 메모리에만 있으므로 새로고침하면 처음으로 돌아간다. order-agent 경로의
// 응답 형식과 이벤트 순서는 실제 order-agent(2026-10-21 develop)에서 받은 응답에 맞췄고,
// service 경로는 serviceRoutes 위 주석에 적은 service 컨트롤러의 규칙을 따른다.
//
// 주문 실행은 Skill 편집 화면의 계약 점검과 같은 buildGraph(frontend/src/utils/flowGraph.js)로 정적
// 계약 검사를 하고, 통과하면 단계마다 업무 도구를 흉내 낸다. 단계 Skill 의 실행 규칙(rules)은 도구
// 전에 평가해 맞으면 주문을 보류하거나 거절한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2 절). Skill 저장 경로인
// POST /api/snapshots/edits 와 진단 조회 경로(/service/api/diagnoses, /api/skill-changes)는 흉내
// 내지 않는다. 그 화면들은 frontend/src/api/client.js 의 USE_MOCK 을 보고 요청을 보내지 않는다.
//
// 실서버의 이벤트 이름이나 순서가 바뀌면 execute 를, 경로나 응답
// 모양이 바뀌면 orderAgentRoutes 와 serviceRoutes 를 함께 고친다.
import { ApiError } from '@/api/error'
import { holds, readCondition } from '@/utils/condition'
import { buildGraph } from '@/utils/flowGraph'
import { ENTRY_SKILL, flowRuleProblems, flowSteps, toSkill } from '@/utils/skillSet'
import { PUBLISHED_FILES, fixtureFiles, prepaidPolicyFiles } from './skillFiles'
import proposalB01 from './proposal-b01.json'
import proposalF1 from './proposal-f1.json'
import proposalW1 from './proposal-w1.json'

// 실서버처럼 응답이 조금 늦게 오도록 요청마다 기다리는 시간(밀리초)이다. handleMock 이 쓴다.
const MOCK_LATENCY_MS = 120

// 작은 도우미 함수들이다. clone 은 저장된 상태를 응답으로 내보낼 때 복사해 화면 쪽 변경이
// mock 상태로 새지 않게 한다. uuid 는 snapshot, 주문, 실행, 요청 키, 승인 토큰의 id 를 만든다.
const clone = (v) => structuredClone(v)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const now = () => new Date().toISOString()
const uuid = () => crypto.randomUUID()

// text 의 SHA-256 을 16진수 글자로 돌려준다. 서버처럼 파일과 snapshot 의 hash 를 내용에서 만든다.
async function sha256(text) {
  const bytes = new TextEncoder().encode(text)
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

// 상품 표다. 재고 예약이 성공하면 stock_quantity 가 줄어든다. CAMP-MUG-STEEL 은 재고가
// 0 이라 재고 부족 시나리오(frontend/src/views/OrderView.vue 의 PRESETS)에 쓰인다.
const products = [
  { sku: 'CAMP-LANTERN-LED', name: 'LED 캠핑 랜턴', unit_price: 30000, stock_quantity: 10 },
  { sku: 'CAMP-CHAIR-FOLD', name: '접이식 캠핑 의자', unit_price: 45000, stock_quantity: 5 },
  { sku: 'CAMP-MUG-STEEL', name: '스테인리스 캠핑 머그컵', unit_price: 12000, stock_quantity: 0 },
]
// 메모리 저장소다. snapshots 는 id 마다 { snapshot_id, hash, created_at, metadata,
// files } 이고 files 는 경로마다 { content, hash } 다. orders 와 executions 는 id 마다 주문과
// 실행 기록을 두고, proposals 는 frontend/src/mocks/proposal-f1.json(입력·출력 연결이 끊긴 결함)과
// frontend/src/mocks/proposal-b01.json(본문 정책을 실행 규칙으로 옮기는 encode_rule 후보),
// frontend/src/mocks/proposal-w1.json(notify 가 어떤 도구도 만들지 않는 shipping.weather 를 요구해 후보 대신
// 개발 요청서를 낸 no_valid_fix) 세 건으로 시작한다.
const snapshots = {}
const orders = {}
const executions = {}
const proposals = {
  [proposalF1.proposal_id]: clone(proposalF1),
  [proposalB01.proposal_id]: clone(proposalB01),
  [proposalW1.proposal_id]: clone(proposalW1),
}

// 파일 묶음을 새 snapshot 으로 저장하고 manifest 를 돌려준다. 파일 hash 목록과 metadata 로 묶음
// hash 를 만든다. publishFixture 와 createOrder(세트를 고르지 않은 주문의 게시본 실행)가 부른다.
async function publish(files, metadata) {
  const id = uuid()
  const entries = {}
  for (const [path, content] of Object.entries(files)) entries[path] = { content, hash: await sha256(content) }
  const hashes = Object.keys(entries).sort().map((p) => entries[p].hash)
  const hash = await sha256(JSON.stringify({ files: hashes, metadata }))
  snapshots[id] = { snapshot_id: id, hash, created_at: now(), metadata, files: entries }
  return manifest(snapshots[id])
}

// 준비된 fixture 를 새 snapshot 으로 게시한다. POST /api/snapshots 처리기와 첫 요청
// 때의 seed 가 부른다. 파일은 frontend/src/mocks/skillFiles.js 의 fixtureFiles 가
// 만들고, metadata 는 서버와 같은 { source: 'demo-fixture', fixture } 다.
const publishFixture = (fixture) => publish(fixtureFiles(fixture), { source: 'demo-fixture', fixture })

// snapshot 에서 SKILL.md 파일만 골라 [경로, { content, hash }] 목록으로 돌려준다.
const skillFilesOf = (snapshot) => Object.entries(snapshot.files).filter(([p]) => p.endsWith('/SKILL.md'))

// 글의 UTF-8 바이트 수다. manifest 와 파일 응답의 size 를 서버처럼 바이트로 채운다.
const byteSize = (text) => new TextEncoder().encode(text).length

// 저장된 snapshot 을 GET /api/snapshots/{id} 응답 모양으로 바꾼다.
// files 는 경로 순의 { path, hash, size } 목록이다.
const manifest = (s) => ({
  snapshot_id: s.snapshot_id,
  hash: s.hash,
  created_at: s.created_at,
  metadata: s.metadata,
  files: Object.keys(s.files)
    .sort()
    .map((path) => ({ path, hash: s.files[path].hash, size: byteSize(s.files[path].content) })),
})

// ── 주문 실행 (order-agent OrderHarness 흉내) ─────────────────────────────

// 단계 Skill 마다 부르는 업무 도구 이름이다. 서버의 바인딩 표인
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 의
// BINDINGS 를 흉내 낸다. 여기 없는 단계 Skill 은 execute 가 UNSUPPORTED_SKILL 로 실패시킨다.
const TOOLS = {
  'order-intake': 'order.inspect',
  'inventory-check': 'inventory.reserve',
  payment: 'payment.charge',
  'shipping-reserve': 'shipping.reserve',
  notify: 'notify.send',
}
// 단계 Skill 마다 주문 응답의 처리 단계(order.steps 의 step) 종류다. 서버의 처리 단계 종류
// enum 을 흉내 내고, 그 enum 은
// order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java 다.
const STEP_TYPE = {
  'order-intake': 'INTAKE',
  'inventory-check': 'INVENTORY',
  payment: 'PAYMENT',
  'shipping-reserve': 'SHIPPING',
  notify: 'NOTIFICATION',
}
// 앞 단계가 거절돼 건너뛴 처리 단계에 남기는 문구다.
const SKIP_MESSAGE = '앞 단계 거절로 실행하지 않았습니다.'
// 실행 규칙으로 멈춰 건너뛴 처리 단계에 남기는 문구다.
const RULE_SKIP_MESSAGE = '업무 규칙으로 실행하지 않았습니다.'

/**
 * 단계 Skill 의 실행 규칙을 위에서부터 보고 처음 맞는 규칙의 번호(0부터)를 돌려준다. 없으면 -1 이다.
 * 평가 문맥은 그 단계의 선언 입력 { 키: 값 } 이다. 서버 쪽 대응은 OrderHarness.runStep 의 규칙
 * 평가이고, 조건 문법은 frontend/src/utils/condition.js 가 읽는다. execute 가 단계마다 부른다.
 */
function matchedRule(skill, inputs) {
  return (skill.rules ?? []).findIndex((r) => {
    const condition = readCondition(r.when)
    return !!condition && holds(condition, inputs)
  })
}

/**
 * 업무 도구 하나를 흉내 내고 { context, final_status } 를 돌려준다. context 는 다음 단계가 읽을
 * 키와 값이고, final_status 가 PROCESSING 이 아니면 업무 종료다. 재고 예약은 모든 상품의 재고가
 * 충분할 때만 재고를 실제로 줄이고 그 결과를 order._reserved 에 남긴다. 고객 c_declined 의 카드
 * 결제는 거절(HOLD)된다. 서버 쪽 대응은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의
 * applyTool 이다. execute 가 단계마다 부른다.
 */
function runTool(order, tool, ctx) {
  const id = order.order_id
  switch (tool) {
    case 'order.inspect':
      return { context: { ...ctx, 'order.total': order.total_amount }, final_status: 'PROCESSING' }
    case 'inventory.reserve': {
      const lines = order.items.map((i) => [products.find((p) => p.sku === i.sku), i.qty])
      const reserved = lines.every(([p, qty]) => p.stock_quantity >= qty)
      const available = Math.min(...lines.map(([p]) => p.stock_quantity))
      if (reserved) lines.forEach(([p, qty]) => (p.stock_quantity -= qty))
      order._reserved = reserved
      return {
        context: {
          'inventory.available': reserved,
          'inventory.reservable_quantity': available,
          'inventory.reserved': reserved,
        },
        final_status: reserved ? 'PROCESSING' : 'REJECTED',
      }
    }
    case 'payment.charge': {
      const paid = !(order.customer === 'c_declined' && order.payment_method === 'card')
      return {
        context: paid
          ? { 'payment.status': 'PAID', 'payment.id': `pay_mock_${id}` }
          : { 'payment.status': 'DECLINED' },
        final_status: paid ? 'PROCESSING' : 'HOLD',
      }
    }
    case 'shipping.reserve': {
      const days = order.shipping.country === 'KR' ? 2 : 7
      const eta = new Date(Date.now() + days * 864e5).toISOString().slice(0, 10)
      return { context: { 'shipping.id': `ship_mock_${id}`, 'shipping.eta': eta }, final_status: 'PROCESSING' }
    }
    case 'notify.send':
      return { context: { 'notify.sent': true, 'notify.channel': 'mock' }, final_status: 'PROCESSING' }
  }
}

// 도구에 넘길 인자를 컨텍스트에서 만든다. 서버 ToolCatalog 의 인자 채우기 규칙을 흉내 내고,
// 만든 인자는 trace 의 MODEL_RESPONSE, TOOL_REQUEST, TOOL_RESULT 이벤트에 그대로 남는다.
function toolArgs(tool, ctx) {
  const args = { order_id: ctx['order.id'] }
  if (tool === 'inventory.reserve') args.items = ctx['order.items']
  if (tool === 'payment.charge') {
    Object.assign(args, { amount: ctx['order.total'], method: ctx['order.payment_method'] })
  }
  if (tool === 'shipping.reserve') Object.assign(args, ctx['order.shipping'])
  if (tool === 'notify.send') args.customer = ctx['order.customer']
  return args
}

// 성공한 도구 결과를 주문 응답의 payment, shipment, notification 칸에
// 반영한다. 이벤트는 남기지 않는다. execute 가 도구가 성공한 뒤 부른다.
function applyToolEffects(order, tool, context) {
  if (tool === 'payment.charge') {
    order.payment = { status: 'PAID', reference: context['payment.id'] }
  }
  if (tool === 'shipping.reserve') {
    order.shipment = {
      reference: context['shipping.id'],
      estimated_delivery_date: context['shipping.eta'],
    }
  }
  if (tool === 'notify.send') order.notification = { status: 'SENT' }
}

// 실행 한 건의 기록을 GET /api/executions/{id}/trace 응답 모양으로 만든다. run_id 는
// 서버처럼 r_ 뒤에 하이픈을 뺀 execution_id 를 붙인 별칭이다. 모델을 부르지 않으므로
// fixed 모드로 적고 토큰 수는 null 로 둔다. execute 의 마지막에 부른다.
function executionRecord({ executionId, order, snapshot, completion, errorCategory, finalStatus, started, events }) {
  return {
    execution_id: executionId,
    run_id: `r_${executionId.replaceAll('-', '')}`,
    order_id: order.order_id,
    snapshot_id: snapshot.snapshot_id,
    snapshot_hash: snapshot.hash,
    agent_id: 'camping-order-agent',
    mode: 'live',
    model_mode: 'fixed',
    model: 'fixed-policy-v1',
    completion,
    raw_selection: ENTRY_SKILL,
    resolved_skill_id: ENTRY_SKILL,
    error_category: errorCategory,
    final_status: finalStatus,
    api_calls: 0,
    input_tokens: null,
    output_tokens: null,
    elapsed_ms: Date.now() - started,
    started_at: events[0].at,
    finished_at: now(),
    events,
  }
}

/**
 * order-agent 의 OrderHarness 실행 루프를 흉내 내어 trace events 와 주문 처리 단계(order.steps)를
 * 만든다. 서버 쪽 원본은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 다.
 *
 * 흐름은 네 부분이다. 먼저 실행 준비 이벤트(EXECUTION_STARTED 부터 진입 Skill 의
 * SKILL_LOADED 까지)를 남긴다. 다음으로 buildGraph 로 정적 계약 검사를 해서, 읽지 못한
 * Skill 이나 계약 위반이 있으면 업무 도구를 하나도 실행하지 않고 실패한다. 통과하면
 * FLOW_VALIDATED 를 남기고 steps 순서대로 단계마다 SKILL_LOADED, SKILL_STARTED,
 * DECISION_INPUT, MODEL_RESPONSE, TOOL_REQUEST, TOOL_RESULT, SKILL_COMPLETED,
 * SKILL_FINISHED 를 남긴다. 재고
 * 부족(REJECTED)과 결제 거절(HOLD)은 실패가 아닌 업무 종료(BUSINESS_STOP)로 끝낸다. 단계의 실행
 * 규칙이 맞으면 도구를 부르지 않고 RULE_MATCHED, (결제 전이면) 재고 해제, SKILL_FINISHED(business_stop),
 * BUSINESS_STOP 순서로 남기고 업무 종료한다. 규칙 조건이 선언 입력이 아닌 값을 보면 정적 검사에서
 * CONTRACT_VIOLATION(reason RULE_KEY_NOT_INPUT)으로 막는다.
 * 마지막에 EXECUTION_FINISHED 를 남기고 실행 기록을 executions 에 넣는다.
 *
 * order 객체를 직접 고친다. 서버의 이벤트 이름이나 순서가 바뀌면 이
 * 함수도 함께 고친다. createOrder 가 부른다.
 */
async function execute(order, snapshot) {
  const execution_id = uuid()
  const events = []
  const started = Date.now()
  // 이벤트에 sequence 번호와 시각을 붙여 events 에 더한다.
  const ev = (kind, skill_id, data) =>
    events.push({ sequence: events.length + 1, kind, skill_id, data, at: now() })
  // 주문 응답의 처리 단계(order.steps)다. step 이 한 줄씩 더한다.
  const steps = []
  const step = (name, status, result_code, message) =>
    steps.push({ sequence: steps.length + 1, step: name, status, result_code, message, started_at: now(), completed_at: now() })
  // 예약 재고 해제를 inventory.release 요청과 결과 이벤트, RELEASE 처리 단계로 남긴다. 결제
  // 거절과 결제 전 실패 때 부른다. mock 은 기록만 남기고 products 의 재고 수량은 되돌리지 않는다.
  const release = (skillId, reason) => {
    const args = { order_id: order.order_id }
    ev('TOOL_REQUEST', skillId, { tool: 'inventory.release', arguments: args, reason })
    ev('TOOL_RESULT', skillId, { tool: 'inventory.release', arguments: args, result: { 'inventory.reserved': false } })
    step('RELEASE', 'SUCCESS', null, '예약 재고를 모두 복원했습니다.')
  }

  // 세트의 SKILL.md 를 모두 읽고, 첫 컨텍스트를 서버의 시작 키 다섯 개로
  // 채운다. order.total 은 order-intake 단계의 도구 결과로 들어온다.
  const skills = skillFilesOf(snapshot).map(([p, f]) => toSkill(p, f.content, f.hash))
  const ctx = {
    'order.id': order.order_id,
    'order.customer': order.customer,
    'order.items': order.items.map(({ sku, qty }) => ({ sku, qty })),
    'order.payment_method': order.payment_method,
    'order.shipping': order.shipping,
  }

  // 실행 준비 이벤트다. fixed 모드처럼 모델이 늘 진입 Skill(ENTRY_SKILL)을 고른 것으로 남긴다.
  ev('EXECUTION_STARTED', null, { order_id: order.order_id, mode: 'fixed' })
  ev('SNAPSHOT_FIXED', null, { snapshot_id: snapshot.snapshot_id, hash: snapshot.hash })
  ev('CANDIDATES_OFFERED', null, {
    candidates: skills.map((s) => ({
      skill_id: s.skill_id,
      name: s.skill_id,
      description: s.description,
      version: s.version,
    })),
  })
  ev('DECISION_INPUT', null, { purpose: 'select', inputs: ctx, tool_definition: {}, skill_hash: '' })
  ev('MODEL_RESPONSE', null, { raw_response: JSON.stringify({ skill: ENTRY_SKILL }), input_tokens: null, output_tokens: null })
  ev('SKILL_SELECTED', null, { raw_name: ENTRY_SKILL, resolved_skill_id: null })
  ev('SKILL_RESOLVED', null, { raw_name: ENTRY_SKILL, resolved_skill_id: ENTRY_SKILL })
  // 진입 Skill 을 읽지 못하면 SKILL_LOADED 를 남기지 않고 아래에서 그 오류로 끝낸다.
  // 서버 OrderHarness 도 이 Skill 을 load 한 뒤에야 SKILL_LOADED 를 남긴다.
  const flow = skills.find((s) => s.skill_id === ENTRY_SKILL)
  if (flow && !flow.error) ev('SKILL_LOADED', ENTRY_SKILL, { path: flow.path, version: flow.version, hash: flow.file_hash })

  // 정적 계약 검사다. 진입 Skill 의 steps 순서로 buildGraph 를 부른다.
  const orderSteps = flowSteps(skills)
  const graph = buildGraph(skills, orderSteps)
  let completion = 'COMPLETED'
  let errorCategory = null
  let businessStop = false

  // 실행 실패로 끝낸다. 주문을 FAILED 로 두고, 재고를 예약했지만
  // 결제하지 못했으면 예약 해제를 남긴 뒤 EXECUTION_ERROR 를 기록한다.
  const fail = (category, details) => {
    completion = 'FAILED'
    errorCategory = category
    order.status = 'FAILED'
    order.result_code = category
    if (order._reserved && order.payment.status !== 'PAID') release(null, category)
    ev('EXECUTION_ERROR', details.skill_id ?? null, { category, ...details })
  }

  // 서버 SkillLoader 와 같은 순서로 막는다. 진입 Skill 을 읽지 못함, 실행 순서 구조
  // 오류(INVALID_FLOW), 단계 Skill 을 읽지 못함, 계약 위반(CONTRACT_VIOLATION: 실행 규칙 조건 키 RULE_KEY_NOT_INPUT 과
  // 입력·선행 단계 누락을 단계 순서대로) 순이다. 단계 자리에
  // 흐름 Skill 이 오면(NESTED_FLOW_NOT_SUPPORTED) 서버처럼 계약 위반으로 본다.
  const unreadable = graph.nodes.find((n) => n.parse_error)
  const violation = graph.missing[0]
  const ruleProblems = flowRuleProblems(skills)
  const ruleViolation = ruleProblems[0]
  // 서버 validateFlow 는 단계 순서대로 실행 규칙 조건 키(requireRuleKeysDeclared)를 먼저 보고 입력 공급
  // (validateInputs)을 본다. 그래서 더 앞 단계의 문제를, 같은 단계면 실행 규칙 문제를 먼저 쓴다.
  const stepAt = (id) => orderSteps.indexOf(id)
  const ruleFirst = ruleViolation && (!violation || stepAt(ruleViolation.skill_id) <= stepAt(violation.skill_id))
  step('INTAKE', 'SUCCESS', null, '주문 접수 완료')
  if (!flow || flow.error) {
    // 진입 Skill 은 steps 에 없어 그래프 노드가 되지 않으므로 여기서 따로 본다.
    fail(flow ? flow.error : 'UNKNOWN_SKILL', {})
  } else if (graph.flow_errors.length) {
    // validateFlow 가 단계를 하나도 읽기 전에 거절하는 경우다(빈 순서, 12단계 초과, 중복 단계).
    // 서버는 details 없이 INVALID_FLOW 만 던지므로 여기서도 비워 둔다.
    fail('INVALID_FLOW', {})
  } else if (unreadable) {
    fail(unreadable.parse_error === 'NESTED_FLOW_NOT_SUPPORTED' ? 'CONTRACT_VIOLATION' : unreadable.parse_error, {})
  } else if (ruleFirst) {
    // 실행 규칙의 조건 키가 그 단계의 선언 input 이 아니면 업무 도구 실행 전에 막는다. details 는 서버
    // SkillLoader.requireRuleKeysDeclared 와 같은 모양이고, rule_keys 에는 그 단계의 선언하지 않은 키를 모두 담는다.
    const ruleKeys = [...new Set(ruleProblems.filter((r) => r.skill_id === ruleViolation.skill_id).map((r) => r.key))]
    step(STEP_TYPE[ruleViolation.skill_id] ?? 'RECOVERY', 'FAILED', 'CONTRACT_VIOLATION', '주문 처리 중 오류가 발생했습니다.')
    fail('CONTRACT_VIOLATION', {
      skill_id: ruleViolation.skill_id,
      phase: 'static_validation',
      reason: 'RULE_KEY_NOT_INPUT',
      missing_inputs: [],
      missing_dependencies: [],
      rule_keys: ruleKeys,
      message: `실행 규칙 조건의 값 이름이 Skill의 선언 입력이 아닙니다: ${ruleKeys.join(', ')}`,
    })
  } else if (violation) {
    // 정적 계약 검사에서 막혔으므로 업무 도구는 하나도 실행하지 않고,
    // 첫 위반 단계의 처리 단계만 FAILED 로 남긴다.
    step(STEP_TYPE[violation.skill_id] ?? 'RECOVERY', 'FAILED', 'CONTRACT_VIOLATION', '주문 처리 중 오류가 발생했습니다.')
    fail('CONTRACT_VIOLATION', {
      skill_id: violation.skill_id,
      phase: 'static_validation',
      missing_inputs: violation.missing_inputs,
      missing_dependencies: violation.missing_dependencies,
      message: 'Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다.',
    })
  } else {
    // 검사를 통과하면 steps 순서대로 단계를 실행한다.
    ev('FLOW_VALIDATED', ENTRY_SKILL, { steps: orderSteps })
    for (const [i, id] of orderSteps.entries()) {
      const skill = skills.find((s) => s.skill_id === id)
      const tool = TOOLS[id]
      const stepNo = i + 1
      // 단계 끝(SKILL_FINISHED) 이벤트를 남긴다. extra 로 업무 종료 정보나 출력을 덧붙인다.
      const finished = (extra) =>
        ev('SKILL_FINISHED', id, { step_no: stepNo, version: skill.version, status: 'ok', duration_ms: 5, ...extra })
      ev('SKILL_LOADED', id, { path: skill.path, version: skill.version, hash: skill.file_hash })
      ev('SKILL_STARTED', id, {
        step_no: stepNo,
        input: Object.fromEntries(skill.inputs.filter((k) => k in ctx).map((k) => [k, ctx[k]])),
        version: skill.version,
        hash: skill.file_hash,
      })
      // 실행 규칙이 맞으면 도구를 부르지 않고 주문을 멈춘다. hold 는 HOLD, reject 는 REJECTED 이고
      // 결과 코드는 규칙의 reason 이다. 결제 상태는 건드리지 않고, 결제 전이면 예약 재고를 해제한다.
      // 이 단계와 뒤 업무 단계의 처리 단계는 SKIPPED 로 남긴다.
      const ruleIndex = matchedRule(skill, Object.fromEntries(skill.inputs.map((k) => [k, ctx[k]])))
      if (ruleIndex >= 0) {
        const rule = skill.rules[ruleIndex]
        const finalStatus = rule.then === 'reject' ? 'REJECTED' : 'HOLD'
        // data 는 skill_id, rule_index 와 규칙의 when · then · reason 이다.
        ev('RULE_MATCHED', id, { skill_id: id, rule_index: ruleIndex, ...rule })
        if (order._reserved && order.payment.status !== 'PAID') release(id, rule.reason)
        order.status = finalStatus
        order.result_code = rule.reason
        order.message = `업무 규칙 ${rule.reason} 에 따라 주문을 ${finalStatus === 'REJECTED' ? '거절' : '보류'}했습니다.`
        for (const later of orderSteps.slice(i).filter((x) => x !== 'order-intake' && STEP_TYPE[x])) {
          step(STEP_TYPE[later], 'SKIPPED', rule.reason, RULE_SKIP_MESSAGE)
        }
        finished({ business_stop: true, final_status: finalStatus, rule_reason: rule.reason, output: {} })
        ev('BUSINESS_STOP', id, { final_status: finalStatus })
        businessStop = true
        break
      }
      // 도구가 묶이지 않은 단계 Skill 은 서버의 ToolCatalog 처럼 UNSUPPORTED_SKILL 로 실패한다.
      if (!tool) {
        fail('UNSUPPORTED_SKILL', { skill_id: id })
        break
      }
      const args = toolArgs(tool, ctx)
      ev('DECISION_INPUT', id, {
        purpose: 'tool',
        inputs: Object.fromEntries(skill.inputs.map((k) => [k, ctx[k]])),
        tool_definition: { name: tool },
        skill_hash: skill.file_hash,
      })
      ev('MODEL_RESPONSE', id, { raw_response: JSON.stringify({ arguments: args, tool }), input_tokens: null, output_tokens: null })
      ev('TOOL_REQUEST', id, { tool, arguments: args })
      const result = runTool(order, tool, ctx)
      // 실제 서버와 같은 순서로, 결제가 거절되면 재고 해제 요청과
      // 결과가 결제 결과보다 먼저 기록된다.
      if (result.final_status === 'HOLD') {
        order.payment.status = 'DECLINED'
        step('PAYMENT', 'REJECTED', 'PAYMENT_DECLINED', 'Mock 결제가 거절되었습니다.')
        release(id, 'PAYMENT_DECLINED')
      }
      ev('TOOL_RESULT', id, { tool, arguments: args, result, elapsed_ms: 3 })

      // 재고 부족(REJECTED)과 결제 거절(HOLD)은 정상 업무 종료다. 남은
      // 처리 단계를 SKIPPED 로 남기고 BUSINESS_STOP 뒤 루프를 끝낸다.
      if (result.final_status === 'REJECTED' || result.final_status === 'HOLD') {
        const rejected = result.final_status === 'REJECTED'
        order.status = result.final_status
        order.result_code = rejected ? 'OUT_OF_STOCK' : 'PAYMENT_DECLINED'
        if (rejected) step('INVENTORY', 'REJECTED', 'OUT_OF_STOCK', '재고 부족으로 전체 예약을 거절했습니다.')
        const skipped = rejected ? ['PAYMENT', 'SHIPPING', 'NOTIFICATION'] : ['SHIPPING', 'NOTIFICATION']
        for (const t of skipped) step(t, 'SKIPPED', 'UPSTREAM_REJECTED', SKIP_MESSAGE)
        finished({ business_stop: true, final_status: result.final_status, output: result.context })
        ev('BUSINESS_STOP', id, { final_status: result.final_status })
        businessStop = true
        break
      }
      // 성공한 도구 결과를 컨텍스트와 주문 기록에 반영하고, 선언한 outputs 의 값만
      // SKILL_COMPLETED 와 SKILL_FINISHED 이벤트의 출력으로 남긴다.
      Object.assign(ctx, result.context)
      applyToolEffects(order, tool, result.context)
      if (id !== 'order-intake') step(STEP_TYPE[id], 'SUCCESS', null, '단계 완료')
      const output = Object.fromEntries(skill.outputs.map((k) => [k, ctx[k]]))
      ev('SKILL_COMPLETED', id, { declared_output: output })
      finished({ output })
    }
    // 모든 단계를 마쳤는데 steps 에 알림 단계가 없으면 서버
    // order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java 의
    // REQUIRED_FINAL_STEP 규칙처럼 INCOMPLETE_FLOW 로 실패한다. 아니면 주문을 SHIPPED 로 끝낸다.
    if (completion === 'COMPLETED' && order.status === 'PROCESSING') {
      if (!orderSteps.includes('notify')) fail('INCOMPLETE_FLOW', {})
      else {
        order.status = 'SHIPPED'
        order.message = '주문 처리가 완료되었습니다. Mock 배송이 예약되었습니다.'
      }
    }
  }

  // 실행 종료 이벤트를 남기고, 주문 응답에 처리 단계, execution_id,
  // trace_url 을 붙인 뒤 실행 기록을 저장한다.
  const finalStatus = order.status
  ev('EXECUTION_FINISHED', null, {
    completion,
    final_status: finalStatus,
    // 실제 서버는 정상 완료면 error_category 키를 넣지 않고, 업무
    // 종료와 실패면 키를 넣는다. 업무 종료의 값은 null 이다.
    ...(errorCategory || businessStop ? { error_category: errorCategory } : {}),
  })
  order.steps = steps
  order.execution_id = execution_id
  order.trace_url = `/api/executions/${execution_id}/trace`
  executions[execution_id] = executionRecord({
    executionId: execution_id,
    order,
    snapshot,
    completion,
    errorCategory,
    finalStatus,
    started,
    events,
  })
}

/**
 * POST /api/orders 를 흉내 낸다. Idempotency-Key 가 없으면 400 VALIDATION_ERROR 다.
 * snapshot_id 가 있으면 그 세트로, 없으면 게시본(PUBLISHED_FILES)을 새 snapshot 으로 게시해
 * 실행한다. 없는 snapshot 이나 상품이면 404 다. 같은 키로 다시 보내도 새 주문을 만들며, 서버의
 * 멱등 처리는 흉내 내지 않는다. orderAgentRoutes 의 POST /api/orders 처리기와 seed 가 부른다.
 */
async function createOrder(body, headers) {
  if (!headers['Idempotency-Key']) {
    throw new ApiError(400, '주문 입력을 확인해주세요.', { code: 'VALIDATION_ERROR' })
  }
  let snapshot
  if (body.snapshot_id) {
    snapshot = requireSnapshot(body.snapshot_id)
  } else {
    const published = await publish(PUBLISHED_FILES, { source: 'published-bundle', release: 'camping-v1' })
    snapshot = snapshots[published.snapshot_id]
  }
  const items = body.items.map((i) => {
    const p = products.find((x) => x.sku === i.sku)
    if (!p) throw new ApiError(404, '존재하지 않는 상품입니다.', { code: 'PRODUCT_NOT_FOUND' })
    return { sku: p.sku, name: p.name, qty: i.qty, unit_price: p.unit_price, line_total: p.unit_price * i.qty }
  })
  const order = {
    order_id: uuid(),
    request_id: headers['Idempotency-Key'],
    customer: body.customer,
    items,
    total_amount: items.reduce((s, i) => s + i.line_total, 0),
    currency: 'KRW',
    payment_method: body.payment_method,
    shipping: { country: body.shipping.country, express: !!body.shipping.express },
    status: 'PROCESSING',
    result_code: null,
    message: null,
    payment: { status: 'NOT_ATTEMPTED', reference: null },
    shipment: null,
    notification: { status: 'NOT_ATTEMPTED' },
    created_at: now(),
    updated_at: now(),
  }
  orders[order.order_id] = order
  await execute(order, snapshot)
  if (order.status === 'FAILED') {
    // order-agent 와 같이 Agent 실행 실패는 상태 500 과 order_id 로 알린다.
    // frontend/src/api/orderAgent.js 의 createOrder 가 이 order_id 로 주문을 다시 읽는다.
    throw new ApiError(500, '주문 처리 중 오류가 발생했습니다. 주문 결과를 확인해주세요.', {
      code: 'INTERNAL_ERROR',
      orderId: order.order_id,
    })
  }
  return publicOrder(order)
}

// 주문 상태별 기본 안내 문구다. 주문에 message 가 없을 때 publicOrder 가 서버 응답처럼 채운다.
const STATUS_MESSAGE = {
  REJECTED: '재고가 부족해 주문을 접수하지 못했습니다.',
  HOLD: '결제가 거절되어 주문이 보류되었습니다.',
  FAILED: '주문 처리 중 오류가 발생했습니다.',
}

// 저장된 주문을 응답 모양으로 바꾼다. 복사본에서 내부 표시(_reserved)를
// 빼고, message 가 비었으면 STATUS_MESSAGE 로 채운다.
function publicOrder(o) {
  const { _reserved, ...rest } = clone(o)
  return { ...rest, message: rest.message ?? STATUS_MESSAGE[o.status] ?? null }
}

// 실행 기록에서 events 를 뺀 요약이다. GET /api/executions 목록 한 줄의 모양이다.
const summary = ({ events: _events, ...rest }) => clone(rest)

// GET /api/snapshots/{id}/skills 의 서버 파싱을 흉내 낸다. frontend/src/utils/skillSet.js 의
// toSkill 로 읽을 수 있는 Skill 만 items 에 skill_id 순으로 넣고, 나머지는 parse_errors 에
// SKILL_METADATA_INVALID 로 남긴다. 서버에서 같은 일을 하는 곳은 SkillQueryService 의 list() 이고,
// 그 파일은
// order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java 다.
function serverSkills(snapshot) {
  const items = []
  const parse_errors = []
  for (const [path, f] of skillFilesOf(snapshot)) {
    const sk = toSkill(path, f.content, f.hash)
    if (sk.error) {
      parse_errors.push({ path, code: 'SKILL_METADATA_INVALID', message: sk.error_message })
      continue
    }
    const { skill_id, name, version, description, owner, inputs, outputs, depends_on, steps } = sk
    items.push({ skill_id, name, version, description, owner, path, hash: f.hash, inputs, outputs, depends_on, steps })
  }
  items.sort((a, b) => a.skill_id.localeCompare(b.skill_id))
  return { items, parse_errors }
}

// ── 로그인 (mock): 데모 계정은 service application.yml 과 같다 ──────────────
// 데모 계정이다. 아이디, 비밀번호, 역할은 service/src/main/resources/application.yml 의 계정과
// 같다. owner 는 읽기만 하고, operator 는 승인과 거절을 할 수 있다.
const USERS = {
  owner: { password: 'owner1234', display_name: '업무 담당자', roles: ['OWNER'] },
  operator: { password: 'operator1234', display_name: '운영자', roles: ['OPERATOR'] },
}
// 승인과 거절 기록이다. 최근 것이 앞에 온다.
const approvals = []
// mock 토큰의 수명(초)이다. 응답의 expires_in 으로 나가고,
// frontend/src/stores/auth.js 가 이 값으로 만료 시각을 계산한다.
const TOKEN_TTL_S = 7200

// POST /service/api/auth/login 을 흉내 낸다. 토큰은 mock.<아이디>.<만료 시각 밀리초> 모양이라
// mockUser 가 서명 없이 읽는다. 아이디나 비밀번호가 틀리면 401 INVALID_CREDENTIALS 다.
function mockLogin({ username, password }) {
  const u = USERS[username]
  if (!u || u.password !== password) {
    throw new ApiError(401, '아이디 또는 비밀번호가 올바르지 않습니다.', { code: 'INVALID_CREDENTIALS' })
  }
  return {
    access_token: `mock.${username}.${Date.now() + TOKEN_TTL_S * 1000}`,
    token_type: 'Bearer',
    expires_in: TOKEN_TTL_S,
    user: { username, display_name: u.display_name, roles: u.roles },
  }
}

// Authorization 헤더의 mock 토큰에서 사용자를 꺼낸다. 토큰이 없거나 만료됐으면 401
// UNAUTHORIZED 를 던지고, 토큰을 실은 요청이었으면 frontend/src/api/client.js 의
// requestMock 이 그 401 을 보고 로그아웃시킨다. 로그인이 필요한 처리기가 맨 먼저 부른다.
function mockUser(headers) {
  const [, username, exp] = (headers.Authorization ?? '').replace('Bearer ', '').split('.')
  const u = USERS[username]
  if (!u || Number(exp) < Date.now()) throw new ApiError(401, '로그인이 필요합니다.', { code: 'UNAUTHORIZED' })
  return { username, display_name: u.display_name, roles: u.roles }
}
// order-agent 의 '실행 자료를 확인할 수 없습니다' 404 응답을 code 와 함께 만든다.
const notFound = (code) => new ApiError(404, `실행 자료를 확인할 수 없습니다: ${code}`, { code })
// 없는 주문과 없는 제안서의 404 다. 문구와 code 는 order-agent 와 service 의 응답과 같다.
const orderNotFound = () => new ApiError(404, '주문을 찾을 수 없습니다.', { code: 'ORDER_NOT_FOUND' })
const proposalNotFound = () => new ApiError(404, '제안서를 찾을 수 없습니다.', { code: 'PROPOSAL_NOT_FOUND' })

// id 의 snapshot 을 돌려준다. 없으면 404 SNAPSHOT_NOT_FOUND 를 던진다.
function requireSnapshot(id) {
  if (!snapshots[id]) throw notFound('SNAPSHOT_NOT_FOUND')
  return snapshots[id]
}

// 제안서를 목록 한 줄로 줄인다. 모양은
// service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의
// ProposalSummary 와 같다.
const summaryOf = (p) => ({
  proposal_id: p.proposal_id,
  status: p.status,
  created_at: p.created_at,
  title: p.document.title,
  summary: p.document.summary,
  root_cause_skill: p.proposal.root_cause?.skill ?? null,
  recommended_candidate_no: p.recommended_candidate_no,
  valid_candidate_nos: clone(p.valid_candidate_nos),
  candidate_count: p.proposal.candidates.length,
})

// order-agent 경로 표다. 상품, 주문, 실행 기록, snapshot 을 흉내 낸다. 각 줄은
// [메서드, 경로 정규식, 처리기] 이고, 처리기는 정규식이 잡은 조각 목록, 요청 본문,
// 헤더를 받는다. 실제 경로는 frontend/src/api/orderAgent.js 가 부르고
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller 의 ProductController,
// OrderController, EvidenceController 가 받는다.
const orderAgentRoutes = [
  ['GET', /^\/api\/products$/, () => ({ items: clone(products) })],
  ['POST', /^\/api\/orders$/, (_, body, headers) => createOrder(body, headers)],
  [
    'GET',
    /^\/api\/orders\/([^/]+)$/,
    ([id]) => {
      if (!orders[id]) throw orderNotFound()
      return publicOrder(orders[id])
    },
  ],
  [
    'GET',
    /^\/api\/orders\/by-request\/([^/]+)$/,
    ([key]) => {
      const o = Object.values(orders).find((x) => x.request_id === key)
      if (!o) throw orderNotFound()
      return publicOrder(o)
    },
  ],
  // 실행 실패(FAILED, INTERRUPTED)만 최근 것부터 고르고, 첫 EXECUTION_ERROR 의
  // data 를 error 로 붙인다. 서버 EvidenceController 의 failed() 응답 모양이다.
  [
    'GET',
    /^\/api\/executions\/failed$/,
    () => ({
      items: Object.values(executions)
        .filter((x) => x.completion === 'FAILED' || x.completion === 'INTERRUPTED')
        .reverse()
        .map((x) => ({
          execution_id: x.execution_id,
          order_id: x.order_id,
          snapshot_id: x.snapshot_id,
          snapshot_hash: x.snapshot_hash,
          completion: x.completion,
          final_status: x.final_status,
          started_at: x.started_at,
          finished_at: x.finished_at,
          error: clone(x.events.find((e) => e.kind === 'EXECUTION_ERROR')?.data ?? { category: x.error_category }),
        })),
    }),
  ],
  // 최근 실행을 최근 것부터 20건까지 events 없이 돌려준다.
  ['GET', /^\/api\/executions$/, () => ({ items: Object.values(executions).map(summary).reverse().slice(0, 20) })],
  [
    'GET',
    /^\/api\/executions\/([^/]+)\/trace$/,
    ([id]) => {
      if (!executions[id]) throw notFound('EXECUTION_NOT_FOUND')
      return clone(executions[id])
    },
  ],
  // 준비된 세 fixture 만 받는다. 이름 목록은 frontend/src/api/orderAgent.js 의 FIXTURES 와 같다.
  [
    'POST',
    /^\/api\/snapshots$/,
    (_, { fixture }) => {
      if (!['baseline', 'broken-contract', 'repaired'].includes(fixture)) {
        throw new ApiError(400, '실행 자료를 확인할 수 없습니다: UNKNOWN_FIXTURE', { code: 'UNKNOWN_FIXTURE' })
      }
      return publishFixture(fixture)
    },
  ],
  [
    'GET',
    /^\/api\/snapshots\/([^/?]+)$/,
    ([id]) => manifest(requireSnapshot(id)),
  ],
  // 서버가 읽은 Skill 목록과 parse_errors 를 돌려준다. 읽지 못한 파일이 있으면 partial 이 참이다.
  [
    'GET',
    /^\/api\/snapshots\/([^/?]+)\/skills$/,
    ([id]) => {
      const snapshot = requireSnapshot(id)
      const { items, parse_errors } = serverSkills(snapshot)
      return { snapshot_id: id, snapshot_hash: snapshot.hash, items, partial: parse_errors.length > 0, parse_errors }
    },
  ],
  // 읽을 수 있는 Skill 하나의 메타데이터에 원문을 붙여 돌려준다.
  // 없거나 읽지 못한 Skill 이면 404 SKILL_NOT_FOUND 다.
  [
    'GET',
    /^\/api\/snapshots\/([^/?]+)\/skills\/([^/?]+)$/,
    ([id, skillId]) => {
      const snapshot = requireSnapshot(id)
      const item = serverSkills(snapshot).items.find((x) => x.skill_id === skillId)
      if (!item) throw notFound('SKILL_NOT_FOUND')
      const content = snapshot.files[item.path].content
      return { ...item, snapshot_id: id, snapshot_hash: snapshot.hash, size: byteSize(content), encoding: 'utf-8', content }
    },
  ],
  // 파일 하나의 원문을 돌려준다. 쿼리의 path 는 handleMock 이 디코딩해 넘긴다.
  [
    'GET',
    /^\/api\/snapshots\/([^/?]+)\/files\?path=(.+)$/,
    ([id, path]) => {
      const file = snapshots[id]?.files[path]
      if (!file) throw notFound('SNAPSHOT_FILE_NOT_FOUND')
      return { snapshot_id: id, path, encoding: 'utf-8', hash: file.hash, size: byteSize(file.content), content: file.content }
    },
  ],
]

// SkillOps service 경로 표다. 로그인, 제안서 조회, 승인과 거절을 흉내 낸다. 실제 경로는
// frontend/src/api/auth.js 와 frontend/src/api/proposals.js 가 부르고, 규칙은
// service/src/main/java/com/capstone4/skillops/auth/AuthController.java,
// service/src/main/java/com/capstone4/skillops/api/ProposalController.java,
// service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 와 같다.
const serviceRoutes = [
  ['POST', /^\/service\/api\/auth\/login$/, (_, body) => mockLogin(body)],
  ['GET', /^\/service\/api\/auth\/me$/, (_, __, headers) => mockUser(headers)],
  // 제안서 목록을 만든 시각이 늦은 것부터 요약으로 돌려준다.
  [
    'GET',
    /^\/service\/api\/proposals$/,
    (_, __, headers) => {
      mockUser(headers)
      const items = Object.values(proposals).sort((a, b) => b.created_at.localeCompare(a.created_at))
      return { items: items.map(summaryOf) }
    },
  ],
  [
    'GET',
    /^\/service\/api\/proposals\/([^/]+)$/,
    ([id], __, headers) => {
      mockUser(headers)
      if (!proposals[id]) throw proposalNotFound()
      return clone(proposals[id])
    },
  ],
  // 승인과 거절 규칙이다. 검사 순서대로 OPERATOR 가 아니면 403, 의도 확인 없는 승인은 400, 없는
  // 제안서는 404, 이미 결정한 제안서는 409, 승인할 안이 없는 제안서의 승인은 409, 통과하지 못한
  // 후보의 승인은 400 이다. 통과하면 결정 기록을 만들고 제안서 상태를 바꾼다. 서버 쪽 대응은
  // service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 다.
  [
    'POST',
    /^\/service\/api\/proposals\/([^/]+)\/(approve|reject)$/,
    ([proposalId, action], body, headers) => {
      const user = mockUser(headers)
      if (!user.roles.includes('OPERATOR')) throw new ApiError(403, '이 작업을 할 권한이 없습니다.', { code: 'FORBIDDEN' })
      const approve = action === 'approve'
      if (approve && !body?.intent_confirmed) {
        throw new ApiError(400, '의도 확인 없이 승인할 수 없습니다.', { code: 'INTENT_NOT_CONFIRMED' })
      }
      const p = proposals[proposalId]
      if (!p) throw proposalNotFound()
      if (p.status === 'approved' || p.status === 'rejected') {
        throw new ApiError(409, '이미 결정한 제안서입니다.', { code: 'PROPOSAL_ALREADY_DECIDED' })
      }
      if (approve && p.status === 'no_valid_fix') {
        throw new ApiError(409, '승인할 수 있는 수정안이 없는 제안서입니다.', { code: 'NO_VALID_FIX' })
      }
      if (approve && !p.valid_candidate_nos.includes(body.candidate_no)) {
        throw new ApiError(400, '모든 확인을 통과한 후보만 승인할 수 있습니다.', { code: 'CANDIDATE_NOT_VALID' })
      }
      const record = {
        proposal_id: proposalId,
        decision: approve ? 'approved' : 'rejected',
        candidate_no: approve ? body.candidate_no : null,
        intent_confirmed: approve,
        approval_token: approve ? `a_${uuid().replaceAll('-', '').slice(0, 10).toUpperCase()}` : null,
        approved_by: user.username,
        approver_name: user.display_name,
        decided_at: now(),
        used_at: null,
      }
      p.status = record.decision
      p.decided_at = record.decided_at
      p.decision = record
      approvals.unshift(record)
      return clone(record)
    },
  ],
  [
    'GET',
    /^\/service\/api\/approvals$/,
    (_, __, headers) => {
      mockUser(headers)
      return { items: clone(approvals) }
    },
  ],
]

// handleMock 이 찾는 전체 경로 표다.
const routes = [...orderAgentRoutes, ...serviceRoutes]

// 처음 열었을 때 볼 거리가 있도록 정상본과 결함본, 실행 규칙 예시 세트로 주문을 한 건씩 미리
// 실행해 둔다. seeded 는 그 작업의 Promise 이고 첫 요청 때 한 번만 만든다.
let seeded = null
// 정상본(baseline)과 결함본(broken-contract) snapshot 을 차례로 게시하고 각 세트로
// 같은 주문을 실행한다. 결함본 주문은 계약 위반으로 실패하므로 그 오류는 무시한다.
// 마지막으로 payment 의 '선입금 주문만 결제' 정책을 실행 규칙으로 옮긴 내 수정본(user-edit)을
// 게시해 같은 카드 주문을 돌린다. 이 주문은 payment 단계에서 규칙 PREPAID_ONLY 에 걸려 HOLD 로 멈춘다.
// 실제 서버에서는 이런 세트를 POST /api/snapshots/edits 로 저장하며, metadata 의 intent 는 그때 담당자가
// 적은 의도다.
async function seed() {
  const order = {
    customer: 'c_1001',
    items: [{ sku: 'CAMP-LANTERN-LED', qty: 1 }],
    payment_method: 'card',
    shipping: { country: 'KR', express: false },
  }
  for (const fixture of ['baseline', 'broken-contract']) {
    const { snapshot_id } = await publishFixture(fixture)
    await createOrder({ ...order, snapshot_id }, { 'Idempotency-Key': uuid() }).catch(() => {})
  }
  const ruled = await publish(prepaidPolicyFiles({ withRule: true }), {
    source: 'user-edit',
    intent: { text: proposalB01.proposal.intent.text, expectations: clone(proposalB01.proposal.intent.expectations) },
  })
  await createOrder({ ...order, snapshot_id: ruled.snapshot_id }, { 'Idempotency-Key': uuid() })
}

/**
 * mock 요청 하나를 처리한다. frontend/src/api/client.js 의 requestMock 이 부르고, 실서버 요청과
 * 같이 응답 본문을 돌려주거나 ApiError 를 던진다. 첫 요청이면 seed 를 먼저 마치고,
 * MOCK_LATENCY_MS 만큼 기다린 뒤 routes 에서 메서드와 경로가 맞는 첫 처리기를 부른다. 정규식이
 * 잡은 조각은 decodeURIComponent 로 되돌려 넘긴다. 맞는 경로가 없으면 404 ApiError 를 던진다.
 */
export async function handleMock(method, url, body, headers = {}) {
  await (seeded ??= seed())
  await sleep(MOCK_LATENCY_MS)
  for (const [m, re, fn] of routes) {
    const match = m === method && url.match(re)
    if (match) return fn(match.slice(1).map((s) => s && decodeURIComponent(s)), body, headers)
  }
  throw new ApiError(404, `mock 에 없는 경로: ${method} ${url}`)
}
