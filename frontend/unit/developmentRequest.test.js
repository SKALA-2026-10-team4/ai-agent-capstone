// frontend/src/utils/developmentRequest.js 의 개발 요청서 이름표, 제안 방법 문장, 마크다운 문서를 고정한다.
// 모양은 docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절이다. mock 제안서
// frontend/src/mocks/proposal-w1.json(notify 가 shipping.weather 를 요구하는 날씨 사례)이 이 칸들을 모두 가진
// 예시이고, 그 파일이 docs/contracts/proposal.schema.json 의 development_request 필수 칸을 지키는지도 본다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  GAP_FIELD_LABEL,
  GAP_KIND_LABEL,
  OPTION_TYPE_LABEL,
  developmentRequestMarkdown,
  developmentRequestOf,
  gapFieldLabel,
  gapKindLabel,
  markdownContextOf,
  optionProduces,
  optionText,
  optionTypeLabel,
} from '../src/utils/developmentRequest.js'

const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
const w1 = read('../src/mocks/proposal-w1.json')
const b01 = read('../src/mocks/proposal-b01.json')
const schema = read('../../docs/contracts/proposal.schema.json')
const request = w1.proposal.development_request
const [extend, add] = request.options

// 마크다운의 절 제목(## …)만 순서대로 뽑는다.
const headings = (text) => text.split('\n').filter((l) => l.startsWith('## ')).map((l) => l.slice(3))
// 절 하나의 본문(다음 ## 까지)을 돌려준다.
const sectionOf = (text, heading) => text.split(`## ${heading}\n`)[1].split('\n## ')[0]

test('빈틈 종류·칸·제안 방법 이름표', () => {
  assert.deepStrictEqual(GAP_KIND_LABEL, {
    missing_value: '어떤 도구도 만들지 않는 값',
    tool_output_missing: '도구가 내지 않는 출력',
  })
  assert.equal(gapKindLabel('missing_value'), '어떤 도구도 만들지 않는 값')
  assert.equal(gapKindLabel('tool_output_missing'), '도구가 내지 않는 출력')
  assert.equal(gapKindLabel('other'), 'other')
  assert.equal(gapKindLabel(undefined), '')
  assert.deepStrictEqual(Object.keys(GAP_FIELD_LABEL), ['inputs', 'rules', 'outputs'])
  assert.equal(gapFieldLabel('rules'), '실행 규칙(rules)')
  assert.equal(gapFieldLabel('other'), 'other')
  assert.deepStrictEqual(Object.keys(OPTION_TYPE_LABEL), ['extend_tool', 'new_tool'])
  assert.equal(optionTypeLabel('new_tool'), '새 도구 추가')
  assert.equal(optionTypeLabel(null), '')
  // 계약의 enum 과 이름표의 키가 같다.
  const props = schema.properties.development_request.properties
  assert.deepStrictEqual(Object.keys(GAP_KIND_LABEL), props.gaps.items.properties.kind.enum)
  assert.deepStrictEqual(Object.keys(GAP_FIELD_LABEL), props.gaps.items.properties.field.enum)
  assert.deepStrictEqual(Object.keys(OPTION_TYPE_LABEL), props.options.items.properties.type.enum)
})

test('developmentRequestOf: 상세 맨 위 칸을 먼저, 없으면 원본 제안서 칸, 빈틈이 없으면 null', () => {
  assert.equal(developmentRequestOf(w1), request)
  const top = { kind: 'needs_tool', title: 't', gaps: [{ key: 'a' }], options: [] }
  assert.equal(developmentRequestOf({ development_request: top, proposal: w1.proposal }), top)
  assert.equal(developmentRequestOf({ proposal: { development_request: { ...top, gaps: [] } } }), null)
  assert.equal(developmentRequestOf({ proposal: { development_request: null } }), null)
  assert.equal(developmentRequestOf(b01), null)
  assert.equal(developmentRequestOf(null), null)
})

