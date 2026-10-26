// Skill 편집 화면의 '바꾸려는 업무 규칙(의도)' 입력을 저장 요청의 intent 칸으로 바꾼다. intent 모양은
// docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3 절의 { text, expectations: [{ scope, checks }] }
// 이고, frontend/src/api/orderAgent.js 의 saveSkillEdits 가 POST /api/snapshots/edits 에 실어 보낸다.
// 받는 쪽 형식 검사는 order-agent 의 EditRequest 가 하고, 같은 값이 service 를 거쳐 skillops-agent 의
// DiagnoseRequest.intent 가 된다. 여기서는 저장 전에 담당자에게 문제를 바로 보여 주려고 가볍게만
// 검사한다(글자 수, 줄 수, 조건 문법). 판정은 늘 서버가 다시 한다.
//
// 고급 입력은 한 줄에 기대 결과 하나를 `범위 => 확인 조건; 확인 조건` 으로 적는다. 범위를 비우거나
// `*` 로 적으면 모든 기준 주문에 붙는 조건(scope null)이다. 조건 문법은
// frontend/src/utils/condition.js 가 읽는다. frontend/src/views/SkillDraftView.vue 가 쓰고
// frontend/unit/intentInput.test.js 가 지금 동작을 고정한다.
import { readCondition, splitOutsideQuotes } from './condition.js'

// 2.3 절의 한도다. 의도 글은 500자, 기대 결과는 10개, 기대 결과마다 확인 조건은 1~10개다.
export const INTENT_TEXT_MAX = 500
export const INTENT_EXPECTATIONS_MAX = 10
export const INTENT_CHECKS_MAX = 10
// 범위(scope)에 쓸 수 있는 값 이름이다. service 가 시험 주문의 처음 값으로 범위를 평가하므로 이 다섯
// 가지만 의미가 있다. order.total 은 service 가 상품 단가 × 수량으로 계산한다(2.3 절). 주문 Agent 의
// 저장 검사(validIntent)는 이 다섯 이름과 정확히 같은 것만 받으므로(2.10 절 V3-2, 하위 경로나 앞부분도
// INVALID_SKILL_EDITS) 화면도 저장 전에 같은 기준으로 막는다.
export const SCOPE_KEYS = [
  'order.payment_method',
  'order.customer',
  'order.shipping.country',
  'order.shipping.express',
  'order.total',
]
// 범위와 확인 조건을 나누는 표시, 확인 조건끼리 나누는 표시, 범위 없음을 뜻하는 표시다.
const ARROW = '=>'
const CHECK_SEP = ';'
const ANY_SCOPE = '*'

/**
 * 고급 입력 한 줄을 { expectation, error } 로 읽는다. 읽으면 expectation 이 { scope, checks } 이고
 * error 는 null 이다. 틀리면 expectation 은 null 이고 error 가 화면 문구다. 범위는 앞뒤 공백을 빼고
 * 비었거나 '*' 이면 null 이다. 범위의 값 이름은 SCOPE_KEYS 가운데 하나와 정확히 같아야 한다. 확인
 * 조건은 따옴표 밖의 ';' 로 나누며 빈 조각(끝의 ';')은 버린다.
 */
export function parseExpectationLine(line) {
  const parts = splitOutsideQuotes(line, ARROW)
  if (parts.length < 2) {
    return { expectation: null, error: "'범위 => 확인 조건' 모양으로 적어 주세요. 범위가 없으면 '* => …' 로 시작해요." }
  }
  const [head, ...rest] = parts
  const scopeText = head.trim()
  const scope = scopeText === '' || scopeText === ANY_SCOPE ? null : scopeText
  if (scope !== null) {
    const read = readCondition(scope)
    if (!read) return { expectation: null, error: `범위 조건을 읽을 수 없어요: ${scope} (예: order.payment_method == 'card')` }
    if (!SCOPE_KEYS.includes(read.key)) {
      return {
        expectation: null,
        error: `범위의 값 이름 ${read.key} 는 쓸 수 없어요. 시험 주문의 처음 값(${SCOPE_KEYS.join(', ')}) 가운데 하나를 그대로 적어 주세요.`,
      }
    }
  }
  // '=>' 가 두 번 나오면 뒤쪽은 확인 조건 글로 되돌려 붙인다. 그 조건은 아래에서 읽지 못해 걸린다.
  const checks = splitOutsideQuotes(rest.join(ARROW), CHECK_SEP)
    .map((c) => c.trim())
    .filter(Boolean)
  if (!checks.length) return { expectation: null, error: '확인 조건을 하나 이상 적어 주세요.' }
  if (checks.length > INTENT_CHECKS_MAX) {
    return { expectation: null, error: `확인 조건은 한 줄에 ${INTENT_CHECKS_MAX}개까지예요(지금 ${checks.length}개).` }
  }
  const bad = checks.find((c) => !readCondition(c))
  if (bad) return { expectation: null, error: `확인 조건을 읽을 수 없어요: ${bad} (예: payment.status == null)` }
  return { expectation: { scope, checks }, error: null }
}

