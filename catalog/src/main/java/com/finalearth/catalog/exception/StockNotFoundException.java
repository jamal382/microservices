package com.finalearth.catalog.exception;

/**
 * Inventory answered, correctly, that it holds no stock record for a product.
 *
 * <p>This exists to keep a <em>business</em> outcome from being mistaken for a
 * <em>failure</em>. A 404 here means inventory is healthy and did its job — the product
 * genuinely has no stock row. If that counted toward the circuit breaker, then browsing
 * a handful of unstocked products would trip the breaker and cut off the perfectly
 * healthy replica behind it; retrying would be pure waste, since the answer will be 404
 * again however many times you ask.
 *
 * <p>Resilience4j sorts every call into one of three buckets, and this type is listed in
 * {@code ignore-exceptions} so it lands in the third:
 *
 * <ul>
 *   <li><b>failure</b> — counted against the breaker ({@code record-exceptions}: a 500 or
 *       a transport error)</li>
 *   <li><b>success</b> — the default for anything unlisted, holding the breaker closed</li>
 *   <li><b>ignored</b> — not counted either way, as though the call never happened</li>
 * </ul>
 *
 * <p>Ignoring is deliberate rather than letting it fall through to "success". A 404 is
 * not evidence of a problem, but it is not much evidence of health either, and a burst of
 * them should not pad the success rate and hide real failures occurring alongside.
 */
public class StockNotFoundException extends RuntimeException {

    private final Long productId;

    public StockNotFoundException(Long productId) {
        super("No stock record for product " + productId);
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
