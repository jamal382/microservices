package com.finalearth.inventory.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * The inbox: one row per event this service has already acted on.
 *
 * <p><strong>Why it is needed at all.</strong> Kafka delivers at least once. A consumer
 * that commits its database transaction and then dies before committing its offset will be
 * handed the identical record on restart -- as will every consumer in a group after a
 * rebalance, for any record that was in flight. This is the service where that hurts most:
 * a redelivered {@code order.placed} would reserve the same units a second time, and a
 * redelivered {@code order.cancelled} would release units already released, inventing
 * stock that does not exist.
 *
 * <p><strong>Why the primary key does the work.</strong> The check and the insert both
 * happen inside the same transaction as the business change, so all three commit together
 * or none of them do. Two concurrent deliveries of the same event both see an empty inbox
 * and both try to insert; the primary key lets exactly one through and the loser rolls
 * back its business change with it. The correctness does not depend on the {@code exists}
 * check winning a race — that check is only there to make the common case cheap.
 */
@Entity
@Table(name = "processed_events", schema = "inventory")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt = Instant.now();

    public ProcessedEvent() {}

    public ProcessedEvent(UUID eventId, String topic) {
        this.eventId = eventId;
        this.topic = topic;
    }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
