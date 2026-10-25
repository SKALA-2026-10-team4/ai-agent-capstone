// order-agent/skills 의 게시본(release camping-v1) 파일을 그대로 옮긴 mock
// 데이터다. frontend/src/mocks/index.js 가 게시본 실행과 fixture snapshot 에
// 쓰고, frontend/unit/flowGraph.test.js, frontend/unit/flowGraphOf.test.js,
// frontend/unit/skillSet.test.js, frontend/unit/snapshotDiff.test.js 가
// 실제와 같은 Skill 세트로 쓴다. import 가 없어 Node 테스트에서 바로 불러올
// 수 있다. order-agent/skills 의 파일을 고치면 이 파일도 함께 고친다. 맨 아래의
// prepaidPolicyFiles, weatherNotifyFiles, withoutInput 은 게시본이 아니라 실행 규칙(rules) 예시와 제안서
// 근거 실행을 만드는 mock 전용 세트다.

// 게시본 묶음의 경로마다 파일 원문이다. resources/policy.md 처럼
// SKILL.md 가 아닌 관련 파일도 들어 있다.
export const PUBLISHED_FILES = {
  "skills/inventory-check/SKILL.md": "---\nname: inventory-check\ndescription: 상품 전체 재고를 확인하고 예약한다\ninputs: [order.id, order.items]\noutputs: [inventory.available, inventory.reserved]\ndepends_on: [order-intake]\nowner: 주문운영팀\nversion: 1\n---\n# 상품 전체 재고를 확인하고 예약한다\n\n재고 예약 도구에 주문번호와 모든 상품·수량을 전달합니다. 하나라도 부족하면 전체 거절하고 종료합니다. 충분하면 전체 수량을 예약하고 실제 예약 결과를 기록합니다.\n",
  "skills/inventory-check/resources/policy.md": "# 예약 정책\n\n여러 상품 중 하나라도 부족하면 부분 예약하지 않습니다. 미결제 예약만 해제할 수 있습니다.\n",
  "skills/notify/SKILL.md": "---\nname: notify\ndescription: 배송 예약 결과를 고객에게 알린다\ninputs: [order.id, order.customer, payment.status, shipping.id, shipping.eta]\noutputs: [notify.sent, notify.channel]\ndepends_on: [shipping-reserve]\nowner: 주문운영팀\nversion: 1\n---\n# 배송 예약 결과를 고객에게 알린다\n\n배송이 예약된 주문만 성공 알림 도구를 호출합니다. 주문번호와 고객 식별자를 전달합니다. 실제 알림 결과를 기록합니다.\n",
  "skills/order-flow/SKILL.md": "---\nname: order-flow\ndescription: 캠핑용품 주문의 처리 순서\nsteps: [order-intake, inventory-check, payment, shipping-reserve, notify]\nowner: 주문운영팀\nversion: 1\n---\n# 캠핑용품 주문의 처리 순서\n\n위 steps 순서대로 처리합니다. 재고가 부족하면 결제와 배송을 중단합니다. 결제가 거절되면 예약을 해제하고 배송과 성공 알림을 중단합니다.\n",
  "skills/order-intake/SKILL.md": "---\nname: order-intake\ndescription: 접수된 주문의 내용과 서버 계산 금액을 검수한다\ninputs: [order.id, order.customer, order.items, order.payment_method, order.shipping]\noutputs: [order.id, order.total]\ndepends_on: []\nowner: 주문운영팀\nversion: 1\n---\n# 접수된 주문의 내용과 서버 계산 금액을 검수한다\n\n주문 조회 도구로 이미 접수된 주문과 서버가 계산한 금액을 확인합니다. 새 주문을 생성하지 않습니다. 확인된 주문번호와 총액을 기록합니다.\n",
  "skills/payment/SKILL.md": "---\nname: payment\ndescription: 예약된 주문 금액을 결제한다\ninputs: [order.id, order.total, order.payment_method, inventory.reserved]\noutputs: [payment.id, payment.status]\ndepends_on: [inventory-check]\nowner: 주문운영팀\nversion: 1\n---\n# 예약된 주문 금액을 결제한다\n\n재고 예약 성공을 확인합니다. 결제 도구에 주문번호·서버 총액·결제 방식을 전달합니다. 승인 시 PAID와 결제 참조를 기록합니다. 거절 시 DECLINED로 종료하고 서버의 재고 해제 정책을 따릅니다.\n",
  "skills/shipping-reserve/SKILL.md": "---\nname: shipping-reserve\ndescription: 결제 완료 주문의 배송을 예약한다\ninputs: [order.id, order.shipping, payment.id, payment.status, inventory.reserved]\noutputs: [shipping.id, shipping.eta]\ndepends_on: [payment]\nowner: 주문운영팀\nversion: 1\n---\n# 결제 완료 주문의 배송을 예약한다\n\n결제 PAID와 재고 예약을 확인합니다. 배송 예약 도구에 주문번호·배송 국가·빠른 배송 여부를 전달합니다. 실제 배송 참조와 예정일을 기록합니다.\n"
}

