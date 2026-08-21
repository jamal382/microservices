package com.finalearth.inventory.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the order cannot be filled.
 *
 * <p>This is a business answer from a healthy service, and the saga's cheapest ending:
 * nothing was changed, so nothing needs compensating and {@code payment} is never involved.
 * The same distinction Lab 04 makes about circuit breakers applies here — "not enough
 * stock" is a successful conversation, not a failure of the system, and the fact that it
 * travels on its own topic rather than as an exception makes that impossible to confuse.
 */
public record StockRejectedEvent(
        UUID eventId,
        Long orderId,
        String reason,
        Instant occurredAt) implements DomainEvent {}
