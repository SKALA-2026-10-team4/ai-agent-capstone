// TODO(A/C-1 UI 연동): 공용 trace 와 snapshot API 가 확정되면 이 모듈에서 경로와 DTO 를 변환한다.
// snapshot ID 와 실행 ID 를 혼용하거나 존재하지 않는 과거 실행 기록을 생성하지 않는다.
//
// 실행 증거(trace, snapshot)를 읽고 시연용 Skill 후보를 고정하는 order-agent REST 호출 모음이다.
// 모든 경로는 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 가 받는다.
// order-agent/docs/agent-evidence.md 의 담당자 표가 이 모듈을 공용 API 연동 지점(A/C-1)으로 가리키므로,
// 증거 API 호출은 반드시 이 파일의 함수를 거친다. 경로 조각과 path 쿼리는 encodeURIComponent 로 인코딩한다.
// order-agent/frontend/src/composables/useExecutionEvidence.js 가 기본 api 로 이 함수들을 쓰고,
// order-agent/frontend/tests/api.test.js 가 경로, 본문, 기본 timeout 을 고정한다.
// 루트 frontend 의 frontend/src/api/orderAgent.js 도 같은 경로를 따로 부르므로, 응답 형식을 바꾸면 두 화면을 함께 확인한다.
import { postJson, request } from "./client.js";
/**
 * GET /api/executions 로 최근 실행 목록({ items: [...] })을 읽는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 recent() 가 받는다.
 */
export const listExecutions = (options) => request("/executions", options);
/**
 * GET /api/executions/{id}/trace 로 실행 하나의 trace(선택한 Skill, 실행 모드, 관측 비용, 이벤트 목록)를 읽는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 trace() 가 받는다.
 */
export const getTrace = (id, options) =>
  request(`/executions/${encodeURIComponent(id)}/trace`, options);
/**
 * GET /api/snapshots/{id} 로 실행에 고정된 파일 묶음의 manifest(전체 해시, metadata, 파일 경로와 크기)를 읽는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 snapshot() 이 받는다.
 */
export const getSnapshot = (id, options) =>
  request(`/snapshots/${encodeURIComponent(id)}`, options);
/**
 * GET /api/snapshots/{id}/files?path= 로 snapshot 안 파일 하나의 해시, 인코딩, 본문을 읽는다.
 * UTF-8 로 읽을 수 없는 파일은 서버가 base64 로 보낸다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 file() 이 받는다.
 */
export const getSnapshotFile = (id, path, options) =>
  request(
    `/snapshots/${encodeURIComponent(id)}/files?path=${encodeURIComponent(path)}`,
    options,
  );
/**
 * POST /api/snapshots 에 { fixture } 를 보내 미리 준비한 시연 후보를 새 snapshot 으로 고정하고 그 manifest 를 받는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 publish() 가 받아
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의 demo() 로 만든다.
 * fixture 이름은 그 클래스의 DEMO_FIXTURES(baseline, broken-contract, repaired) 중 하나여야 하고,
 * order-agent.demo-enabled 설정이 꺼져 있으면 서버가 404 로 거절한다.
 */
export const publishSnapshot = (fixture, options) =>
  postJson("/snapshots", { fixture }, options);
