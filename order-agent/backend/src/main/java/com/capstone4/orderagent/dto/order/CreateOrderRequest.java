package com.capstone4.orderagent.dto.order;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record CreateOrderRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "\\s*c_[A-Za-z0-9_]+\\s*") String customer,
    @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items,
    @NotNull @Pattern(regexp = "card|prepaid") String paymentMethod,
    @NotNull @Valid Shipping shipping,
    java.util.UUID snapshotId
) {
    public CreateOrderRequest(String customer, List<Item> items, String method, Shipping shipping) {
        this(customer, items, method, shipping, null);
    }
    public record Item(@NotBlank @Size(max = 64) String sku, @NotNull @Min(1) @Max(99) Integer qty) {}
    public record Shipping(@NotNull @Pattern(regexp = "KR|US|JP") String country, boolean express) {}
}
