package com.finalearth.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductDto(
    Long id,
    String sku,
    String name,
    String description,
    BigDecimal price,
    Long categoryId,
    String categoryName,
    Boolean active,
    Instant createdAt
) {}
