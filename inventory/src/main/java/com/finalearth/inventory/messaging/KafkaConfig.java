package com.finalearth.inventory.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Topics this service owns, and what happens to records it cannot process.
 *
 * <p>{@code inventory} declares the three outcomes it produces. It consumes
 * {@code order.placed} and {@code order.cancelled}, both declared by {@code sales} — a
 * topic is created by whoever writes to it, so ownership of the data and ownership of the
 * definition stay in the same place.
 *
 * <p><strong>This is the service where partition count stops being theoretical.</strong>
 * There are two {@code inventory} containers and they share one {@code group-id}, which
 * makes them one consumer group and means each {@code order.placed} record is handled by
 * exactly one of them. Kafka assigns whole partitions to members, so with three partitions
 * the split is 2/1. With one partition it would be 1/0 — one replica doing all the work and
 * the other sitting idle while looking perfectly healthy. Partition count is the ceiling on
 * consumer parallelism, and it is fixed at creation time.
 */
@Configuration
@EnableScheduling
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    @Bean
    NewTopic stockReservedTopic() {
        return TopicBuilder.name(Topics.STOCK_RESERVED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic stockRejectedTopic() {
        return TopicBuilder.name(Topics.STOCK_REJECTED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic stockReleasedTopic() {
        return TopicBuilder.name(Topics.STOCK_RELEASED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic inventoryDltTopic() {
        return TopicBuilder.name(Topics.DLT).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * Retry a few times, then dead-letter and move on.
     *
     * <p>Without this, a record that always throws is retried forever and the offset never
     * advances — the partition stops, and every order queued behind the bad one stops with
     * it. On a shared group that is worse than it sounds: the stalled partition is one of
     * three, so a third of all orders hang while the service reports itself healthy and the
     * other two partitions keep flowing. Dead-lettering converts an invisible partial
     * outage into a visible, bounded loss.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> {
                    log.error("[kafka] DEAD-LETTER topic={} partition={} offset={} key={} cause={}",
                            record.topic(), record.partition(), record.offset(), record.key(),
                            exception.getMessage());
                    return new TopicPartition(Topics.DLT, -1);
                });

        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(
                tools.jackson.core.JacksonException.class,
                IllegalArgumentException.class);
        return handler;
    }
}
