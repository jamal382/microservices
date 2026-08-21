package com.finalearth.sales.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The compensating command. Emitted when the saga has failed after stock was already
 * reserved, and that reservation now has to be undone.
 *
 * <p>This is the piece the README's "no compensating transactions" limitation was about.
 * A distributed saga cannot roll back — there is no transaction spanning {@code inventory}
 * and {@code payment} to roll back — so the only way to undo a committed step is to issue
 * a new, explicit action that reverses it. {@code inventory} releases the units when it
 * consumes this.
 *
 * <p>It is written to the outbox in the same transaction that records the payment failure,
 * which is what stops the system from ending up with a failed order whose stock was never
 * returned.
 */
public record OrderCancelledEvent(
        UUID eventId,
        Long orderId,
        String reason,
        Instant occurredAt) implements DomainEvent {}