/**
 * 고급 입력 전체를 { expectations, errors } 로 읽는다. 빈 줄은 건너뛰고, errors 는
 * [{ line, message }] 이며 line 은 입력 칸의 줄 번호(1부터, 빈 줄 포함)다. 줄 수가 한도를 넘으면
 * line 이 null 인 문구를 맨 앞에 둔다. 틀린 줄이 있어도 읽은 줄은 expectations 에 남긴다.
 */
export function parseExpectations(text) {
  const expectations = []
  const errors = []
  const lines = (text ?? '').replace(/\r\n/g, '\n').split('\n')
  lines.forEach((raw, i) => {
    if (!raw.trim()) return
    const { expectation, error } = parseExpectationLine(raw)
    if (error) errors.push({ line: i + 1, message: error })
    else expectations.push(expectation)
  })
  const count = lines.filter((l) => l.trim()).length
  if (count > INTENT_EXPECTATIONS_MAX) {
    errors.unshift({ line: null, message: `기대 결과는 ${INTENT_EXPECTATIONS_MAX}줄까지 적을 수 있어요(지금 ${count}줄).` })
  }
  return { expectations, errors }
}

/**
 * 의도 한 줄(text)과 고급 입력(expectationsText)으로 저장 요청의 intent 를 만든다. 돌려주는 값은
 * { intent, textError, lineErrors } 다. 의도 글이 비면 intent 는 null 이라 요청에서 칸을 뺀다.
 * 다만 고급 입력만 적었으면 textError 로 의도 글을 먼저 적게 한다(intent 를 보낼 때 text 가 필수다).
 * 문제가 하나라도 있으면 intent 는 null 이고, 화면은 저장 버튼을 막고 문구를 보여 준다.
 * 글자 수는 서버(Java)의 String.length 와 같게 UTF-16 단위로 센다.
 */
export function buildIntent(text, expectationsText) {
  const body = (text ?? '').trim()
  const { expectations, errors } = parseExpectations(expectationsText)
  let textError = null
  if (!body && (expectations.length || errors.length)) textError = '기대 결과를 보내려면 의도를 한 줄로 먼저 적어 주세요.'
  else if (body.length > INTENT_TEXT_MAX) textError = `의도는 ${INTENT_TEXT_MAX}자 이하로 적어 주세요(지금 ${body.length}자).`
  const intent = !body || textError || errors.length ? null : { text: body, expectations }
  return { intent, textError, lineErrors: errors }
}

// 문제가 하나라도 있는지 본다. buildIntent 결과를 받는다. 저장 버튼을 막을 때 쓴다.
export const intentHasProblems = (built) => !!built.textError || built.lineErrors.length > 0

/**
 * 기대 결과 목록을 고급 입력 모양의 줄 목록으로 되돌린다. 범위가 null 이면 '*' 로 적는다.
 * 진단·제안서 화면이 받은 expectations 를 담당자가 적은 모양 그대로 보여 줄 때 쓴다.
 */
export const expectationLines = (expectations) =>
  (expectations ?? []).map((e) => `${e.scope ?? ANY_SCOPE} ${ARROW} ${(e.checks ?? []).join(`${CHECK_SEP} `)}`)
