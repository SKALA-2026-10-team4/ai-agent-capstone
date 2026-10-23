<script setup>
// 제안서의 사실 목록(이름과 값)을 그린다. 값이 실행 번호 목록이면 번호마다 실행 화면 링크로
// 보여 주고, 글은 텍스트 보간으로만 넣는다. frontend/src/components/ProposalDocument.vue 가
// 절의 facts 와 후보 카드의 facts 를 그릴 때 쓴다.
// 실행 번호 목록은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 joined 가
// ", " 로 이어 붙인 글이다(예: '원인 확인 실행', '근거 실행' 칸).
// 그 구분자를 바꾸면 runLinks 도 함께 고친다.
import { computed } from 'vue'
import { executionPath } from '@/utils/traceRun'

// stopClick 이 참이면 실행 링크 클릭을 위로 올리지 않는다. 누르면 그 후보를 고르는 후보 카드만
// 켠다. 절의 사실 목록에서는 리팩토링 전처럼 클릭이 문서까지 올라간다.
const props = defineProps({
  facts: { type: Array, required: true },
  stopClick: { type: Boolean, default: false },
})

/**
 * 사실 값 하나를 실행 링크 목록으로 바꾼다. "r_…, r_…" 처럼 ", " 로 나눈 조각이
 * 모두 실행 번호일 때만 [{ id, to }] 를 돌려준다. 하나라도 아니면 null 을 돌려주어
 * 글을 그대로 보여 준다. 아래 rows 가 사실마다 부른다.
 * 실행 번호인지와 그 주소는 frontend/src/utils/traceRun.js 의 executionPath 가 정한다.
 */
function runLinks(value) {
  const ids = String(value).split(', ')
  const paths = ids.map(executionPath)
  return paths.every(Boolean) ? ids.map((id, i) => ({ id, to: paths[i] })) : null
}

// 사실마다 링크 목록을 한 번만 계산해 둔다. 후보 카드는 누르면 그 후보를 고르므로,
// stopClick 이 켜진 후보 카드에서만 template 의 링크가 클릭을 카드까지 올리지 않는다.
const rows = computed(() => props.facts.map((fact) => ({ fact, runs: runLinks(fact.value) })))
</script>

<template>
  <dl class="facts">
    <template v-for="{ fact, runs } in rows" :key="fact.label">
      <dt>{{ fact.label }}</dt>
      <dd v-if="runs" class="runs">
        <RouterLink v-for="run in runs" :key="run.id" :to="run.to" @click="stopClick && $event.stopPropagation()">{{ run.id }}</RouterLink>
      </dd>
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
  gap: 4px 10px;
}
</style>
