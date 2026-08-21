package com.finalearth.payment.messaging;

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
 * The two outcomes {@code payment} publishes, and its dead-letter policy.
 *
 * <p>It consumes {@code stock.reserved}, which {@code inventory} declares — the same topic
 * {@code sales} also reads, under a different group id. Two groups on one topic each
 * receive every record and track their own offsets independently, which is the whole reason
 * a third consumer could be added tomorrow without touching either of them.
 */
@Configuration
@EnableScheduling
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    @Bean
    NewTopic paymentCompletedTopic() {
        return TopicBuilder.name(Topics.PAYMENT_COMPLETED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic paymentFailedTopic() {
        return TopicBuilder.name(Topics.PAYMENT_FAILED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic paymentDltTopic() {
        return TopicBuilder.name(Topics.DLT).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * <strong>Dead-lettering matters more here than anywhere else in the system.</strong>
     * A stalled partition on this service means orders sit at {@code STOCK_RESERVED} with
     * their stock held and no charge attempted — inventory quietly leaking into
     * unsellable while nothing reports an error. Better to move the one bad record aside,
     * keep the partition flowing, and leave the failure somewhere a person can find it.
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
