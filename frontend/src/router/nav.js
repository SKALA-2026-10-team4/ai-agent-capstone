// 위쪽 막대 메뉴와 화면 머리의 구역 번호를 정하는 표다. 메뉴를 더하거나 순서를 바꿀 때 이 파일만 고친다.
// frontend/src/components/AppTopBar.vue 가 메뉴 알약을, frontend/src/components/PageHeader.vue 가
// '01 · 개선안' 같은 머리 위 작은 줄(eyebrow)을 이 표로 그린다. vue 와 router 를 import 하지 않으므로
// frontend/unit/nav.test.js 가 Node 에서 바로 불러 규칙을 고정한다.
//
// 메뉴는 담당자의 일 순서대로 묶었다. 개선안을 받고(검증·진단 기록, 받은 개선안), Skill 을 손보고
// (Skill 편집, 버전 비교), 실행해 본다(주문 실행 · 기록). to 는 frontend/src/router/index.js 의 경로와
// 맞추고, match 는 그 메뉴를 켤 주소 접두어다. 실행 상세(/executions/…)는 주문 실행 · 기록 메뉴에 든다.
// badge 가 참인 항목 옆에 검토 대기 제안서 수를 붙인다.
export const NAV_GROUPS = [
  {
    title: '개선안',
    items: [
      { to: '/diagnoses', label: '검증·진단 기록', match: ['/diagnoses'] },
      { to: '/proposals', label: '받은 개선안', match: ['/proposals'], badge: true },
    ],
  },
  {
    title: 'Skill',
    items: [
      { to: '/skills', label: 'Skill 편집', match: ['/skills'] },
      { to: '/compare', label: '버전 비교', match: ['/compare'] },
    ],
  },
  {
    title: '실행',
    items: [{ to: '/orders', label: '주문 실행 · 기록', match: ['/orders', '/executions'] }],
  },
]

// 주소가 접두어와 같거나 접두어 뒤에 '/' 나 '?' 가 이어질 때만 맞다고 본다. '/skillsx' 는 '/skills' 가 아니다.
const underPrefix = (path, prefix) => path === prefix || path.startsWith(`${prefix}/`) || path.startsWith(`${prefix}?`)

// 메뉴 항목이 지금 주소에서 켜져야 하는지 본다.
export const isNavActive = (item, path) => item.match.some((prefix) => underPrefix(path ?? '', prefix))

/**
 * 주소가 속한 구역을 { no, group, label } 로 돌려준다. no 는 묶음 순서를 두 자리로 쓴 번호('01')이고
 * group 은 묶음 이름, label 은 메뉴 이름이다. 어느 메뉴에도 들지 않으면 null 이다.
 */
export function sectionOf(path) {
  for (const [i, g] of NAV_GROUPS.entries()) {
    const item = g.items.find((it) => isNavActive(it, path))
    if (item) return { no: String(i + 1).padStart(2, '0'), group: g.title, label: item.label }
  }
  return null
}
