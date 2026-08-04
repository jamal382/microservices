package com.finalearth.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateOrderRequest(
    @NotNull(message = "Customer ID is required")
    Long customerId,

    @NotEmpty(message = "Items list cannot be empty")
    List<@Valid OrderItemRequest> items,

    @NotBlank(message = "Payment method is required")
    String paymentMethod
) {}