test('optionText: 기존 도구 확장과 새 도구 추가(예시 이름) 문장', () => {
  assert.equal(optionText(extend), '기존 도구 shipping.reserve(shipping-reserve)가 shipping.weather 를 내도록 확장')
  assert.ok(
    optionText(add).startsWith('새 도구 weather.lookup(예시 이름)을 notify 앞에 추가 — 쓸 수 있는 입력: order.id, order.customer,'),
    optionText(add),
  )
  assert.ok(optionText(add).endsWith('shipping.id, shipping.eta'))
  const code = (s) => `<${s}>`
  assert.equal(
    optionText({ type: 'extend_tool', tool: 't', skill: 's', add_produces: ['a', 'b'] }, code),
    '기존 도구 <t>(s)가 <a>, <b> 를 내도록 확장',
  )
  assert.equal(
    optionText({ type: 'new_tool', suggested_name: 'x.lookup', place_before: 'notify', available_inputs: [] }),
    '새 도구 x.lookup(예시 이름)을 notify 앞에 추가 — 쓸 수 있는 입력: 없음',
  )
  assert.equal(optionText({ type: 'something_new' }), 'something_new')
  assert.deepStrictEqual(optionProduces(extend), ['shipping.weather'])
  assert.deepStrictEqual(optionProduces(add), ['shipping.weather'])
  assert.deepStrictEqual(optionProduces({ type: 'new_tool' }), [])
})

test('markdownContextOf: 제안서 번호·의도·원인·세트 id', () => {
  assert.deepStrictEqual(markdownContextOf(w1), {
    proposalId: 'p_mockw1',
    intentText: '배송지에 태풍 경보가 있으면 알림을 보내기 전에 주문을 보류한다',
    rootCause: w1.proposal.root_cause,
    baselineSnapshotId: '66666666-6666-4666-8666-666666666666',
    snapshotId: '77777777-7777-4777-8777-777777777777',
  })
  const trig = { proposal: { trigger: { baseline_snapshot_id: 'tb', candidate_snapshot_id: 'tc' } } }
  assert.equal(markdownContextOf(trig).baselineSnapshotId, 'tb')
  assert.equal(markdownContextOf(trig).snapshotId, 'tc')
  assert.deepStrictEqual(markdownContextOf(null), {
    proposalId: null,
    intentText: null,
    rootCause: null,
    baselineSnapshotId: null,
    snapshotId: null,
  })
})

test('developmentRequestMarkdown: 날씨 사례의 절 순서와 내용', () => {
  const text = developmentRequestMarkdown(request, markdownContextOf(w1))
  assert.ok(text.startsWith('# notify 가 쓰는 shipping.weather 를 만드는 업무 도구 개발\n\n'))
  assert.ok(text.endsWith('\n') && !text.endsWith('\n\n'))
  assert.deepStrictEqual(headings(text), ['배경', '필요한 값', '제안 방법', '바꿀 곳', '임시 대안', '근거 실행'])

  const background = sectionOf(text, '배경')
  assert.ok(background.includes('담당자가 notify Skill 에 새로 넣은 값을 지금 어떤 업무 도구도 만들지 않습니다.'))
  assert.ok(background.includes('SKILL.md 수정만으로는 해결할 수 없어'))
  assert.ok(background.includes('- 담당자 의도: 배송지에 태풍 경보가 있으면 알림을 보내기 전에 주문을 보류한다'))
  assert.ok(background.includes('- 원인: notify — inputs 와 rules 에 shipping.weather 를 더했지만'))
  assert.ok(background.includes('- 제안서 번호: `p_mockw1`'))
  assert.ok(background.includes('- 수정 전 Skill 세트: `66666666-6666-4666-8666-666666666666`'))

  assert.equal(
    sectionOf(text, '필요한 값'),
    [
      '',
      '| 값 | 필요로 하는 Skill | 칸 | 종류 |',
      '| --- | --- | --- | --- |',
      '| `shipping.weather` | notify | `inputs` | 어떤 도구도 만들지 않는 값 |',
      '| `shipping.weather` | notify | `rules` | 어떤 도구도 만들지 않는 값 |',
      '',
    ].join('\n'),
  )

  const options = sectionOf(text, '제안 방법')
  assert.ok(options.includes('1. 기존 도구 `shipping.reserve`(shipping-reserve)가 `shipping.weather` 를 내도록 확장\n'))
  assert.ok(options.includes('2. 새 도구 `weather.lookup`(예시 이름)을 notify 앞에 추가 — 쓸 수 있는 입력: `order.id`, `order.customer`,'))
  assert.ok(options.includes('   - 내야 할 값: `shipping.weather`\n'))

  assert.equal(
    sectionOf(text, '바꿀 곳'),
    [
      '',
      '- `order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java`',
      '- `order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java`',
      '- `order-agent/skills/shipping-reserve/SKILL.md`',
      '',
    ].join('\n'),
  )

  const interim = sectionOf(text, '임시 대안')
  assert.ok(interim.includes(request.interim.summary))
  assert.ok(interim.includes(`\`\`\`diff\n${request.interim.patch.replace(/\n$/, '')}\n\`\`\`\n`))
  assert.ok(interim.includes('증명 실행(이 변경을 되돌리면 통과한 원인 실험): `r_e1000000000000000000000000000001`, `r_e1000000000000000000000000000002`'))

  assert.equal(
    sectionOf(text, '근거 실행'),
    '\n- `r_e0000000000000000000000000000001`\n- `r_e0000000000000000000000000000002`\n',
  )
})

