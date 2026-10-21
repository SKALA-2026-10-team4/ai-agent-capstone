// order-agent 없이 화면을 띄우기 위한 메모리 mock (`npm run dev:mock`).
// 경로·응답 형식·이벤트 순서는 실제 order-agent(2026-10-21 develop)에서 받은 응답과 맞췄다.
// 주문 실행은 흐름 캔버스와 같은 buildGraph 로 정적 계약 검사를 하고, 통과하면 5단계 도구를 흉내 낸다.
import { ApiError } from '@/api/error'
import { buildGraph } from '@/utils/flowGraph'
import { flowSteps, toSkill } from '@/utils/skillSet'
import { PUBLISHED_FILES, fixtureFiles } from './skillFiles'
import proposalF1 from './proposal-f1.json'

const clone = (v) => structuredClone(v)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const now = () => new Date().toISOString()
const uuid = () => crypto.randomUUID()

async function sha256(text) {
  const bytes = new TextEncoder().encode(text)
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

const products = [
  { sku: 'CAMP-LANTERN-LED', name: 'LED 캠핑 랜턴', unit_price: 30000, stock_quantity: 10 },
  { sku: 'CAMP-CHAIR-FOLD', name: '접이식 캠핑 의자', unit_price: 45000, stock_quantity: 5 },
  { sku: 'CAMP-MUG-STEEL', name: '스테인리스 캠핑 머그컵', unit_price: 12000, stock_quantity: 0 },
]
const snapshots = {} // id → { snapshot_id, hash, created_at, metadata, files: {path: {content, hash}} }
const orders = {}
const executions = {}
const proposals = { [proposalF1.id]: clone(proposalF1) }

async function publish(files, metadata) {
  const id = uuid()
  const entries = {}
  for (const [path, content] of Object.entries(files)) entries[path] = { content, hash: await sha256(content) }
  const hashes = Object.keys(entries).sort().map((p) => entries[p].hash)
  const hash = await sha256(JSON.stringify({ files: hashes, metadata }))
  snapshots[id] = { snapshot_id: id, hash, created_at: now(), metadata, files: entries }
  return manifest(snapshots[id])
}

const byteSize = (text) => new TextEncoder().encode(text).length

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

const TOOLS = {
  'order-intake': 'order.inspect',
  'inventory-check': 'inventory.reserve',
  payment: 'payment.charge',
  'shipping-reserve': 'shipping.reserve',
  notify: 'notify.send',
}
const STEP_TYPE = {
  'order-intake': 'INTAKE',
  'inventory-check': 'INVENTORY',
  payment: 'PAYMENT',
  'shipping-reserve': 'SHIPPING',
  notify: 'NOTIFICATION',
}
const SKIP_MESSAGE = '앞 단계 거절로 실행하지 않았습니다.'

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

async function execute(order, snapshot) {
  const execution_id = uuid()
  const events = []
  const started = Date.now()
  const ev = (kind, skill_id, data) =>
    events.push({ sequence: events.length + 1, kind, skill_id, data, at: now() })
  const steps = []
  const step = (name, status, result_code, message) =>
    steps.push({ sequence: steps.length + 1, step: name, status, result_code, message, started_at: now(), completed_at: now() })
  const release = (skillId, reason) => {
    const args = { order_id: order.order_id }
    ev('TOOL_REQUEST', skillId, { tool: 'inventory.release', arguments: args, reason })
    ev('TOOL_RESULT', skillId, { tool: 'inventory.release', arguments: args, result: { 'inventory.reserved': false } })
    step('RELEASE', 'SUCCESS', null, '예약 재고를 모두 복원했습니다.')
  }

  const skills = Object.entries(snapshot.files)
    .filter(([p]) => p.endsWith('/SKILL.md'))
    .map(([p, f]) => toSkill(p, f.content, f.hash))
  const ctx = {
    'order.id': order.order_id,
    'order.customer': order.customer,
    'order.items': order.items.map(({ sku, qty }) => ({ sku, qty })),
    'order.payment_method': order.payment_method,
    'order.shipping': order.shipping,
  }

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
  ev('MODEL_RESPONSE', null, { raw_response: '{"skill":"order-flow"}', input_tokens: null, output_tokens: null })
  ev('SKILL_SELECTED', null, { raw_name: 'order-flow', resolved_skill_id: null })
  ev('SKILL_RESOLVED', null, { raw_name: 'order-flow', resolved_skill_id: 'order-flow' })
  const flow = skills.find((s) => s.skill_id === 'order-flow')
  ev('SKILL_LOADED', 'order-flow', { path: flow.path, version: flow.version, hash: flow.file_hash })

  const orderSteps = flowSteps(skills)
  const graph = buildGraph(skills, orderSteps)
  let completion = 'COMPLETED'
  let errorCategory = null
  let businessStop = false

  const fail = (category, details) => {
    completion = 'FAILED'
    errorCategory = category
    order.status = 'FAILED'
    order.result_code = category
    if (order._reserved && order.payment.status !== 'PAID') release(null, category)
    ev('EXECUTION_ERROR', details.skill_id ?? null, { category, ...details })
  }

  const unreadable = graph.nodes.find((n) => n.parse_error)
  const violation = graph.missing[0]
  step('INTAKE', 'SUCCESS', null, '주문 접수 완료')
  if (unreadable) {
    fail(unreadable.parse_error === 'NESTED_FLOW_NOT_SUPPORTED' ? 'CONTRACT_VIOLATION' : unreadable.parse_error, {})
  } else if (violation) {
    // 정적 계약 검사에서 차단: 업무 도구는 하나도 실행하지 않는다.
    step(STEP_TYPE[violation.skill_id] ?? 'RECOVERY', 'FAILED', 'CONTRACT_VIOLATION', '주문 처리 중 오류가 발생했습니다.')
    fail('CONTRACT_VIOLATION', {
      skill_id: violation.skill_id,
      phase: 'static_validation',
      missing_inputs: violation.missing_inputs,
      missing_dependencies: violation.missing_dependencies,
      message: 'Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다.',
    })
  } else {
    ev('FLOW_VALIDATED', 'order-flow', { steps: orderSteps })
    for (const [i, id] of orderSteps.entries()) {
      const skill = skills.find((s) => s.skill_id === id)
      const tool = TOOLS[id]
      const stepNo = i + 1
      const finished = (extra) =>
        ev('SKILL_FINISHED', id, { step_no: stepNo, version: skill.version, status: 'ok', duration_ms: 5, ...extra })
      ev('SKILL_LOADED', id, { path: skill.path, version: skill.version, hash: skill.file_hash })
      ev('SKILL_STARTED', id, {
        step_no: stepNo,
        input: Object.fromEntries(skill.inputs.filter((k) => k in ctx).map((k) => [k, ctx[k]])),
        version: skill.version,
        hash: skill.file_hash,
      })
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
      // 실제 서버와 같은 순서: 결제 거절이면 재고 해제 요청·결과가 결제 결과보다 먼저 기록된다.
      if (result.final_status === 'HOLD') {
        order.payment.status = 'DECLINED'
        step('PAYMENT', 'REJECTED', 'PAYMENT_DECLINED', 'Mock 결제가 거절되었습니다.')
        release(id, 'PAYMENT_DECLINED')
      }
      ev('TOOL_RESULT', id, { tool, arguments: args, result, elapsed_ms: 3 })

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
      Object.assign(ctx, result.context)
      if (tool === 'payment.charge') {
        order.payment = { status: 'PAID', reference: result.context['payment.id'] }
      }
      if (tool === 'shipping.reserve') {
        order.shipment = {
          reference: result.context['shipping.id'],
          estimated_delivery_date: result.context['shipping.eta'],
        }
      }
      if (tool === 'notify.send') order.notification = { status: 'SENT' }
      if (id !== 'order-intake') step(STEP_TYPE[id], 'SUCCESS', null, '단계 완료')
      const output = Object.fromEntries(skill.outputs.map((k) => [k, ctx[k]]))
      ev('SKILL_COMPLETED', id, { declared_output: output })
      finished({ output })
    }
    if (completion === 'COMPLETED' && order.status === 'PROCESSING') {
      if (!orderSteps.includes('notify')) fail('INCOMPLETE_FLOW', {})
      else {
        order.status = 'SHIPPED'
        order.message = '주문 처리가 완료되었습니다. Mock 배송이 예약되었습니다.'
      }
    }
  }

  const finalStatus = order.status
  ev('EXECUTION_FINISHED', null, {
    completion,
    final_status: finalStatus,
    // 실제 서버: 정상 완료는 error_category 키가 없고, 업무 종료·실패는 키가 있다(값은 null 일 수 있음).
    ...(errorCategory || businessStop ? { error_category: errorCategory } : {}),
  })
  order.steps = steps
  order.execution_id = execution_id
  order.trace_url = `/api/executions/${execution_id}/trace`
  executions[execution_id] = {
    execution_id,
    run_id: `r_${execution_id.replaceAll('-', '')}`,
    order_id: order.order_id,
    snapshot_id: snapshot.snapshot_id,
    snapshot_hash: snapshot.hash,
    agent_id: 'camping-order-agent',
    mode: 'live',
    model_mode: 'fixed',
    model: 'fixed-policy-v1',
    completion,
    raw_selection: 'order-flow',
    resolved_skill_id: 'order-flow',
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

async function createOrder(body, headers) {
  if (!headers['Idempotency-Key']) {
    throw new ApiError(400, '주문 입력을 확인해주세요.', { code: 'VALIDATION_ERROR' })
  }
  let snapshot
  if (body.snapshot_id) {
    snapshot = snapshots[body.snapshot_id]
    if (!snapshot) {
      throw new ApiError(404, '실행 자료를 확인할 수 없습니다: SNAPSHOT_NOT_FOUND', { code: 'SNAPSHOT_NOT_FOUND' })
    }
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
    // order-agent 와 같이 Agent 실행 실패는 500 + order_id 로 알린다.
    throw new ApiError(500, '주문 처리 중 오류가 발생했습니다. 주문 결과를 확인해주세요.', {
      code: 'INTERNAL_ERROR',
      orderId: order.order_id,
    })
  }
  return publicOrder(order)
}

const STATUS_MESSAGE = {
  REJECTED: '재고가 부족해 주문을 접수하지 못했습니다.',
  HOLD: '결제가 거절되어 주문이 보류되었습니다.',
  FAILED: '주문 처리 중 오류가 발생했습니다.',
}

function publicOrder(o) {
  const { _reserved, ...rest } = clone(o)
  return { ...rest, message: rest.message ?? STATUS_MESSAGE[o.status] ?? null }
}

const summary = ({ events: _events, ...rest }) => clone(rest)

// GET /snapshots/{id}/skills 흉내: 실행 로더와 같은 검사로 읽을 수 있는 것만 items, 나머지는 parse_errors.
function serverSkills(snapshot) {
  const items = []
  const parse_errors = []
  for (const [path, f] of Object.entries(snapshot.files)) {
    if (!path.endsWith('/SKILL.md')) continue
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
const USERS = {
  owner: { password: 'owner1234', display_name: '업무 담당자', roles: ['OWNER'] },
  operator: { password: 'operator1234', display_name: '운영자', roles: ['OPERATOR'] },
}
const approvals = []
const TOKEN_TTL_S = 7200

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

function mockUser(headers) {
  const [, username, exp] = (headers.Authorization ?? '').replace('Bearer ', '').split('.')
  const u = USERS[username]
  if (!u || Number(exp) < Date.now()) throw new ApiError(401, '로그인이 필요합니다.', { code: 'UNAUTHORIZED' })
  return { username, display_name: u.display_name, roles: u.roles }
}
const notFound = (code) => new ApiError(404, `실행 자료를 확인할 수 없습니다: ${code}`, { code })

const routes = [
  ['GET', /^\/api\/products$/, () => ({ items: clone(products) })],
  ['POST', /^\/api\/orders$/, (_, body, headers) => createOrder(body, headers)],
  [
    'GET',
    /^\/api\/orders\/([^/]+)$/,
    ([id]) => {
      if (!orders[id]) throw new ApiError(404, '주문을 찾을 수 없습니다.', { code: 'ORDER_NOT_FOUND' })
      return publicOrder(orders[id])
    },
  ],
  [
    'GET',
    /^\/api\/orders\/by-request\/([^/]+)$/,
    ([key]) => {
      const o = Object.values(orders).find((x) => x.request_id === key)
      if (!o) throw new ApiError(404, '주문을 찾을 수 없습니다.', { code: 'ORDER_NOT_FOUND' })
      return publicOrder(o)
    },
  ],
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
  ['GET', /^\/api\/executions$/, () => ({ items: Object.values(executions).map(summary).reverse().slice(0, 20) })],
  [
    'GET',
    /^\/api\/executions\/([^/]+)\/trace$/,
    ([id]) => {
      if (!executions[id]) throw notFound('EXECUTION_NOT_FOUND')
      return clone(executions[id])
    },
  ],
  [
    'POST',
    /^\/api\/snapshots$/,
    (_, { fixture }) => {
      if (!['baseline', 'broken-contract', 'repaired'].includes(fixture)) {
        throw new ApiError(400, '실행 자료를 확인할 수 없습니다: UNKNOWN_FIXTURE', { code: 'UNKNOWN_FIXTURE' })
      }
      return publish(fixtureFiles(fixture), { source: 'demo-fixture', fixture })
    },
  ],
  [
    'GET',
    /^\/api\/snapshots\/([^/?]+)$/,
    ([id]) => {
      if (!snapshots[id]) throw notFound('SNAPSHOT_NOT_FOUND')
      return manifest(snapshots[id])
    },
  ],
  [
    'GET',
    /^\/api\/snapshots\/([^/?]+)\/skills$/,
    ([id]) => {
      if (!snapshots[id]) throw notFound('SNAPSHOT_NOT_FOUND')
      const { items, parse_errors } = serverSkills(snapshots[id])
      return { snapshot_id: id, snapshot_hash: snapshots[id].hash, items, partial: parse_errors.length > 0, parse_errors }
    },
  ],
  [
    'GET',
    /^\/api\/snapshots\/([^/?]+)\/skills\/([^/?]+)$/,
    ([id, skillId]) => {
      if (!snapshots[id]) throw notFound('SNAPSHOT_NOT_FOUND')
      const item = serverSkills(snapshots[id]).items.find((x) => x.skill_id === skillId)
      if (!item) throw notFound('SKILL_NOT_FOUND')
      const content = snapshots[id].files[item.path].content
      return { ...item, snapshot_id: id, snapshot_hash: snapshots[id].hash, size: byteSize(content), encoding: 'utf-8', content }
    },
  ],
  [
    'GET',
    /^\/api\/snapshots\/([^/?]+)\/files\?path=(.+)$/,
    ([id, path]) => {
      const file = snapshots[id]?.files[path]
      if (!file) throw notFound('SNAPSHOT_FILE_NOT_FOUND')
      return { snapshot_id: id, path, encoding: 'utf-8', hash: file.hash, size: byteSize(file.content), content: file.content }
    },
  ],
  // skillops-agent: 실제로는 조회 API 가 없다(연결 대기). 형식만 state.py 에 맞춘 mock.
  ['GET', /^\/agent\/proposals$/, () => Object.values(proposals).map(clone)],
  [
    'GET',
    /^\/agent\/proposals\/([^/]+)$/,
    ([id]) => {
      if (!proposals[id]) throw new ApiError(404, `Proposal '${id}' not found`)
      return clone(proposals[id])
    },
  ],
  // SkillOps service: 로그인과 승인 중계 (service/auth, service/api/ApprovalController 와 같은 규칙)
  ['POST', /^\/service\/api\/auth\/login$/, (_, body) => mockLogin(body)],
  ['GET', /^\/service\/api\/auth\/me$/, (_, __, headers) => mockUser(headers)],
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
      const record = {
        proposal_id: proposalId,
        decision: approve ? 'approved' : 'rejected',
        candidate: approve ? body.candidate : null,
        intent_confirmed: approve,
        approval_token: approve ? `a_${uuid().replaceAll('-', '').slice(0, 10).toUpperCase()}` : null,
        approved_by: user.username,
        approver_name: user.display_name,
        decided_at: now(),
        used_at: null,
        agent_result: 'ACCEPTED',
        agent_message: approve ? 'approved' : 'rejected',
      }
      if (!p || p.approval_status !== 'pending') {
        record.agent_result = 'PROPOSAL_NOT_FOUND'
        approvals.unshift(record)
        throw new ApiError(404, `Proposal '${proposalId}' not found or already processed`, { code: 'PROPOSAL_NOT_FOUND' })
      }
      p.approval_status = record.decision
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

// 처음 열었을 때 볼 거리가 있도록 정상본·결함본 주문을 한 건씩 미리 실행해 둔다.
let seeded = null
async function seed() {
  const order = {
    customer: 'c_1001',
    items: [{ sku: 'CAMP-LANTERN-LED', qty: 1 }],
    payment_method: 'card',
    shipping: { country: 'KR', express: false },
  }
  for (const fixture of ['baseline', 'broken-contract']) {
    const { snapshot_id } = await publish(fixtureFiles(fixture), { source: 'demo-fixture', fixture })
    await createOrder({ ...order, snapshot_id }, { 'Idempotency-Key': uuid() }).catch(() => {})
  }
}

export async function handleMock(method, url, body, headers = {}) {
  await (seeded ??= seed())
  await sleep(120)
  for (const [m, re, fn] of routes) {
    const match = m === method && url.match(re)
    if (match) return fn(match.slice(1).map((s) => s && decodeURIComponent(s)), body, headers)
  }
  throw new ApiError(404, `mock 에 없는 경로: ${method} ${url}`)
}
