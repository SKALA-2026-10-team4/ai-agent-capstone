import { request } from "./client.js";
export const listProducts = (options) => request("/products", options);
