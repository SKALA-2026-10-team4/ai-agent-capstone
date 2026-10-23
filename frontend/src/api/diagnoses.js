// 검증·진단 기록을 읽는 API 모듈이다. frontend/src/views/DiagnosisView.vue 의 loadThread,
// followChange, loadList 가 부른다. 진단 조회는 /service 경로로 SkillOps service 를 거친다.
// service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 가 서비스
// 토큰을 붙여 skillops-agent 의 GET /runs 와 GET /runs/{thread_id}/events
// (skillops-agent/skillops_agent/main.py)로 중계한다. 진단 Agent 는 서비스 토큰이 필요하므로
// 브라우저가 직접 부르지 않는다. 저장한 변경의 전달 상태만 order-agent(/api)에서 읽는다.
// mock 에는 이 세 경로가 없고, mock 모드에서는 DiagnosisView 가 조회를 아예 하지 않는다.
import { apiPath, get } from './client'

// 최근 진단 목록 { items: [...] } 를 읽는다.
// GET /service/api/diagnoses 는 DiagnosisController 의 list() 가 받는다.
export const listDiagnoses = () => get('/service/api/diagnoses')
// 진단 한 건(thread)의 상태와 기록을 읽는다. GET /service/api/diagnoses/{threadId} 는
// DiagnosisController 의 status() 가 받는다. 응답의 diagnosis_status 를
// frontend/src/utils/diagnosisView.js 의 shouldPollAgain 이 보고 다시 읽을지 정한다.
export const getDiagnosis = (threadId) => get(apiPath`/service/api/diagnoses/${threadId}`)
// 저장한 Skill 변경(change_id)이 service 를 거쳐 진단 Agent 에 전달됐는지 읽는다.
// GET /api/skill-changes/{changeId} 는
// order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
// changeDelivery() 가 받는다. 응답은
// order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java 의
// status() 가 만든 { change_id, snapshot_id, thread_id, attempts, last_error, delivery_status } 다.
// thread_id 가 생기면 DiagnosisView 가 진단 상세 주소로 바꾼다.
export const getChangeDelivery = (changeId) => get(apiPath`/api/skill-changes/${changeId}`)
