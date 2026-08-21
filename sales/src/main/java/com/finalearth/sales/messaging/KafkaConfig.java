package com.finalearth.sales.messaging;

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
 * Topic declarations and the consumer-side error policy.
 *
 * <p><strong>A service declares the topics it produces, and only those.</strong>
 * {@code sales} owns {@code order.placed} and {@code order.cancelled}; it consumes four
 * others that {@code inventory} and {@code payment} declare. Auto-creation is switched off
 * at the broker, so this is the only way a topic comes into existence — which means
 * partition counts are a decision someone made rather than a default nobody noticed.
 * Declaring a topic that already exists is a no-op, and consumers tolerate a topic that
 * does not exist yet: with {@code auto-offset-reset=earliest} the group reads from the
 * beginning once the topic appears, so nothing published during startup is lost.
 */
@Configuration
@EnableScheduling
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * Three partitions on every saga topic, with a single broker holding all of them.
     *
     * <p>Partitions are the unit of consumer parallelism, and the ceiling is hard: a
     * consumer group can never usefully run more members than the topic has partitions,
     * because a partition is assigned to exactly one member at a time. Three is what lets
     * {@code inventory}'s two replicas split {@code order.placed} between them (2 + 1) and
     * leaves headroom for a third. One partition — which is what auto-creation would have
     * given us — would silently pin the whole group to a single consumer and leave the
     * other replica idle, looking healthy, doing nothing.
     */
    private static final int PARTITIONS = 3;

    /** One broker means one replica. Anything higher fails to create. */
    private static final short REPLICAS = 1;

    @Bean
    NewTopic orderPlacedTopic() {
        return TopicBuilder.name(Topics.ORDER_PLACED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic orderCancelledTopic() {
        return TopicBuilder.name(Topics.ORDER_CANCELLED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic salesDltTopic() {
        return TopicBuilder.name(Topics.DLT).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * What happens when a listener throws.
     *
     * <p>Without an error handler Spring Kafka retries the record forever, and because the
     * offset is never committed the consumer never advances — one bad record stops the
     * partition permanently, and every well-formed order queued behind it stops with it.
     * That failure mode is called a poison pill and it is the single most common way an
     * event-driven system falls over.
     *
     * <p>So: a few quick retries for the transient case (the database was briefly
     * unreachable), then give up and move the record to {@code sales.dlt} so the partition
     * can continue. The offset is committed once the record has been safely re-published,
     * which converts an unbounded stall into a bounded loss that someone can go and look at.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> {
                    log.error("[kafka] DEAD-LETTER topic={} partition={} offset={} key={} cause={}",
                            record.topic(), record.partition(), record.offset(), record.key(),
                            exception.getMessage());
                    // Partition -1 lets the producer choose by key, so a record keeps its
                    // ordering guarantees even in the dead-letter topic.
                    return new TopicPartition(Topics.DLT, -1);
                });

        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));

        // A malformed payload will never parse, no matter how many times it is retried.
        // Retrying it just delays the dead-lettering by four seconds and fills the log.
        handler.addNotRetryableExceptions(
                tools.jackson.core.JacksonException.class,
                IllegalArgumentException.class);

        return handler;
    }
}
