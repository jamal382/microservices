package com.finalearth.sales.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed from {@code inventory}: the compensation actually ran and the units are back.
 *
 * <p>This is what lets {@code CANCELLED} mean something. Without it the order would sit at
 * {@code PAYMENT_FAILED} and nobody could tell, from the order alone, whether its stock had
 * been returned. The saga is only finished when the last compensating step reports back.
 */
public record StockReleasedEvent(
        UUID eventId,
        Long orderId,
        Instant occurredAt) implements DomainEvent {}
