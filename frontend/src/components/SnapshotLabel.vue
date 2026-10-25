<script setup>
// Skill 세트 번호(snapshot UUID)를 화면에 찍지 않고 '기준본', '내 수정본', '수정본 · 10월 26일 06:41' 같은
// 이름으로 보여 준다. 원래 id 는 title(마우스를 올리면 보임)과 data-snapshot-id 에만 둔다.
// 세트 종류와 만든 시각은 먼저 frontend/src/stores/workspace.js 의 snapshotMeta 로 화면이 아는 값을 쓰고,
// 모자라면 frontend/src/api/runRecords.js 의 loadSnapshotManifest(GET /api/snapshots/{id}, 캐시)로 묻는다.
// 서버에 없는 세트면 아는 값만으로 이름을 정한다. 이름 규칙은 frontend/src/utils/runRecord.js 의
// snapshotLabelText 다. frontend/src/components/ProposalFacts.vue(수정 전·후 Skill 세트),
// frontend/src/components/RunRecordList.vue(실행에 쓴 세트), frontend/src/views/DiagnosisView.vue,
// frontend/src/views/ExecutionView.vue 가 쓴다. frontend/tests/diagnosis.spec.js 가 data-snapshot-id 로
// 저장한 세트가 화면에 보이는지 확인한다.
import { computed, ref, watch } from 'vue'
import { loadSnapshotManifest } from '@/api/runRecords'
import { setKindLabel, useWorkspaceStore } from '@/stores/workspace'
import { snapshotLabelText } from '@/utils/runRecord'

// role 은 그 화면에서 세트가 맡은 역할이다. 'baseline'(수정 전 기준본), 'candidate'(수정본), 없으면 종류 이름을 쓴다.
// plain 이 참이면 앞의 작은 점 없이 글만 그린다. short 가 참이면 만든 시각을 빼고 이름만 쓴다(실행 기록 줄처럼
// 좁은 자리에서 쓴다).
const props = defineProps({
  id: { type: String, required: true },
  role: { type: String, default: null },
  plain: { type: Boolean, default: false },
  short: { type: Boolean, default: false },
})

const ws = useWorkspaceStore()
// 서버에서 읽은 manifest 다. 읽지 않았거나 읽지 못했으면 null 이다.
const manifest = ref(null)

const known = computed(() => ws.snapshotMeta(props.id))
const label = computed(() =>
  snapshotLabelText({
    role: props.role,
    kind: known.value.kind ?? setKindLabel(manifest.value?.metadata),
    createdAt: props.short ? null : (known.value.createdAt ?? manifest.value?.created_at ?? null),
  }),
)

// id 가 바뀔 때마다(처음 포함) 화면이 아는 값이 모자라면 세트 정보를 읽는다. 늦게 온 이전 id 의 응답은 버린다.
watch(
  () => props.id,
  async (id) => {
    manifest.value = null
    if (known.value.kind && (known.value.createdAt || props.short)) return
    try {
      const m = await loadSnapshotManifest(id)
      if (id === props.id) manifest.value = m
    } catch {
      // 서버에 없는 세트(지난 기록, mock 의 예시 번호 등)는 아는 값만으로 이름을 정한다.
    }
  },
  { immediate: true },
)
</script>

<template>
  <span class="snap" :class="[role, { plain }]" :title="`Skill 세트 ID ${id}`" :data-snapshot-id="id">{{ label }}</span>
</template>

<style scoped>
.snap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
  white-space: nowrap;
}

.snap:not(.plain)::before {
  content: '';
  width: 6px;
  height: 6px;
  border: 1px solid var(--muted);
  border-radius: 50%;
  flex: none;
}

.snap.candidate:not(.plain)::before {
  border-color: var(--accent);
  background: var(--accent);
}
</style>
