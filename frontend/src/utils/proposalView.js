// 제안서를 담당자 말로 보여 주기 위한 이름표. KIND 의 이름은 service 의 제안서 본문(ProposalDocumentWriter)과 같다.
import { GitBranch, Link2, Wrench } from 'lucide-vue-next'

export const KIND = {
  same_skill: { label: '바뀐 Skill만 고치기', hint: '바뀐 Skill 하나만 고친다', icon: Wrench },
  linked_skills: { label: '주고받는 값까지 함께 고치기', hint: '값을 주고받는 Skill 의 입출력을 함께 맞춘다', icon: Link2 },
  run_order: { label: '실행 순서 다시 짜기', hint: 'order-flow 의 단계 순서를 다시 짠다', icon: GitBranch },
}

export const STATUS = {
  proposed: { label: '검토 대기', tone: 'info' },
  no_valid_fix: { label: '쓸 수 있는 안 없음', tone: '' },
  approved: { label: '승인됨', tone: 'ok' },
  rejected: { label: '거절됨', tone: 'err' },
}

export const TRIGGER = {
  failure: '주문 실패로 시작',
  skill_saved: 'Skill 저장 때 사전 점검',
}
