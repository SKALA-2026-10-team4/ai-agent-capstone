import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/e2e",
  workers: 1,
  retries: 0,
  timeout: 25000,
  use: {
    baseURL: process.env.ORDER_DEMO_UI_URL || "http://127.0.0.1:5174",
    screenshot: "only-on-failure",
  },
});
