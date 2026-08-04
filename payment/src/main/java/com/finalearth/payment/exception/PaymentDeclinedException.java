package com.finalearth.payment.exception;

public class PaymentDeclinedException extends RuntimeException {
    private final Long orderId;
    private final String failureReason;

    public PaymentDeclinedException(Long orderId, String failureReason) {
        super(failureReason);
        this.orderId = orderId;
        this.failureReason = failureReason;
    }

    public Long getOrderId() { return orderId; }
    public String getFailureReason() { return failureReason; }
}
