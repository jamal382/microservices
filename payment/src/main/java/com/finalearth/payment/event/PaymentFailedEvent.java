package com.finalearth.payment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the provider declines. An authoritative "no money moved".
 *
 * <p>This event is the answer to the second known limitation this project carried for a
 * long time. The synchronous client could not tell a decline from a lost response, so an
 * unreachable {@code payment} left the order stranded at {@code STOCK_RESERVED} — because
 * marking it {@code PAYMENT_FAILED} would have asserted something nobody actually knew.
 * Publishing the outcome removes the guesswork: the row and the event commit together, so
 * this event existing <em>is</em> the proof that the decline is real and recorded.
 * "Unreachable" is now just an event that has not arrived yet, and the broker will keep it
 * until it does.
 */
public record PaymentFailedEvent(
        UUID eventId,
        Long orderId,
        String reason,
        Instant occurredAt) implements DomainEvent {}
