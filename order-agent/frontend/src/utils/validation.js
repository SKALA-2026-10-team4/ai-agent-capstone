export const defaultForm = () => ({
  customer: "c_1001",
  items: [{ sku: "CAMP-LANTERN-LED", qty: 1 }],
  payment_method: "card",
  shipping: { country: "KR", express: false },
});
export function validate(form) {
  const errors = {};
  if (
    !/^c_[A-Za-z0-9_]+$/.test(form.customer.trim()) ||
    form.customer.trim().length > 64
  )
    errors.customer = "데모 고객을 선택해주세요.";
  if (!form.items.length || form.items.length > 20)
    errors.items = "상품을 1개 이상 선택해주세요.";
  const seen = new Set();
  form.items.forEach((item, index) => {
    if (!item.sku || seen.has(item.sku))
      errors[`items[${index}].sku`] = "서로 다른 상품을 선택해주세요.";
    seen.add(item.sku);
    if (!Number.isInteger(item.qty) || item.qty < 1 || item.qty > 99)
      errors[`items[${index}].qty`] = "수량은 1~99 정수로 입력해주세요.";
  });
  if (!["card", "prepaid"].includes(form.payment_method))
    errors.payment_method = "결제 방식을 선택해주세요.";
  if (!["KR", "US", "JP"].includes(form.shipping.country))
    errors["shipping.country"] = "배송 국가를 선택해주세요.";
  return errors;
}
export function estimate(form, products) {
  return form.items.reduce(
    (total, item) =>
      total +
      (products.find((p) => p.sku === item.sku)?.unit_price || 0) *
        (Number.isInteger(item.qty) && item.qty > 0 ? item.qty : 0),
    0,
  );
}
