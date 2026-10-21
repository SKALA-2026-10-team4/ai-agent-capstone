// order-flow steps 와 단계 Skill 의 frontmatter 로 의존 그래프와 계약 위반을 계산한다.
// 규칙은 order-agent SkillLoader.validateFlow(static_validation)와 같고, 결과 필드는 service MCP
// get_dependency_graph(DependencyGraphResult)와 같다. 서버가 이 결과를 REST 로 열면 그대로 바꿔 끼운다.

export const ORDER_NODE = '__order'
// 주문이 처음 제공하는 컨텍스트 키 (SkillLoader.validateFlow 의 available 초기값)
export const ORDER_KEYS = [
  'order.id',
  'order.items',
  'order.customer',
  'order.payment_method',
  'order.shipping',
]

// skills: toSkill() 결과 목록, steps: 검사할 순서(초안이면 바뀐 순서)
export function buildGraph(skills, steps, entrySkillId = 'order-flow') {
  const byId = Object.fromEntries(skills.map((s) => [s.skill_id, s]))
  const nodes = []
  const io = new Map() // `${from}->${to}` → keys
  const edges = []
  const missing = []
  const available = new Map(ORDER_KEYS.map((k) => [k, ORDER_NODE])) // key → 마지막 공급자
  const done = new Set()

  steps.forEach((id, i) => {
    const skill = byId[id]
    if (!skill || skill.error) {
      // 읽을 수 없는 skill 은 missing 이 아니라 parse_error 로 남긴다(service DependencyGraph 와 같음).
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

// 화면에 띄울 문제 수: 계약 위반 + 읽을 수 없는 skill
export const problemCount = (graph) =>
  graph.missing.length + graph.nodes.filter((n) => n.parse_error).length
