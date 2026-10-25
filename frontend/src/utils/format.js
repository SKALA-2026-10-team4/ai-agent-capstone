// 화면 표시 형식 도우미다. UUID 줄이기, id 앞부분, 원 표기, 날짜 표기를 한곳에 둔다.
// frontend/src/utils/eventView.js, frontend/src/utils/traceRun.js,
// frontend/src/stores/workspace.js 와 여러 화면이 쓴다. import 가 없어
// frontend/unit/format.test.js 가 Node 에서 바로 불러온다.

// UUID 모양(16진수 8-4-4-4-12 자리)의 정규식 원문이다.
const UUID_PATTERN = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'

// 문자열 전체가 UUID 인지 보는 정규식이다. 대소문자를 가리지 않는다.
// frontend/src/utils/traceRun.js 의 executionPath 도 쓴다.
export const UUID_RE = new RegExp(`^${UUID_PATTERN}$`, 'i')
// 글 속의 UUID 를 모두 찾는 전역 정규식이다. replace 에만 쓰므로
// lastIndex 가 남아 다음 호출에 영향을 주지 않는다.
const UUID_IN_TEXT = new RegExp(UUID_PATTERN, 'gi')

// 값이 UUID 모양의 문자열인지 본다.
export const isUuid = (value) => typeof value === 'string' && UUID_RE.test(value)

// UUID 면 앞 8자 뒤에 '…' 를 붙이고, 아니면 값을 그대로 돌려준다.
export const shortUuid = (value) => (isUuid(value) ? `${value.slice(0, 8)}…` : value)

// 글 속의 UUID 를 모두 앞 8자와 '…' 로 줄인다. pay_mock_<uuid> 같은 참조 번호에 쓴다.
export const shortenUuidsIn = (text) => text.replace(UUID_IN_TEXT, (m) => shortUuid(m))

// id 앞 8자를 돌려준다. snapshot 과 실행 번호를 짧게 보일 때 쓴다.
export const shortId = (id) => id.slice(0, 8)

// 금액을 '30,000원' 처럼 쓴다. 자릿수 구분은 실행 환경 locale 을 따른다.
export const won = (amount) => `${amount.toLocaleString()}원`

// ISO 시각을 '2026.10.21 14:10' 처럼 쓴다. 실행 환경의 시간대로 보여 주고,
// 값이 없거나 읽을 수 없으면 빈 글자다. frontend/src/views/ProposalListView.vue 의 생성일시가 쓴다.
export const dateTime = (iso) => {
  const d = iso ? new Date(iso) : null
  if (!d || Number.isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
