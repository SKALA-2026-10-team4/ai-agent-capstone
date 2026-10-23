// 실행 증거 패널 order-agent/frontend/src/components/ExecutionInspector.vue 가 보여주는 문구를 만드는 순수 함수와
// trace 이벤트 라벨표를 모은 모듈이다. 템플릿 보간에 있던 식을 그대로 옮겼기 때문에, 값을 문자열로 바꿀 때
// Vue 의 toDisplayString 을 써서 템플릿과 같은 결과를 낸다. 그래서 null 과 undefined 는 빈 문자열이 된다.
// 폴백 연산자는 일부러 나눠 쓴다. 라벨은 || 로 빈 값까지 원래 값으로 되돌리고, 증거 값(raw_selection, resolved_skill_id,
// 토큰 수, elapsed_ms)은 ?? 로 0 과 빈 문자열을 그대로 보여준다.
// 이벤트 라벨을 바꾸거나 더하려면 EVENT_KIND_TEXT 를 고친다.
// order-agent/frontend/tests/evidence.test.js 가 라벨 수, 일부 라벨, 문구 함수의 결과를 고정하고,
// order-agent/frontend/tests/e2e/evidence.spec.js 가 일부 문구를 실제 화면에서 정확히 비교한다.
import { toDisplayString } from "vue";
import { labelOf, timestamp } from "./format.js";

// trace 이벤트 kind 를 화면 라벨로 바꾸는 표다. kind 는 order-agent backend 의
// order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 와
// order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java 등이 기록한다.
// 표에 없는 kind 는 원래 이름으로 보인다. 백엔드가 이미 내보내는 SKILL_STARTED 와 SKILL_FINISHED 도 지금은 표에 없어
// 원래 이름으로 보이며, 루트 frontend 의 frontend/src/utils/eventView.js 에는 이 두 라벨이 있다.
// 라벨을 더하면 order-agent/frontend/tests/evidence.test.js 의 개수 단언(21개)도 함께 고친다.
export const EVENT_KIND_TEXT = {
  SNAPSHOT_FIXED: "실행 파일 고정",
  CANDIDATES_OFFERED: "실제 제공한 후보",
  SKILL_SELECTED: "Skill 선택",
  SKILL_RESOLVED: "Skill 이름 해석",
  SKILL_LOADED: "실제 파일 로드",
  FLOW_VALIDATED: "흐름 계약 검사",
  DECISION_INPUT: "실행기에 제공한 입력",
  MODEL_RESPONSE: "원래 응답",
  MODEL_REQUEST: "API 호출",
  TOOL_REQUEST: "도구 요청",
  TOOL_RESULT: "도구 실행 결과",
  TOOL_ERROR: "도구 오류",
  TOOL_REPLAY: "멱등 결과 재사용",
  SKILL_COMPLETED: "단계 결과 전달",
  EXECUTION_ERROR: "실행 오류",
  BUSINESS_STOP: "정상 업무 종료",
  EXECUTION_FINISHED: "실행 종료",
  EXECUTION_STARTED: "실행 시작",
  DECISION_ERROR: "응답 오류",
  SKILL_LOAD_REJECTED: "파일 로드 거절",
  EXECUTION_INTERRUPTED: "중단 실행 복구",
};
/** trace 이벤트 kind 의 화면 라벨을 돌려준다. 표에 없으면 kind 를 그대로 돌려준다. */
export const eventKindLabel = (kind) => labelOf(EVENT_KIND_TEXT, kind);
/** kind 이름에 ERROR 나 REJECTED 가 들어 있으면 오류 이벤트로 본다. 패널은 그 항목을 event-error 클래스로 강조한다. */
export const isErrorEvent = (kind) =>
  kind.includes("ERROR") || kind.includes("REJECTED");
/**
 * 이벤트 시각 ISO 문자열에서 시:분:초.밀리초 부분(인덱스 11부터 22까지)만 자른다. 패널은 뒤에 UTC 를 붙여 보여준다.
 * Date 포맷터로 바꾸면 표시가 달라진다. 서버 Instant 문자열은 끝의 0 소수 자릿수를 생략하므로,
 * 소수 자릿수가 짧은 시각은 자른 결과에 끝의 Z 가 들어간다.
 */
export const eventClock = (at) => at.slice(11, 23);
/** 이벤트 data 와 snapshot metadata 를 두 칸 들여쓴 JSON 문자열로 바꾼다. undefined 는 그대로 돌려줘 화면에 빈 칸으로 보인다. */
export const prettyJson = (value) => JSON.stringify(value, null, 2);
/** 최근 실행 select 의 선택지 문구를 만든다. 시작 시각, 최종 상태(없으면 completion), execution_id 앞 8글자를 잇는다. */
export const executionOptionText = (item) =>
  `${timestamp(item.started_at)} · ${toDisplayString(item.final_status || item.completion)} · ${item.execution_id.slice(0, 8)}`;
/**
 * 모델이 원래 고른 Skill 이름과 서버가 해석한 Skill ID 를 화살표로 이은 문구를 만든다.
 * 값이 null 이나 undefined 면 각각 "응답 없음"과 "해석 없음"을 쓴다.
 * order-agent/frontend/tests/e2e/evidence.spec.js 가 이 문구 전체를 exact 로 비교하므로 공백 하나도 바꾸지 않는다.
 */
export const selectionText = (trace) =>
  `${toDisplayString(trace.raw_selection ?? "응답 없음")} → ${toDisplayString(trace.resolved_skill_id ?? "해석 없음")}`;
/**
 * 실행 모드 문구를 만든다. model_mode 가 fixed 이면 모델 이름 대신 실제 모델을 부르지 않았다는 안내를 보여준다.
 * fixed 는 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/FixedDecisionProvider.java 의 MODE 값이다.
 */
export const modelText = (trace) =>
  trace.model_mode === "fixed" ? "고정 응답 · 실제 모델 호출 없음" : trace.model;
/**
 * 관측 비용 문구를 만든다. API 호출 수, 입력 토큰 수, 출력 토큰 수, 경과 시간을 잇고,
 * 토큰 수가 없으면 "미관측", 경과 시간이 없으면 "진행 중"으로 채운다.
 * ?? 를 쓰기 때문에 토큰 0 은 0 으로 보인다. 고정 응답 실행은 토큰을 기록하지 않아 "미관측"이 된다.
 */
export const costText = (trace) =>
  `API ${toDisplayString(trace.api_calls)}회 · 입력 ${toDisplayString(trace.input_tokens ?? "미관측")} / 출력 ${toDisplayString(trace.output_tokens ?? "미관측")} 토큰 · ${toDisplayString(trace.elapsed_ms ?? "진행 중")}ms`;
