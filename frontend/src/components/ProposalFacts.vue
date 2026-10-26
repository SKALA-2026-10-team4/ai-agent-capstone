<script setup>
// 제안서의 사실 목록(이름과 값)을 그린다. 값이 실행 번호 목록이면 번호마다 실행 화면 링크로
// 보여 주고, 글은 텍스트 보간으로만 넣는다. frontend/src/components/ProposalDocument.vue 가
// 절의 facts 와 후보 카드의 facts 를 그릴 때 쓴다.
// 실행 번호 목록은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 joined 가
// ", " 로 이어 붙인 글이다(예: '원인 확인 실행', '근거 실행' 칸).
// 그 구분자를 바꾸면 frontend/src/utils/factNames.js 의 factParts 도 함께 고친다.
// 실행 번호와 Skill 세트 id 는 names 에 이름표가 있으면 'g-05 · 실패', '정상본 · c71401b1' 처럼 보여 주고,
// 원래 번호는 마우스를 올리면 보이게 둔다. 같은 이름표가 반복되면 'g-01 · 통과 ×2' 처럼 하나로 묶는다.
// 이름표는 frontend/src/views/ProposalView.vue 가 만든다.
import { computed } from 'vue'
import { executionPath } from '@/utils/traceRun'
import { factParts, groupRuns, isRunFact, isSkillSetFact } from '@/utils/factNames'

// stopClick 이 참이면 실행 링크 클릭을 위로 올리지 않는다. 누르면 그 후보를 고르는 후보 카드만
// 켠다. 절의 사실 목록에서는 리팩토링 전처럼 클릭이 문서까지 올라간다.
// names 는 번호(실행 번호나 snapshot id)마다 { text, title, passed } 이름표를 담은 객체다. 없는 번호는 원래 번호로 보인다.
const props = defineProps({
  facts: { type: Array, required: true },
  stopClick: { type: Boolean, default: false },
  names: { type: Object, default: () => ({}) },
})

// 번호의 이름표를 돌려준다. 이름표가 없으면 번호를 그대로 쓴다.
const tagOf = (id) => props.names[id] ?? { text: id, title: id, passed: null }

/**
 * 사실 값 하나를 실행 링크 목록으로 바꾼다. "r_…, r_…" 처럼 ", " 로 나눈 조각이
 * 모두 실행 번호일 때만 [{ id, to, tag }] 를 돌려준다. 하나라도 아니면 null 을 돌려주어
 * 글을 그대로 보여 준다. Skill 세트, 진단 번호, 변경 번호 칸도 UUID 모양이지만 실행이 아니므로 frontend/src/utils/factNames.js 의
 * isRunFact 로 걸러 링크로 만들지 않는다.
 * 아래 rows 가 사실마다 부른다. 실행 번호인지와 그 주소는 frontend/src/utils/traceRun.js 의 executionPath 가 정한다.
 */
function runLinks(fact) {
  if (!isRunFact(fact)) return null
  const ids = factParts(fact.value)
  const paths = ids.map(executionPath)
  return paths.every(Boolean) ? ids.map((id, i) => ({ id, to: paths[i], tag: tagOf(id) })) : null
}

// 사실마다 묶은 실행 링크 목록과 Skill 세트 이름표를 한 번만 계산해 둔다. 후보 카드는 누르면 그 후보를 고르므로,
// stopClick 이 켜진 후보 카드에서만 template 의 링크가 클릭을 카드까지 올리지 않는다.
const rows = computed(() =>
  props.facts.map((fact) => {
    const links = runLinks(fact)
    return {
      fact,
      runs: links && groupRuns(links),
      set: isSkillSetFact(fact) ? tagOf(String(fact.value)) : null,
    }
  }),
)

// 통과 여부로 이름표 색을 고른다. 모르면 색을 칠하지 않는다.
const toneOf = (tag) => (tag.passed === true ? 'ok' : tag.passed === false ? 'err' : '')
</script>

<template>
  <dl class="facts">
    <template v-for="{ fact, runs, set } in rows" :key="fact.label">
      <dt>{{ fact.label }}</dt>
      <dd v-if="runs" class="runs">
        <RouterLink
          v-for="run in runs"
          :key="run.key"
          :to="run.to"
          :title="run.title"
          class="tag"
          :class="toneOf(run)"
          @click="stopClick && $event.stopPropagation()"
        >{{ run.text }}<span v-if="run.count > 1" class="count"> ×{{ run.count }}</span></RouterLink>
      </dd>
      <dd v-else-if="set"><span class="tag" :title="set.title">{{ set.text }}</span></dd>
      <dd v-else>{{ fact.value }}</dd>
    </template>
  </dl>
</template>

<style scoped>
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

.runs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  display: inline-block;
  padding: 1px 8px;
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  white-space: nowrap;
}

.count {
  opacity: 0.75;
}

.tag.ok {
  border-color: var(--ok);
  background: var(--ok-soft);
  color: var(--ok);
}

.tag.err {
  border-color: var(--err);
  background: var(--err-soft);
  color: var(--err);
}
</style>
