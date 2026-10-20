<script setup>
import { money, statusText, timestamp } from "../utils/format.js";
import OrderSteps from "./OrderSteps.vue";
defineProps({
  order: Object,
  previous: Boolean,
  requestState: String,
  error: Object,
  canResend: Boolean,
  locked: Boolean,
  requestId: String,
});
defineEmits(["recover", "resend", "new-order"]);
const paymentLabels = {
  PAID: "승인 완료",
  DECLINED: "결제 거절",
  NOT_ATTEMPTED: "미실행",
};
</script>
<template>
  <section
    class="panel result-panel"
    aria-labelledby="result-title"
    aria-live="polite"
    aria-atomic="false"
  >
    <div class="section-heading">
      <div>
        <span class="eyebrow">02 / RESULT</span>
        <h2 id="result-title">주문 결과</h2>
      </div>
      <span v-if="order" class="pill" :class="order.status.toLowerCase()">{{
        statusText[order.status] || order.status
      }}</span>
    </div>
    <div
      v-if="requestState === 'submitting' || requestState === 'polling'"
      class="notice"
      role="status"
    >
      <span class="spinner" aria-hidden="true"></span>
      {{
        requestState === "submitting"
          ? "주문을 처리하고 있습니다."
          : "서버의 주문 상태를 확인하고 있습니다."
      }}
    </div>
    <div v-if="error" class="notice error" role="alert">
      <strong>{{
        requestState === "recoverable"
          ? "결과 확인이 필요해요"
          : "요청을 확인해주세요"
      }}</strong>
      <p>{{ error.message }}</p>
    </div>
    <template v-if="order">
      <p v-if="previous" class="notice subtle">직전 주문 결과입니다. 현재 작성 중인 입력과 다를 수 있어요.</p>
      <p class="result-message">{{ order.message }}</p>
      <dl class="order-identifiers">
        <div>
          <dt>주문 ID</dt>
          <dd>{{ order.order_id }}</dd>
        </div>
        <div>
          <dt>요청 ID</dt>
          <dd>{{ order.request_id }}</dd>
        </div>
        <div>
          <dt>접수 시각</dt>
          <dd>{{ timestamp(order.created_at) }}</dd>
        </div>
      </dl>
      <div class="receipt">
        <div v-for="item in order.items" :key="item.sku" class="receipt-line">
          <span
            >{{ item.name }} <small>× {{ item.qty }}</small></span
          ><strong>{{ money(item.line_total) }}</strong>
        </div>
        <div class="receipt-total">
          <span>확정 금액</span><strong>{{ money(order.total_amount) }}</strong>
        </div>
      </div>
      <dl class="order-summary">
        <div>
          <dt>결제</dt>
          <dd>
            {{ paymentLabels[order.payment.status] || order.payment.status }}
          </dd>
        </div>
        <div>
          <dt>배송 예정일</dt>
          <dd>{{ order.shipment?.estimated_delivery_date || "미실행" }}</dd>
        </div>
        <div>
          <dt>성공 알림</dt>
          <dd>
            {{
              order.notification.status === "SENT" ? "Mock 기록 완료" : "미실행"
            }}
          </dd>
        </div>
      </dl>
      <details v-if="order.payment.reference || order.shipment">
        <summary>Mock 처리 참조 보기</summary>
        <p class="mono">{{ order.payment.reference || "결제 미실행" }}</p>
        <p class="mono">{{ order.shipment?.reference || "배송 미실행" }}</p>
      </details>
      <p v-if="order.execution_id" class="request-key">Agent 실행 ID <span class="mono">{{ order.execution_id }}</span> · 아래 Trace · Snapshot에서 실제 기록을 확인하세요.</p>
      <OrderSteps :steps="order.steps" />
    </template>
    <div v-else-if="requestState === 'idle'" class="empty-state">
      <div class="tent-mark" aria-hidden="true">△</div>
      <h3>다음 캠핑의 시작</h3>
      <p>
        상품을 고르고 주문을 실행하면<br />이곳에서 결과와 처리 단계를 확인할 수
        있어요.
      </p>
      <div class="empty-flow">
        재고 확인 <span>→</span> 결제 <span>→</span> 배송 예약
      </div>
    </div>
    <p v-if="requestId && !order" class="request-key">
      요청 ID <span class="mono">{{ requestId }}</span>
    </p>
    <div class="button-row result-actions">
      <button
        v-if="requestState === 'recoverable'"
        class="button primary"
        type="button"
        @click="$emit('recover')"
      >
        상태 다시 확인
      </button>
      <button
        v-if="canResend"
        class="button ghost"
        type="button"
        @click="$emit('resend')"
      >
        같은 요청으로 재전송
      </button>
      <button
        v-if="order && !locked"
        class="button ghost"
        type="button"
        @click="$emit('new-order')"
      >
        새 주문 작성
      </button>
    </div>
    <small v-if="requestState === 'recoverable'" class="muted"
      >네트워크 오류가 주문 실패를 뜻하지는 않아요. 새 주문 전에 현재 결과를
      확인해주세요.</small
    >
  </section>
</template>
