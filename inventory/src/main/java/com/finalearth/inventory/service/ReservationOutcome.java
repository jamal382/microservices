package com.finalearth.inventory.service;

/**
 * The result of an event-driven reservation attempt.
 *
 * <p><strong>Why this exists instead of just throwing {@code InsufficientStockException}
 * like the HTTP path does.</strong> On the event path the rejection is not an error — it is
 * the outcome, and it has to be <em>published</em>, which means writing a
 * {@code stock.rejected} row to the outbox. That write happens in the same transaction that
 * attempted the reservation, and an exception thrown inside a transaction marks it
 * rollback-only: the outbox row would be discarded along with the failed reservation, the
 * listener would retry, fail identically three times, and dead-letter a record that was
 * never actually broken. {@code sales} would wait forever for an answer that could not be
 * sent.
 *
 * <p>Returning the outcome instead of throwing keeps the transaction alive so the rejection
 * can be recorded and delivered. It is a small, easily-missed consequence of the rule that
 * a saga step must always report back — success and failure alike are events, and an event
 * you cannot commit is an event you cannot send.
 */
public record ReservationOutcome(boolean reserved, String reason) {

    public static ReservationOutcome success() {
        return new ReservationOutcome(true, null);
    }

    public static ReservationOutcome rejected(String reason) {
        return new ReservationOutcome(false, reason);
    }
}
