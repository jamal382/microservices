package com.finalearth.inventory.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once the compensation has actually run and the units are back on the shelf.
 *
 * <p>Compensation that nobody confirms is indistinguishable from compensation that never
 * happened. This event is what lets {@code sales} move an order to {@code CANCELLED} — a
 * status that means "failed and cleaned up", as opposed to {@code PAYMENT_FAILED}, which
 * only means the cleanup was requested.
 */
public record StockReleasedEvent(
        UUID eventId,
        Long orderId,
        Instant occurredAt) implements DomainEvent {}
