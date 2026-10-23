// 특성 테스트다. frontend/src/utils/flowGraph.js 가 order-flow steps 와 단계 Skill 로 계약
// 그래프(buildGraph)와 문제 수(problemCount)를 계산하는 지금 동작을 고정한다. 입력은
// frontend/src/mocks/skillFiles.js 의 게시본과 fixture 세트다. 규칙은
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의
// validateFlow 와 같아야 하므로 서버 규칙이 바뀌면 기대값도 함께 고친다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ORDER_KEYS, ORDER_NODE, buildGraph, problemCount } from '../src/utils/flowGraph.js'
import { parseSkillMd } from '../src/utils/frontmatter.js'
import { PUBLISHED_FILES, fixtureFiles } from '../src/mocks/skillFiles.js'

// frontend/src/utils/skillSet.js 와 같은 Skill 경로 규칙이다.
const SKILL_PATH = /^skills\/([a-z][a-z0-9-]*)\/SKILL\.md$/

// 게시본이나 fixture 파일을 buildGraph 가 읽는 Skill 모양(skill_id 와 frontmatter)으로 바꾼다.
// stepsOf 는 진입 Skill 의 steps 를, withoutEntry 는 진입 Skill 을 뺀 목록을 돌려준다.
const skillsOf = (files) =>
  Object.entries(files)
    .filter(([path]) => SKILL_PATH.test(path))
    .map(([path, text]) => ({ skill_id: path.match(SKILL_PATH)[1], ...parseSkillMd(text) }))
const stepsOf = (skills) => skills.find((s) => s.skill_id === 'order-flow').steps
const withoutEntry = (skills) => skills.filter((s) => s.skill_id !== 'order-flow')

// 게시본 order-flow 의 단계 순서다.
const STEPS = ['order-intake', 'inventory-check', 'payment', 'shipping-reserve', 'notify']
// 기대 노드와 간선을 짧게 쓰는 도구다. order 는 앞뒤 단계 사이의
// flow_order 와 depends_on 간선 한 쌍이고, io 는 키를 넘기는 간선이다.
const node = (skill_id, inputs, outputs, depends_on, parse_error = null) => ({
  skill_id,
  inputs,
  outputs,
  depends_on,
  parse_error,
})
const order = (from, to) => [
  { type: 'flow_order', from, to, keys: [] },
  { type: 'depends_on', from, to, keys: [] },
]
const io = (from, to, keys) => ({ type: 'io', from, to, keys })

// 게시본 단계 Skill 의 inputs 다.
const INTAKE_IN = ['order.id', 'order.customer', 'order.items', 'order.payment_method', 'order.shipping']
const PAYMENT_IN = ['order.id', 'order.total', 'order.payment_method', 'inventory.reserved']
const SHIPPING_IN = ['order.id', 'order.shipping', 'payment.id', 'payment.status', 'inventory.reserved']
const NOTIFY_IN = ['order.id', 'order.customer', 'payment.status', 'shipping.id', 'shipping.eta']

test('주문이 처음 주는 컨텍스트 키와 주문 노드 이름', () => {
  assert.equal(ORDER_NODE, '__order')
  assert.deepStrictEqual(ORDER_KEYS, ['order.id', 'order.items', 'order.customer', 'order.payment_method', 'order.shipping'])
})

test('게시본: 계약 위반이 없고 노드·간선 순서가 정해져 있다', () => {
  const skills = skillsOf(PUBLISHED_FILES)
  const graph = buildGraph(withoutEntry(skills), stepsOf(skills))
  assert.deepStrictEqual(graph, {
    entry_skill_id: 'order-flow',
    steps: STEPS,
    nodes: [
      node('order-intake', INTAKE_IN, ['order.id', 'order.total'], []),
      node('inventory-check', ['order.id', 'order.items'], ['inventory.available', 'inventory.reserved'], ['order-intake']),
      node('payment', PAYMENT_IN, ['payment.id', 'payment.status'], ['inventory-check']),
      node('shipping-reserve', SHIPPING_IN, ['shipping.id', 'shipping.eta'], ['payment']),
      node('notify', NOTIFY_IN, ['notify.sent', 'notify.channel'], ['shipping-reserve']),
    ],
    edges: [
      ...order('order-intake', 'inventory-check'),
      ...order('inventory-check', 'payment'),
      ...order('payment', 'shipping-reserve'),
      ...order('shipping-reserve', 'notify'),
      io('__order', 'order-intake', INTAKE_IN),
      io('order-intake', 'inventory-check', ['order.id']),
      io('__order', 'inventory-check', ['order.items']),
      io('order-intake', 'payment', ['order.id', 'order.total']),
      io('__order', 'payment', ['order.payment_method']),
      io('inventory-check', 'payment', ['inventory.reserved']),
      io('order-intake', 'shipping-reserve', ['order.id']),
      io('__order', 'shipping-reserve', ['order.shipping']),
      io('payment', 'shipping-reserve', ['payment.id', 'payment.status']),
      io('inventory-check', 'shipping-reserve', ['inventory.reserved']),
      io('order-intake', 'notify', ['order.id']),
      io('__order', 'notify', ['order.customer']),
      io('payment', 'notify', ['payment.status']),
      io('shipping-reserve', 'notify', ['shipping.id', 'shipping.eta']),
    ],
    missing: [],
    complete: true,
  })
  assert.equal(problemCount(graph), 0)
})

