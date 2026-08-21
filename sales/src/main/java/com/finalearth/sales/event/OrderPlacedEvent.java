package com.finalearth.sales.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Emitted when an order has been priced and persisted as {@code PENDING}. The event that
 * starts the saga.
 *
 * <p>It carries the total and the payment method even though the immediate consumer
 * ({@code inventory}) needs neither. That is deliberate, and it is the central trade of
 * choreography: there is no orchestrator holding the order in memory, so each event has to
 * carry everything the rest of the chain will need. {@code inventory} copies these two
 * fields into {@code stock.reserved} so {@code payment} can charge without calling back to
 * {@code sales} — which would reintroduce exactly the synchronous coupling the saga exists
 * to remove.
 */
public record OrderPlacedEvent(
        UUID eventId,
        Long orderId,
        String orderNumber,
        Long customerId,
        BigDecimal totalAmount,
        String paymentMethod,
        List<Item> items,
        Instant occurredAt) implements DomainEvent {

    public record Item(Long productId, Integer quantity) {}
}
