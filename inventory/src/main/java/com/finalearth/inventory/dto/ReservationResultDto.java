package com.finalearth.inventory.dto;

import java.util.List;

public record ReservationResultDto(
    Long orderId,
    String status,
    List<ReservationDetailDto> reservations
) {}
