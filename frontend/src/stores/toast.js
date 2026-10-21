import { ref } from 'vue'
import { defineStore } from 'pinia'

// 화면 오른쪽 아래의 짧은 알림. 저장·적용·승인처럼 결과가 화면 밖에 있는 동작 뒤에 띄운다.
export const useToastStore = defineStore('toast', () => {
  const items = ref([])
  let seq = 0

  function show(message, tone = 'ok', ms = 3500) {
    const id = ++seq
    items.value.push({ id, message, tone })
    setTimeout(() => dismiss(id), ms)
  }

  function dismiss(id) {
    items.value = items.value.filter((t) => t.id !== id)
  }

  return { items, show, dismiss }
})
