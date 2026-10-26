// 제안서 사실 칸의 실행 번호와 Skill 세트 id 를 사람이 읽을 이름으로 바꾸는 규칙이다.
// service 가 쓴 본문(service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java)은 '근거 실행',
// '원인 확인 실행' 칸에 실행 번호를, '수정 전 Skill 세트', '수정 후 Skill 세트' 칸에 snapshot id 를 그대로 적는다.
// 화면은 실행 번호를 'g-05 · 실패' 처럼 시험 주문과 결과로, snapshot id 를 '정상본 · c71401b1' 처럼 세트 종류로 보여 준다.
// frontend/src/views/ProposalView.vue 가 이름표를 만들고 frontend/src/components/ProposalFacts.vue 가 그린다.
// frontend/unit/factNames.test.js 가 Node 에서 바로 불러오므로 상대 경로로만 import 한다.
import { UUID_RE, shortId } from './format.js'

// 실행 번호 모양이다. frontend/src/utils/traceRun.js 의 RUN_ID 와 같다.
const RUN_ID = /^r_[0-9a-f]{32}$/i
// 실행 번호 목록을 이어 붙인 구분자다. ProposalDocumentWriter 의 joined 와 같다.
const SEPARATOR = ', '
// 실패한 실행을 재현한 시험 주문 번호의 앞부분이다. 나머지는 원래 실행의 execution_id 다.
const REPLAY_PREFIX = 'f-'

// 사실 칸이 Skill 세트 id 를 담은 칸인지 본다. 이름표가 '… Skill 세트' 로 끝나는 칸이다.
export const isSkillSetFact = (fact) => String(fact.label).endsWith('Skill 세트')

// 사실 칸이 실행 번호를 담을 수 있는 칸인지 본다. 이름표에 '실행' 이 든 칸이다('근거 실행', '원인 확인 실행', '실패한 실행').
// '진단 번호', '변경 번호' 칸도 UUID 라서 이름표로 가르지 않으면 실행 링크로 잘못 그려진다.
export const isRunFact = (fact) => String(fact.label).includes('실행') && !isSkillSetFact(fact)

// 사실 값을 구분자로 나눈 조각 목록을 돌려준다. frontend/src/components/ProposalFacts.vue 의 runLinks 도 같은 규칙으로 나눈다.
export const factParts = (value) => String(value).split(SEPARATOR)

// 본문의 절과 후보 카드에 있는 사실 칸을 모두 돌려준다.
function factsOf(document) {
  return (document?.sections ?? []).flatMap((s) => [...(s.facts ?? []), ...(s.parts ?? []).flatMap((p) => p.facts ?? [])])
}

// 본문에 나온 실행 번호(r_… 나 실행 UUID)와 Skill 세트 id 를 중복 없이 모은다. ProposalView 가 이름을 한 번에 찾을 때 쓴다.
export function idsInDocument(document) {
  const runs = new Set()
  const sets = new Set()
  for (const fact of factsOf(document)) {
    if (isSkillSetFact(fact)) sets.add(String(fact.value))
    else if (isRunFact(fact)) for (const part of factParts(fact.value)) if (RUN_ID.test(part) || UUID_RE.test(part)) runs.add(part)
  }
  return { runIds: [...runs], snapshotIds: [...sets] }
}

// 장부 한 줄로 실행 이름표를 만든다. 이름표는 { text, title, passed } 이고 title 은 마우스를 올리면 보이는 전체 설명이다.
// 장부에 없으면 '실행 4b80f265' 처럼 번호 앞 8자로 줄인다. 실제 주문의 실행처럼 UUID 로 적힌 번호도 같은 방식으로 줄인다.
export function runTag(id, run) {
  if (!run) return { text: `실행 ${RUN_ID.test(id) ? id.slice(2, 10) : shortId(id)}`, title: id, passed: null }
  const replay = run.scenario_id.startsWith(REPLAY_PREFIX)
  const scenario = replay ? '실패 주문 재현' : run.scenario_id
  const name = run.scenario_name ?? (replay ? `원래 실행 ${run.scenario_id.slice(REPLAY_PREFIX.length, REPLAY_PREFIX.length + 8)}` : '')
  return {
    text: `${scenario} · ${run.passed ? '통과' : '실패'}`,
    title: [name, id].filter(Boolean).join('\n'),
    passed: run.passed,
  }
}

// snapshot manifest 의 metadata 로 Skill 세트 이름표를 만든다. 세트 종류는 metadata 의 fixture 나 source 를
// labels(frontend/src/stores/workspace.js 의 SET_LABEL)로 바꾸고, 모르면 'Skill 세트' 로 둔다.
export function setTag(id, metadata, labels) {
  const key = metadata?.fixture ?? metadata?.source
  return { text: `${labels[key] ?? key ?? 'Skill 세트'} · ${shortId(id)}`, title: id, passed: null }
}

// 실행 링크 목록에서 이름표 글이 같은 것끼리 묶는다. 같은 시험 주문을 여러 번 돌리면 'g-01 · 통과' 가 반복되므로
// 'g-01 · 통과 ×2' 처럼 하나로 보이게 하려는 것이다. 처음 나온 순서를 지키고, 묶음은 첫 실행의 주소로 링크한다.
// runs 는 [{ id, to, tag }] 이고 묶음은 { key, text, count, to, title, passed } 다. title 에는 묶인 실행 번호를 모두 담는다.
// frontend/src/components/ProposalFacts.vue 의 rows 가 부른다.
export function groupRuns(runs) {
  const groups = new Map()
  for (const { id, to, tag } of runs) {
    const group = groups.get(tag.text)
    if (group) group.ids.push(id)
    else groups.set(tag.text, { key: id, text: tag.text, to, passed: tag.passed, name: tag.title.split('\n')[0], ids: [id] })
  }
  return [...groups.values()].map(({ name, ids, ...g }) => ({
    ...g,
    count: ids.length,
    title: [...new Set([name, ...ids])].join('\n'),
  }))
}
