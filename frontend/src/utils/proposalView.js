// 제안서 상태를 담당자 말로 보여 주는 이름표와 검토 대기 판정이다. 상태 문자열은
// service/src/main/java/com/capstone4/skillops/proposal/ProposalStatus.java 의 값과 같다.

// 상태마다 화면 라벨과 배지 색을 정하는 톤이다. frontend/src/components/ProposalStatusBadge.vue 가
// 그리고, 표에 없는 상태는 원래 이름으로 보인다.
export const STATUS = {
  proposed: { label: '검토 대기', tone: 'info' },
  no_valid_fix: { label: '쓸 수 있는 안 없음', tone: '' },
  approved: { label: '승인됨', tone: 'ok' },
  rejected: { label: '거절됨', tone: 'err' },
}

// 운영자가 아직 결정하지 않은 제안서인지 본다. 승인할 안이 없는 제안서(no_valid_fix)도
// 운영자가 확인하고 거절로 닫아야 하므로 넣는다. frontend/src/views/ProposalListView.vue 의
// 검토 대기 탭과 frontend/src/components/AppSidebar.vue 의 검토 대기 숫자가 함께 쓴다.
export const awaitsDecision = (p) => p.status === 'proposed' || p.status === 'no_valid_fix'
