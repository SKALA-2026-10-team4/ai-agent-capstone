// 글을 클립보드에 넣는 도우미다. frontend/src/components/DevelopmentRequestCard.vue('마크다운으로 복사'),
// frontend/src/components/RunRecordList.vue('ID 복사'), frontend/src/components/DevInfo.vue(개발자용 정보의
// 복사 버튼)가 쓴다. 브라우저 전역(navigator, document)을 쓰므로 Node 단위 테스트에서는 부르지 않는다.

/**
 * 글을 클립보드에 넣는다. Clipboard API 가 없으면(보안 연결이 아닌 주소 등) 숨긴 textarea 를 골라
 * 복사 명령을 쓰고, 그것도 안 되면 오류를 던진다.
 */
export async function copyText(text) {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text)
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  const ok = document.execCommand('copy')
  area.remove()
  if (!ok) throw new Error('copy failed')
}
