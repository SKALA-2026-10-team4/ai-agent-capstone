<script setup>
// SKILL.md 편집기다. textarea 하나에, before 가 있으면 그 뒤에 같은 글꼴과 줄 간격의 배경 층을 깔아
// before 와 달라진 줄을 칠한다. 제안서 후보를 초안으로 가져온 Skill 에서 그 후보가 더하거나 바꾼 줄이
// 어디인지 바로 보이게 하려는 것이다. 바꾼 줄은 노랑, 더한 줄은 초록, 줄이 지워진 자리는 빨간 선이다.
// 화면에 들어오거나 before 가 바뀌면 칠한 줄이 잠깐 깜빡여 눈에 띄게 한다.
// 줄 표시는 frontend/src/utils/diffLines.js 의 markLines 가 계산한다. textarea 가 줄을 접는 방식과
// 배경 층이 같아야 칠한 줄이 맞으므로 글꼴, 안쪽 여백, 테두리, 줄바꿈 규칙, 스크롤 막대 자리를 같게 둔다.
// frontend/src/views/SkillDraftView.vue 의 편집 탭이 쓰고, before 는 workspace store 의 currentAccepted 다.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { markLines } from '@/utils/diffLines'

// 칠한 줄이 깜빡이는 시간(밀리초)이다. 아래 style 의 flash 애니메이션 길이와 같다.
const FLASH_MS = 1800

// text 는 지금 원문, before 는 비교할 원문이며 null 이면 칠하지 않는다. rows 는 textarea 높이다.
const props = defineProps({
  text: { type: String, required: true },
  before: { type: String, default: null },
  rows: { type: Number, default: 22 },
})
// textarea 에 입력하면 새 원문을 input 이벤트로 올린다.
const emit = defineEmits(['input'])

// 배경 층의 줄 목록이다. before 가 없으면 null 이라 배경 층을 그리지 않는다.
const lines = computed(() => (props.before == null ? null : markLines(props.before, props.text).lines))

const backdrop = ref(null)
// textarea 를 스크롤하면 배경 층도 같은 만큼 옮긴다.
function syncScroll(event) {
  if (backdrop.value) backdrop.value.scrollTop = event.target.scrollTop
}

// 칠한 줄을 깜빡이는 중인지다. before 가 처음 생기거나 바뀔 때 FLASH_MS 동안 참이다.
const flashing = ref(false)
let timer = null
watch(
  () => props.before,
  (before) => {
    clearTimeout(timer)
    flashing.value = before != null
    if (flashing.value) timer = setTimeout(() => (flashing.value = false), FLASH_MS)
  },
  { immediate: true },
)
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="hl">
    <div v-if="lines" ref="backdrop" class="backdrop" aria-hidden="true">
      <div
        v-for="(l, i) in lines"
        :key="i"
        class="line"
        :class="[l.mark, { cut: l.cut, flash: flashing && (l.mark || l.cut) }]"
      >{{ l.text || ' ' }}</div>
    </div>
    <textarea :value="text" spellcheck="false" :rows="rows" @input="emit('input', $event.target.value)" @scroll="syncScroll" />
  </div>
</template>

<style scoped>
.hl {
  position: relative;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
}

textarea,
.backdrop {
  padding: 14px 16px;
  border: 1px solid transparent;
  font-family: var(--mono);
  font-size: var(--fs-sm);
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  scrollbar-gutter: stable;
}

textarea {
  position: relative;
  display: block;
  width: 100%;
  min-height: 420px;
  border-color: var(--border-strong);
  background: transparent;
  resize: vertical;
}

.backdrop {
  position: absolute;
  inset: 0;
  overflow: hidden;
  color: transparent;
  pointer-events: none;
}

.line {
  margin: 0 -16px;
  padding: 0 16px;
}

.line.add {
  background: var(--ok-soft);
  box-shadow: inset 3px 0 0 var(--ok);
}

.line.change {
  background: var(--warn-soft);
  box-shadow: inset 3px 0 0 var(--warn);
}

.line.cut {
  box-shadow: inset 0 2px 0 var(--err);
}

.line.add.cut {
  box-shadow: inset 3px 0 0 var(--ok), inset 0 2px 0 var(--err);
}

.line.change.cut {
  box-shadow: inset 3px 0 0 var(--warn), inset 0 2px 0 var(--err);
}

.line.flash {
  animation: flash 1.8s ease-out;
}

@keyframes flash {
  0%,
  40% {
    filter: brightness(2.2) saturate(1.4);
  }

  100% {
    filter: none;
  }
}
</style>
