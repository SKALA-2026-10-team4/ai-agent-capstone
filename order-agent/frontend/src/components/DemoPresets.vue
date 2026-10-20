<script setup>
defineProps({ disabled: Boolean });
defineEmits(["preset"]);
</script>
<template>
  <section class="presets" aria-label="개발·시연 예시">
    <div class="eyebrow">개발 · 시연</div>
    <p>예시를 채운 뒤 주문을 실행해보세요.</p>
    <div class="button-row">
      <button
        type="button"
        class="button ghost"
        :disabled="disabled"
        @click="$emit('preset', 'normal')"
      >
        정상 주문
      </button>
      <button
        type="button"
        class="button ghost"
        :disabled="disabled"
        @click="$emit('preset', 'soldout')"
      >
        재고 부족
      </button>
      <button
        type="button"
        class="button ghost"
        :disabled="disabled"
        @click="$emit('preset', 'declined')"
      >
        결제 거절
      </button>
    </div>
    <small>입력만 채워집니다. 재고는 주문 사이에 유지됩니다.</small>
  </section>
</template>
