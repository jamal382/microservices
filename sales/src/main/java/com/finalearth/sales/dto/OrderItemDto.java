package com.finalearth.sales.dto;

import java.math.BigDecimal;

public record OrderItemDto(
    Long productId,
    String productName,
    Integer quantity,
    BigDecimal unitPrice
) {}
