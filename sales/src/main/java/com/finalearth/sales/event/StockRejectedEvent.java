package com.finalearth.sales.event;

import java.time.Instant;
import java.util.UUID;

/** Consumed from {@code inventory}: not enough stock. Terminal — nothing to compensate. */
public record StockRejectedEvent(
        UUID eventId,
        Long orderId,
        String reason,
        Instant occurredAt) implements DomainEvent {}
