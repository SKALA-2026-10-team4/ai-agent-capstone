// 창 폭에 맞춰 운영 화면 전체를 비례 확대한다. 화면의 글자, 간격, 카드 폭이 모두 고정 px 이라
// 글자 크기 토큰만 바꾸면 모양이 어긋나므로, 기준 폭(1440px)에서 보이는 화면을 통째로 키운다.
// 배율은 html 요소의 CSS 변수 --app-zoom 에 넣고, frontend/src/assets/base.css 의 #app 이 zoom 으로 쓴다.
// 기준 값은 frontend/src/config.js 의 VIEWPORT_ZOOM 이다. frontend/src/main.js 가 installViewportZoom 을
// 부르고, frontend/unit/viewportZoom.test.js 가 Node 에서 바로 불러오므로 상대 경로로만 import 한다.
import { VIEWPORT_ZOOM } from '../config.js'

// 창 폭(px)에 맞는 배율을 돌려준다. 기준 폭 이하면 1, 넓으면 창 폭 / 기준 폭이고 maxZoom 을 넘지 않는다.
// 소수 셋째 자리에서 자르는 것은 창을 끌 때 글자가 미세하게 흔들리지 않게 하려는 것이다.
export function zoomFor(width, { baseWidth, maxZoom } = VIEWPORT_ZOOM) {
  if (!(width > baseWidth)) return 1
  return Math.min(maxZoom, Math.floor((width / baseWidth) * 1000) / 1000)
}

// 지금 창 폭으로 배율을 정해 root 의 --app-zoom 에 넣고, 창 크기가 바뀔 때마다 다시 정한다.
// 같은 배율이면 다시 쓰지 않는다. 등록한 resize 처리기를 지우는 함수를 돌려준다.
// frontend/src/main.js 가 앱을 mount 하기 전에 window 와 document.documentElement 로 부른다.
export function installViewportZoom(win, root, options = VIEWPORT_ZOOM) {
  let current = null
  const apply = () => {
    const zoom = zoomFor(win.innerWidth, options)
    if (zoom === current) return
    current = zoom
    root.style.setProperty('--app-zoom', String(zoom))
  }
  apply()
  win.addEventListener('resize', apply)
  return () => win.removeEventListener('resize', apply)
}
