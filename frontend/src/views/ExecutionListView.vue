<script setup>
// 테스트 목록 화면이다. 사이드바(frontend/src/components/AppSidebar.vue)의 '테스트 목록' 메뉴이며
// frontend/src/router/index.js 가 /executions 경로에 연결한다.
// 예전에는 frontend/src/views/OrderView.vue 아래쪽의 실행 기록이었는데, 테스트 실행 화면에는 방금 실행한
// 주문만 남기고 이전 기록은 이 화면으로 옮겼다.
// 실행 기록은 두 탭으로 보여 준다. '최근' 탭은 GET /api/executions 가 주는 최근 20건으로,
// frontend/src/stores/workspace.js 의 executions 에 들어 있다. '실행 실패만' 탭은
// frontend/src/api/orderAgent.js 의 listFailedExecutions 로 읽는 GET /api/executions/failed 목록이고,
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
// failed() 가 돌려준다. 줄을 누르면 frontend/src/views/ExecutionView.vue 의 실행 상세 화면으로 간다.
import { computed, onMounted, ref } from 'vue'
import { ListChecks } from 'lucide-vue-next'
import { listFailedExecutions } from '@/api/orderAgent'
import { useWorkspaceStore } from '@/stores/workspace'
import { executionLabel } from '@/utils/traceRun'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'

const ws = useWorkspaceStore()
const error = ref(null)

// 실행 기록 탭이다. 'recent' 는 최근 실행(최대 20건)이고, 'failed' 는 실행 실패만 모은 목록이다.
// 실행 실패 목록(GET /api/executions/failed)에는 재고 부족이나 결제 거절 같은
// 업무 거절이 들어가지 않는다.
const listMode = ref('recent')
const failed = ref([])
const shownRuns = computed(() => (listMode.value === 'failed' ? failed.value : ws.executions))

/**
 * '실행 실패만' 탭으로 바꾸고 frontend/src/api/orderAgent.js 의 listFailedExecutions 로
 * 목록을 읽는다. EvidenceController 의 failed() 가 답하며, 탭을 누를 때마다 다시 읽는다.
 */
async function showFailed() {
  listMode.value = 'failed'
  try {
    failed.value = (await listFailedExecutions()).items
  } catch (e) {
    error.value = e.message
  }
}

/**
 * 실행 기록 한 줄의 글이다. template 의 목록이 줄마다 부른다.
 * 줄 모양은 frontend/src/utils/traceRun.js 의 executionLabel 이 정한다.
 * 실행 실패 목록의 항목은 오류를 error 객체로 주므로, 그 category 를 error_category 자리에 넣고
 * 실패한 skill_id 를 뒤에 붙인다.
 */
const runLabel = (x) =>
  x.error ? `${executionLabel({ ...x, error_category: x.error.category })} · ${x.error.skill_id ?? ''}` : executionLabel(x)

// 화면을 열 때마다 최근 실행 목록을 다시 읽어, 테스트 실행에서 막 돌린 주문도 바로 보이게 한다.
onMounted(() => ws.refreshExecutions())
</script>

<template>
  <PageHeader
    title="테스트 목록"
    description="지금까지 실행한 주문 기록이에요. 줄을 누르면 단계별 실행 결과(trace)를 볼 수 있어요."
    :icon="ListChecks"
    description-tip
  />

  <section class="board">
    <div class="tabs">
      <button class="ghost sm" :class="{ on: listMode === 'recent' }" @click="listMode = 'recent'">최근</button>
      <button class="ghost sm" :class="{ on: listMode === 'failed' }" @click="showFailed">실행 실패만</button>
    </div>

    <p v-if="error" class="error-text">{{ error }}</p>
    <EmptyState
      v-else-if="!shownRuns.length"
      :title="listMode === 'failed' ? '실행이 실패한 주문이 없어요' : '아직 실행 기록이 없어요'"
      :icon="ListChecks"
    >
      {{ listMode === 'failed' ? '재고 부족·결제 거절은 정상 업무 결과라 여기 없어요.' : '테스트 실행에서 주문을 실행하면 이곳에 쌓여요.' }}
    </EmptyState>

    <!-- 목록이 길어지면 이 칸만 스크롤한다. 높이는 화면에서 머리와 탭 줄을 뺀 만큼이다. -->
    <ul v-else class="runs card">
      <li v-for="x in shownRuns" :key="x.execution_id">
        <RouterLink :to="`/executions/${x.execution_id}`">{{ runLabel(x) }}</RouterLink>
      </li>
    </ul>
  </section>
</template>

<style scoped>
/* 탭과 목록을 담는 회색 판이다. 수정안 목록(ProposalListView)과 같은 모양이다 */
.board {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border-radius: var(--radius);
  background: var(--surface-2);
}

.tabs {
  display: flex;
  align-self: flex-start;
  gap: 2px;
  padding: 2px;
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.tabs .on {
  background: var(--surface-2);
  color: var(--accent);
  box-shadow: var(--shadow-sm);
}

.runs {
  max-height: calc(var(--view-h) - 240px);
  margin: 0;
  padding: 4px 16px;
  overflow-y: auto;
  list-style: none;
}

.runs li {
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
  font-size: var(--fs-sm);
}

.runs li:last-child {
  border-bottom: none;
}
</style>
