// 조건 문법 v2 한 줄(`값이름 연산자 값`)을 읽고 평가한다. 문법은
// docs/plans/2026-10-26-behavior-edit-root-fix.md 2.1 절이고, 공용 사례는
// docs/contracts/examples/condition-cases.json 이다. service 의
// service/src/main/java/com/capstone4/skillops/scenario/Check.java 와 주문 Agent 의 RuleCondition 이
// 같은 사례로 같은 결과를 내야 하므로, 이 파일의 LINE, NUMBER, literal, splitOutsideQuotes 도 그
// 규칙을 그대로 따른다. 화면에서는 담당자 의도 입력 검사(frontend/src/utils/intentInput.js),
// Skill 실행 규칙 rules 의 형식 검사(frontend/src/utils/skillSet.js), mock 주문 실행의 규칙
// 평가(frontend/src/mocks/index.js)가 쓴다. 판정은 늘 서버가 다시 하므로 여기서는 저장 전에
// 담당자에게 미리 알려 주는 용도다. frontend/unit/condition.test.js 가 공용 사례로 확인한다.

// 조건 한 줄의 모양이다. 공백 없는 값 이름, 연산자, 값 순서이고 연산자 앞뒤에 공백이 있어야 한다.
// 앞뒤 공백은 무시한다. 두 글자 연산자를 한 글자보다 먼저 맞춰 본다.
const LINE = /^\s*(\S+)\s+(==|!=|>=|<=|>|<|in)\s+(.+?)\s*$/
// 숫자로 읽는 값의 모양이다. -12 나 3.5 만 받고 +1, 1e3 같은 모양은 받지 않는다.
const NUMBER = /^-?\d+(\.\d+)?$/
// 숫자 비교 연산자다. 오른쪽은 숫자만 읽는다.
const NUMERIC_OPS = new Set(['>', '>=', '<', '<='])
// literal 이 값을 읽지 못했을 때 돌려주는 표시다. null 도 값이라 따로 둔다.
const UNREADABLE = Symbol('unreadable')

/**
 * 따옴표 밖의 sep 로 글을 나눈다. 작은따옴표 안의 sep 는 나누지 않는다. 조건의 in 목록(쉼표),
 * 의도 입력의 확인 조건 목록(';')과 범위 구분('=>')을 나눌 때 쓴다. Check.java 의
 * splitOutsideQuotes 와 같은 규칙이고, 여러 글자 sep 도 받는다.
 */
export function splitOutsideQuotes(text, sep) {
  const parts = []
  let quoted = false
  let start = 0
  for (let i = 0; i < text.length; i++) {
    if (text[i] === "'") quoted = !quoted
    else if (!quoted && text.startsWith(sep, i)) {
      parts.push(text.slice(start, i))
      start = i + sep.length
      i += sep.length - 1
    }
  }
  parts.push(text.slice(start))
  return parts
}

// 값 한 칸을 읽는다. '글자', true, false, null, 숫자 순서로 보고 어느 것도 아니면 UNREADABLE 이다.
function literal(raw) {
  const v = raw.trim()
  if (v.length >= 2 && v.startsWith("'") && v.endsWith("'")) return v.slice(1, -1)
  if (v === 'true' || v === 'false') return v === 'true'
  if (v === 'null') return null
  if (NUMBER.test(v)) return Number(v)
  return UNREADABLE
}

/**
 * 조건 한 줄을 { text, key, op, value } 로 읽는다. 읽지 못하면 null 이다. value 는 글자, 숫자,
 * 참·거짓, null 이고 in 이면 그 값들의 목록이다. 숫자 비교(>, >=, <, <=)의 오른쪽은 숫자만 받고,
 * [..] 목록은 in 뒤에만 온다(빈 목록 허용).
 */
export function readCondition(text) {
  const m = typeof text === 'string' ? text.match(LINE) : null
  if (!m) return null
  const [, key, op, raw] = m
  let value
  if (op === 'in') {
    if (!raw.startsWith('[') || !raw.endsWith(']')) return null
    const inner = raw.slice(1, -1)
    value = inner.trim() ? splitOutsideQuotes(inner, ',').map(literal) : []
    if (value.includes(UNREADABLE)) return null
  } else if (NUMERIC_OPS.has(op)) {
    if (!NUMBER.test(raw)) return null
    value = Number(raw)
  } else {
    value = literal(raw)
    if (value === UNREADABLE) return null
  }
  return { text, key, op, value }
}

// 조건을 읽을 수 있는지 본다.
export const isReadableCondition = (text) => readCondition(text) !== null

// 값이 { } 객체(Map)인지 본다. 목록과 null 은 아니다.
const isMap = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

/**
 * 문맥에서 값 이름 key 의 값을 찾는다. 그 이름이 그대로 있으면 그 값이고, 없으면 점으로 나눈 가장 긴
 * 앞부분이 문맥에 있을 때 그 값이 객체면 나머지 경로로 내려간다. 더 짧은 앞부분으로 다시 시도하지
 * 않고, 끝내 없으면 null 이다. 2.1 절의 '값 찾기' 규칙이다.
 */
export function lookup(context, key) {
  if (Object.hasOwn(context, key)) return context[key]
  const parts = key.split('.')
  for (let n = parts.length - 1; n > 0; n--) {
    const prefix = parts.slice(0, n).join('.')
    if (!Object.hasOwn(context, prefix)) continue
    let value = context[prefix]
    for (const part of parts.slice(n)) {
      if (!isMap(value) || !Object.hasOwn(value, part)) return null
      value = value[part]
    }
    return value ?? null
  }
  return null
}

// 두 값이 같은지 본다. 하나라도 null(또는 없음)이면 둘 다 없을 때만 같다. 참·거짓은 숫자 1·0 과
// 같지 않고, 객체와 목록은 어떤 값과도 같지 않다.
function same(a, b) {
  if (a == null || b == null) return a == null && b == null
  return typeof a === typeof b && typeof a !== 'object' && a === b
}

/**
 * 읽은 조건(readCondition 결과)이 문맥에서 맞는지 본다. 숫자 비교는 실제 값이 숫자가 아니면
 * 거짓이다. frontend/src/mocks/index.js 의 규칙 평가가 쓴다.
 */
export function holds(condition, context) {
  const actual = lookup(context, condition.key)
  switch (condition.op) {
    case '==':
      return same(actual, condition.value)
    case '!=':
      return !same(actual, condition.value)
    case 'in':
      return condition.value.some((v) => same(actual, v))
    default: {
      if (typeof actual !== 'number') return false
      const v = condition.value
      return condition.op === '>' ? actual > v : condition.op === '>=' ? actual >= v : condition.op === '<' ? actual < v : actual <= v
    }
  }
}