test('developmentRequestMarkdown: 빈 칸이어도 절 순서는 같고 없다고 적는다', () => {
  const text = developmentRequestMarkdown({ kind: 'needs_tool', title: '', gaps: [], options: [] })
  assert.ok(text.startsWith('# 개발 요청\n'))
  assert.deepStrictEqual(headings(text), ['배경', '필요한 값', '제안 방법', '바꿀 곳', '임시 대안', '근거 실행'])
  assert.ok(text.includes('담당자가 Skill 에 새로 넣은 값을'))
  assert.ok(!text.includes('- 제안서 번호'))
  for (const line of ['적힌 값이 없습니다.', '적힌 방법이 없습니다.', '적힌 곳이 없습니다.', '임시 대안이 없습니다.', '적힌 실행이 없습니다.']) {
    assert.ok(text.includes(line), line)
  }
  assert.equal(developmentRequestMarkdown(null), developmentRequestMarkdown({}))
})

test('developmentRequestMarkdown: 표의 세로 막대와 patch 속 백틱이 문서를 깨지 않는다', () => {
  const text = developmentRequestMarkdown({
    title: 't',
    gaps: [{ key: 'a|b', needed_by: 'x\ny', field: 'outputs', kind: 'tool_output_missing' }],
    options: [],
    interim: { summary: '', patch: 'line\n```\nend\n', proof_run_ids: [] },
  })
  assert.ok(text.includes('| `a\\|b` | x y | `outputs` | 도구가 내지 않는 출력 |'))
  assert.ok(text.includes('````diff\nline\n```\nend\n````\n'))
  assert.ok(!text.includes('증명 실행'))
})

test('mock 제안서 w1: 날씨 사례가 계약의 필수 칸을 지킨다', () => {
  assert.equal(w1.status, 'no_valid_fix')
  assert.equal(w1.proposal.status, 'no_valid_fix')
  assert.deepStrictEqual(w1.valid_candidate_nos, [])
  assert.deepStrictEqual(w1.proposal.candidates, [])
  const contract = schema.properties.development_request
  for (const key of contract.required) assert.ok(key in request, key)
  assert.equal(request.kind, contract.properties.kind.const)
  for (const g of request.gaps) {
    for (const key of contract.properties.gaps.items.required) assert.ok(key in g, key)
  }
  assert.deepStrictEqual(request.gaps.map((g) => [g.key, g.needed_by, g.kind]), [
    ['shipping.weather', 'notify', 'missing_value'],
    ['shipping.weather', 'notify', 'missing_value'],
  ])
  assert.deepStrictEqual(request.options.map((o) => o.type), ['extend_tool', 'new_tool'])
  assert.equal(extend.tool, 'shipping.reserve')
  assert.equal(add.suggested_name, 'weather.lookup')
  // 임시 대안의 증명은 원인 실험 실행이다(2.11 절).
  assert.deepStrictEqual(request.interim.proof_run_ids, w1.proposal.root_cause.proof_run_ids)
  // 새 도구가 쓸 수 있는 입력에 자기가 낼 값은 없다.
  assert.ok(!add.available_inputs.includes('shipping.weather'))
})
