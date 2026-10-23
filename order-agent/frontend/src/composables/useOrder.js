// 주문 데모 화면의 상태 기계 useOrder 를 정의하는 composable 이다.
// 상태 전이와 요청 순서는 이 파일에 있고, 흐름 튜닝 값과 분기 규칙은 order-agent/frontend/src/utils/orderPolicy.js 에 있다.
import { computed, ref } from "vue";
import { listProducts } from "../api/products.js";
import { createOrder, getOrder, getByRequest } from "../api/orders.js";
import { ApiError } from "../api/client.js";
import { defaultForm, validate } from "../utils/validation.js";
import { presetForm } from "../utils/demoPresets.js";
import {
  ORDER_TIMING,
  LOCKED_REQUEST_STATES,
  IN_FLIGHT_REQUEST_STATES,
  isOrderResponse,
  isTerminalStatus,
  isRejectedBeforeAcceptance,
  isOrderNotFound,
  toFieldErrors,
} from "../utils/orderPolicy.js";

/** 폼을 JSON 왕복으로 깊은 복사한다. form.value 는 Vue reactive proxy 라서 structuredClone 을 쓰면 DataCloneError 가 난다. */
const copy = (value) => JSON.parse(JSON.stringify(value));

/**
 * 주문 데모 화면의 상태 기계다. 상품 조회, 주문 제출, polling, 응답 유실 복구, 같은 요청 재전송, 시연 preset,
 * 후보 snapshot 선택, 새 주문, 정리를 맡는다.
 *
 * 요청 상태(requestState)는 다섯 가지다. idle 은 아직 제출하지 않은 상태, submitting 은 POST 응답을 기다리는 상태,
 * polling 은 주문 상태를 다시 조회하는 상태다. recoverable 은 결과를 알 수 없어 사용자가 "상태 다시 확인"을 눌러야 하는
 * 상태이고, settled 는 결과가 확정됐거나 접수 전에 입력이 거절된 상태다. submitting, polling, recoverable 동안은
 * 입력을 잠가 중복 접수를 막는다.
 *
 * 흐름은 submit 에서 시작한다. 폼을 검증한 뒤 { key, body } 로 요청을 고정하고 createOrder 를 부른다.
 * 응답이 최종 상태면 settled 로 확정하고, CREATED 나 PROCESSING 이면 polling 한다. 오류는 failed 가 접수 전 거절과
 * 결과 확인 대상으로 나눈다. recover 는 같은 요청의 주문을 주문 ID 나 요청 키로 다시 찾고, 서버가 요청을 받지 못했다고
 * 확인되면 resend 로 같은 key 와 body 를 다시 보낼 수 있게 한다.
 *
 * 튜닝 지점은 order-agent/frontend/src/utils/orderPolicy.js 다. timeout, polling 간격과 한도, 최종 상태, 잠금 상태,
 * 오류 분류 규칙이 그 파일에 있다. 시연 preset 은 order-agent/frontend/src/utils/demoPresets.js 에,
 * 입력 검증은 order-agent/frontend/src/utils/validation.js 에 있다.
 *
 * generation 카운터로 늦게 도착한 응답을 버린다. 제출, 재전송, recover, 새 주문, dispose 가 카운터를 올리면
 * 그 전에 시작한 요청의 결과는 반영하지 않는다.
 * api 와 options 를 주입받고 lifecycle hook 을 부르지 않으므로 node --test 에서 가짜 API 로 검사할 수 있다.
 * order-agent/frontend/src/App.vue 가 한 번 만들고, order-agent/frontend/tests/order.test.js,
 * order-agent/frontend/tests/orderFlow.test.js, order-agent/frontend/tests/orderPolicy.test.js 가 상태 전이를 고정한다.
 *
 * @param api 주문 API 함수 묶음이다. 기본값은 order-agent/frontend/src/api/products.js 와 order-agent/frontend/src/api/orders.js 의 함수다.
 * @param options uuid(요청 키 생성기), pollMs, pollLimit, statusTimeoutMs 를 바꿀 때 쓴다.
 *   시간 값의 기본값은 order-agent/frontend/src/utils/orderPolicy.js 의 ORDER_TIMING 이다.
 */