/**
 * fixture 이름으로 Skill 세트 파일을 만든다. baseline 은 게시본 그대로이고, 나머지는
 * inventory-check 의 inventory.available 을 모두 inventory.reservable_quantity 로 바꾼다.
 * broken-contract 는 여기에 inventory.reserved 출력까지 빼서 payment 와 shipping-reserve 의 입력이
 * 끊기게 하고, repaired 는 그 출력을 남긴 채 inventory.reserved 를 기록하는 규칙 문장을 본문 끝에
 * 더한다. 변환 규칙은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의
 * demo() 와 같다. frontend/src/mocks/index.js 의 publishFixture 와 frontend/unit/flowGraph.test.js,
 * frontend/unit/flowGraphOf.test.js, frontend/unit/snapshotDiff.test.js 가 부른다.
 */
export function fixtureFiles(fixture) {
  const files = { ...PUBLISHED_FILES }
  if (fixture === 'baseline') return files
  const path = 'skills/inventory-check/SKILL.md'
  let text = files[path].replaceAll('inventory.available', 'inventory.reservable_quantity')
  if (fixture === 'broken-contract') text = text.replace(', inventory.reserved', '')
  else text += '\n예약 가능 수량이 주문 수량 이상이면 inventory.reserved를 true로 기록한다.\n'
  files[path] = text
  return files
}

// 실행 규칙(rules) 예시에 쓰는 담당자 정책 문장과 규칙이다. docs/plans/2026-10-26-behavior-edit-root-fix.md
// 2.9 절 B-01('선입금 주문만 결제')의 모양이다. frontend/src/mocks/proposal-b01.json 의 제안서도 같은
// 문장과 규칙을 쓴다.
export const PREPAID_POLICY = '선입금 주문만 결제합니다. 카드 주문은 결제하지 않고 보류합니다.'
export const PREPAID_RULE = "rules:\n  - when: \"order.payment_method != 'prepaid'\"\n    then: hold\n    reason: PREPAID_ONLY\n"

/**
 * 게시본 payment 본문에 PREPAID_POLICY 를 더하고, withRule 이 참이면 그 정책을 frontmatter 의 실행
 * 규칙(PREPAID_RULE)으로도 옮긴 Skill 세트 파일을 만든다. 규칙이 없으면 주문 Agent 는 본문 문장만으로
 * 보류하지 않고, 규칙이 있으면 카드 주문이 payment 단계에서 HOLD 로 멈춘다.
 * frontend/src/mocks/index.js 의 seed 와 frontend/unit/skillRules.test.js 가 부른다.
 */
export function prepaidPolicyFiles({ withRule }) {
  const files = { ...PUBLISHED_FILES }
  const path = 'skills/payment/SKILL.md'
  let text = `${files[path]}\n${PREPAID_POLICY}\n`
  if (withRule) text = text.replace('version: 1\n---\n', `version: 1\n${PREPAID_RULE}---\n`)
  files[path] = text
  return files
}

/**
 * files 의 skill SKILL.md frontmatter inputs 에서 key 하나를 뺀 사본을 돌려준다. 원본은 고치지 않는다.
 * frontend/src/mocks/index.js 의 seedPastRuns 가 제안서 후보 세트(받는 값을 줄인 수정안)를 만들 때 부른다.
 */
export function withoutInput(files, skill, key) {
  const path = `skills/${skill}/SKILL.md`
  const text = files[path]
    .split('\n')
    .map((line) => (line.startsWith('inputs: [') ? `inputs: [${line.slice(9, -1).split(', ').filter((k) => k !== key).join(', ')}]` : line))
    .join('\n')
  return { ...files, [path]: text }
}

// notify 에 '배송지에 태풍 경보가 있으면 알림 전에 보류' 정책을 실행 규칙으로 넣은 모양이다.
// frontend/src/mocks/proposal-w1.json 의 수정 후 세트와 같다.
export const WEATHER_RULE = "rules:\n  - when: \"shipping.weather == 'storm'\"\n    then: hold\n    reason: WEATHER_HOLD\n"

/**
 * 게시본 notify 의 inputs 에 shipping.weather 를 더하고 WEATHER_RULE 을 넣어 version 2 로 올린 세트 파일을
 * 만든다. 어떤 업무 도구도 shipping.weather 를 만들지 않으므로 이 세트로 주문하면 notify 에서 실행 전 계약
 * 검사(CONTRACT_VIOLATION)에 걸린다. frontend/src/mocks/index.js 의 seedPastRuns 가 부른다.
 */
export function weatherNotifyFiles() {
  const files = { ...PUBLISHED_FILES }
  const path = 'skills/notify/SKILL.md'
  files[path] = files[path]
    .replace('shipping.eta]', 'shipping.eta, shipping.weather]')
    .replace('version: 1\n---\n', `version: 2\n${WEATHER_RULE}---\n`)
  return files
}
