// 모의 실행 장부를 읽는 모듈이다. 제안서에는 실행 번호(r_…)만 있어서, 그 실행이 어느 시험 주문을 돌려 통과했는지를
// service 의 장부에서 찾아 화면 이름을 붙이는 데 쓴다. 받는 쪽은
// service/src/main/java/com/capstone4/skillops/api/RunController.java 의 find() 이고 로그인한 사람만 읽는다.
import { get } from './client'

// 실행 번호 목록으로 장부를 읽는다. GET /service/api/runs?ids=r_a,r_b 의 응답은
// { items: [{ run_id, scenario_id, scenario_name, passed }] } 이고 장부에 없는 번호는 빠진다.
// 번호가 없으면 요청하지 않고 빈 목록을 돌려준다. frontend/src/views/ProposalView.vue 의 loadNames 가 부른다.
export const getRuns = (ids) =>
  ids.length ? get(`/service/api/runs?ids=${ids.map(encodeURIComponent).join(',')}`) : Promise.resolve({ items: [] })
