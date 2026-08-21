package com.finalearth.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published after the charge is committed. {@code sales} confirms the order on it. */
public record PaymentCompletedEvent(
        UUID eventId,
        Long orderId,
        String paymentRef,
        BigDecimal amount,
        Instant occurredAt) implements DomainEvent {}
