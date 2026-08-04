package com.finalearth.inventory.dto;

import java.time.Instant;

public record StockItemDto(
    Long id,
    Long productId,
    Integer quantityAvailable,
    Integer quantityReserved,
    Instant updatedAt
) {}
