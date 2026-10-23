// 주문 폼의 기본값, 입력 선택지, 클라이언트 검증, 예상 금액 계산을 모은 모듈이다.
// 검증은 서버 order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java 의 규칙
// (고객 형식과 64자, 상품 1~20줄, 수량 1~99, 결제 card 와 prepaid, 국가 KR, US, JP)을 화면에서 먼저 적용하고,
// 같은 상품을 두 줄에 고르는 입력도 막는다. 선택지나 수량 범위를 바꾸려면 이 파일의 상수와 서버 검증을 함께 고친다.
// order-agent/frontend/src/components/OrderForm.vue 가 같은 상수로 선택지와 수량 입력 범위를 그리고 estimate 로 예상 금액을 보여준다.
// order-agent/frontend/src/composables/useOrder.js 가 defaultForm 과 validate 를,
// order-agent/frontend/src/utils/demoPresets.js 가 defaultForm 과 DECLINED_CUSTOMER 를 쓴다.
// order-agent/frontend/tests/validation.test.js, order-agent/frontend/tests/inputOptions.test.js, order-agent/frontend/tests/order.test.js 가
// 규칙과 문구를 고정한다.

// 결제 거절을 시연하는 고객 ID 다. order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/PaymentMockAdapter.java 의
// DECLINED_CUSTOMER 와 같아야 이 고객의 카드 결제가 거절된다.
export const DECLINED_CUSTOMER = "c_declined";
// 데모 고객 select 의 선택지이며 화면 순서대로 둔다. 고객 검증은 이 목록이 아니라 validate 의 정규식이 한다.
export const DEMO_CUSTOMERS = [
  { value: "c_1001", label: "일반 고객 · c_1001" },
  { value: DECLINED_CUSTOMER, label: "결제 거절 고객 · c_declined" },
];
// 결제 방식 선택지다. 서버가 허용하는 card 와 prepaid 만 두고, validate 도 이 목록으로 검사한다.
export const PAYMENT_METHODS = [
  { value: "card", label: "카드 · Mock" },
  { value: "prepaid", label: "선입금 · Mock" },
];
// 배송 국가 선택지다. 서버가 허용하는 KR, US, JP 만 두고, validate 도 이 목록으로 검사한다.
export const SHIPPING_COUNTRIES = [
  { value: "KR", label: "대한민국" },
  { value: "US", label: "미국" },
  { value: "JP", label: "일본" },
];
// 수량 입력 범위다. 서버 order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java 의
// Item 수량 범위와 같고, order-agent/frontend/src/components/OrderForm.vue 의 number 입력과 validate 가 함께 쓴다.
// estimate 는 이 범위를 적용하지 않는다.
export const QTY_MIN = 1;
export const QTY_MAX = 99;
/**
 * 상품 행 필드의 오류 키(items[0].qty 형식)를 만든다.
 * 서버 field_errors 의 field 경로와 같은 형식이라 서버 오류와 화면 검증 오류를 같은 입력 칸에 보여줄 수 있다.
 */
export const itemFieldKey = (index, field) => `items[${index}].${field}`;
/** value 가 선택지 목록의 value 중 하나인지 확인한다. 결제 방식과 배송 국가 검사에 쓴다. */
const isOption = (options, value) =>
  options.map((option) => option.value).includes(value);
/**
 * 새 기본 주문 폼을 만든다. 호출할 때마다 새 객체를 돌려주므로 폼끼리 값을 공유하지 않는다.
 * 키는 POST /api/orders 본문과 같은 snake_case 이고, 기본 상품 CAMP-LANTERN-LED 는
 * order-agent/backend/src/main/resources/data.sql 의 데모 상품이다.
 * order-agent/frontend/tests/validation.test.js 가 이 폼의 JSON 문자열과 키 순서를 고정한다.
 */
export const defaultForm = () => ({
  customer: "c_1001",
  items: [{ sku: "CAMP-LANTERN-LED", qty: 1 }],
  payment_method: "card",
  shipping: { country: "KR", express: false },
});
/**
 * 주문 폼을 서버 검증 규칙으로 미리 검사하고 { 오류 키: 메시지 } 객체를 돌려준다. 오류가 없으면 빈 객체다.
 * 오류 키는 order-agent/frontend/src/utils/orderPolicy.js 의 toFieldErrors 가 서버 field_errors 를 바꾼 키와 같은 형식
 * (customer, items, items[N].sku, items[N].qty, payment_method, shipping.country)이다.
 * 고객은 목록 포함 여부가 아니라 서버와 같은 c_ 접두 정규식과 64자 제한으로 검사한다.
 * 상품 줄의 sku 가 비었거나 앞 줄과 같으면 그 줄에 sku 오류를 내므로, 중복은 두 번째 줄부터 표시된다.
 * 오류 키를 넣는 순서와 메시지는 order-agent/frontend/tests/validation.test.js 가 고정한다.
 * order-agent/frontend/src/composables/useOrder.js 의 submit 이 요청을 보내기 전에 부른다.
 */
export function validate(form) {
  const errors = {};
  if (
    !/^c_[A-Za-z0-9_]+$/.test(form.customer.trim()) ||
    form.customer.trim().length > 64
  )
    errors.customer = "데모 고객을 선택해주세요.";
  if (!form.items.length || form.items.length > 20)
    errors.items = "상품을 1개 이상 선택해주세요.";
  const seen = new Set();
  form.items.forEach((item, index) => {
    if (!item.sku || seen.has(item.sku))
      errors[itemFieldKey(index, "sku")] = "서로 다른 상품을 선택해주세요.";
    seen.add(item.sku);
    if (
      !Number.isInteger(item.qty) ||
      item.qty < QTY_MIN ||
      item.qty > QTY_MAX
    )
      errors[itemFieldKey(index, "qty")] =
        `수량은 ${QTY_MIN}~${QTY_MAX} 정수로 입력해주세요.`;
  });
  if (!isOption(PAYMENT_METHODS, form.payment_method))
    errors.payment_method = "결제 방식을 선택해주세요.";
  if (!isOption(SHIPPING_COUNTRIES, form.shipping.country))
    errors["shipping.country"] = "배송 국가를 선택해주세요.";
  return errors;
}
/**
 * 화면에 보일 예상 주문 금액을 계산한다. 단가는 products 에서 sku 로 찾고, 수량이 양의 정수가 아니면 그 줄을 0 원으로 본다.
 * 수량 상한(QTY_MAX)은 적용하지 않아 99 를 넘는 수량도 금액에 들어간다. 최종 금액은 서버가 확정한다.
 * order-agent/frontend/src/components/OrderForm.vue 가 예상 금액 줄에 쓴다.
 */
export function estimate(form, products) {
  return form.items.reduce(
    (total, item) =>
      total +
      (products.find((p) => p.sku === item.sku)?.unit_price || 0) *
        (Number.isInteger(item.qty) && item.qty > 0 ? item.qty : 0),
    0,
  );
}
