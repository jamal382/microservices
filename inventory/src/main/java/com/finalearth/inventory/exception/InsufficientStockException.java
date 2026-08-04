package com.finalearth.inventory.exception;

public class InsufficientStockException extends RuntimeException {
    private final Long productId;
    private final int available;
    private final int requested;

    public InsufficientStockException(Long productId, int available, int requested) {
        super(String.format("Product %d has %d unit available, %d requested", productId, available, requested));
        this.productId = productId;
        this.available = available;
        this.requested = requested;
    }

    public Long getProductId() { return productId; }
    public int getAvailable() { return available; }
    public int getRequested() { return requested; }
}
