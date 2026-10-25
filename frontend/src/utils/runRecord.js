// 실행 번호(run_id, execution_id)와 Skill 세트 번호(snapshot_id)를 화면에 그대로 찍지 않고, 담당자가 읽는
// '실행 기록'으로 바꾸는 규칙이다. 실행 한 건을 '14:32:05 · 규칙 PREPAID_ONLY 로 보류 · LED 캠핑 랜턴 1개 · 카드
// · 국내 일반 배송 · 내 수정본' 처럼 시각, 결과, 주문 요약, 세트 종류로 요약한다.
// trace 모양은 frontend/src/api/orderAgent.js 의 getTrace(GET /api/executions/{id}/trace)가 주는 그대로이고,
// 단계 상태 계산은 frontend/src/utils/traceRun.js 의 traceRun 을 함께 쓴다.
// 화면 쪽은 frontend/src/api/runRecords.js(trace 를 읽어 캐시하고 요약), frontend/src/components/RunRecordList.vue
// (실행 기록 타임라인), frontend/src/components/SnapshotLabel.vue(세트 이름)가 쓴다.
// Node 단위 테스트(frontend/unit/runRecord.test.js)가 바로 불러오도록 상대 경로로만 import 하고, API 를
// 부르는 일(fetch)은 여기 두지 않는다. 동시 요청 제한과 캐시는 불러올 함수를 받는 모양이라 여기서 검사한다.
import { UUID_RE, clockOf, dayOf, sameDay, stampOf } from './format.js'
import { executionPath, traceRun } from './traceRun.js'

// ── 실행 번호 읽기 ────────────────────────────────────

// run_id 모양(r_ 뒤 16진수 32자리)이다. 규칙은 frontend/src/utils/traceRun.js 의 executionPath 와 같다.
const RUN_ID = /^r_[0-9a-f]{32}$/i

// 값이 run_id 모양의 글인지 본다.
export const isRunId = (value) => typeof value === 'string' && RUN_ID.test(value)

/**
 * 실행 번호(run_id 나 UUID)를 실행 화면이 쓰는 execution_id(UUID)로 바꾼다. 실행 번호가 아니면 null 이다.
 * run_id 는 frontend/src/utils/traceRun.js 의 executionPath 가 하이픈을 다시 넣어 되돌린다.
 */
export function executionIdOf(ref) {
  if (typeof ref !== 'string') return null
  const path = executionPath(ref.trim())
  return path ? path.slice('/executions/'.length) : null
}

/**
 * 제안서 사실 값 같은 글을 ', ' 로 나눠 id 목록으로 돌려준다. 조각이 모두 test 를 통과할 때만 목록이고,
 * 하나라도 아니거나 비었으면 null 이다. 구분자는
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 joined 와 같다.
 */
export function idListIn(value, test) {
  if (typeof value !== 'string' || !value.trim()) return null
  const parts = value.split(',').map((s) => s.trim())
  return parts.every((p) => p && test(p)) ? parts : null
}

// 글 전체가 run_id 목록이면 그 목록을, 아니면 null 을 돌려준다.
export const runIdsIn = (value) => idListIn(value, isRunId)
// 글 전체가 UUID 목록이면 그 목록을, 아니면 null 을 돌려준다.
export const uuidsIn = (value) => idListIn(value, (s) => UUID_RE.test(s))

// 글 속의 내부 번호다. 'p_' 나 'r_' 같은 한 글자 접두어 뒤 16진수 16자리 이상, 그리고 UUID 다. 바로 앞의 ': ' 나 빈칸도
// 함께 잡는다.
const ID_IN_TEXT = /(?:\s*[:：]\s*|\s+)?(?:\b[a-z]_[0-9a-f]{16,}\b|\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b)/gi

/**
 * 서버가 쓴 자유 글(진단 진행 로그 등)에서 내부 번호를 지운다. '제안서 저장: p_1fa6…' 은 '제안서 저장' 이 된다.
 * 원래 글은 화면의 title 에 남긴다. frontend/src/views/DiagnosisView.vue 의 진행 로그가 쓴다.
 */
export const withoutIds = (text) =>
  String(text ?? '')
    .replace(ID_IN_TEXT, '')
    .replace(/\s{2,}/g, ' ')
    .trim()

// ── 주문 요약 ─────────────────────────────────────────

// 결제 방식과 배송 국가의 화면 이름이다. 값은 frontend/src/views/OrderView.vue 의 주문 폼과 같다.
// 표에 없는 값은 원래 값으로 보인다.
export const PAYMENT_LABEL = { card: '카드', prepaid: '선입금' }
export const COUNTRY_LABEL = { KR: '국내', US: '미국', JP: '일본' }

/**
 * trace 에서 주문 입력(상품, 결제 방식, 배송)을 찾는다. 첫 단계(order-intake)의 SKILL_STARTED input 을
 * 먼저 보고, 정적 검사에서 막혀 단계가 시작하지 않은 실행이면 진입 Skill 선택의 DECISION_INPUT inputs 를
 * 본다. 둘 다 없으면 null 이다. 키 이름은 order-agent 의 시작 컨텍스트 키(order.items 등)다.
 */
