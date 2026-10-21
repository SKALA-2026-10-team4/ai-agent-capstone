<script setup>
// 제안서 본문. service 가 저장할 때 만든 document(목차·문장)를 그대로 그린다. 글은 모두 텍스트 보간으로만 넣는다.
// 제목(document.title)은 화면 머리(PageHeader)가 보여 주므로 여기서는 절만 그린다.
defineProps({
  document: { type: Object, required: true },
  selectedNo: { type: Number, default: null },
  canSelect: { type: Boolean, default: true },
})
defineEmits(['select'])
</script>

<template>
  <div class="doc stack">
    <section v-for="(section, i) in document.sections" :key="section.key" class="card">
      <h2 class="sec"><span class="step-no">{{ i + 1 }}</span> {{ section.heading }}</h2>
      <p v-for="(text, j) in section.paragraphs" :key="`p${j}`" class="para">{{ text }}</p>
      <blockquote v-if="section.quote">“{{ section.quote }}”</blockquote>
      <ul v-if="section.items.length" class="items">
        <li v-for="item in section.items" :key="item"><code>{{ item }}</code></li>
      </ul>
      <dl v-if="section.facts.length" class="facts">
        <template v-for="fact in section.facts" :key="fact.label">
          <dt>{{ fact.label }}</dt>
          <dd>{{ fact.value }}</dd>
        </template>
      </dl>
      <div v-if="section.parts.length" class="parts">
        <button
          v-for="part in section.parts"
          :key="part.candidate_no"
          type="button"
          class="part"
          :class="{ selected: part.candidate_no === selectedNo }"
          :disabled="!canSelect"
          @click="$emit('select', part.candidate_no)"
        >
          <h3>{{ part.heading }}</h3>
          <p v-for="(text, j) in part.paragraphs" :key="`q${j}`" class="para">{{ text }}</p>
          <dl v-if="part.facts.length" class="facts">
            <template v-for="fact in part.facts" :key="fact.label">
              <dt>{{ fact.label }}</dt>
              <dd>{{ fact.value }}</dd>
            </template>
          </dl>
        </button>
      </div>
      <slot v-if="section.key === 'intent'" name="intent" />
      <slot v-if="section.key === 'candidates'" name="candidates" />
    </section>
  </div>
</template>

<style scoped>
.sec {
  display: flex;
  align-items: center;
  gap: 10px;
}

.para {
  margin: 8px 0 0;
  line-height: 1.6;
}

blockquote {
  margin: 12px 0 0;
  padding: 12px 16px;
  border-radius: var(--radius);
  background: var(--accent-soft);
  font-size: var(--fs-lg);
  line-height: 1.5;
}

.items {
  margin: 10px 0 0;
  padding-left: 20px;
}

.facts {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 6px 20px;
  margin: 14px 0 0;
  font-size: var(--fs-sm);
}

.facts dt {
  color: var(--muted);
}

.facts dd {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.parts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
  margin-top: 16px;
}

.part {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  text-align: left;
  padding: 14px 16px;
  border: 1.5px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text);
  font: inherit;
  cursor: pointer;
}

.part:disabled {
  cursor: default;
}

.part.selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.part h3 {
  margin: 0;
}
</style>
