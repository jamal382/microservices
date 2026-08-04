package com.finalearth.inventory.dto;

public record ReservationDetailDto(
    Long productId,
    Integer quantity,
    Integer quantityAvailable
) {}
