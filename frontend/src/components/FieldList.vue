<script setup>
// 이름과 값의 목록(dl)을 그린다. fields 는 [{ label, value }] 모양이고,
// 값은 텍스트 보간으로만 넣는다.
// frontend/src/components/EventTimeline.vue 가 이벤트마다 frontend/src/utils/eventView.js 의
// describe 가 만든 fields 를 넘긴다. 그 항목은 대부분 같은 파일의 fieldsOf 가
// 컨텍스트 키를 화면 이름으로, 값을 짧은 글로 바꿔 만든 것이다.
defineProps({ fields: { type: Array, required: true } })
</script>

<template>
  <dl class="fields">
    <template v-for="(f, i) in fields" :key="i">
      <dt>{{ f.label }}</dt>
      <dd>{{ f.value }}</dd>
    </template>
  </dl>
</template>

<style scoped>
.fields {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 2px 12px;
  margin: 0;
  font-size: 13px;
}

dt {
  color: var(--muted);
}

dd {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
