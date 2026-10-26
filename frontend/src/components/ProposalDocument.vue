<script setup>
// 제안서 본문 컴포넌트다. service 가 제안서를 저장할 때 한 번 만든 document
// (목차 순서의 절과 문장)를 그대로 그린다. document 의 모양은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalDocument.java 가 정한다.
// 글은 모두 텍스트 보간으로만 넣으므로 글 속의 HTML 태그도 글자 그대로 보인다.
// 제목(document.title)은 화면 머리(PageHeader)가 보여 주므로 여기서는 sections 만 그린다.
// 사실 목록은 frontend/src/components/ProposalFacts.vue 가 그린다. 사실 칸의 값이 실행 번호
// 목록이면 번호마다 실행 화면 링크로 보여 주어 담당자가 근거 실행을 직접 열어 확인할 수 있게 한다.
// frontend/src/views/ProposalView.vue 가 쓴다. key 가 intent 인 절 아래에는
// intent slot(의도 확인 체크)을, candidates 인 절 아래에는
// candidates slot(고른 후보의 patch 비교)을 연다.
// 이 key 값은 service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 가
// 절에 붙이는 key 와 같아야 하므로 그쪽을 바꾸면 여기도 고친다.
// candidates 를 넘기면 후보 카드 제목 아래에 그 후보의 경고 꼬리표
// (frontend/src/components/CandidateTags.vue)를 붙인다. 종류 이름은 service 가 쓴 카드 제목에 이미
// 있으므로 뺀다. 본문은 service 가 쓴 글 그대로 두고, 꼬리표만 Agent 원본 제안서의 후보 칸
// (is_revert, weakens_contract)에서 읽는다.
import CandidateTags from './CandidateTags.vue'
import ProposalFacts from './ProposalFacts.vue'

// selectedNo 는 지금 고른 후보 번호이며 그 후보 카드를 강조한다.
// canSelect 가 false 면 후보를 고를 수 없다. ProposalView 는 canSelect 를 넘기지 않으므로
// 지금은 기본값 true 로 쓴다. candidates 는 Agent 원본 제안서의 후보 목록이다.
const props = defineProps({
  document: { type: Object, required: true },
  selectedNo: { type: Number, default: null },
  canSelect: { type: Boolean, default: true },
  candidates: { type: Array, default: () => [] },
})
// 후보 번호로 원본 후보를 찾는다. 없으면 null 이라 꼬리표를 그리지 않는다.
const candidateOf = (no) => props.candidates.find((c) => c.candidate_no === no) ?? null
// 후보 카드(parts)나 '후보 n번 고르기' 버튼을 누르면 select 이벤트로
// 그 후보 번호(candidate_no)를 올려 보낸다. ProposalView 가 받아 selectedNo 를 바꾼다.
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
      <ProposalFacts v-if="section.facts.length" :facts="section.facts" />
      <div v-if="section.parts.length" class="parts">
        <article
          v-for="part in section.parts"
          :key="part.candidate_no"
          class="part"
          :class="{ selected: part.candidate_no === selectedNo }"
          @click="canSelect && $emit('select', part.candidate_no)"
        >
          <h3>{{ part.heading }}</h3>
          <CandidateTags :candidate="candidateOf(part.candidate_no)" :show-kind="false" />
          <p v-for="(text, j) in part.paragraphs" :key="`q${j}`" class="para">{{ text }}</p>
          <ProposalFacts v-if="part.facts.length" :facts="part.facts" stop-click />
          <button
            type="button"
            class="ghost sm pick"
            :disabled="!canSelect || part.candidate_no === selectedNo"
            @click.stop="$emit('select', part.candidate_no)"
          >
            {{ part.candidate_no === selectedNo ? '고른 후보' : `후보 ${part.candidate_no}번 고르기` }}
          </button>
        </article>
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

.pick {
  align-self: flex-start;
  margin-top: 12px;
}

.part.selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.part h3 {
  margin: 0 0 4px;
}
</style>
