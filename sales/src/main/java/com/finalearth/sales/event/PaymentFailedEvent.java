package com.finalearth.sales.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed from {@code payment}: the charge was declined.
 *
 * <p>Note what this event is <em>not</em>: it is not "we could not reach the payment
 * service". It is an authoritative statement from a healthy provider that no money moved.
 * That distinction used to be the hard part — the synchronous client could not tell a
 * decline from a lost response, and the README's second known limitation was the result.
 * Here it disappears, because {@code payment} publishes its outcome only after committing
 * it, and the broker keeps redelivering until {@code sales} acknowledges having seen it.
 */
public record PaymentFailedEvent(
        UUID eventId,
        Long orderId,
        String reason,
        Instant occurredAt) implements DomainEvent {}
