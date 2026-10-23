<script setup>
// 주문 입력 패널이다. 상품 행(상품, 수량, 삭제), 데모 고객, 결제 방식, 배송 국가, 빠른 배송, 예상 금액, 필드 오류,
// 재고 경고, 잠금 안내를 보여준다.
// 폼 상태는 부모가 가진다. 이 컴포넌트는 props.form 을 직접 고치지 않고, 바뀐 새 폼 객체를 update:form 으로 올린다.
// order-agent/frontend/src/App.vue 가 form 을 v-model:form 으로 묶고 products, productStatus, productError, locked,
// fieldErrors 를 넘기며, submit 과 reload-products 이벤트를 order-agent/frontend/src/composables/useOrder.js 의
// submit 과 loadProducts 에 연결한다.
// 새 폼은 기존 폼을 펼친 뒤 바뀐 값만 덮어써 키 순서를 지킨다. useOrder.js 의 previousOrder 가 폼을 JSON 문자열로
// 비교하고 서버는 모르는 키를 거절하므로, 폼에 화면 전용 키를 넣지 않는다.
// 선택지와 수량 범위는 order-agent/frontend/src/utils/validation.js 의 상수를, 금액 문구는
// order-agent/frontend/src/utils/format.js 의 money 를 쓴다.
// 입력은 locked 이거나 productStatus 가 ready 가 아니면 잠긴다. 오류가 있는 입력의 aria-invalid 에는 boolean 을 넘겨야
// App.vue 의 첫 오류 입력 focus 가 동작한다.
import { computed } from "vue";
import {
  estimate,
  defaultForm,
  itemFieldKey,
  DECLINED_CUSTOMER,
  DEMO_CUSTOMERS,
  PAYMENT_METHODS,
  SHIPPING_COUNTRIES,
  QTY_MIN,
  QTY_MAX,
} from "../utils/validation.js";
import { money } from "../utils/format.js";
const props = defineProps({
  form: { type: Object, default: defaultForm },
  products: { type: Array, default: () => [] },
  productStatus: String,
  productError: String,
  locked: Boolean,
  fieldErrors: { type: Object, default: () => ({}) },
});
// update:form 은 바뀐 새 폼을 싣는다. submit 은 주문 실행을, reload-products 는 "상품 다시 불러오기" 버튼을 눌렀음을 알린다.
const emit = defineEmits(["update:form", "submit", "reload-products"]);
// 화면에 보일 예상 주문 금액이다. 최종 금액은 서버가 확정한다.
const total = computed(() => estimate(props.form, props.products));
/** 바뀐 값을 기존 폼 위에 덮어쓴 새 폼을 부모에게 올린다. 기존 키 순서를 지키려고 펼침 순서를 바꾸지 않는다. */
function update(change) {
  emit("update:form", { ...props.form, ...change });
}
/** index 번째 상품 행의 field 값만 바꾼 새 items 배열로 폼을 갱신한다. */
function itemChange(index, field, value) {
  update({
    items: props.form.items.map((item, i) =>
      i === index ? { ...item, [field]: value } : item,
    ),
  });
}
/** 배송 정보(country, express) 중 바뀐 값만 덮어쓴다. */
function updateShipping(change) {
  update({ shipping: { ...props.form.shipping, ...change } });
}
/** 아직 어느 행에서도 고르지 않은 첫 상품을 수량 1 로 새 행에 더한다. 남은 상품이 없으면 아무것도 하지 않는다. */
function addItem() {
  const available = props.products.find(
    (p) => !props.form.items.some((i) => i.sku === p.sku),
  );
  if (available)
    update({ items: [...props.form.items, { sku: available.sku, qty: 1 }] });
}
/** index 번째 상품 행을 지운다. 템플릿은 행이 하나뿐이면 삭제 버튼을 끈다. */
function removeItem(index) {
  update({ items: props.form.items.filter((_, i) => i !== index) });
}
/** sku 로 상품을 찾는다. 같은 sku 가 여러 개면 첫 항목을 쓴다. */
const product = (sku) => props.products.find((p) => p.sku === sku);
/** 다른 행에서 이미 고른 상품인지 확인한다. 참이면 이 행의 선택지에서 그 상품을 끈다. */
const isTakenByOtherRow = (sku, index) =>
  props.form.items.some((i, n) => n !== index && i.sku === sku);
/** 상품 단가 문구를 만든다. 목록에 없는 sku 는 0 원으로 보인다. */
const unitPriceText = (sku) => money(product(sku)?.unit_price || 0);
/** 상품 재고 문구를 만든다. 재고가 0 이면 품절이지만 재고 부족 거절을 시연할 수 있다고 알리고, 모르는 상품은 "재고 —개" 로 보인다. */
const stockText = (sku) => {
  const stock = product(sku)?.stock_quantity;
  return stock === 0 ? "품절 · 시연 가능" : `재고 ${stock ?? "—"}개`;
};
/** 주문 수량이 현재 재고보다 많은지 확인한다. 참이어도 경고만 보이고 제출은 막지 않는다. 재고 부족 거절도 시연 대상이기 때문이다. */
const exceedsStock = (item) =>
  item.qty > (product(item.sku)?.stock_quantity ?? Infinity);
/**
 * 수량 입력값을 숫자로 바꾼다. 빈 입력은 빈 문자열로 둔다.
 * 서버는 소수점이 있는 수량을 거절하므로, 정수인지는 order-agent/frontend/src/utils/validation.js 의 validate 가
 * 제출 전에 검사한다.
 */
const parseQty = (raw) => (raw === "" ? "" : Number(raw));
/** 상품 행 필드의 오류 문구를 찾는다. 키는 서버 field_errors 와 같은 items[N].field 형식이다. */
const fieldError = (index, field) =>
  props.fieldErrors[itemFieldKey(index, field)];
