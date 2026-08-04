package com.finalearth.catalog.dto;

import java.math.BigDecimal;

public record ProductStockDto(
    Long productId,
    String sku,
    String name,
    BigDecimal price,
    Boolean active,
    Integer quantityAvailable,
    Integer quantityReserved,
    Boolean inStock
) {}
