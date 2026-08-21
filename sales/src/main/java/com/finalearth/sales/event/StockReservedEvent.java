package com.finalearth.sales.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumed from {@code inventory}. Read by two independent consumer groups: {@code sales}
 * advances the order to {@code STOCK_RESERVED}, and {@code payment} takes it as the signal
 * to charge. Neither knows about the other.
 */
public record StockReservedEvent(
        UUID eventId,
        Long orderId,
        BigDecimal totalAmount,
        String paymentMethod,
        Instant occurredAt) implements DomainEvent {}
