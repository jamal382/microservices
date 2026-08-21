package com.finalearth.sales.messaging;

import com.finalearth.sales.entity.OutboxEvent;
import com.finalearth.sales.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains committed outbox rows onto Kafka. The only component in this service that holds a
 * {@code KafkaTemplate}.
 *
 * <p><strong>Why polling, and not something cleverer.</strong> The production-grade version
 * of this is log tailing — Debezium reading Postgres' write-ahead log and publishing the
 * inserts as they land, with no polling and no added latency. It is also a second piece of
 * infrastructure to run, configure and reason about. A poll every 500ms costs one indexed
 * query against a normally-empty partial index and makes the mechanism completely visible,
 * which is the better trade for a project meant to be read. The pattern is identical; only
 * the trigger differs.
 *
 * <p><strong>The transaction is held across the network call, deliberately.</strong>
 * {@code claimUnpublished} takes a row lock, the send blocks until the broker acknowledges,
 * and only then is {@code published_at} set — all inside one transaction. Holding a
 * database lock across network I/O is normally a mistake; here it is the mechanism. If the
 * process dies mid-publish the transaction rolls back, the row unlocks still marked
 * unpublished, and the next poll retries it. Marking rows published before the ack would
 * turn every broker hiccup into a permanently lost event.
 *
 * <p>The cost of that choice is duplicates: a record the broker accepted but whose
 * {@code published_at} never committed will be sent twice. That is the at-least-once
 * guarantee this whole design is built to tolerate, and the reason every consumer keeps an
 * inbox. Idempotent producer mode ({@code enable.idempotence}) removes the broker's own
 * retry duplicates, but it cannot help across a process restart — a new producer session
 * gets a new producer id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxRelay(OutboxEventRepository outbox,
                       KafkaTemplate<String, String> kafka,
                       @Value("${outbox.relay.batch-size:100}") int batchSize,
                       @Value("${outbox.relay.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:500}")
    @Transactional
    public void drain() {
        List<OutboxEvent> batch = outbox.claimUnpublished(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                // The key is the order id, so every event for one order lands on one
                // partition and stays in order relative to the rest of that order's saga.
                kafka.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted publishing outbox id=" + event.getId(), e);
            } catch (Exception e) {
                // Roll the whole batch back rather than marking any of it published. The
                // rows unlock untouched and the next poll picks them up again.
                throw new IllegalStateException(
                        "Failed publishing outbox id=" + event.getId() + " topic=" + event.getTopic(), e);
            }

            event.setPublishedAt(Instant.now());
            log.info("[outbox] published topic={} key={} id={}",
                    event.getTopic(), event.getAggregateId(), event.getId());
        }
        // No explicit save: the entities are managed, so Hibernate flushes published_at on
        // commit -- which is the same commit that releases the row locks.
    }
}
