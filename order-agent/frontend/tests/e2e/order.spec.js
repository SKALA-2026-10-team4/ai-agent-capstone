import { test, expect } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await expect(page.getByLabel("상품 1", { exact: true })).toBeEnabled();
});

test("normal order uses actual API results and persists five completed steps", async ({
  page,
}) => {
  await page.getByRole("button", { name: "정상 주문", exact: true }).click();
  await expect(page.getByText("₩60,000", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "주문 실행" }).click();
  await expect(page.locator(".pill.shipped")).toHaveText("배송 예약 완료");
  await expect(page.locator(".timeline li.success")).toHaveCount(5);
  await expect(page.getByText("승인 완료", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "test-results/order-success.png",
    fullPage: true,
  });
});

test("sold out order shows rejection and skipped downstream work", async ({
  page,
}) => {
  await page.getByRole("button", { name: "재고 부족", exact: true }).click();
  await page.getByRole("button", { name: "주문 실행" }).click();
  await expect(page.locator(".pill.rejected")).toBeVisible();
  await expect(page.locator(".timeline li.skipped")).toHaveCount(3);
  await expect(page.locator(".timeline li.rejected")).toHaveCount(1);
});

test("declined payment records release and skips shipment and notification", async ({
  page,
}) => {
  await page.getByRole("button", { name: "결제 거절", exact: true }).click();
  await page.getByRole("button", { name: "주문 실행" }).click();
  await expect(page.locator(".pill.hold")).toBeVisible();
  await expect(page.getByText("재고 예약 해제", { exact: true })).toBeVisible();
  await expect(page.locator(".timeline li.skipped")).toHaveCount(2);
});

test("lost response recovers the existing order without a second POST", async ({
  page,
}) => {
  let calls = 0;
  await page.route("**/api/orders", async (route) => {
    calls++;
    await route.fetch(); // Server commits the order, browser loses only the response.
    await route.abort("failed");
  });
  await page.getByRole("button", { name: "주문 실행" }).click();
  await expect(
    page.getByRole("button", { name: "상태 다시 확인" }),
  ).toBeVisible();
  await expect(page.getByLabel("수량")).toBeDisabled();
  await page.getByRole("button", { name: "상태 다시 확인" }).click();
  await expect(page.locator(".pill.shipped")).toBeVisible();
  expect(calls).toBe(1);
});

test("request never received by server can be resent with identical key and body", async ({
  page,
}) => {
  let first;
  await page.route("**/api/orders", async (route) => {
    if (!first) {
      first = {
        key: route.request().headers()["idempotency-key"],
        body: route.request().postData(),
      };
      await route.abort("failed");
    } else {
      expect(route.request().headers()["idempotency-key"]).toBe(first.key);
      expect(route.request().postData()).toBe(first.body);
      await route.continue();
    }
  });
  await page.getByRole("button", { name: "주문 실행" }).click();
  await page.getByRole("button", { name: "상태 다시 확인" }).click();
  await expect(
    page.getByRole("button", { name: "같은 요청으로 재전송" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "같은 요청으로 재전송" }).click();
  await expect(page.locator(".pill.shipped")).toBeVisible();
});

test("invalid quantity is focused without sending an order", async ({
  page,
}) => {
  let calls = 0;
  page.on("request", (r) => {
    if (r.method() === "POST") calls++;
  });
  await page.getByLabel("수량").fill("0");
  await page.getByRole("button", { name: "주문 실행" }).click();
  await expect(page.getByLabel("수량")).toBeFocused();
  await expect(
    page.getByText("수량은 1~99 정수로 입력해주세요."),
  ).toBeVisible();
  expect(calls).toBe(0);
});

test("mobile layout fits viewport and exposes order inputs", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    page.getByRole("heading", { name: "캠핑을 준비하세요" }),
  ).toBeVisible();
  const fits = await page.evaluate(
    () => document.documentElement.scrollWidth <= window.innerWidth,
  );
  expect(fits).toBe(true);
  await page.screenshot({
    path: "test-results/order-mobile.png",
    fullPage: true,
  });
});
