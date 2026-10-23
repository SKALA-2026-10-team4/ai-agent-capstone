<script setup>
// order-agent 가 주는 상태 값을 색 배지로 보여 준다. 주문 상태(SHIPPED, REJECTED, HOLD, FAILED 등),
// 결제와 알림 상태(PAID, DECLINED, SENT 등), 처리 단계 상태(SUCCESS, SKIPPED 등),
// 실행 상태(COMPLETED, RUNNING, INTERRUPTED)를 한 컴포넌트로 다룬다.
// 글자는 상태 값을 그대로 쓰고 색만 TONE 으로 정한다. 주문, 결제, 알림, 처리 단계의 상태 값은
// order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums 의 OrderStatus,
// PaymentStatus, NotificationStatus, ProcessingStepStatus 에 있다.
// frontend/src/views/OrderView.vue(주문 결과, 결제, 알림, 처리 단계)와
// frontend/src/views/ExecutionView.vue(final_status, completion)가 쓴다.
// 제안서 상태는 소문자 값이라 여기서 다루지 않고
// frontend/src/components/ProposalStatusBadge.vue 가 그린다.
import { computed } from 'vue'

const props = defineProps({ status: { type: String, required: true } })

// 상태 값별 색 톤이다. ok 는 정상 완료이고, warn 은 업무 규칙에 따른 거절과 보류,
// 그리고 서버 재시작으로 끊긴 실행(INTERRUPTED)이다. info 는 진행 중, err 는 실패다.
// 표에 없는 값은 muted 로 흐리게 그린다. order-agent 에 새 상태 값이 생기면 여기에 더한다.
const TONE = {
  SHIPPED: 'ok',
  SUCCESS: 'ok',
  COMPLETED: 'ok',
  PAID: 'ok',
  SENT: 'ok',
  REJECTED: 'warn',
  HOLD: 'warn',
  DECLINED: 'warn',
  INTERRUPTED: 'warn',
  RUNNING: 'info',
  PROCESSING: 'info',
  FAILED: 'err',
}
const tone = computed(() => TONE[props.status] ?? 'muted')
</script>

<template>
  <span class="badge" :class="tone">{{ status }}</span>
</template>

<style scoped>
.badge {
  display: inline-block;
  font-size: 12px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid currentColor;
  white-space: nowrap;
}

.ok {
  color: var(--ok);
}

.warn {
  color: var(--warn);
}

.err {
  color: var(--err);
}

.info {
  color: var(--accent);
}

.muted {
  color: var(--muted);
}
</style>