</script>
<template>
  <section class="panel" aria-labelledby="form-title">
    <div class="section-heading">
      <div>
        <span class="eyebrow">01 / ORDER</span>
        <h2 id="form-title">캠핑을 준비하세요</h2>
      </div>
      <span class="pill neutral">Mock 주문</span>
    </div>
    <div v-if="productStatus === 'loading'" class="notice" role="status">
      상품과 재고를 불러오고 있습니다.
    </div>
    <div v-if="productStatus === 'error'" class="notice error" role="alert">
      {{ productError }}
      <button
        type="button"
        class="text-button"
        @click="emit('reload-products')"
      >
        상품 다시 불러오기
      </button>
    </div>
    <form :aria-busy="locked" novalidate @submit.prevent="emit('submit')">
      <fieldset
        :disabled="locked || productStatus !== 'ready'"
        class="plain-fieldset"
      >
        <legend class="sr-only">주문 입력</legend>
        <div class="row-label">
          <h3>담을 상품</h3>
          <span class="muted">재고는 주문 시 다시 확인해요</span>
        </div>
        <div
          v-for="(item, index) in form.items"
          :key="index"
          class="product-row"
        >
          <div class="product-selector">
            <label :for="`sku-${index}`">상품 {{ index + 1 }}</label>
            <select
              :id="`sku-${index}`"
              :value="item.sku"
              :aria-invalid="!!fieldError(index, 'sku')"
              @change="itemChange(index, 'sku', $event.target.value)"
            >
              <option
                v-for="p in products"
                :key="p.sku"
                :value="p.sku"
                :disabled="isTakenByOtherRow(p.sku, index)"
              >
                {{ p.name }}
              </option>
            </select>
            <span class="product-meta"
              >{{ unitPriceText(item.sku) }}
              <span aria-hidden="true">·</span>
              {{ stockText(item.sku) }}</span
            >
            <small v-if="fieldError(index, 'sku')" class="field-error">{{
              fieldError(index, "sku")
            }}</small>
          </div>
          <div class="quantity-selector">
            <label :for="`qty-${index}`">수량</label
            ><input
              :id="`qty-${index}`"
              :value="item.qty"
              type="number"
              :min="QTY_MIN"
              :max="QTY_MAX"
              step="1"
              :aria-invalid="!!fieldError(index, 'qty')"
              @input="itemChange(index, 'qty', parseQty($event.target.value))"
            />
          </div>
          <button
            type="button"
            class="remove-button"
            :aria-label="`상품 ${index + 1} 삭제`"
            :disabled="form.items.length === 1"
            @click="removeItem(index)"
          >
            ×
          </button>
          <small
            v-if="fieldError(index, 'qty')"
            class="field-error full-row"
            >{{ fieldError(index, "qty") }}</small
          >
          <small
            v-if="exceedsStock(item)"
            class="stock-warning full-row"
            >현재 재고보다 많습니다. 주문 시 재고 부족으로 거절될 수
            있어요.</small
          >
        </div>
        <p v-if="fieldErrors.items" class="field-error">
          {{ fieldErrors.items }}
        </p>
        <button
          type="button"
          class="text-button add-button"
          :disabled="form.items.length >= products.length"
          @click="addItem"
        >
          ＋ 상품 추가
        </button>
        <div class="divider"></div>
        <div class="form-grid">
          <div>
            <label for="customer">데모 고객</label
            ><select
              id="customer"
              :value="form.customer"
              :aria-invalid="!!fieldErrors.customer"
              @change="update({ customer: $event.target.value })"
            >
              <option
                v-for="option in DEMO_CUSTOMERS"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </option></select
            ><small v-if="fieldErrors.customer" class="field-error">{{
              fieldErrors.customer
            }}</small>
          </div>
          <div>
            <label for="payment">결제 방식</label
            ><select
              id="payment"
              :value="form.payment_method"
              :aria-invalid="!!fieldErrors.payment_method"
              @change="update({ payment_method: $event.target.value })"
            >
              <option
                v-for="option in PAYMENT_METHODS"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </option></select
            ><small v-if="fieldErrors.payment_method" class="field-error">{{
              fieldErrors.payment_method
            }}</small>
          </div>
          <div>
            <label for="country">배송 국가</label
            ><select
              id="country"
              :value="form.shipping.country"
              :aria-invalid="!!fieldErrors['shipping.country']"
              @change="updateShipping({ country: $event.target.value })"
            >
              <option
                v-for="option in SHIPPING_COUNTRIES"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </option>
            </select>
          </div>
          <label class="checkbox-field"
            ><input
              type="checkbox"
              :checked="form.shipping.express"
              @change="updateShipping({ express: $event.target.checked })"
            /><span
              >빠른 배송<small>일반 배송보다 1일 빠르게</small></span
            ></label
          >
        </div>
        <p v-if="form.customer === DECLINED_CUSTOMER" class="notice subtle">
          시연 규칙: 이 고객의 카드 결제는 거절되고 선입금은 승인됩니다.
        </p>
        <div class="total-line">
          <span
            >예상 주문 금액<small
              >배송비 없음 · 최종 금액은 서버에서 확정</small
            ></span
          ><strong>{{ money(total) }}</strong>
        </div>
        <button class="button primary submit-button" type="submit">
          {{ locked ? "주문 처리 중…" : "주문 실행" }}
          <span aria-hidden="true">↗</span>
        </button>
      </fieldset>
      <p v-if="locked" class="notice" role="status">
        요청한 주문의 결과를 확인하고 있습니다. 중복 접수를 막기 위해 입력을
        잠갔어요.
      </p>
    </form>
  </section>
</template>
