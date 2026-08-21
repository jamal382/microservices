package com.finalearth.payment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The three fields every event on the order saga carries: a producer-assigned
 * {@code eventId} for de-duplication, an {@code orderId} that doubles as the partition key,
 * and the {@code occurredAt} of the fact at its source.
 *
 * <p>Duplicated from the producers rather than shared through a common jar — the JSON is
 * the contract, and a shared class would make independently deployable services share a
 * build.
 */
public interface DomainEvent {
    UUID eventId();
    Long orderId();
    Instant occurredAt();
}