export function useOrder(
  api = { listProducts, createOrder, getOrder, getByRequest },
  options = {},
) {
  // 서버 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 가 요청 키를
  // UUID 형식으로 검사하므로 기본 생성기는 crypto.randomUUID 다. 나머지 세 값은 테스트가 ORDER_TIMING 기본값을 바꿀 때 쓴다.
  const {
    uuid = () => crypto.randomUUID(),
    pollMs = ORDER_TIMING.pollIntervalMs,
    pollLimit = ORDER_TIMING.pollLimitMs,
    statusTimeoutMs = ORDER_TIMING.statusTimeoutMs,
  } = options;
  // 상품 목록 상태다. productStatus 는 loading, ready, error 중 하나이고,
  // order-agent/frontend/src/components/OrderForm.vue 가 안내와 입력 잠금에, order-agent/frontend/src/App.vue 가
  // preset 버튼 잠금에 쓴다.
  const products = ref([]),
    productStatus = ref("loading"),
    productError = ref("");
  // form 은 화면에서 고치는 입력, order 는 마지막으로 받은 주문 응답, submittedRequest 는 제출 순간 고정한 { key, body } 다.
  // 재전송과 결과 확인은 지금 폼이 아니라 submittedRequest 를 쓴다.
  const form = ref(defaultForm()),
    order = ref(null),
    submittedRequest = ref(null);
  // requestError 는 화면에 보일 오류, fieldErrors 는 입력 칸별 오류, canResend 는 같은 요청 재전송 허용 여부다.
  const requestState = ref("idle"),
    requestError = ref(null),
    fieldErrors = ref({}),
    canResend = ref(false);
  // 입력을 잠그는 상태인지 나타낸다. 잠금 상태 목록은 order-agent/frontend/src/utils/orderPolicy.js 의 LOCKED_REQUEST_STATES 다.
  const locked = computed(() =>
    LOCKED_REQUEST_STATES.includes(requestState.value),
  );
  // 보이는 결과가 지금 입력과 다른 직전 주문의 결과인지 나타낸다.
  // 폼과 제출 본문을 JSON 문자열로 비교하므로 폼을 고칠 때 키 순서를 바꾸지 않는다.
  // order-agent/frontend/src/components/OrderResult.vue 가 직전 주문 결과 안내에 쓴다.
  const previousOrder = computed(
    () =>
      !!order.value &&
      !!submittedRequest.value &&
      JSON.stringify(form.value) !==
        JSON.stringify(submittedRequest.value.body),
  );
  // dispose 가 진행 중인 모든 요청을 한 번에 취소하도록 취소 신호 하나를 모든 호출이 함께 쓴다.
  const controller = new AbortController();
  // generation 은 주문 요청, productGeneration 은 상품 조회의 세대 번호다.
  // timer 와 releaseWait 는 dispose 가 polling 대기를 바로 끝내려고 보관한다.
  // knownOrderId 는 주문 응답이나 오류 응답에서 알게 된 주문 ID 이며, recover 가 요청 키 대신 주문 ID 로 조회할 때 쓴다.
  let disposed = false,
    generation = 0,
    productGeneration = 0,
    timer,
    releaseWait,
    knownOrderId = null;
  /** 정리되지 않았고 가장 최근에 시작한 주문 요청인지 확인한다. 거짓이면 늦게 온 응답이라 버린다. */
  const active = (id) => !disposed && id === generation;
  /** 정리되지 않았고 가장 최근에 시작한 상품 조회인지 확인한다. */
  const productActive = (id) => !disposed && id === productGeneration;
  /** API 호출 옵션에 공유 취소 신호를 붙인다. extra 로 timeout 같은 옵션을 더한다. */
  const requestOptions = (extra) => ({ signal: controller.signal, ...extra });

  /**
   * 상품 목록을 읽어 products 와 productStatus 를 갱신한다. 응답의 items 가 배열이 아니면 형식 오류로 본다.
   * 겹쳐 부르면 가장 나중에 시작한 조회 결과만 반영하고, 실패해도 이전 products 는 지우지 않는다.
   * order-agent/frontend/src/App.vue 가 화면이 붙을 때와
   * order-agent/frontend/src/components/OrderForm.vue 의 reload-products 이벤트("상품 다시 불러오기" 버튼)에 연결한다.
   * 주문이 확정되면 재고가 바뀌었을 수 있고 PRODUCT_NOT_FOUND 로 거절되면 상품 목록이 낡았을 수 있으므로,
   * 이 파일의 accept 와 showInputRejection 도 이 함수를 부른다.
   */
  async function loadProducts() {
    const id = ++productGeneration;
    productStatus.value = "loading";
    try {
      const result = await api.listProducts(requestOptions());
      if (!productActive(id)) return;
      if (!Array.isArray(result.items))
        throw new ApiError("상품 목록 형식이 올바르지 않습니다.");
      products.value = result.items;
      productStatus.value = "ready";
      productError.value = "";
    } catch (error) {
      if (!productActive(id)) return;
      productStatus.value = "error";
      productError.value = error.message;
    }
  }
  /**
   * polling 사이를 ms 만큼 기다린다. dispose 가 releaseWait 로 대기를 바로 끝낼 수 있게 resolve 를 보관한다.
   * pollUntilTerminal 이 부른다.
   */
  const wait = (ms) =>
    new Promise((resolve) => {
      releaseWait = resolve;
      timer = setTimeout(() => {
        releaseWait = null;
        resolve();
      }, ms);
    });
  /**
   * 주문 응답을 화면에 반영하고, 최종 상태면 settled 로 확정한 뒤 true 를 돌려준다. 아직 처리 중이면 false 다.
   * 응답 형식이 맞지 않으면 ApiError 를 던져 호출 쪽 catch 가 결과 확인 대상 오류로 다루게 한다.
   * 확정 뒤에는 재고가 바뀌었을 수 있으므로 상품을 다시 읽는다. 이 재조회는 기다리지 않으므로 submit 이나 recover 가
   * 끝나는 시점을 늦추지 않는다. 재조회가 실패해도 productStatus 만 error 가 되고 확정된 주문은 그대로 남는다.
   * finish 와 pollUntilTerminal 이 부른다.
   */
  function accept(result) {
    if (!isOrderResponse(result))
      throw new ApiError("주문 응답 형식을 확인할 수 없습니다.");
    order.value = result;
    knownOrderId = result.order_id;
    if (isTerminalStatus(result.status)) {
      requestState.value = "settled";
      requestError.value = null;
      canResend.value = false;
      void loadProducts();
      return true;
    }
    return false;
  }
  /**
   * 서버가 접수 전에 입력을 거절했을 때 필드 오류를 보여주고 입력 잠금을 푼다.
   * 주문이 생기지 않았으므로 submittedRequest 를 지워 결과 확인과 재전송 대상에서 뺀다.
   * 없는 상품 때문에 거절됐다면 화면의 상품 목록이 낡았을 수 있어 상품을 다시 읽는다. failed 가 부른다.
   */
  function showInputRejection(error) {
    fieldErrors.value = toFieldErrors(error.fieldErrors);
    requestState.value = "settled";
    submittedRequest.value = null;
    if (error.code === "PRODUCT_NOT_FOUND") void loadProducts();
  }
  /**
   * 결과를 다시 확인해야 하는 상태로 두고 입력을 계속 잠근다.
   * 서버가 주문을 접수했을 수 있으므로 새 주문을 막고, 사용자가 "상태 다시 확인"으로 결과를 찾게 한다.
   * 재전송은 recover 가 서버에 요청이 없음을 확인한 뒤에만 허용하므로 여기서는 끈다. failed 와 finish 가 부른다.
   */
  function markRecoverable(error) {
    requestState.value = "recoverable";
    requestError.value = error;
    canResend.value = false;
  }
  /**
   * 제출, 재전송, 결과 확인 중 생긴 오류를 분류한다. id 가 최신 요청이 아니면 늦게 온 오류라 버린다.
   * 오류에 주문 ID 가 있으면 knownOrderId 에 기억해 다음 recover 가 주문 ID 로 조회하게 한다.
   * order-agent/frontend/src/utils/orderPolicy.js 의 isRejectedBeforeAcceptance 가 참이면 입력 거절로,
   * 아니면 결과 확인 대상으로 둔다. dispatch 와 recover 의 catch 가 부른다.
   */
  function failed(error, id) {
    if (!active(id)) return;
    requestError.value = error;
    if (error.orderId) knownOrderId = error.orderId;
    if (isRejectedBeforeAcceptance(error)) showInputRejection(error);
    else markRecoverable(error);
  }
  /**
   * pollLimit 마감 안에서 pollMs 간격으로 주문 상태를 하나씩 차례로 조회한다.
   * 최종 상태를 반영하면 true, 마감이 지나거나 새 요청이 시작돼 멈추면 false 다.
   * GET 하나의 timeout 은 statusTimeoutMs 와 남은 시간 중 짧은 쪽이라 마감을 넘겨 기다리지 않는다.
   * finish 가 부르고, 조회 오류는 잡지 않고 finish 를 부른 dispatch 나 recover 의 catch 로 넘긴다.
   */
  async function pollUntilTerminal(id) {
    const deadline = Date.now() + pollLimit;
    while (active(id) && Date.now() < deadline) {
      await wait(Math.min(pollMs, Math.max(0, deadline - Date.now())));
      if (!active(id) || Date.now() >= deadline) break;
      const next = await api.getOrder(
        order.value.order_id,
        requestOptions({
          timeout: Math.min(statusTimeoutMs, deadline - Date.now()),
        }),
      );
      if (!active(id)) return false;
      if (accept(next)) return true;
    }
    return false;
  }
  /**
   * POST 나 결과 확인 조회의 응답을 반영한다. 최종 상태면 끝내고, 아니면 polling 으로 바꿔 pollUntilTerminal 을 돌린다.
   * 한도 안에 최종 상태가 오지 않으면 FAILED 로 단정하지 않고 "아직 처리 중" 오류와 함께 recoverable 로 둔다.
   * dispatch 와 recover 가 부른다.
   */
  async function finish(result, id) {
    if (!active(id) || accept(result)) return;
    requestState.value = "polling";
    if (await pollUntilTerminal(id)) return;
    if (active(id))
      markRecoverable(
        new ApiError(
          "아직 처리 중입니다. “상태 다시 확인”으로 결과를 확인해주세요.",
        ),
      );
  }
  /**
   * 고정한 submittedRequest 의 key 와 body 로 POST /api/orders 를 보내고, 응답은 finish 에, 오류는 failed 에 넘긴다.
   * id 는 이 요청의 세대 번호이며, 그 사이 다른 요청이 시작되면 결과를 버린다. sendSubmittedRequest 가 부른다.
   */
  async function dispatch(id) {
    const snapshot = submittedRequest.value;
    try {
      const result = await api.createOrder(
        snapshot.key,
        snapshot.body,
        requestOptions(),
      );
      await finish(result, id);
    } catch (error) {
      failed(error, id);
    }
  }
  /**
   * 고정한 요청을 보내기 시작한다. 이전 오류와 재전송 허용을 지우고 submitting 으로 바꾼 뒤 새 세대 번호로 dispatch 한다.
   * submit 과 resend 가 함께 쓴다.
   */
  function sendSubmittedRequest() {
    requestError.value = null;
    canResend.value = false;
    requestState.value = "submitting";
    return dispatch(++generation);
  }
  /**
   * 주문을 제출한다. 잠금 중이면 무시하고, 클라이언트 검증 오류가 있으면 요청 없이 멈춘다.
   * 검증 오류가 생기면 order-agent/frontend/src/App.vue 의 watch 가 첫 오류 입력으로 focus 를 옮긴다.
   * 요청 키를 새로 만들고 폼을 깊은 복사해 submittedRequest 로 고정하므로, 이후 폼을 고쳐도 보낸 본문과 재전송 본문은 그대로다.
   * order-agent/frontend/src/App.vue 가 order-agent/frontend/src/components/OrderForm.vue 의 submit 이벤트에 연결한다.
   */
  async function submit() {
    if (locked.value) return;
    fieldErrors.value = validate(form.value);
    if (Object.keys(fieldErrors.value).length) return;
    submittedRequest.value = { key: uuid(), body: copy(form.value) };
    order.value = null;
    knownOrderId = null;
    await sendSubmittedRequest();
  }
  /**
   * 제출한 요청의 주문을 찾는다. 확인된 주문 ID 가 있으면 GET /api/orders/{id} 로,
   * 없으면 요청 키로 GET /api/orders/by-request/{key} 를 부른다. 두 조회 모두 timeout 은 statusTimeoutMs 다.
   * recover 가 부른다.
   */
  function lookupSubmittedOrder(existingId) {
    const lookupOptions = requestOptions({ timeout: statusTimeoutMs });
    return existingId
      ? api.getOrder(existingId, lookupOptions)
      : api.getByRequest(submittedRequest.value.key, lookupOptions);
  }
  /**
   * 서버에 요청이 닿지 않았음을 확인했으므로 같은 요청의 재전송을 허용한다.
   * 재전송할 수 있다는 안내를 requestError 로 함께 보여준다. recover 가 부른다.
   */
  function allowResend() {
    canResend.value = true;
    requestError.value = new ApiError(
      "접수된 주문을 찾지 못했습니다. 같은 요청으로 다시 전송할 수 있습니다.",
    );
  }
  /**
   * 응답을 잃었거나 polling 한도를 넘긴 주문의 결과를 다시 확인한다.
   * 정리된 뒤, 제출 이력이 없을 때, 이미 응답을 기다리는 중일 때는 무시한다.
   * requestError 는 지우지 않으므로 조회하는 동안 이전 오류가 그대로 보인다. 조회 결과는 제출 흐름과 같은 finish 로 반영한다.
   * 조회 전에 주문 ID 를 몰랐고 404 ORDER_NOT_FOUND 를 받았을 때만 재전송을 허용한다. 요청 키로 찾은 주문을 polling 하다
   * 받은 404 도 같은 조건으로 본다. 주문 ID 를 알고 있었다면 접수된 주문이 있다는 뜻이라 재전송하지 않는다.
   * failed 로 먼저 분류한 뒤에 재전송을 허용하므로 두 호출의 순서를 바꾸지 않는다.
   * order-agent/frontend/src/App.vue 가 order-agent/frontend/src/components/OrderResult.vue 의 recover 이벤트
   * ("상태 다시 확인" 버튼)에 연결한다.
   */
  async function recover() {
    if (
      disposed ||
      !submittedRequest.value ||
      IN_FLIGHT_REQUEST_STATES.includes(requestState.value)
    )
      return;
    const id = ++generation;
    requestState.value = "polling";
    canResend.value = false;
    const existingId = order.value?.order_id || knownOrderId;
    try {
      const result = await lookupSubmittedOrder(existingId);
      await finish(result, id);
    } catch (error) {
      failed(error, id);
      if (active(id) && isOrderNotFound(error) && !existingId) allowResend();
    }
  }
  /**
   * recover 가 허용한 경우에만 고정한 같은 key 와 body 로 주문을 다시 보낸다.
   * 서버가 같은 Idempotency-Key 의 중복 접수를 막으므로 두 번 보내도 주문은 하나다.
   * order-agent/frontend/tests/e2e/order.spec.js 가 두 POST 의 요청 키와 본문이 같은지 확인한다.
   * order-agent/frontend/src/App.vue 가 order-agent/frontend/src/components/OrderResult.vue 의 resend 이벤트에 연결한다.
   */
  async function resend() {
    if (!canResend.value || !submittedRequest.value) return;
    await sendSubmittedRequest();
  }
  /**
   * 시연 preset 으로 폼을 새로 갈아끼우고 필드 오류를 지운다. 잠금 중이면 무시한다.
   * order-agent/frontend/src/utils/demoPresets.js 의 presetForm 이 새 기본 폼을 만들므로 고정했던 snapshot_id 도 사라진다.
   * order-agent/frontend/src/App.vue 가 order-agent/frontend/src/components/DemoPresets.vue 의 preset 이벤트에 연결한다.
   */
  function preset(name) {
    if (locked.value) return;
    form.value = presetForm(name);
    fieldErrors.value = {};
  }
  /**
   * 고정한 Skill 후보의 snapshot_id 를 다음 주문 입력에 넣는다. 잠금 중이면 무시한다.
   * snapshot_id 는 폼 끝에 붙어 다음 POST 본문으로 가고, 서버
   * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java 의 snapshotId 로 받는다.
   * 서버는 그 값을 주문에 기록하고 Harness 가 그 snapshot 의 파일로 Agent 를 실행한다.
   * order-agent/frontend/src/App.vue 가 order-agent/frontend/src/components/ExecutionInspector.vue 의 snapshot 이벤트에
   * 연결한다. 그 패널도 잠금 중에는 후보를 고정하지 않지만, 고정 요청이 도는 사이에 주문이 시작될 수 있어 여기서도 막는다.
   */
  function selectSnapshot(snapshotId) {
    if (locked.value) return;
    form.value = { ...form.value, snapshot_id: snapshotId };
  }
  /**
   * 결과를 지우고 기본 폼으로 새 주문을 시작한다. 잠금 중이면 무시한다.
   * 세대 번호를 올려 남은 응답을 버리고, 폼을 기본값으로 바꾸므로 고정했던 snapshot_id 도 지운다.
   * order-agent/frontend/src/App.vue 가 order-agent/frontend/src/components/OrderResult.vue 의 new-order 이벤트
   * ("새 주문 작성" 버튼)에 연결한다.
   */
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
  /**
   * 화면이 사라질 때 남은 요청을 취소하고 늦은 응답을 모두 버린다. polling 대기도 바로 풀어 submit promise 가 끝나게 한다.
   * order-agent/frontend/src/App.vue 가 onUnmounted 에 연결한다.
   */
  function dispose() {
    disposed = true;
    generation++;
    productGeneration++;
    controller.abort();
    clearTimeout(timer);
    releaseWait?.();
  }
  // order-agent/frontend/src/App.vue 가 이 객체를 구조 분해해 ref 를 템플릿에 넘긴다.
  // 키 이름은 App.vue 와 테스트가 함께 쓰는 계약이라 바꾸지 않는다.
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
    previousOrder,
    loadProducts,
    submit,
    recover,
    resend,
    preset,
    selectSnapshot,
    newOrder,
    dispose,
  };
}
