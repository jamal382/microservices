package com.finalearth.inventory.messaging;

import com.finalearth.inventory.dto.ReservationItemRequest;
import com.finalearth.inventory.entity.ProcessedEvent;
import com.finalearth.inventory.event.*;
import com.finalearth.inventory.repository.ProcessedEventRepository;
import com.finalearth.inventory.service.InventoryService;
import com.finalearth.inventory.service.ReservationOutcome;
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
import java.util.function.Consumer;

/**
 * The two commands {@code sales} sends to {@code inventory}: hold this stock, and give it
 * back.
 *
 * <p><strong>Both replicas run this class, and that is the point.</strong> They share a
 * {@code group-id}, so Kafka treats them as one consumer group and gives each partition of
 * {@code order.placed} to exactly one of them. No order is reserved twice and no
 * coordination code exists to make that true — group membership does it. Contrast the
 * pre-Kafka arrangement, where both replicas sat behind a DNS alias and the only thing
 * deciding which one served a request was whichever A record the caller happened to cache.
 *
 * <p>Scaling now has a hard ceiling that is worth knowing before you meet it: a third
 * replica would take the third partition and help, a fourth would join the group and
 * receive nothing at all.
 *
 * <p>Each listener is one transaction covering the inbox check, the stock change, the
 * outbox row and the inbox insert — so the reply is as durable as the change it describes,
 * and a redelivery finds the work already done.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final InventoryService inventory;
    private final EventPublisher events;
    private final ProcessedEventRepository inbox;
    private final ObjectMapper mapper;

    public OrderEventListener(InventoryService inventory,
                              EventPublisher events,
                              ProcessedEventRepository inbox,
                              ObjectMapper mapper) {
        this.inventory = inventory;
        this.events = events;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    /**
     * Saga step 2. Either outcome is published; neither is thrown.
     *
     * <p>A rejection here is a perfectly good answer and ends the saga cleanly: nothing was
     * changed, so there is nothing to compensate and {@code payment} never hears about the
     * order at all. Silence, by contrast, would strand it — {@code sales} would hold it at
     * {@code PENDING} indefinitely with no way to tell a slow reservation from a lost one.
     * Every saga step must report back, and that obligation is why the service returns a
     * {@link ReservationOutcome} rather than throwing.
     */
    @KafkaListener(topics = Topics.ORDER_PLACED)
    @Transactional
    public void onOrderPlaced(ConsumerRecord<String, String> record) {
        consume(record, OrderPlacedEvent.class, event -> {
            var items = event.items().stream()
                    .map(i -> new ReservationItemRequest(i.productId(), i.quantity()))
                    .toList();

            ReservationOutcome outcome = inventory.reserveForOrder(event.orderId(), items);

            if (outcome.reserved()) {
                // totalAmount and paymentMethod are carried straight through from
                // order.placed so payment can charge without asking sales for them.
                events.emit(Topics.STOCK_RESERVED, new StockReservedEvent(
                        UUID.randomUUID(), event.orderId(),
                        event.totalAmount(), event.paymentMethod(), Instant.now()));
            } else {
                events.emit(Topics.STOCK_REJECTED, new StockRejectedEvent(
                        UUID.randomUUID(), event.orderId(), outcome.reason(), Instant.now()));
            }
        });
    }

    /**
     * The compensating step. Publishes {@code stock.released} only when units actually moved
     * — a cancellation for an order that reserved nothing, or that was already released, is
     * acknowledged and dropped rather than answered with a claim that is not true.
     */
    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    @Transactional
    public void onOrderCancelled(ConsumerRecord<String, String> record) {
        consume(record, OrderCancelledEvent.class, event -> {
            log.warn("[saga] order={} COMPENSATING reason=\"{}\"", event.orderId(), event.reason());
            if (inventory.releaseForOrder(event.orderId())) {
                events.emit(Topics.STOCK_RELEASED, new StockReleasedEvent(
                        UUID.randomUUID(), event.orderId(), Instant.now()));
            }
        });
    }

    private <T extends DomainEvent> void consume(ConsumerRecord<String, String> record,
                                                 Class<T> type,
                                                 Consumer<T> handler) {
        T event;
        try {
            event = mapper.readValue(record.value(), type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unparseable " + type.getSimpleName() + " at " + record.topic() + "-"
                            + record.partition() + "@" + record.offset(), e);
        }

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
