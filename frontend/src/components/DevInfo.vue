<script setup>
// 화면에서 뺀 원래 id(진단 번호, 변경 번호, Skill 세트 id, 실행 번호 등)를 디버깅용으로 접어 두는 칸이다.
// '개발자용 정보' 를 펼치면 이름과 값을 보여 주고, 값마다 복사 버튼을 둔다. 값이 비면 그 줄을 빼고,
// 줄이 하나도 없으면 아무것도 그리지 않는다. frontend/src/views/DiagnosisView.vue, frontend/src/views/ProposalView.vue,
// frontend/src/views/ExecutionView.vue 가 화면 맨 아래에 둔다. 모양은 frontend/src/assets/base.css 의 details.tech 다.
import { computed } from 'vue'
import { Copy } from 'lucide-vue-next'
import { useToastStore } from '@/stores/toast'
import { copyText } from '@/utils/clipboard'

// rows 는 [{ label, value }] 이고 value 는 글이거나 글 목록이다.
const props = defineProps({ rows: { type: Array, required: true } })
const toast = useToastStore()

// 빈 값을 빼고, 목록은 원소마다 따로 복사할 수 있게 펼친다.
const shown = computed(() =>
  props.rows
    .map((r) => ({ label: r.label, values: (Array.isArray(r.value) ? r.value : [r.value]).filter(Boolean).map(String) }))
    .filter((r) => r.values.length),
)

// 값 하나를 복사하고 토스트로 알린다.
async function copy(value) {
  try {
    await copyText(value)
    toast.show('복사했어요.', 'info', 1800)
  } catch {
    toast.show('복사하지 못했어요. 브라우저의 클립보드 권한을 확인해 주세요.', 'err')
  }
}
</script>

<template>
  <details v-if="shown.length" class="tech">
    <summary>개발자용 정보</summary>
    <dl>
      <template v-for="r in shown" :key="r.label">
        <dt>{{ r.label }}</dt>
        <dd>
          <span v-for="v in r.values" :key="v" class="val">
            <code>{{ v }}</code>
            <button type="button" class="icon-btn" :aria-label="`${r.label} 복사`" title="복사" @click="copy(v)"><Copy :size="12" /></button>
          </span>
        </dd>
      </template>
    </dl>
  </details>
</template>

<style scoped>
dl {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 6px 18px;
  margin: 0;
  padding: 12px 14px 14px;
}

dt {
  color: var(--muted);
  padding-top: 3px;
}

dd {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  min-width: 0;
}

.val {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

code {
  overflow-wrap: anywhere;
  background: transparent;
  padding: 0;
  color: var(--text-2);
}

.icon-btn {
  width: 22px;
  height: 22px;
  flex: none;
}

@media (max-width: 760px) {
  dl {
    grid-template-columns: minmax(0, 1fr);
    gap: 2px;
  }

  dt {
    margin-top: 8px;
  }
}
</style>
