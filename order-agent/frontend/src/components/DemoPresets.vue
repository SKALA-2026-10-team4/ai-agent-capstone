<script setup>
// 시연용 입력 채우기 버튼 묶음이다. 버튼을 누르면 preset 이름을 preset 이벤트로 올리고, 폼은 부모 쪽 상태 기계가 바꾼다.
// 버튼 목록과 문구는 order-agent/frontend/src/utils/demoPresets.js 의 DEMO_PRESETS 에서 오므로,
// preset 을 더하거나 바꾸려면 이 컴포넌트가 아니라 그 표를 고친다.
// order-agent/frontend/src/App.vue 가 disabled 를 넘기고 preset 이벤트를
// order-agent/frontend/src/composables/useOrder.js 의 preset 에 연결한다.
import { DEMO_PRESETS } from "../utils/demoPresets.js";
// disabled 가 참이면 모든 버튼을 끈다. order-agent/frontend/src/App.vue 는 입력이 잠겼거나 상품 목록이 준비되지 않았을 때
// 참을 넘긴다.
defineProps({ disabled: Boolean });
// preset 이벤트는 DEMO_PRESETS 항목의 name(normal, soldout, declined)을 싣는다.
defineEmits(["preset"]);
</script>
<template>
  <section class="presets" aria-label="개발·시연 예시">
    <div class="eyebrow">개발 · 시연</div>
    <p>예시를 채운 뒤 주문을 실행해보세요.</p>
    <div class="button-row">
      <button
        v-for="preset in DEMO_PRESETS"
        :key="preset.name"
        type="button"
        class="button ghost"
        :disabled="disabled"
        @click="$emit('preset', preset.name)"
      >
        {{ preset.label }}
      </button>
    </div>
    <small>입력만 채워집니다. 재고는 주문 사이에 유지됩니다.</small>
  </section>
</template>
