package com.capstone4.orderagent.controller;
import java.net.URI;
import java.util.UUID;
import com.capstone4.orderagent.dto.order.*;
import com.capstone4.orderagent.exception.*;
import com.capstone4.orderagent.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }
    @PostMapping
    ResponseEntity<OrderResponse> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateOrderRequest request) {
        var result = service.createOrder(uuid(key), request);
        return result.created() ? ResponseEntity.created(URI.create("/api/orders/" + result.order().orderId())).body(result.order())
            : ResponseEntity.ok(result.order());
    }
    @GetMapping("/{id}") OrderResponse get(@PathVariable String id) { return service.getOrder(uuid(id)); }
    @GetMapping("/by-request/{key}") OrderResponse byRequest(@PathVariable String key) { return service.getByRequestId(uuid(key)); }
    private UUID uuid(String raw) {
        try {
            var id = UUID.fromString(raw);
            if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
    }
}
