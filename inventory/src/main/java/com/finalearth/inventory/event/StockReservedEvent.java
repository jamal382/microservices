package com.finalearth.inventory.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when units have actually been decremented and the movement recorded.
 *
 * <p>Read by two independent consumer groups: {@code sales} advances the order's status,
 * {@code payment} treats it as authorisation to charge. Neither knows the other exists, and
 * adding a third reader later requires no change here — that is the property a topic has
 * and a direct HTTP call does not.
 */
public record StockReservedEvent(
        UUID eventId,
        Long orderId,
        BigDecimal totalAmount,
        String paymentMethod,
        Instant occurredAt) implements DomainEvent {}
