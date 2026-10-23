// order-flow 의 steps 와 단계 Skill 의 frontmatter 로 의존 그래프와 계약 위반을 계산한다. 규칙은
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의
// validateFlow(static_validation)와 같고, 결과 칸 이름은 service MCP 도구 get_dependency_graph 의
// 결과인 service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphResult.java 와
// 같다. 서버가 이 결과를 REST 로 열면 그대로 바꿔 끼울 수 있다.
//
// 쓰는 곳은 frontend/src/views/SkillDraftView.vue(저장 전 계약 점검),
// frontend/src/views/SnapshotCompareView.vue(두 세트의 위반 비교),
// frontend/src/mocks/index.js 의 execute(mock 정적 검사)다.
//
// 계약 규칙을 바꾸려면 buildGraph 와 ORDER_KEYS 를 고친다. 서버 쪽에서 같은 규칙을 가진
// service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 와 SkillLoader 의
// validateFlow 도 함께 맞춘다.
import { ENTRY_SKILL, flowSteps } from './skillSet.js'

// 주문 자체를 뜻하는 가상 노드 이름이다. 처음 주어지는 컨텍스트 키는
// 이 노드에서 출발하는 io 간선이 된다.
export const ORDER_NODE = '__order'
// 주문이 처음 제공하는 컨텍스트 키다. 서버 정적 검사의 시작 키인
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java 의
// INITIAL_INPUT_KEYS 와 같아야 하고, service 의 DependencyAnalyzer.ORDER_INPUT_KEYS 도
// 같은 목록을 들고 있다. 키를 더하거나 빼면 세 곳을 함께 고친다.
export const ORDER_KEYS = [
  'order.id',
  'order.items',
  'order.customer',
  'order.payment_method',
  'order.shipping',
]

/**
 * steps 순서대로 단계 Skill 을 따라가며 의존 그래프와 계약 위반을 만든다.
 * skills 는 toSkill() 결과 목록이고 steps 는 검사할 순서(초안이면 바뀐 순서)다.
 * 돌려주는 값은 { entry_skill_id, steps, nodes, edges, missing, complete } 다.
 *
 * 단계마다 inputs 가 앞 단계의 outputs 나 ORDER_KEYS 로 채워졌는지, depends_on 이
 * 이미 지난 단계인지 본다. 빠진 것은 missing 에 { skill_id, missing_inputs,
 * missing_dependencies } 로 남긴다. 간선은 세 종류다. flow_order 는 steps 의 앞뒤
 * 순서, depends_on 은 Skill 이 선언한 선행 단계, io 는 입력 키를 마지막으로 공급한
 * 노드에서 오는 간선이다. 없거나 읽을 수 없는 Skill 은 parse_error 노드가 되어
 * 간선을 만들지 않고, steps 를 가진 Skill 은 NESTED_FLOW_NOT_SUPPORTED 노드가 되지만
 * 계약 검사는 계속한다. complete 는 parse_error 노드가 하나도 없을 때 참이다.
 */
export function buildGraph(skills, steps, entrySkillId = ENTRY_SKILL) {
  const byId = Object.fromEntries(skills.map((s) => [s.skill_id, s]))
  const nodes = []
  // io 간선을 출발 노드와 도착 노드의 쌍마다 하나로 모으고, 그 쌍으로 넘어가는 키를 keys 에 담는다.
  const io = new Map()
  const edges = []
  const missing = []
  // 키마다 마지막으로 공급한 노드다. 처음에는 주문 노드가 ORDER_KEYS 를 공급한다.
  const available = new Map(ORDER_KEYS.map((k) => [k, ORDER_NODE]))
  const done = new Set()

  steps.forEach((id, i) => {
    const skill = byId[id]
    if (!skill || skill.error) {
      // 읽을 수 없는 skill 은 missing 이 아니라 parse_error 로 남긴다.
      // service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraph.java 와 같은 규칙이다.
      nodes.push({ skill_id: id, inputs: [], outputs: [], depends_on: [], parse_error: skill?.error ?? 'UNKNOWN_SKILL' })
      return
    }
    nodes.push({
      skill_id: id,
      inputs: skill.inputs,
      outputs: skill.outputs,
      depends_on: skill.depends_on,
      parse_error: skill.steps.length ? 'NESTED_FLOW_NOT_SUPPORTED' : null,
    })
    if (i > 0) edges.push({ type: 'flow_order', from: steps[i - 1], to: id, keys: [] })
    for (const dep of skill.depends_on) edges.push({ type: 'depends_on', from: dep, to: id, keys: [] })

    const missingInputs = skill.inputs.filter((k) => !available.has(k))
    const missingDeps = skill.depends_on.filter((d) => !done.has(d))
    if (missingInputs.length || missingDeps.length) {
      missing.push({ skill_id: id, missing_inputs: missingInputs, missing_dependencies: missingDeps })
    }
    for (const key of skill.inputs) {
      const from = available.get(key)
      if (!from) continue
      const edgeId = `${from}->${id}`
      if (!io.has(edgeId)) io.set(edgeId, { type: 'io', from, to: id, keys: [] })
      io.get(edgeId).keys.push(key)
    }
    // order-agent 는 검사 실패 단계에서 멈추지만, 그래프는 뒤 단계 위반까지 모두 보여 준다.
    done.add(id)
    for (const key of skill.outputs) available.set(key, id)
  })

  return {
    entry_skill_id: entrySkillId,
    steps,
    nodes,
    edges: [...edges, ...io.values()],
    missing,
    complete: nodes.every((n) => !n.parse_error),
  }
}

// Skill 세트 전체의 계약 그래프를 만든다. 진입 Skill(ENTRY_SKILL)을 노드에서 빼고 그
// steps 순서로 buildGraph 를 부른다. frontend/src/views/SkillDraftView.vue 와
// frontend/src/views/SnapshotCompareView.vue 가 함께 쓰고,
// frontend/unit/flowGraphOf.test.js 가 예전 화면 식과 같은지 확인한다.
export const flowGraphOf = (skills) =>
  buildGraph(
    skills.filter((s) => s.skill_id !== ENTRY_SKILL),
    flowSteps(skills),
    ENTRY_SKILL,
  )

// 화면에 띄울 문제 수를 센다. 계약 위반 수와 parse_error 노드 수를 더한다.
// frontend/src/views/SkillDraftView.vue 가 저장 전 점검 숫자로 쓴다.
export const problemCount = (graph) =>
  graph.missing.length + graph.nodes.filter((n) => n.parse_error).length
