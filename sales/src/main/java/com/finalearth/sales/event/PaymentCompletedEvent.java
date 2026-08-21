package com.finalearth.sales.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Consumed from {@code payment}: the charge succeeded. The saga's happy ending. */
public record PaymentCompletedEvent(
        UUID eventId,
        Long orderId,
        String paymentRef,
        BigDecimal amount,
        Instant occurredAt) implements DomainEvent {}
