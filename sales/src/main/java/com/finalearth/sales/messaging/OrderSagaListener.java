package com.finalearth.sales.messaging;

import tools.jackson.databind.ObjectMapper;
import com.finalearth.sales.entity.ProcessedEvent;
import com.finalearth.sales.event.*;
import com.finalearth.sales.repository.ProcessedEventRepository;
import com.finalearth.sales.service.SalesService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

/**
 * Everything the rest of the saga has to say back to {@code sales}.
 *
 * <p><strong>The transaction boundary is this method, and that is the design.</strong> Each
 * listener runs the inbox check, the order's state change, any newly queued outbox event
 * and the inbox insert inside a single database transaction. All four commit together or
 * none of them do. That is what makes a redelivery harmless: either the whole reaction
 * happened and the inbox row proves it, or none of it happened and the event is genuinely
 * unprocessed. There is no interleaving where the order advanced but the system forgot it
 * had seen the event.
 *
 * <p><strong>The offset commit is deliberately outside that transaction</strong>, and cannot
 * be inside it — Kafka's offsets live in Kafka, Postgres' rows live in Postgres, and there
 * is no transaction spanning the two. So the honest ordering is: commit the database, then
 * commit the offset, and accept that a crash in between means the record is delivered
 * again. That is not a flaw to be engineered away; it is the at-least-once contract, and
 * the inbox is the thing that makes living with it cheap.
 */
@Component
public class OrderSagaListener {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaListener.class);

    private final SalesService sales;
    private final ProcessedEventRepository inbox;
    private final ObjectMapper mapper;

    public OrderSagaListener(SalesService sales, ProcessedEventRepository inbox, ObjectMapper mapper) {
        this.sales = sales;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.STOCK_RESERVED)
    @Transactional
    public void onStockReserved(ConsumerRecord<String, String> record) {
        consume(record, StockReservedEvent.class, sales::onStockReserved);
    }

    @KafkaListener(topics = Topics.STOCK_REJECTED)
    @Transactional
    public void onStockRejected(ConsumerRecord<String, String> record) {
        consume(record, StockRejectedEvent.class, sales::onStockRejected);
    }

    @KafkaListener(topics = Topics.PAYMENT_COMPLETED)
    @Transactional
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        consume(record, PaymentCompletedEvent.class, sales::onPaymentCompleted);
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED)
    @Transactional
    public void onPaymentFailed(ConsumerRecord<String, String> record) {
        consume(record, PaymentFailedEvent.class, sales::onPaymentFailed);
    }

    @KafkaListener(topics = Topics.STOCK_RELEASED)
    @Transactional
    public void onStockReleased(ConsumerRecord<String, String> record) {
        consume(record, StockReleasedEvent.class, sales::onStockReleased);
    }

    /**
     * Parse, de-duplicate, act, record. The order matters: the inbox row is written
     * <em>after</em> the handler runs, in the same transaction, so a handler that throws
     * rolls the claim back and leaves the event genuinely unprocessed for the retry.
     */
    private <T extends DomainEvent> void consume(ConsumerRecord<String, String> record,
                                                 Class<T> type,
                                                 Consumer<T> handler) {
        T event;
        try {
            event = mapper.readValue(record.value(), type);
        } catch (Exception e) {
            // Non-retryable by configuration: a payload that will not parse now will not
            // parse in a second. Let it reach the dead-letter topic immediately.
            throw new IllegalArgumentException(
                    "Unparseable " + type.getSimpleName() + " at " + record.topic() + "-"
                            + record.partition() + "@" + record.offset(), e);
        }

        // Reuse the request-id slot so a saga step logs under the order it belongs to, and
        // `docker compose logs sales | grep order-42` shows the whole chain.
        MDC.put("reqId", "order-" + event.orderId());
        try {
            if (inbox.existsById(event.eventId())) {
                log.info("[kafka] DUPLICATE topic={} partition={} offset={} order={} event={} -- already applied, skipping",
                        record.topic(), record.partition(), record.offset(), event.orderId(), event.eventId());
                return;
            }

            log.info("[kafka] consume topic={} partition={} offset={} key={} order={} event={}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    event.orderId(), event.eventId());

            handler.accept(event);
            inbox.save(new ProcessedEvent(event.eventId(), record.topic()));
        } finally {
            MDC.remove("reqId");
        }
    }
}