export function orderInputOf(trace) {
  for (const e of trace?.events ?? []) {
    const ctx = e.data?.input?.['order.items'] ? e.data.input : e.data?.inputs?.['order.items'] ? e.data.inputs : null
    if (ctx) {
      return {
        items: ctx['order.items'],
        payment_method: ctx['order.payment_method'] ?? null,
        shipping: ctx['order.shipping'] ?? null,
      }
    }
  }
  return null
}

/**
 * 주문 입력을 'LED 캠핑 랜턴 1개 · 카드 · 국내 일반 배송' 한 줄로 쓴다. 상품 이름은 항목의 name, 없으면
 * products({ sku: 이름 })에서, 그것도 없으면 sku 를 쓴다. 상품이 셋 이상이면 '첫 상품 외 N건' 으로 줄인다.
 * 칸이 없으면 그 조각을 빼고, 입력이 없으면 null 이다.
 */
export function orderSummary(input, products = {}) {
  if (!input) return null
  const items = (input.items ?? []).map((i) => `${i.name ?? products[i.sku] ?? i.sku} ${i.qty}개`)
  const parts = []
  if (items.length) parts.push(items.length > 2 ? `${items[0]} 외 ${items.length - 1}건` : items.join(', '))
  if (input.payment_method) parts.push(PAYMENT_LABEL[input.payment_method] ?? input.payment_method)
  if (input.shipping?.country) {
    const country = COUNTRY_LABEL[input.shipping.country] ?? input.shipping.country
    parts.push(`${country} ${input.shipping.express ? '빠른' : '일반'} 배송`)
  }
  return parts.length ? parts.join(' · ') : null
}

// ── 실행 결과 ─────────────────────────────────────────

// 실행 실패 분류(EXECUTION_ERROR 의 category)의 짧은 화면 이름이다. 분류 목록은
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 가 남기는 값이다.
// CONTRACT_VIOLATION 은 failureLabel 이 못 받은 입력, 끝나지 않은 앞 단계, 규칙 입력 문제로 다시 나눈다.
// 표에 없는 분류는 원래 이름으로 보인다.
export const FAILURE_LABEL = {
  CONTRACT_VIOLATION: '계약 위반',
  INCOMPLETE_FLOW: '알림 단계 없음',
  UNSUPPORTED_SKILL: '연결된 도구 없음',
  INVALID_TOOL_ARGUMENTS: '도구 입력 오류',
  MISSING_SKILL_OUTPUT: '출력 누락',
  SKILL_METADATA_INVALID: 'Skill 형식 오류',
}

// 실패 오류 data 를 짧은 이름으로 바꾼다. 계약 위반은 사유를 보고 더 구체적인 이름을 쓴다.
export function failureLabel(error) {
  const category = error?.category ?? null
  if (category === 'CONTRACT_VIOLATION') {
    if (error.reason === 'RULE_KEY_NOT_INPUT') return '규칙 입력 없음'
    if (error.missing_inputs?.length) return '입력 누락'
    if (error.missing_dependencies?.length) return '앞 단계 누락'
  }
  return FAILURE_LABEL[category] ?? category ?? '오류'
}

/**
 * trace 의 실행 결과를 { text, tone } 으로 쓴다. tone 은 ok(정상 완료), warn(업무 규칙에 따른 보류·거절),
 * err(실행 실패), info(진행 중), muted(알 수 없음) 가운데 하나다. 순서대로 진행 중, 서버 재시작 중단,
 * 실행 실패('실패 · payment 단계 입력 누락'), 실행 규칙에 따른 멈춤('규칙 PREPAID_ONLY 로 보류'),
 * 배송 완료, 결제 거절 보류, 재고 부족 거절을 본다. 실패한 단계는 오류의 skill_id, 없으면
 * frontend/src/utils/traceRun.js 의 traceRun 이 오류로 판정한 단계다.
 */
export function outcomeOf(trace) {
  const events = trace?.events ?? []
  const status = trace?.final_status ?? null
  const completion = trace?.completion ?? null
  if (completion === 'RUNNING' || status === 'PROCESSING') return { text: '실행 중', tone: 'info' }
  if (completion === 'INTERRUPTED') return { text: '중단됨 · 서버 재시작', tone: 'warn' }
  if (completion === 'FAILED' || status === 'FAILED') {
    const error = events.findLast((e) => e.kind === 'EXECUTION_ERROR')?.data ?? { category: trace?.error_category }
    const steps = events.find((e) => e.kind === 'FLOW_VALIDATED')?.data?.steps ?? []
    const run = traceRun(trace, steps)
    const skill = error.skill_id ?? steps.find((id) => run.bySkill[id]?.status === 'error') ?? null
    return { text: skill ? `실패 · ${skill} 단계 ${failureLabel(error)}` : `실패 · ${failureLabel(error)}`, tone: 'err' }
  }
  const rule = events.findLast((e) => e.kind === 'RULE_MATCHED')?.data
  if (rule?.reason) return { text: `규칙 ${rule.reason} 로 ${rule.then === 'reject' ? '거절' : '보류'}`, tone: 'warn' }
  if (status === 'SHIPPED') return { text: '배송 완료', tone: 'ok' }
  if (status === 'HOLD') return { text: '보류 · 결제 거절', tone: 'warn' }
  if (status === 'REJECTED') return { text: '거절 · 재고 부족', tone: 'warn' }
  return { text: status ?? completion ?? '상태 없음', tone: 'muted' }
}

