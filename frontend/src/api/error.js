// order-agent ErrorResponse {code, message, order_id, field_errors} 를 그대로 담는다.
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
