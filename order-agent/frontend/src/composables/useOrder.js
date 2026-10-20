import { computed, ref } from "vue";
import { listProducts } from "../api/products.js";
import { createOrder, getOrder, getByRequest } from "../api/orders.js";
import { ApiError } from "../api/client.js";
import { defaultForm, validate } from "../utils/validation.js";

const terminal = new Set(["SHIPPED", "REJECTED", "HOLD", "FAILED"]);
const known = new Set([...terminal, "CREATED", "PROCESSING"]);
const copy = (value) => JSON.parse(JSON.stringify(value));

export function useOrder(
  api = { listProducts, createOrder, getOrder, getByRequest },
  options = {},
) {
  const {
    uuid = () => crypto.randomUUID(),
    pollMs = 1000,
    pollLimit = 10000,
  } = options;
  const products = ref([]),
    productStatus = ref("loading"),
    productError = ref("");
  const form = ref(defaultForm()),
    order = ref(null),
    submittedRequest = ref(null);
  const requestState = ref("idle"),
    requestError = ref(null),
    fieldErrors = ref({}),
    canResend = ref(false);
  const locked = computed(() =>
    ["submitting", "polling", "recoverable"].includes(requestState.value),
  );
  const controller = new AbortController();
  let disposed = false,
    generation = 0,
    productGeneration = 0,
    timer,
    releaseWait,
    knownOrderId = null;
  const active = (id) => !disposed && id === generation;

  async function loadProducts() {
    const id = ++productGeneration;
    productStatus.value = "loading";
    try {
      const result = await api.listProducts({ signal: controller.signal });
      if (disposed || id !== productGeneration) return;
      if (!Array.isArray(result.items))
        throw new ApiError("상품 목록 형식이 올바르지 않습니다.");
      products.value = result.items;
      productStatus.value = "ready";
      productError.value = "";
    } catch (error) {
      if (disposed || id !== productGeneration) return;
      productStatus.value = "error";
      productError.value = error.message;
    }
  }
  const wait = (ms) =>
    new Promise((resolve) => {
      releaseWait = resolve;
      timer = setTimeout(() => {
        releaseWait = null;
        resolve();
      }, ms);
    });
  function accept(result) {
    if (!result?.order_id || !known.has(result.status))
      throw new ApiError("주문 응답 형식을 확인할 수 없습니다.");
    order.value = result;
    knownOrderId = result.order_id;
    if (terminal.has(result.status)) {
      requestState.value = "settled";
      requestError.value = null;
      canResend.value = false;
      void loadProducts();
      return true;
    }
    return false;
  }
  function failed(error, id) {
    if (!active(id)) return;
    requestError.value = error;
    if (error.orderId) knownOrderId = error.orderId;
    const invalidBeforeAccept =
      !error.orderId &&
      (error.status === 400 ||
        (error.status === 404 && error.code === "PRODUCT_NOT_FOUND"));
    if (invalidBeforeAccept) {
      fieldErrors.value = Object.fromEntries(
        (error.fieldErrors || []).map((f) => [
          f.field.replace("paymentMethod", "payment_method"),
          f.message,
        ]),
      );
      requestState.value = "settled";
      submittedRequest.value = null;
      if (error.code === "PRODUCT_NOT_FOUND") void loadProducts();
    } else {
      requestState.value = "recoverable";
      canResend.value = false;
    }
  }
  async function finish(result, id) {
    if (!active(id) || accept(result)) return;
    requestState.value = "polling";
    const deadline = Date.now() + pollLimit;
    while (active(id) && Date.now() < deadline) {
      await wait(Math.min(pollMs, Math.max(0, deadline - Date.now())));
      if (!active(id) || Date.now() >= deadline) break;
      const next = await api.getOrder(order.value.order_id, {
        signal: controller.signal,
        timeout: Math.min(5000, deadline - Date.now()),
      });
      if (!active(id) || accept(next)) return;
    }
    if (active(id)) {
      requestState.value = "recoverable";
      requestError.value = new ApiError(
        "아직 처리 중입니다. “상태 다시 확인”으로 결과를 확인해주세요.",
      );
    }
  }
  async function dispatch(id) {
    const snapshot = submittedRequest.value;
    try {
      const result = await api.createOrder(snapshot.key, snapshot.body, {
        signal: controller.signal,
      });
      await finish(result, id);
    } catch (error) {
      failed(error, id);
    }
  }
  async function submit() {
    if (locked.value) return;
    fieldErrors.value = validate(form.value);
    if (Object.keys(fieldErrors.value).length) return;
    submittedRequest.value = { key: uuid(), body: copy(form.value) };
    order.value = null;
    knownOrderId = null;
    requestError.value = null;
    canResend.value = false;
    requestState.value = "submitting";
    await dispatch(++generation);
  }
  async function recover() {
    if (
      disposed ||
      !submittedRequest.value ||
      ["submitting", "polling"].includes(requestState.value)
    )
      return;
    const id = ++generation;
    requestState.value = "polling";
    canResend.value = false;
    const existingId = order.value?.order_id || knownOrderId;
    try {
      const result = existingId
        ? await api.getOrder(existingId, { signal: controller.signal })
        : await api.getByRequest(submittedRequest.value.key, {
            signal: controller.signal,
          });
      await finish(result, id);
    } catch (error) {
      failed(error, id);
      if (
        active(id) &&
        error.status === 404 &&
        error.code === "ORDER_NOT_FOUND" &&
        !existingId
      ) {
        canResend.value = true;
        requestError.value = new ApiError(
          "접수된 주문을 찾지 못했습니다. 같은 요청으로 다시 전송할 수 있습니다.",
        );
      }
    }
  }
  async function resend() {
    if (!canResend.value || !submittedRequest.value) return;
    canResend.value = false;
    requestState.value = "submitting";
    requestError.value = null;
    await dispatch(++generation);
  }
  function preset(name) {
    if (locked.value) return;
    const next = defaultForm();
    if (name === "normal") next.items[0].qty = 2;
    if (name === "soldout") next.items[0].sku = "CAMP-MUG-STEEL";
    if (name === "declined") next.customer = "c_declined";
    form.value = next;
    fieldErrors.value = {};
  }
  function newOrder() {
    if (locked.value) return;
    generation++;
    knownOrderId = null;
    form.value = defaultForm();
    order.value = null;
    submittedRequest.value = null;
    requestState.value = "idle";
    requestError.value = null;
    fieldErrors.value = {};
  }
  function dispose() {
    disposed = true;
    generation++;
    productGeneration++;
    controller.abort();
    clearTimeout(timer);
    releaseWait?.();
  }
  return {
    products,
    productStatus,
    productError,
    form,
    order,
    submittedRequest,
    requestState,
    requestError,
    fieldErrors,
    canResend,
    locked,
    loadProducts,
    submit,
    recover,
    resend,
    preset,
    newOrder,
    dispose,
  };
}
