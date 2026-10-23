<script setup>
// 서버가 저장한 주문 처리 단계를 타임라인으로 보여주는 컴포넌트다.
// 단계를 sequence 순으로 정렬해 아이콘, 단계 이름, 상태, 메시지, 완료 시각과 함께 그린다.
// 라벨과 아이콘은 order-agent/frontend/src/utils/format.js 에서 온다.
// 각 행은 상태값을 소문자로 바꾼 클래스(li.success 같은)를 단다. order-agent/frontend/src/styles/main.css 는 이 클래스로
// 상태별 모양을 정하고, order-agent/frontend/tests/e2e/order.spec.js 는 이 클래스로 상태별 단계 수를 센다.
// order-agent/frontend/src/components/OrderResult.vue 가 주문 응답의 steps 를 넘긴다.
import { computed } from "vue";
import {
  stepIcon,
  stepLabel,
  stepStatusLabel,
  timestamp,
} from "../utils/format.js";
const props = defineProps({ steps: { type: Array, default: () => [] } });
// 서버 응답 순서와 관계없이 sequence 순으로 보여준다. props 배열을 바꾸지 않도록 복사한 뒤 정렬한다.
const sorted = computed(() =>
  [...props.steps].sort((a, b) => a.sequence - b.sequence),
);
</script>
<template>
  <section class="steps" aria-labelledby="steps-title">
    <div class="row-label">
      <h3 id="steps-title">처리 단계</h3>
      <small class="muted">서버에 저장된 실제 결과</small>
    </div>
    <p v-if="!sorted.length" class="muted">아직 확인된 단계가 없습니다.</p>
    <ol v-else class="timeline">
      <li
        v-for="step in sorted"
        :key="step.sequence"
        :class="step.status.toLowerCase()"
      >
        <span class="step-dot" aria-hidden="true">{{
          stepIcon(step.status)
        }}</span>
        <div class="step-content">
          <div class="row-label">
            <strong>{{ stepLabel(step.step) }}</strong
            ><span class="step-state">{{ stepStatusLabel(step.status) }}</span>
          </div>
          <p>{{ step.message }}</p>
          <small>{{ timestamp(step.completed_at) }}</small>
        </div>
      </li>
    </ol>
  </section>
</template>
