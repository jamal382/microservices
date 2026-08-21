package com.finalearth.payment.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.finalearth.payment.entity.OutboxEvent;
import com.finalearth.payment.event.DomainEvent;
import com.finalearth.payment.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes an event to the outbox. Never touches Kafka.
 *
 * <p>That is the entire discipline of the pattern, and it is worth being blunt about: no
 * code on a business path in this service is allowed to hold a {@code KafkaTemplate}. If it
 * could, someone would eventually publish directly from inside a transaction, the
 * transaction would roll back, and a downstream service would act on an order that does not
 * exist. Emitting is a database write and nothing else, so it inherits the atomicity of
 * whatever transaction it is called from.
 *
 * <p>There is no {@code @Transactional} here on purpose. This method must <em>join</em> the
 * caller's transaction, never start its own — a new transaction would commit the event
 * independently of the order it describes, which is the dual-write bug wearing a different
 * hat. It is only ever called from an already-transactional method.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;

    public EventPublisher(OutboxEventRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public void emit(String topic, DomainEvent event) {
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JacksonException e) {
            // Jackson 3 made its exceptions unchecked, so this catch is a choice rather
            // than a requirement -- and letting it propagate is the point. An event that
            // cannot be serialised must fail the transaction, because the alternative is
            // committing the order and silently dropping the step that carries it forward.
            throw new IllegalStateException("Cannot serialise " + event.getClass().getSimpleName(), e);
        }

        outbox.save(new OutboxEvent(
                event.eventId(),
                "order",
                String.valueOf(event.orderId()),
                topic,
                payload));

        log.info("[outbox] queued topic={} order={} event={} id={}",
                topic, event.orderId(), event.getClass().getSimpleName(), event.eventId());
    }
}
