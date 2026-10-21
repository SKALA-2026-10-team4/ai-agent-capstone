<script setup>
import { computed } from 'vue'

// order-agent 상태값 공용: 주문(SHIPPED/REJECTED/HOLD/FAILED…), 단계(SUCCESS/SKIPPED…),
// 실행(COMPLETED/RUNNING/INTERRUPTED), 수정안(proposed/approved/…)
const props = defineProps({ status: { type: String, required: true } })

const TONE = {
  SHIPPED: 'ok',
  SUCCESS: 'ok',
  COMPLETED: 'ok',
  PAID: 'ok',
  SENT: 'ok',
  approved: 'ok',
  applied: 'ok',
  REJECTED: 'warn',
  HOLD: 'warn',
  DECLINED: 'warn',
  INTERRUPTED: 'warn',
  pending: 'info',
  proposed: 'info',
  RUNNING: 'info',
  PROCESSING: 'info',
  FAILED: 'err',
  rejected: 'err',
  no_valid_fix: 'err',
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
