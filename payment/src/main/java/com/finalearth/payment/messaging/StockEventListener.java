package com.finalearth.payment.messaging;

import com.finalearth.payment.entity.ProcessedEvent;
import com.finalearth.payment.event.*;
import com.finalearth.payment.repository.ProcessedEventRepository;
import com.finalearth.payment.service.PaymentOutcome;
import com.finalearth.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Charges an order once {@code inventory} confirms its stock is held.
 *
 * <p><strong>This listener is the whole reason the project has Kafka.</strong> The old
 * synchronous version of this step had a failure mode with no good answer: if
 * {@code sales} called {@code payment} and the response was lost, the charge could not be
 * retried (it might already have gone through) and could not be assumed failed (it might
 * also have gone through). The order was left at {@code STOCK_RESERVED} and a human was
 * expected to sort it out.
 *
 * <p>Nothing here is cleverer than that code was — the difference is entirely in where the
 * durability lives. The instruction to charge is a committed record on a topic, so it
 * cannot be lost by a network that drops a packet. The outcome is written to this service's
 * outbox in the same transaction as the payment row, so the answer cannot exist without the
 * charge or the charge without the answer. And the offset is only committed after both, so
 * a crash mid-flight replays the event rather than dropping it — into a method that checks
 * for an existing payment before touching a card.
 *
 * <p>What is left is not "no failures" but a much smaller claim: every failure leaves the
 * system in a state that is either already correct or still retryable, and none of them
 * leave it in a state nobody can interpret.
 */
@Component
public class StockEventListener {

    private static final Logger log = LoggerFactory.getLogger(StockEventListener.class);

    private final PaymentService payments;
    private final EventPublisher events;
    private final ProcessedEventRepository inbox;
    private final ObjectMapper mapper;

    public StockEventListener(PaymentService payments,
                              EventPublisher events,
                              ProcessedEventRepository inbox,
                              ObjectMapper mapper) {
        this.payments = payments;
        this.events = events;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.STOCK_RESERVED)
    @Transactional
    public void onStockReserved(ConsumerRecord<String, String> record) {
        StockReservedEvent event;
        try {
            event = mapper.readValue(record.value(), StockReservedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unparseable StockReservedEvent at " + record.topic() + "-"
                            + record.partition() + "@" + record.offset(), e);
        }

        MDC.put("reqId", "order-" + event.orderId());
        try {
            if (inbox.existsById(event.eventId())) {
                log.info("[kafka] DUPLICATE topic={} partition={} offset={} order={} event={} -- already charged, skipping",
                        record.topic(), record.partition(), record.offset(), event.orderId(), event.eventId());
                return;
            }

            log.info("[kafka] consume topic={} partition={} offset={} key={} order={} event={}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    event.orderId(), event.eventId());

            PaymentOutcome outcome =
                    payments.chargeForOrder(event.orderId(), event.totalAmount(), event.paymentMethod());

            if (outcome.completed()) {
                events.emit(Topics.PAYMENT_COMPLETED, new PaymentCompletedEvent(
                        UUID.randomUUID(), event.orderId(), outcome.paymentRef(),
                        event.totalAmount(), Instant.now()));
            } else {
                // Publishing the decline is what triggers compensation upstream: sales
                // records PAYMENT_FAILED and emits order.cancelled, and inventory gives the
                // units back. Failing to publish here would strand the reserved stock.
                events.emit(Topics.PAYMENT_FAILED, new PaymentFailedEvent(
                        UUID.randomUUID(), event.orderId(), outcome.reason(), Instant.now()));
            }

            inbox.save(new ProcessedEvent(event.eventId(), record.topic()));
        } finally {
            MDC.remove("reqId");
        }
    }
}