test('결함본: payment·shipping-reserve 가 inventory.reserved 를 받지 못한다', () => {
  const skills = skillsOf(fixtureFiles('broken-contract'))
  const graph = buildGraph(withoutEntry(skills), stepsOf(skills))
  assert.deepStrictEqual(graph.missing, [
    { skill_id: 'payment', missing_inputs: ['inventory.reserved'], missing_dependencies: [] },
    { skill_id: 'shipping-reserve', missing_inputs: ['inventory.reserved'], missing_dependencies: [] },
  ])
  assert.deepStrictEqual(graph.nodes[1], node('inventory-check', ['order.id', 'order.items'], ['inventory.reservable_quantity'], ['order-intake']))
  // 공급자가 없는 키는 io 간선을 만들지 않는다.
  assert.equal(graph.edges.some((e) => e.keys.includes('inventory.reserved')), false)
  assert.equal(graph.complete, true)
  assert.equal(problemCount(graph), 2)
})

test('수정본: 계약 위반이 없다', () => {
  const skills = skillsOf(fixtureFiles('repaired'))
  const graph = buildGraph(withoutEntry(skills), stepsOf(skills))
  assert.deepStrictEqual(graph.missing, [])
  assert.deepStrictEqual(graph.nodes[1].outputs, ['inventory.reservable_quantity', 'inventory.reserved'])
  assert.equal(problemCount(graph), 0)
})

test('steps 순서가 depends_on 보다 앞서면 missing_dependencies 와 입력 누락이 함께 남는다', () => {
  const skills = withoutEntry(skillsOf(PUBLISHED_FILES))
  const graph = buildGraph(skills, ['order-intake', 'payment', 'inventory-check'])
  assert.deepStrictEqual(graph.missing, [
    { skill_id: 'payment', missing_inputs: ['inventory.reserved'], missing_dependencies: ['inventory-check'] },
  ])
  assert.deepStrictEqual(graph.edges.slice(0, 4), [
    { type: 'flow_order', from: 'order-intake', to: 'payment', keys: [] },
    { type: 'depends_on', from: 'inventory-check', to: 'payment', keys: [] },
    { type: 'flow_order', from: 'payment', to: 'inventory-check', keys: [] },
    { type: 'depends_on', from: 'order-intake', to: 'inventory-check', keys: [] },
  ])
})

test('없는 skill 은 UNKNOWN_SKILL, 읽지 못한 skill 은 그 오류 코드로 노드에 남고 간선을 만들지 않는다', () => {
  const broken = { skill_id: 'payment', error: 'INVALID_SKILL_FORMAT' }
  const intake = skillsOf(PUBLISHED_FILES).find((s) => s.skill_id === 'order-intake')
  const graph = buildGraph([intake, broken], ['order-intake', 'ghost', 'payment'])
  assert.deepStrictEqual(graph.nodes, [
    node('order-intake', INTAKE_IN, ['order.id', 'order.total'], []),
    node('ghost', [], [], [], 'UNKNOWN_SKILL'),
    node('payment', [], [], [], 'INVALID_SKILL_FORMAT'),
  ])
  assert.deepStrictEqual(graph.edges, [io('__order', 'order-intake', INTAKE_IN)])
  assert.deepStrictEqual(graph.missing, [])
  assert.equal(graph.complete, false)
  assert.equal(problemCount(graph), 2)
})

test('steps 를 가진 skill(중첩 흐름)은 NESTED_FLOW_NOT_SUPPORTED 지만 계약 검사는 계속한다', () => {
  const skills = skillsOf(PUBLISHED_FILES)
  // 진입 Skill 을 빼지 않으면(mock 실행기처럼) order-flow 가 자기 steps 를 가진 단계가 된다.
  const graph = buildGraph(skills, ['order-flow', 'order-intake'])
  assert.deepStrictEqual(graph.nodes[0], node('order-flow', [], [], [], 'NESTED_FLOW_NOT_SUPPORTED'))
  assert.deepStrictEqual(graph.edges, [
    { type: 'flow_order', from: 'order-flow', to: 'order-intake', keys: [] },
    io('__order', 'order-intake', INTAKE_IN),
  ])
  assert.equal(graph.complete, false)
  assert.equal(problemCount(graph), 1)
  // 진입 Skill 을 뺀 목록에서는 같은 이름이 UNKNOWN_SKILL 이 된다.
  assert.equal(buildGraph(withoutEntry(skills), ['order-flow']).nodes[0].parse_error, 'UNKNOWN_SKILL')
})

test('진입 Skill 이름은 기본값 order-flow 이고 인자로 바꿀 수 있다', () => {
  assert.equal(buildGraph([], []).entry_skill_id, 'order-flow')
  assert.equal(buildGraph([], [], 'other-flow').entry_skill_id, 'other-flow')
  assert.deepStrictEqual(buildGraph([], []), {
    entry_skill_id: 'order-flow',
    steps: [],
    nodes: [],
    edges: [],
    missing: [],
    complete: true,
  })
})

test('같은 키를 다시 내보내면 마지막 공급자가 io 간선의 출발점이 된다', () => {
  const a = { skill_id: 'a', inputs: ['order.id'], outputs: ['x'], depends_on: [], steps: [], error: null }
  const b = { skill_id: 'b', inputs: [], outputs: ['x', 'order.id'], depends_on: [], steps: [], error: null }
  const c = { skill_id: 'c', inputs: ['x', 'order.id', 'y'], outputs: [], depends_on: ['a'], steps: [], error: null }
  const graph = buildGraph([a, b, c], ['a', 'b', 'c'])
  assert.deepStrictEqual(graph.edges, [
    { type: 'flow_order', from: 'a', to: 'b', keys: [] },
    { type: 'flow_order', from: 'b', to: 'c', keys: [] },
    { type: 'depends_on', from: 'a', to: 'c', keys: [] },
    io('__order', 'a', ['order.id']),
    io('b', 'c', ['x', 'order.id']),
  ])
  assert.deepStrictEqual(graph.missing, [{ skill_id: 'c', missing_inputs: ['y'], missing_dependencies: [] }])
})
