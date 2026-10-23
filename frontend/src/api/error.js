/**
 * 화면이 받는 모든 API 오류의 모양이다. order-agent 의 오류 본문 { code, message, order_id,
 * field_errors } 를 그대로 담고, service 의 오류 본문 { code, message } 도 같은 칸에 담는다.
 * 두 본문의 정의는
 * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/common/ErrorResponse.java 와
 * service/src/main/java/com/capstone4/skillops/common/ApiError.java 에 있다. status 는 HTTP
 * 상태이고 연결 실패와 시간 초과는 0 이다. code, orderId 는 없으면 null, fieldErrors 는 없으면
 * 빈 목록이고, body 는 응답 본문 원문이다. frontend/src/api/client.js 의 httpError 와 send,
 * frontend/src/api/orderAgent.js 의 recover, frontend/src/mocks/index.js 의 모든 오류 응답이
 * 이 객체를 만든다. 화면은 대개 message 를 보여 주고, frontend/src/views/LoginView.vue 는
 * status 401 을, frontend/src/views/OrderView.vue 는 fieldErrors 를,
 * frontend/src/api/orderAgent.js 의 createOrder 는 status 와 orderId 를 읽는다.
 */
export class ApiError extends Error {
  constructor(status, message, { code = null, orderId = null, fieldErrors = [], body = null } = {}) {
    super(message)
    this.status = status
    this.code = code
    this.orderId = orderId
    this.fieldErrors = fieldErrors
    this.body = body
  }
}
