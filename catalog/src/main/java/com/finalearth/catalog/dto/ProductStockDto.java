package com.finalearth.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * A product with its stock levels, where the stock half may be missing.
 *
 * <p>{@code stockStatus} is what makes the degraded case legible to whoever is holding
 * the response. {@code AVAILABLE} and {@code OUT_OF_STOCK} are real answers from
 * inventory; {@code UNAVAILABLE} means the call did not succeed and the quantity fields
 * are absent rather than zero. A client that only checks {@code inStock} would read a
 * failed lookup as "out of stock" and be wrong about it, so the distinction is carried
 * explicitly instead of being inferred from a null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductStockDto(
    Long productId,
    String sku,
    String name,
    BigDecimal price,
    Boolean active,
    Integer quantityAvailable,
    Integer quantityReserved,
    Boolean inStock,
    StockStatus stockStatus,
    String stockNote
) {
    public enum StockStatus { AVAILABLE, OUT_OF_STOCK, UNAVAILABLE }
}
