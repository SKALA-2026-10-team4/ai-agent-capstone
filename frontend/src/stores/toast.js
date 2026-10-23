// 화면 오른쪽 아래의 짧은 알림 목록을 담는 Pinia 스토어다. 저장, 적용, 승인처럼 결과가 화면 밖에
// 있는 동작 뒤에 띄운다. frontend/src/components/ToastHost.vue 가 items 를 그리고,
// frontend/src/views/SkillDraftView.vue 와 frontend/src/views/ProposalView.vue 가 show 를 부른다.
import { ref } from 'vue'
import { defineStore } from 'pinia'

// 알림 스토어다. items 는 화면에 떠 있는 알림 { id, message, tone } 목록이고, seq 는
// 알림마다 새 id 를 매기는 번호다.
export const useToastStore = defineStore('toast', () => {
  const items = ref([])
  let seq = 0

  // 알림 하나를 띄우고 ms 밀리초(기본 3500) 뒤 저절로 지운다. tone 은
  // ok, err, info 가운데 하나이고 ToastHost 의 아이콘과 색을 정한다.
  function show(message, tone = 'ok', ms = 3500) {
    const id = ++seq
    items.value.push({ id, message, tone })
    setTimeout(() => dismiss(id), ms)
  }

  // id 의 알림을 지운다. ToastHost 의 닫기 버튼과 show 의 자동 제거가 부른다.
  function dismiss(id) {
    items.value = items.value.filter((t) => t.id !== id)
  }

  return { items, show, dismiss }
})
