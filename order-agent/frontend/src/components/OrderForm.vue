<script setup>
import { computed } from "vue";
import { estimate, defaultForm } from "../utils/validation.js";
import { money } from "../utils/format.js";
const props = defineProps({
  form: { type: Object, default: defaultForm },
  products: { type: Array, default: () => [] },
  productStatus: String,
  productError: String,
  locked: Boolean,
  fieldErrors: { type: Object, default: () => ({}) },
});
const emit = defineEmits(["update:form", "submit", "reload-products"]);
const total = computed(() => estimate(props.form, props.products));
function update(change) {
  emit("update:form", { ...props.form, ...change });
}
function itemChange(index, field, value) {
  update({
    items: props.form.items.map((item, i) =>
      i === index ? { ...item, [field]: value } : item,
    ),
  });
}
function add() {
  const available = props.products.find(
    (p) => !props.form.items.some((i) => i.sku === p.sku),
  );
  if (available)
    update({ items: [...props.form.items, { sku: available.sku, qty: 1 }] });
}
const product = (sku) => props.products.find((p) => p.sku === sku);
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
              :aria-invalid="!!fieldErrors[`items[${index}].sku`]"
              @change="itemChange(index, 'sku', $event.target.value)"
            >
              <option
                v-for="p in products"
                :key="p.sku"
                :value="p.sku"
                :disabled="
                  form.items.some((i, n) => n !== index && i.sku === p.sku)
                "
              >
                {{ p.name }}
              </option>
            </select>
            <span class="product-meta"
              >{{ money(product(item.sku)?.unit_price || 0) }}
              <span aria-hidden="true">·</span>
              {{
                product(item.sku)?.stock_quantity === 0
                  ? "품절 · 시연 가능"
                  : `재고 ${product(item.sku)?.stock_quantity ?? "—"}개`
              }}</span
            >
            <small
              v-if="fieldErrors[`items[${index}].sku`]"
              class="field-error"
              >{{ fieldErrors[`items[${index}].sku`] }}</small
            >
          </div>
          <div class="quantity-selector">
            <label :for="`qty-${index}`">수량</label
            ><input
              :id="`qty-${index}`"
              :value="item.qty"
              type="number"
              min="1"
              max="99"
              step="1"
              :aria-invalid="!!fieldErrors[`items[${index}].qty`]"
              @input="
                itemChange(
                  index,
                  'qty',
                  $event.target.value === '' ? '' : Number($event.target.value),
                )
              "
            />
          </div>
          <button
            type="button"
            class="remove-button"
            :aria-label="`상품 ${index + 1} 삭제`"
            :disabled="form.items.length === 1"
            @click="update({ items: form.items.filter((_, i) => i !== index) })"
          >
            ×
          </button>
          <small
            v-if="fieldErrors[`items[${index}].qty`]"
            class="field-error full-row"
            >{{ fieldErrors[`items[${index}].qty`] }}</small
          >
          <small
            v-if="item.qty > (product(item.sku)?.stock_quantity ?? Infinity)"
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
          @click="add"
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
              <option value="c_1001">일반 고객 · c_1001</option>
              <option value="c_declined">
                결제 거절 고객 · c_declined
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
              <option value="card">카드 · Mock</option>
              <option value="prepaid">선입금 · Mock</option></select
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
              @change="
                update({
                  shipping: { ...form.shipping, country: $event.target.value },
                })
              "
            >
              <option value="KR">대한민국</option>
              <option value="US">미국</option>
              <option value="JP">일본</option>
            </select>
          </div>
          <label class="checkbox-field"
            ><input
              type="checkbox"
              :checked="form.shipping.express"
              @change="
                update({
                  shipping: {
                    ...form.shipping,
                    express: $event.target.checked,
                  },
                })
              "
            /><span
              >빠른 배송<small>일반 배송보다 1일 빠르게</small></span
            ></label
          >
        </div>
        <p v-if="form.customer === 'c_declined'" class="notice subtle">
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
