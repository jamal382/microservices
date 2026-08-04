package com.finalearth.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ReserveStockRequest(
    @NotNull(message = "Order ID is required")
    Long orderId,

    @NotEmpty(message = "Items list cannot be empty")
    List<@Valid ReservationItemRequest> items
) {}
