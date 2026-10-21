// 개선안을 담당자 말로 보여 주기 위한 이름표. 데이터 형식은 skillops_agent/state.py 그대로다.
import { GitBranch, Link2, Wrench } from 'lucide-vue-next'

export const KIND = {
  local: { label: '이 Skill 만 고치기', hint: '바뀐 Skill 하나만 손본다', icon: Wrench },
  contract: { label: '연결된 Skill 까지 맞추기', hint: '주고받는 값(입출력)을 함께 맞춘다', icon: Link2 },
  flow: { label: '처리 순서 바꾸기', hint: 'order-flow 의 단계 순서를 다시 짠다', icon: GitBranch },
}

export const APPROVAL = {
  pending: { label: '검토 대기', tone: 'info' },
  approved: { label: '승인됨', tone: 'ok' },
  rejected: { label: '거절됨', tone: 'err' },
}

export const TRIGGER = {
  failure: '주문 실패로 시작',
  skill_saved: 'Skill 저장 때 사전 점검',
}
