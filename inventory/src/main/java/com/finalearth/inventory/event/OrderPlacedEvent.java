package com.finalearth.inventory.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Consumed from {@code sales}: an order has been accepted and needs stock held for it.
 *
 * <p>{@code totalAmount} and {@code paymentMethod} are not used to reserve anything. They
 * are carried through onto {@code stock.reserved} so {@code payment} can charge without
 * calling back to {@code sales} for them. Passing a downstream consumer's data through an
 * intermediate service feels wrong the first time — it is the defining shape of
 * choreography, where no orchestrator holds the order in memory and each event must carry
 * what the rest of the chain still needs.
 *
 * <p>This record only names the fields this service reads. {@code sales} also publishes
 * {@code orderNumber} and {@code customerId}, and both are ignored here without any
 * configuration: unknown properties are tolerated so the producer can add fields without
 * every consumer needing a matching release. That tolerance is what makes independent
 * deployment possible, and it is why the event carries a schema rather than a Java type.
 */
public record OrderPlacedEvent(
        UUID eventId,
        Long orderId,
        BigDecimal totalAmount,
        String paymentMethod,
        List<Item> items,
        Instant occurredAt) implements DomainEvent {

    public record Item(Long productId, Integer quantity) {}
}
