<script setup>
// 주문 데모 화면 전체를 배선하는 최상위 컴포넌트다. order-agent/frontend/src/main.js 가 이 컴포넌트로 앱을 만든다.
// order-agent/frontend/src/composables/useOrder.js 로 주문과 상품 상태를 한 번 만들고, 그 상태와 동작을
// order-agent/frontend/src/components/OrderForm.vue, order-agent/frontend/src/components/DemoPresets.vue,
// order-agent/frontend/src/components/OrderResult.vue, order-agent/frontend/src/components/ExecutionInspector.vue 에
// props 와 이벤트로 나눠 준다. 상태 전이 규칙은 useOrder.js 에, 흐름 튜닝 값은 order-agent/frontend/src/utils/orderPolicy.js 에
// 있고, 이 파일은 배선, lifecycle 연결, 첫 오류 입력 focus 만 맡는다. lifecycle 연결을 useOrder 가 아니라 여기에 두는
// 이유는, useOrder 가 lifecycle hook 을 부르지 않아야 node --test 에서 경고 없이 검사할 수 있기 때문이다.
// DemoPresets 버튼은 입력이 잠겼거나 상품 목록이 준비되지 않았을 때 꺼진다. 이 조건은 OrderForm 의 입력 잠금 조건과 같으므로
// 한쪽을 바꾸면 다른 쪽도 함께 고친다.
// ExecutionInspector 가 고정한 Skill 후보의 snapshot_id 는 snapshot 이벤트로 올라와 useOrder 의 selectSnapshot 이
// 다음 주문 입력에 넣는다.
import { onMounted, onUnmounted, watch, nextTick } from "vue";
import { useOrder } from "./composables/useOrder.js";
import OrderForm from "./components/OrderForm.vue";
import OrderResult from "./components/OrderResult.vue";
import ExecutionInspector from "./components/ExecutionInspector.vue";
import DemoPresets from "./components/DemoPresets.vue";
// 화면 하나에 주문 상태 기계 하나를 둔다. submit, recover 같은 동작은 state 의 메서드를 이벤트에 바로 연결한다.
const state = useOrder();
// 템플릿이 ref 를 자동으로 풀고 v-model:form 이 form.value 에 쓰도록 최상위 변수로 꺼낸다.
// state.form 처럼 객체 속성으로 넘기면 v-model 이 ref 자체를 새 값으로 덮어쓴다.
const {
  products,
  productStatus,
  productError,
  form,
  order,
  submittedRequest,
  requestState,
  requestError,
  fieldErrors,
  canResend,
  locked,
  previousOrder,
} = state;
// 화면이 붙으면 상품을 읽고, 화면이 사라지면 남은 요청과 polling 대기를 정리한다.
onMounted(state.loadProducts);
onUnmounted(state.dispose);
// 검증 오류가 생기면 DOM 갱신을 기다린 뒤 첫 aria-invalid 입력으로 focus 를 옮긴다.
// order-agent/frontend/src/components/OrderForm.vue 가 aria-invalid 에 boolean 을 넘겨야 이 선택자가 맞고,
// order-agent/frontend/tests/e2e/order.spec.js 의 'invalid quantity is focused without sending an order' 가 이 동작을 확인한다.
watch(fieldErrors, async (errors) => {
  if (Object.keys(errors).length) {
    await nextTick();
    document.querySelector('[aria-invalid="true"]')?.focus();
  }
});
</script>
<template>
  <main class="app-shell">
    <header class="app-header">
      <a class="brand" href="/" aria-label="Order Agent 처음 화면"
        ><svg
          width="38"
          height="38"
          viewBox="0 0 40 40"
          fill="none"
          aria-hidden="true"
        >
          <rect width="40" height="40" rx="12" fill="currentColor" />
          <path d="M9 29 20 10l11 19H9Z" stroke="#F5F2E9" stroke-width="1.6" />
          <path
            d="m16 29 4-8 4 8M20 10v11"
            stroke="#F5F2E9"
            stroke-width="1.6"
          /></svg
        ><span>ORDER AGENT<small>CAMPING ORDER DEMO</small></span></a
      ><span class="environment"><i></i>독립 주문 서비스 · MVP</span>
    </header>
    <section class="hero">
      <div class="eyebrow">READY FOR YOUR NEXT CAMP</div>
      <h1>
        가볍게 담고,<br class="mobile-break" />
        흐름을 확인하세요.
      </h1>
      <p>
        캠핑용품 주문부터 재고, 결제, 배송 예약까지.<br />외부 결제·배송이
        발생하지 않는 주문 데모입니다.
      </p>
    </section>
    <div class="workspace">
      <div class="input-column">
        <OrderForm
          v-model:form="form"
          :products="products"
          :product-status="productStatus"
          :product-error="productError"
          :locked="locked"
          :field-errors="fieldErrors"
          @submit="state.submit"
          @reload-products="state.loadProducts"
        /><DemoPresets
          :disabled="locked || productStatus !== 'ready'"
          @preset="state.preset"
        />
      </div>
      <OrderResult
        :order="order"
        :previous="previousOrder"
        :request-state="requestState"
        :error="requestError"
        :can-resend="canResend"
        :locked="locked"
        :request-id="submittedRequest?.key"
        @recover="state.recover"
        @resend="state.resend"
        @new-order="state.newOrder"
      />
    </div>
    <ExecutionInspector :execution-id="order?.execution_id" :locked="locked" :selected-snapshot-id="form.snapshot_id"
      @snapshot="state.selectSnapshot" />
    <footer class="app-footer">
      <span>Order Agent / 캠핑용품 주문 실험실</span
      ><span>Spring Boot · Vue · PostgreSQL</span>
    </footer>
  </main>
</template>
