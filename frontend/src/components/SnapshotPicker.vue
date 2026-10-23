<script setup>
// 지금 볼 Skill 파일 세트(snapshot)를 고르는 막대다.
// order-agent 에 snapshot 목록 API 가 없어서 세 가지 방법으로 고른다.
// 준비된 fixture 를 새 snapshot 으로 고정하거나, 고정 이력과 최근 실행이 쓴 snapshot 중에서
// 고르거나(frontend/src/stores/workspace.js 의 knownSnapshots), snapshot_id 를 직접 입력한다.
// 고른 snapshot 은 workspace store 의 snapshotId 에 들어가고,
// Skill 편집, 주문 실행, 제안서 초안 적용이 그 값을 함께 쓴다.
// frontend/src/views/SkillDraftView.vue 와 frontend/src/views/OrderView.vue 가
// 위쪽 도구 막대에 둔다. fixture 버튼 목록은 frontend/src/api/orderAgent.js 의 FIXTURES 이고,
// 버튼 이름은 workspace.js 의 SET_LABEL 이다.
// frontend/tests/diagnosis.spec.js 가 '정상본 고정' 버튼 이름으로 이 버튼을 찾으므로,
// 이름을 바꾸면 e2e 도 함께 고친다.
import { onMounted, ref } from 'vue'
import { FIXTURES } from '@/api/orderAgent'
import { SET_LABEL, useWorkspaceStore } from '@/stores/workspace'
import { shortId } from '@/utils/format'

const ws = useWorkspaceStore()
// 직접 입력 칸의 snapshot_id 다.
const manual = ref('')
// fixture 고정 요청 중이면 true 이고, 그동안 fixture 버튼을 끈다.
const busy = ref(false)
const error = ref(null)

/**
 * fixture 하나를 새 snapshot 으로 고정하고 그 snapshot 을 고른다. fixture 버튼이 부른다.
 * workspace store 의 pin 이 frontend/src/api/orderAgent.js 의 pinFixture(POST /api/snapshots)를
 * 부르고 고정 이력에 넣는다. 이 요청은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
 * publish() 가 받는다. 시연 기능이 꺼져 있으면
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DemoGuard.java 가 404 로
 * 감춘다. 실패하면 막대 끝에 오류 문구를 보여 준다.
 */
async function pin(fixture) {
  busy.value = true
  error.value = null
  try {
    await ws.pin(fixture)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

/**
 * 선택 상자나 직접 입력에서 고른 id 로 workspace store 의 select 를 부른다.
 * select 는 snapshotId 를 바꾸고 그 세트를 불러 캐시한다. 빈 값이면 아무것도 하지 않는다.
 */
async function pick(id) {
  if (id) await ws.select(id)
}

// 처음 그릴 때 workspace store 의 init 으로 최근 실행 목록을 읽고,
// 저장해 둔 snapshot 을 다시 불러온다. 저장해 둔 것이 없거나 읽지 못하면
// 선택지의 첫 snapshot 을 고른다.
onMounted(() => ws.init())
</script>

<template>
  <div class="picker">
    <label>
      <span>Skill 파일 세트</span>
      <select :value="ws.snapshotId ?? ''" @change="pick($event.target.value)">
        <option value="" disabled>snapshot 선택</option>
        <option v-if="ws.snapshotId && !ws.knownSnapshots.some((s) => s.id === ws.snapshotId)" :value="ws.snapshotId">
          {{ shortId(ws.snapshotId) }}
        </option>
        <option v-for="s in ws.knownSnapshots" :key="s.id" :value="s.id">{{ s.label }}</option>
      </select>
    </label>
    <input v-model.trim="manual" placeholder="snapshot_id 직접 입력" @keyup.enter="pick(manual)" />
    <span class="fixtures">
      <button v-for="f in FIXTURES" :key="f" :disabled="busy" :title="`준비된 ${f} 후보를 새 snapshot 으로 고정`" @click="pin(f)">
        {{ SET_LABEL[f] }} 고정
      </button>
    </span>
    <span v-if="ws.current" class="meta muted">
      {{ ws.kindLabelOf(ws.snapshotId) }} · hash
      <code>{{ ws.current.snapshot.hash.slice(0, 12) }}</code>
    </span>
    <span v-if="error || ws.error" class="error-text">{{ error || ws.error }}</span>
  </div>
</template>

<style scoped>
.picker {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

select {
  max-width: 320px;
}

input {
  font: inherit;
  padding: 4px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  color: var(--text);
  width: 200px;
}

.fixtures {
  display: flex;
  gap: 4px;
}

.fixtures button {
  padding: 4px 10px;
  font-size: 13px;
}

.meta {
  font-size: 12px;
}
</style>
