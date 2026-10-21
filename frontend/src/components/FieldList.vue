<script setup>
// 이름-값 목록. utils/eventView 의 fieldsOf()·describe().fields 결과를 그린다.
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
