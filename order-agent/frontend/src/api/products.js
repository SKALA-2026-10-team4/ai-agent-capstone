// 상품 목록을 읽는 order-agent REST 호출이다.
// order-agent/frontend/src/composables/useOrder.js 의 loadProducts 가 기본 api 로 이 함수를 쓰고,
// 제한 시간은 order-agent/frontend/src/api/client.js 의 DEFAULT_TIMEOUT_MS 를 따른다.
import { request } from "./client.js";
/**
 * GET /api/products 로 상품 목록({ items: [{ sku, name, unit_price, stock_quantity }] })을 읽는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ProductController.java 의 list() 가 받는다.
 */
export const listProducts = (options) => request("/products", options);
