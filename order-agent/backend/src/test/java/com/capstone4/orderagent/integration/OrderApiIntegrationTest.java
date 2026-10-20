package com.capstone4.orderagent.integration;

import java.util.*;
import java.util.concurrent.*;
import com.capstone4.orderagent.adapter.ShippingMockAdapter;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.entity.enums.OrderStatus;
import com.capstone4.orderagent.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5434/order_agent_test",
    "spring.datasource.username=order_test", "spring.datasource.password=order_test_local"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "ORDER_DEMO_INTEGRATION_TESTS", matches = "true")
class OrderApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService service;
    @Autowired OrderLifecycleService lifecycle;
    @MockitoSpyBean ShippingMockAdapter shipping;
    final JsonMapper json = JsonMapper.builder().build();
    static final String LANTERN = "CAMP-LANTERN-LED";

    @BeforeEach void reset() {
        jdbc.update("delete from orders");
        jdbc.update("update products set stock_quantity = case sku when 'CAMP-LANTERN-LED' then 10 when 'CAMP-CHAIR-FOLD' then 5 else 0 end");
    }
    String body(String customer, String sku, int qty) {
        return """
            {"customer":"%s","items":[{"sku":"%s","qty":%d}],
             "payment_method":"card","shipping":{"country":"KR","express":false}}
            """.formatted(customer, sku, qty);
    }
    String create(String key, String body, int status) throws Exception {
        return mvc.perform(post("/api/orders").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    int stock() { return jdbc.queryForObject("select stock_quantity from products where sku=?", Integer.class, LANTERN); }

    @Test void normalOrderPersistsAndReturnsActualSteps() throws Exception {
        String key = UUID.randomUUID().toString();
        String response = create(key, body("c_1001", LANTERN, 2), 201);
        var root = json.readTree(response);
        assertThat(root.path("status").asString()).isEqualTo("SHIPPED");
        assertThat(root.path("total_amount").asLong()).isEqualTo(60000);
        assertThat(root.path("steps").size()).isEqualTo(5);
        assertThat(stock()).isEqualTo(8);
        String id = root.path("order_id").asString();
        mvc.perform(get("/api/orders/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("payment.status").value("PAID"))
            .andExpect(jsonPath("notification.status").value("SENT"));
        mvc.perform(get("/api/orders/by-request/" + key)).andExpect(jsonPath("order_id").value(id));
    }
    @Test void stockRejectionNeverPaysShipsOrNotifies() throws Exception {
        var root = json.readTree(create(UUID.randomUUID().toString(), body("c_1001", "CAMP-MUG-STEEL", 1), 201));
        assertThat(root.path("status").asString()).isEqualTo("REJECTED");
        assertThat(root.path("payment").path("status").asString()).isEqualTo("NOT_ATTEMPTED");
        assertThat(root.path("shipment").isNull()).isTrue();
        assertThat(root.path("steps").get(2).path("status").asString()).isEqualTo("SKIPPED");
        assertThat(stock()).isEqualTo(10);
    }
    @Test void declineRestoresStockAndRecordsRelease() throws Exception {
        var root = json.readTree(create(UUID.randomUUID().toString(), body("c_declined", LANTERN, 2), 201));
        assertThat(root.path("status").asString()).isEqualTo("HOLD");
        assertThat(root.path("payment").path("status").asString()).isEqualTo("DECLINED");
        assertThat(root.path("steps").get(3).path("step").asString()).isEqualTo("RELEASE");
        assertThat(root.path("shipment").isNull()).isTrue();
        assertThat(stock()).isEqualTo(10);
    }
    @Test void multiItemReservationIsAllOrNothing() throws Exception {
        String request = body("c_1001", LANTERN, 2).replace("}],", "},{\"sku\":\"CAMP-MUG-STEEL\",\"qty\":1}],");
        var result = json.readTree(create(UUID.randomUUID().toString(), request, 201));
        assertThat(result.path("status").asString()).isEqualTo("REJECTED");
        assertThat(stock()).isEqualTo(10);
    }
    @Test void sameKeyIsIdempotentAndChangedBodyConflicts() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = json.readTree(create(key, body("c_1001", LANTERN, 2), 201));
        var again = json.readTree(create(key, body("c_1001", LANTERN, 2), 200));
        assertThat(first.path("order_id").asString()).isEqualTo(again.path("order_id").asString());
        create(key, body("c_1001", LANTERN, 3), 409);
        assertThat(stock()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(1);
    }
    @Test void duplicateSkusNormalizeBeforeHashAndStockCheck() throws Exception {
        String key = UUID.randomUUID().toString();
        String duplicate = body("c_1001", LANTERN, 1).replace("}],", "},{\"sku\":\"CAMP-LANTERN-LED\",\"qty\":1}],");
        create(key, duplicate, 201);
        create(key, body("c_1001", LANTERN, 2), 200);
        assertThat(stock()).isEqualTo(8);
        String excess = duplicate.replace("\"qty\":1", "\"qty\":60");
        create(UUID.randomUUID().toString(), excess, 400);
    }
    @Test void invalidInputAndMissingResourcesAreSafeErrors() throws Exception {
        create(UUID.randomUUID().toString(), body("c_1001", LANTERN, 0), 400);
        create("1-1-1-1-1", body("c_1001", LANTERN, 1), 400);
        create(UUID.randomUUID().toString(), body("c_1001", "NO-SUCH-SKU", 1), 404);
        create(UUID.randomUUID().toString(), body("c_1001", LANTERN, 1).replace("\"qty\":1", "\"qty\":1.5"), 400);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders/" + UUID.randomUUID())).andExpect(status().isNotFound())
            .andExpect(jsonPath("code").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/orders/by-request/invalid")).andExpect(status().isBadRequest());
    }
    @Test void failurePreservesCommittedPaymentAndTrace() throws Exception {
        doThrow(new IllegalStateException("injected failure: never return to client"))
            .when(shipping).reserve(any(UUID.class), anyString(), anyBoolean(), anyBoolean(), anyBoolean());
        String key = UUID.randomUUID().toString();
        var error = json.readTree(create(key, body("c_1001", LANTERN, 2), 500));
        assertThat(error.path("order_id").asString()).isNotBlank();
        mvc.perform(get("/api/orders/by-request/" + key)).andExpect(status().isOk())
            .andExpect(jsonPath("status").value("FAILED"))
            .andExpect(jsonPath("payment.status").value("PAID"))
            .andExpect(jsonPath("steps[3].step").value("SHIPPING"))
            .andExpect(jsonPath("steps[3].status").value("FAILED"));
        assertThat(stock()).isEqualTo(8);
    }
    CreateOrderRequest input() {
        return new CreateOrderRequest("c_1001", List.of(new CreateOrderRequest.Item(LANTERN, 1)),
            "card", new CreateOrderRequest.Shipping("KR", false));
    }
    @Test void concurrentOrdersNeverOversellLastStock() throws Exception {
        jdbc.update("update products set stock_quantity=1 where sku=?", LANTERN);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<OrderStatus> action = () -> { start.await(); return service.createOrder(UUID.randomUUID(), input()).order().status(); };
            var a = executor.submit(action); var b = executor.submit(action); start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(OrderStatus.SHIPPED, OrderStatus.REJECTED);
        }
        assertThat(stock()).isZero();
    }
    @Test void concurrentSameKeyProducesOneOrderAndOneCharge() throws Exception {
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<UUID> action = () -> { start.await(); return service.createOrder(key, input()).order().orderId(); };
            var a = executor.submit(action); var b = executor.submit(action); start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        }
        assertThat(stock()).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(1);
    }
    @Test void restartRecoveryDoesNotReplayIncompleteOrders() throws Exception {
        var completed = service.createOrder(UUID.randomUUID(), input()).order();
        UUID pendingId = UUID.randomUUID(), key = UUID.randomUUID();
        // Simulate a committed acceptance/claim whose separate processing transaction never committed.
        jdbc.update("""
            insert into orders (id, request_id, request_hash, customer, payment_method,
              shipping_country, shipping_express, total_amount, status, payment_status,
              notification_status, created_at, updated_at, version)
            select ?, ?, request_hash, customer, payment_method, shipping_country,
              shipping_express, total_amount, 'PROCESSING', 'NOT_ATTEMPTED',
              'NOT_ATTEMPTED', created_at, updated_at, 0 from orders where id=?
            """, pendingId, key, completed.orderId());
        jdbc.update("""
            insert into order_items (order_id, product_id, sku, product_name, unit_price, quantity, line_total)
            select ?, product_id, sku, product_name, unit_price, quantity, line_total
            from order_items where order_id=?
            """, pendingId, completed.orderId());
        lifecycle.recoverInterrupted();
        assertThat(service.getOrder(pendingId).resultCode()).isEqualTo("PROCESS_INTERRUPTED");
        assertThat(service.getOrder(completed.orderId()).status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(stock()).isEqualTo(9);
        create(key.toString(), body("c_1001", LANTERN, 1), 200);
        assertThat(stock()).isEqualTo(9);
    }
}
