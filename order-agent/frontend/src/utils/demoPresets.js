// 시연용 입력 preset 의 튜닝 지점이다. preset 표 DEMO_PRESETS 와, preset 을 적용한 새 폼을 만드는 presetForm 을 둔다.
// DEMO_PRESETS 가 버튼 순서, 버튼 문구, 폼 수정 규칙을 모두 정하므로 시연 시나리오를 더하거나 바꾸려면 화면 쪽에서는 이 표만 고친다.
// order-agent/frontend/src/components/DemoPresets.vue 가 이 표로 버튼을 그리고,
// order-agent/frontend/src/composables/useOrder.js 의 preset 이 presetForm 으로 폼을 갈아끼운다.
// 각 preset 은 order-agent backend 의 데모 데이터로 결과를 재현한다. soldout 의 CAMP-MUG-STEEL 은
// order-agent/backend/src/main/resources/data.sql 에서 재고가 0 인 상품이고, declined 의 고객은
// order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/PaymentMockAdapter.java 가 카드 결제를 거절하는 고객이다.
// order-agent/frontend/tests/orderPolicy.test.js 가 버튼 순서와 preset 결과를 고정하고,
// order-agent/frontend/tests/e2e/order.spec.js 는 버튼 문구로 버튼을 찾고 preset 결과를 실제 주문으로 확인한다.
// 표의 순서, 문구, 폼 수정 규칙을 바꾸면 두 테스트의 기대값도 함께 고친다.
import { DECLINED_CUSTOMER, defaultForm } from "./validation.js";

// 시연 preset 표다. name 은 order-agent/frontend/src/components/DemoPresets.vue 가 preset 이벤트로 올리는 값이고,
// label 은 버튼 문구다. apply 는 새로 만든 기본 폼의 값만 고치고 키를 더하지 않는다. 서버가 모르는 키를 400 으로 거절하고,
// order-agent/frontend/src/composables/useOrder.js 의 previousOrder 가 폼을 JSON 문자열로 비교하기 때문이다.
export const DEMO_PRESETS = [
  {
    name: "normal",
    label: "정상 주문",
    apply: (form) => {
      form.items[0].qty = 2;
    },
  },
  {
    name: "soldout",
    label: "재고 부족",
    apply: (form) => {
      form.items[0].sku = "CAMP-MUG-STEEL";
    },
  },
  {
    name: "declined",
    label: "결제 거절",
    apply: (form) => {
      form.customer = DECLINED_CUSTOMER;
    },
  },
];
/**
 * 새 기본 폼을 만들고 name 이 맞는 preset 을 적용해 돌려준다. 모르는 이름이면 기본 폼을 그대로 돌려준다.
 * 지금 폼을 고치지 않고 매번 새 폼을 만들기 때문에 이전에 고정한 snapshot_id 도 함께 사라진다.
 * order-agent/docs/team-scenarios.md 와 order-agent/docs/agent-evidence.md 가 이 동작을 시연 절차로 적어 두었다.
 * order-agent/frontend/src/composables/useOrder.js 의 preset 이 부른다.
 */
export function presetForm(name) {
  const form = defaultForm();
  DEMO_PRESETS.find((preset) => preset.name === name)?.apply(form);
  return form;
}
