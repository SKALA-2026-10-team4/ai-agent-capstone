export class ApiError extends Error {
  constructor(
    message,
    {
      status = 0,
      code = "NETWORK_ERROR",
      order_id = null,
      field_errors = [],
    } = {},
  ) {
    super(message);
    this.name = "ApiError";
    Object.assign(this, {
      status,
      code,
      orderId: order_id,
      fieldErrors: field_errors,
    });
  }
}

export async function request(
  path,
  { timeout = 5000, signal, ...options } = {},
) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) controller.abort();
  const timer = setTimeout(abort, timeout);
  try {
    const response = await fetch(`/api${path}`, {
      ...options,
      signal: controller.signal,
    });
    const body = await response.json().catch(() => null);
    if (!response.ok) {
      throw new ApiError(
        body?.message || "서버에 연결할 수 없습니다. 주문 결과를 확인해주세요.",
        {
          ...body,
          status: response.status,
        },
      );
    }
    if (!body)
      throw new ApiError("서버 응답을 확인할 수 없습니다.", {
        code: "INVALID_RESPONSE",
      });
    return body;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(
      controller.signal.aborted
        ? "응답 확인 시간이 초과되었습니다. 주문이 접수됐을 수 있습니다."
        : "네트워크 연결을 확인해주세요. 주문이 접수됐을 수 있습니다.",
    );
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", abort);
  }
}
