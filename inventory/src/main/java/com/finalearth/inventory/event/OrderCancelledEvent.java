package com.finalearth.inventory.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed from {@code sales}: the compensating command. Payment failed after stock was
 * already reserved, so the reservation has to be given back.
 *
 * <p>A saga cannot roll back — the reserving transaction committed long ago, in a different
 * service, and possibly minutes earlier. The only way to undo it is to run a new
 * transaction that does the opposite, which is what makes compensation a business
 * operation rather than a database feature: releasing stock is itself a recorded movement,
 * auditable like any other.
 */
public record OrderCancelledEvent(
        UUID eventId,
        Long orderId,
        String reason,
        Instant occurredAt) implements DomainEvent {}
