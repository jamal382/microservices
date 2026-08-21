package com.finalearth.payment.service;

/**
 * The result of an event-driven charge. Never an exception.
 *
 * <p>A decline has to be <em>published</em>, and publishing means writing a
 * {@code payment.failed} row to the outbox in the same transaction that recorded the
 * decline. Throwing would mark that transaction rollback-only and discard both — the
 * listener would retry, decline identically three times, and dead-letter a record that was
 * never malformed, while {@code sales} waited forever for an answer that could not be sent
 * and the reserved stock was never released.
 *
 * <p>Worth comparing against the HTTP path in {@link PaymentService#processPayment}, which
 * does throw {@code PaymentDeclinedException} after saving the failed row — and therefore
 * rolls that row back. Over HTTP the caller still learns the outcome from the 402, so the
 * lost row is only a missing audit record. On the event path the row and the message are
 * the only channel there is, so the same shape would lose the outcome entirely.
 */
public record PaymentOutcome(boolean completed, String paymentRef, String reason) {

    public static PaymentOutcome completed(String paymentRef) {
        return new PaymentOutcome(true, paymentRef, null);
    }

    public static PaymentOutcome declined(String reason) {
        return new PaymentOutcome(false, null, reason);
    }
}
