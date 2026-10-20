<script setup>
import { computed } from "vue";
import { stepText, stepStatus, timestamp } from "../utils/format.js";
const props = defineProps({ steps: { type: Array, default: () => [] } });
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
          step.status === "SUCCESS"
            ? "✓"
            : step.status === "SKIPPED"
              ? "−"
              : "!"
        }}</span>
        <div class="step-content">
          <div class="row-label">
            <strong>{{ stepText[step.step] || step.step }}</strong
            ><span class="step-state">{{
              stepStatus[step.status] || step.status
            }}</span>
          </div>
          <p>{{ step.message }}</p>
          <small>{{ timestamp(step.completed_at) }}</small>
        </div>
      </li>
    </ol>
  </section>
</template>
