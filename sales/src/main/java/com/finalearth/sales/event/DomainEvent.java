package com.finalearth.sales.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The three fields every event on the order saga carries, and the reason each is there.
 *
 * <p><strong>{@code eventId}</strong> is what makes at-least-once delivery survivable.
 * Kafka guarantees a message is delivered <em>at least</em> once, not exactly once: a
 * consumer that does its work and then dies before committing its offset will be handed
 * the same record again. The id is generated once, by the producer, at the moment the
 * event is written to the outbox — never by the consumer, and never re-generated on
 * redelivery — so a duplicate delivery is recognisably the same event and can be dropped
 * against the {@code processed_events} table.
 *
 * <p><strong>{@code orderId}</strong> is the partition key. Kafka orders records within a
 * partition, not across a topic, so events only stay in order relative to each other if
 * they hash to the same partition. Keying every event of the saga by the order it belongs
 * to is what guarantees {@code stock.reserved} for order 42 cannot overtake a later
 * {@code order.cancelled} for order 42 — while leaving orders 41 and 43 free to be
 * processed on other partitions, in parallel.
 *
 * <p><strong>{@code occurredAt}</strong> is when the fact became true in the producing
 * service, which is not when the consumer sees it. The gap between the two is the
 * queue depth, and it is the number that actually matters when the system is behind.
 */
public interface DomainEvent {
    UUID eventId();
    Long orderId();
    Instant occurredAt();
}
