<script setup>
import { computed, onMounted, onUnmounted, watch, nextTick } from "vue";
import { useOrder } from "./composables/useOrder.js";
import OrderForm from "./components/OrderForm.vue";
import OrderResult from "./components/OrderResult.vue";
import ExecutionInspector from "./components/ExecutionInspector.vue";
import DemoPresets from "./components/DemoPresets.vue";
const state = useOrder();
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
} = state;
const previousOrder = computed(() => !!order.value && !!submittedRequest.value && JSON.stringify(form.value) !== JSON.stringify(submittedRequest.value.body));
onMounted(state.loadProducts);
onUnmounted(state.dispose);
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
      @snapshot="(id) => { if(!locked) form = { ...form, snapshot_id: id } }" />
    <footer class="app-footer">
      <span>Order Agent / 캠핑용품 주문 실험실</span
      ><span>Spring Boot · Vue · PostgreSQL</span>
    </footer>
  </main>
</template>
