import { request } from "./client.js";
export const createOrder = (key, body, options) =>
  request("/orders", {
    method: "POST",
    timeout: 15000,
    headers: { "Content-Type": "application/json", "Idempotency-Key": key },
    body: JSON.stringify(body),
    ...options,
  });
export const getOrder = (id, options) =>
  request(`/orders/${encodeURIComponent(id)}`, options);
export const getByRequest = (key, options) =>
  request(`/orders/by-request/${encodeURIComponent(key)}`, options);
