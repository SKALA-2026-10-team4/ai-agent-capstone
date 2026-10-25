<script setup>
// 제안서의 사실 목록(이름과 값)을 그린다. 값이 실행 번호 목록이면 frontend/src/components/RunRecordList.vue 의
// 실행 기록 타임라인으로, Skill 세트 번호면 frontend/src/components/SnapshotLabel.vue 의 세트 이름('기준본',
// '내 수정본 · 10월 26일 06:41')으로 바꾸고, 제안서·진단·변경 번호 같은 번호 칸은 본문에서 뺀다(그 값은
// frontend/src/views/ProposalView.vue 의 개발자용 정보에 모인다). 어떻게 보일지는
// frontend/src/utils/proposalView.js 의 factView 가 정한다. 글은 텍스트 보간으로만 넣는다.
// frontend/src/components/ProposalDocument.vue 가 절의 facts 와 후보 카드의 facts 를 그릴 때 쓴다.
// 실행 번호 목록은 service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 joined 가
// ", " 로 이어 붙인 글이다(예: '원인 확인 실행', '근거 실행' 칸).
import { computed } from 'vue'
import { factView } from '@/utils/proposalView'
import RunRecordList from './RunRecordList.vue'
import SnapshotLabel from './SnapshotLabel.vue'

// stopClick 이 참이면 실행 기록 줄의 클릭을 위로 올리지 않는다. 누르면 그 후보를 고르는 후보 카드만 켠다.
const props = defineProps({
  facts: { type: Array, required: true },
  stopClick: { type: Boolean, default: false },
})

// 사실마다 보이는 방식을 한 번만 계산하고, 번호 칸(id)은 뺀다.
const rows = computed(() =>
  props.facts.map((fact) => ({ fact, view: factView(fact) })).filter((r) => r.view.kind !== 'id'),
)
</script>

<template>
  <div v-if="rows.length" class="facts-wrap">
    <dl class="facts">
      <template v-for="{ fact, view } in rows" :key="fact.label">
        <dt>{{ fact.label }}</dt>
        <dd v-if="view.kind === 'runs'" class="runs">
          <RunRecordList :refs="view.ids" :stop-click="stopClick" />
        </dd>
        <dd v-else-if="view.kind === 'snapshots'" class="sets">
          <SnapshotLabel v-for="id in view.ids" :id="id" :key="id" :role="view.role" />
        </dd>
        <dd v-else>{{ fact.value }}</dd>
      </template>
    </dl>
  </div>
</template>

<style scoped>
/* 후보 카드처럼 좁은 자리에서는 이름을 값 위로 올려 실행 기록이 넓게 보이게 한다(컨테이너 폭 기준). */
.facts-wrap {
  container-type: inline-size;
}

.facts {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 0;
  margin: 16px 0 0;
  font-size: var(--fs-sm);
  border-top: 1px solid var(--border);
}

.facts dt,
.facts dd {
  padding: 9px 0;
  border-bottom: 1px solid var(--border);
}

.facts dt {
  padding-right: 24px;
  color: var(--muted);
}

.facts dd {
  margin: 0;
  min-width: 0;
  color: var(--text-2);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.facts dd.runs {
  padding: 4px 0;
  white-space: normal;
}

.sets {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
}

@container (max-width: 520px) {
  .facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .facts dt {
    padding: 10px 0 0;
    border-bottom: none;
    font-size: var(--fs-xs);
  }

  .facts dd {
    padding-top: 2px;
  }
}
</style>
