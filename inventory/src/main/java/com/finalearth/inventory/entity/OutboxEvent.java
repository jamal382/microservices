package com.finalearth.inventory.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per event this service intends to publish.
 *
 * <p><strong>The problem it exists to solve.</strong> Reserving stock means doing two
 * things: decrementing {@code stock_items} in Postgres and publishing {@code stock.reserved}
 * to Kafka. Those are two different systems, and there is no transaction spanning both.
 * Write the database first and the process can die before the publish, leaving units held
 * for an order that {@code payment} will never be told to charge -- stock nobody can sell
 * and no order will ever release. Publish first and the process can die before the commit,
 * so {@code payment} charges a customer for stock that was never actually set aside. This
 * is the dual-write problem, and no ordering of the two calls fixes it.
 *
 * <p><strong>The fix is to stop doing two writes.</strong> The event is inserted into
 * <em>this table</em>, in the same local transaction as the order itself. One database,
 * one commit, all-or-nothing: if the order is durable then so is its event, and if the
 * transaction rolls back the event was never there. A separate relay then reads committed
 * rows and publishes them, which is a problem that can safely be retried because the
 * evidence of intent is already persisted.
 *
 * <p><strong>The payload is {@code TEXT}, not {@code jsonb}, on purpose.</strong> The relay
 * must publish exactly the bytes that were committed. Round-tripping through {@code jsonb}
 * would re-serialise the document — Postgres does not preserve key order or insignificant
 * whitespace in {@code jsonb} — so what arrived on the topic would no longer be
 * byte-identical to what the transaction agreed to send. Storing it opaquely keeps the
 * outbox a record of a decision rather than a document to be reinterpreted.
 */
@Entity
@Table(name = "outbox", schema = "inventory")
public class OutboxEvent {

    /**
     * The event's own id, assigned by the producer — not a surrogate key. The same UUID
     * travels in the payload, onto the topic, and into the consumer's
     * {@code processed_events} table, which is what makes de-duplication possible at the
     * far end.
     */
    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /** Becomes the Kafka message key, and therefore decides the partition. */
    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * Null until the broker has acknowledged the record. Deliberately not deleted on
     * publish: the table doubles as an audit log of everything this service has ever
     * emitted, which is the first thing worth having when a consumer claims it never
     * received something.
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    public OutboxEvent() {}

    public OutboxEvent(UUID id, String aggregateType, String aggregateId, String topic, String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}
