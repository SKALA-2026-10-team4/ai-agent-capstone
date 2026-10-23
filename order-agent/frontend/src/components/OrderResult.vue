<script setup>
// 주문 결과 패널이다. 상태 배지, 주문 식별자, 영수증, 결제, 배송, 알림 요약, Agent 실행 ID, 처리 단계,
// 진행 안내와 오류 안내, 그리고 결과 확인, 재전송, 새 주문 버튼을 보여준다.
// 버튼은 recover, resend, new-order 이벤트만 올린다. order-agent/frontend/src/App.vue 가 그 이벤트를
// order-agent/frontend/src/composables/useOrder.js 의 recover, resend, newOrder 에 연결한다.
// "상태 다시 확인" 버튼은 requestState 가 recoverable 일 때, 재전송 버튼은 canResend 일 때,
// 새 주문 버튼은 주문이 있고 입력이 잠기지 않았을 때만 보인다.
// 상태 배지는 상태값을 소문자로 바꾼 클래스를 단다. order-agent/frontend/src/styles/main.css 는 .pill.hold 같은 클래스로
// 색을 정하고, order-agent/frontend/tests/e2e/order.spec.js 와 order-agent/frontend/tests/e2e/evidence.spec.js 는
// .pill.shipped 같은 클래스로 결과를 찾는다.
// 라벨은 order-agent/frontend/src/utils/format.js 에서 오고, 처리 단계는 order-agent/frontend/src/components/OrderSteps.vue 가 그린다.
import { computed } from "vue";
import {
  money,
  notificationLabel,
  orderStatusLabel,
  paymentStatusLabel,
  timestamp,
} from "../utils/format.js";
import OrderSteps from "./OrderSteps.vue";
// previous 는 보이는 결과가 지금 입력과 다른 직전 주문의 결과인지 나타내고, requestId 는 제출한 요청의 Idempotency-Key 다.
const props = defineProps({
  order: Object,
  previous: Boolean,
  requestState: String,
  error: Object,
  canResend: Boolean,
  locked: Boolean,
  requestId: String,
});
// recover 는 "상태 다시 확인", resend 는 "같은 요청으로 재전송", new-order 는 "새 주문 작성" 버튼이 올린다.
defineEmits(["recover", "resend", "new-order"]);
// 진행 안내 문구다. submitting 이면 주문을 처리하는 중이라고, polling 이면 서버의 주문 상태를 확인하는 중이라고 알린다.
const progressText = computed(() =>
  props.requestState === "submitting"
    ? "주문을 처리하고 있습니다."
    : "서버의 주문 상태를 확인하고 있습니다.",
);
// 오류 안내 제목이다. recoverable 이면 결과 확인이 필요하다고 쓰고, 그 밖의 상태에서는 요청을 확인하라고 쓴다.
// 접수 전 입력 거절(settled)과, 이전 오류를 남긴 채 recover 가 조회하는 polling 이 그 밖의 상태에 해당한다.
const errorTitle = computed(() =>
  props.requestState === "recoverable"
    ? "결과 확인이 필요해요"
    : "요청을 확인해주세요",
);
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
        orderStatusLabel(order.status)
      }}</span>
    </div>
    <div
      v-if="requestState === 'submitting' || requestState === 'polling'"
      class="notice"
      role="status"
    >
      <span class="spinner" aria-hidden="true"></span>
      {{ progressText }}
    </div>
    <div v-if="error" class="notice error" role="alert">
      <strong>{{ errorTitle }}</strong>
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
            {{ paymentStatusLabel(order.payment.status) }}
          </dd>
        </div>
        <div>
          <dt>배송 예정일</dt>
          <dd>{{ order.shipment?.estimated_delivery_date || "미실행" }}</dd>
        </div>
        <div>
          <dt>성공 알림</dt>
          <dd>
            {{ notificationLabel(order.notification.status) }}
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