/**
 * trace 한 건을 실행 기록 한 줄로 요약한다. 돌려주는 칸은 executionId, path(실행 화면 주소), at(시작 시각
 * 원문), time('14:32:05'), date(오늘이 아니면 '10월 25일', 오늘이면 null), outcome, tone, order(주문 요약),
 * snapshotId, snapshotKind 다. products 는 { sku: 상품 이름 }, now 는 '오늘' 을 가르는 기준 시각, kindOf 는
 * snapshot id 를 세트 종류 이름으로 바꾸는 함수(frontend/src/stores/workspace.js 의 kindLabelOf 등)이고
 * 없으면 snapshotKind 가 null 이다.
 */
export function summarizeTrace(trace, { products = {}, now = new Date(), kindOf = null } = {}) {
  const { text, tone } = outcomeOf(trace)
  const at = trace?.started_at ?? trace?.events?.[0]?.at ?? null
  const snapshotId = trace?.snapshot_id ?? null
  return {
    executionId: trace?.execution_id ?? null,
    path: trace?.execution_id ? `/executions/${trace.execution_id}` : null,
    at,
    time: clockOf(at),
    date: at && !sameDay(at, now) ? dayOf(at) : null,
    outcome: text,
    tone,
    order: orderSummary(orderInputOf(trace), products),
    snapshotId,
    snapshotKind: kindOf && snapshotId ? kindOf(snapshotId) : null,
  }
}

// ── Skill 세트 이름 ───────────────────────────────────

/**
 * Skill 세트를 id 대신 부르는 이름이다. role 이 'baseline' 이면 '기준본', 'candidate' 면 '수정본'(담당자가
 * 저장한 세트면 '내 수정본')이고, role 이 없으면 세트 종류 이름(kind, 예: 정상본·게시본), 그것도 없으면
 * 'Skill 세트' 다. 만든 시각(createdAt)을 알면 ' · 10월 26일 06:41' 을 붙인다.
 * frontend/src/components/SnapshotLabel.vue 가 쓴다.
 */
export function snapshotLabelText({ role = null, kind = null, createdAt = null } = {}) {
  let base = kind ?? 'Skill 세트'
  if (role === 'baseline') base = '기준본'
  else if (role === 'candidate') base = kind === '내 수정본' ? '내 수정본' : '수정본'
  const stamp = stampOf(createdAt)
  return stamp ? `${base} · ${stamp}` : base
}

// ── 동시 요청 제한과 캐시 ─────────────────────────────

/**
 * 동시에 도는 작업 수를 max 개로 막는 제한기를 만든다. 돌려준 함수에 작업(Promise 를 돌려주는 함수)을 넘기면
 * 자리가 날 때까지 기다렸다가 실행하고 그 결과로 끝나는 Promise 를 돌려준다. 실패한 작업도 자리를 비운다.
 * frontend/src/api/runRecords.js 가 trace 와 snapshot 요청을 frontend/src/config.js 의 RUN_RECORD_CONCURRENCY
 * 개로 묶는 데 쓴다.
 */
export function createLimiter(max = 4) {
  let active = 0
  const queue = []
  const next = () => {
    if (active >= max || !queue.length) return
    active++
    const { task, resolve, reject } = queue.shift()
    Promise.resolve()
      .then(task)
      .then(resolve, reject)
      .finally(() => {
        active--
        next()
      })
  }
  return (task) =>
    new Promise((resolve, reject) => {
      queue.push({ task, resolve, reject })
      next()
    })
}

/**
 * 키마다 load(key) 의 Promise 를 한 번만 만들어 두는 캐시다. 같은 키를 동시에 여러 번 물어도 요청은 하나다.
 * 실패한 Promise 는 지워서 다음에 다시 읽게 한다. forget 은 키 하나를, clear 는 전부를 지운다.
 */
export function createCache(load) {
  const map = new Map()
  return {
    get(key) {
      if (!map.has(key)) {
        const promise = Promise.resolve().then(() => load(key))
        map.set(key, promise)
        promise.catch(() => {
          if (map.get(key) === promise) map.delete(key)
        })
      }
      return map.get(key)
    },
    forget: (key) => map.delete(key),
    clear: () => map.clear(),
  }
}
