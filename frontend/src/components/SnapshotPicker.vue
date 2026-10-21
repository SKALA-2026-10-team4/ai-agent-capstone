<script setup>
import { onMounted, ref } from 'vue'
import { FIXTURES } from '@/api/orderAgent'
import { useWorkspaceStore } from '@/stores/workspace'

// 지금 볼 Skill 파일 세트(snapshot)를 고른다. snapshot 목록 API 가 없어서
// 준비된 fixture 고정 + 최근 실행이 쓴 snapshot + 직접 입력으로 고른다.
const ws = useWorkspaceStore()
const manual = ref('')
const busy = ref(false)
const error = ref(null)

const FIXTURE_LABEL = { baseline: '정상본', 'broken-contract': '결함본', repaired: '수정본' }

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

async function pick(id) {
  if (id) await ws.select(id)
}

onMounted(() => ws.init())
</script>

<template>
  <div class="picker">
    <label>
      <span>Skill 파일 세트</span>
      <select :value="ws.snapshotId ?? ''" @change="pick($event.target.value)">
        <option value="" disabled>snapshot 선택</option>
        <option v-if="ws.snapshotId && !ws.knownSnapshots.some((s) => s.id === ws.snapshotId)" :value="ws.snapshotId">
          {{ ws.snapshotId.slice(0, 8) }}
        </option>
        <option v-for="s in ws.knownSnapshots" :key="s.id" :value="s.id">{{ s.label }}</option>
      </select>
    </label>
    <input v-model.trim="manual" placeholder="snapshot_id 직접 입력" @keyup.enter="pick(manual)" />
    <span class="fixtures">
      <button v-for="f in FIXTURES" :key="f" :disabled="busy" :title="`준비된 ${f} 후보를 새 snapshot 으로 고정`" @click="pin(f)">
        {{ FIXTURE_LABEL[f] }} 고정
      </button>
    </span>
    <span v-if="ws.current" class="meta muted">
      {{ ws.labelOf(ws.snapshotId).split(' · ')[0] }} · hash
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
