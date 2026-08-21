package com.finalearth.inventory.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The three fields every event on the order saga carries.
 *
 * <p>{@code eventId} is the producer-assigned identity that makes at-least-once delivery
 * survivable — it is what the {@code processed_events} table de-duplicates on.
 * {@code orderId} is the partition key, which is what keeps one order's events in order
 * relative to each other while leaving different orders free to be processed in parallel.
 * {@code occurredAt} is when the fact became true at the producer, not when it was read.
 *
 * <p>Deliberately duplicated from {@code sales} rather than shared through a common jar.
 * The JSON on the topic is the contract; a shared class is a build-time dependency between
 * services that are meant to deploy independently.
 */
public interface DomainEvent {
    UUID eventId();
    Long orderId();
    Instant occurredAt();
}
