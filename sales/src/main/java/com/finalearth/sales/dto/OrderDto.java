package com.finalearth.sales.dto;

import com.finalearth.sales.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderDto(
    Long id,
    String orderNumber,
    Long customerId,
    OrderStatus status,
    BigDecimal totalAmount,
    List<OrderItemDto> items,
    Instant createdAt
) {}
