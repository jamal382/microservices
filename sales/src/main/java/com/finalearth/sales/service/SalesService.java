package com.finalearth.sales.service;

import com.finalearth.sales.client.CatalogClient;
import com.finalearth.sales.dto.*;
import com.finalearth.sales.entity.Order;
import com.finalearth.sales.entity.OrderItem;
import com.finalearth.sales.entity.OrderStatus;
import com.finalearth.sales.event.*;
import com.finalearth.sales.messaging.EventPublisher;
import com.finalearth.sales.messaging.Topics;
import com.finalearth.sales.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The order saga, from the {@code sales} side.
 *
 * <p><strong>What changed, and why it is not just "the same thing with a queue".</strong>
 * This class used to call {@code inventory} and then {@code payment} over HTTP, in
 * sequence, inside one request. That design had two failure modes documented as known
 * limitations, and both came from the same root cause: a synchronous call that does not
 * return leaves the caller unable to distinguish "it did not happen" from "I do not know
 * whether it happened". A charge whose response was lost cannot be retried (it might double
 * charge) and cannot be assumed failed (it might have succeeded), so the order was simply
 * abandoned mid-flight at {@code STOCK_RESERVED}.
 *
 * <p>Publishing an event instead removes the ambiguity rather than handling it. The
 * decision is committed to the outbox in the same transaction as the order, so it cannot be
 * lost; the broker retains it until a consumer acknowledges it, so it cannot be dropped;
 * and the consumer's reply comes back as its own durable event, so an outcome that is slow
 * is no longer an outcome that is unknown. Nothing has to be reconciled by hand because
 * nothing is left in doubt.
 *
 * <p><strong>The cost is real and should not be glossed over.</strong> {@code POST
 * /api/orders} now returns {@code 202 Accepted} with the order at {@code PENDING}. The
 * caller has a durable order and an id to poll, but it does not yet have an answer — stock
 * may still be refused and the card may still decline, seconds later, with nobody on the
 * phone to tell. Choreography also means no single place describes the whole flow: to see
 * the saga you read the topics, not this file. That is the trade, and it is worth making
 * here only because the alternative was losing orders.
 *
 * <p>{@code catalog} is still called synchronously. Pricing is a read, it has a definite
 * answer, and the order cannot be written down without it — there is nothing to make
 * asynchronous. Its full Resilience4j stack (retry, breaker, rate limiter, bulkhead,
 * fallback) is untouched and still the right tool for that call.
 */
@Service
public class SalesService {

    private static final Logger log = LoggerFactory.getLogger(SalesService.class);

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final EventPublisher events;
    private final TransactionTemplate tx;

    public SalesService(OrderRepository orderRepository,
                        CatalogClient catalogClient,
                        EventPublisher events,
                        TransactionTemplate tx) {
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.events = events;
        this.tx = tx;
    }

    // ------------------------------------------------------------------
    // Saga step 1 -- accept the order
    // ------------------------------------------------------------------

    /**
     * Prices the order, persists it, and queues {@code order.placed}. Returns as soon as
     * that is durable; everything after this happens on the topics.
     *
     * <p><strong>Why a {@code TransactionTemplate} and not {@code @Transactional} on this
     * method.</strong> Two reasons, and the first is the one that bites. The catalog calls
     * are network I/O, and holding a pooled database connection open across them means a
     * slow dependency exhausts the connection pool — the same cascading-failure shape Lab
     * 04 opens with, moved down a layer. Pricing therefore happens before any transaction
     * is started.
     *
     * <p>The second reason is that the obvious fix — split the transactional half into a
     * private {@code @Transactional} method and call it from here — silently does nothing.
     * Spring's transaction support is proxy-based, so an internal {@code this.} call never
     * crosses the proxy and the annotation is ignored: the order and its event would be
     * written in two separate autocommitted statements, which is precisely the dual-write
     * bug the outbox exists to prevent. It would also fail invisibly. An explicit
     * transaction template has no such trap, and it puts the boundary where a reader can
     * see it.
     */
    public OrderDto placeOrder(CreateOrderRequest req) {
        List<OrderItem> pricedItems = priceItems(req);

        BigDecimal totalAmount = pricedItems.stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order saved = tx.execute(status -> {
            Order order = new Order();
            order.setCustomerId(req.customerId());
            order.setStatus(OrderStatus.PENDING);
            order.setTotalAmount(totalAmount);
            order.setOrderNumber(generateTempOrderNumber());
            pricedItems.forEach(order::addItem);

            // Flush to get the identity-generated id, which the order number and the
            // event key both need. Still the same transaction.
            Order persisted = orderRepository.saveAndFlush(order);
            persisted.setOrderNumber(generateOrderNumber(persisted.getId()));

            events.emit(Topics.ORDER_PLACED, new OrderPlacedEvent(
                    UUID.randomUUID(),
                    persisted.getId(),
                    persisted.getOrderNumber(),
                    persisted.getCustomerId(),
                    persisted.getTotalAmount(),
                    req.paymentMethod(),
                    persisted.getItems().stream()
                            .map(i -> new OrderPlacedEvent.Item(i.getProductId(), i.getQuantity()))
                            .toList(),
                    Instant.now()));

            return persisted;
        });

        log.info("[saga] order={} ACCEPTED status={} total={} -- awaiting stock",
                saved.getId(), saved.getStatus(), saved.getTotalAmount());
        return mapToDto(saved);
    }

    /**
     * Fetches every line's current price from {@code catalog}. Outside any transaction, and
     * still fully protected by the Resilience4j stack on {@link CatalogClient} — a failure
     * here rejects the request outright, before an order exists, which is the one point in
     * the flow where failing fast is unambiguously correct.
     */
    private List<OrderItem> priceItems(CreateOrderRequest req) {
        List<OrderItem> items = new ArrayList<>();
        for (OrderItemRequest itemReq : req.items()) {
            CatalogClient.ProductResponse product = catalogClient.getProductById(itemReq.productId());

            OrderItem item = new OrderItem();
            item.setProductId(product.id());
            item.setProductName(product.name());
            item.setQuantity(itemReq.quantity());
            item.setUnitPrice(product.price());
            items.add(item);
        }
        return items;
    }

    // ------------------------------------------------------------------
    // Saga steps 2-5 -- reactions to what the other services decided
    // ------------------------------------------------------------------
    //
    // Each of these is called from the listener inside the listener's transaction, so the
    // status change, the inbox row and any newly queued event all commit together or not
    // at all. Each one also re-reads the order and checks its current state: events can be
    // redelivered, and on a partition rebalance they can arrive after the order has already
    // moved on. A transition that no longer applies is logged and dropped rather than
    // forced -- an out-of-order event is not an error, it is the normal weather.

    @Transactional
    public void onStockReserved(StockReservedEvent event) {
        transition(event.orderId(), OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, "stock reserved");
    }

    @Transactional
    public void onStockRejected(StockRejectedEvent event) {
        // Terminal, and nothing to compensate: inventory refused before changing anything,
        // and payment never heard about this order at all.
        transition(event.orderId(), OrderStatus.PENDING, OrderStatus.REJECTED,
                "stock rejected: " + event.reason());
    }

    @Transactional
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        transition(event.orderId(), OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED,
                "payment completed ref=" + event.paymentRef());
    }

    /**
     * The failure that used to be unrecoverable, and the only handler that emits.
     *
     * <p>Stock is already reserved at this point and there is no transaction to roll back,
     * so the reservation has to be undone by an explicit compensating command. Queuing
     * {@code order.cancelled} in the same transaction that records {@code PAYMENT_FAILED}
     * is what makes that reliable: the system cannot end up having remembered the failure
     * but forgotten to release the stock.
     */
    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event) {
        Order order = transition(event.orderId(), OrderStatus.STOCK_RESERVED, OrderStatus.PAYMENT_FAILED,
                "payment failed: " + event.reason());
        if (order == null) {
            return;
        }

        events.emit(Topics.ORDER_CANCELLED, new OrderCancelledEvent(
                UUID.randomUUID(),
                order.getId(),
                "payment failed: " + event.reason(),
                Instant.now()));

        log.warn("[saga] order={} COMPENSATING -- releasing reserved stock", order.getId());
    }

    /**
     * The saga's last step. {@code CANCELLED} is only set once {@code inventory} has
     * confirmed the units are actually back, so the status distinguishes "payment failed
     * and we have cleaned up" from "payment failed and cleanup is still in flight". Before
     * this project had a saga, this enum constant existed and nothing ever set it.
     */
    @Transactional
    public void onStockReleased(StockReleasedEvent event) {
        transition(event.orderId(), OrderStatus.PAYMENT_FAILED, OrderStatus.CANCELLED,
                "stock released, saga complete");
    }

    /**
     * Applies a state change only if the order is still in the state the event was a
     * reply to. Returns the order on success, or null if the transition did not apply.
     */
    private Order transition(Long orderId, OrderStatus expected, OrderStatus next, String reason) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            // Possible in exactly one way that is not a bug: the topic outlives the
            // database. Replaying retained events against a volume that has been wiped
            // will reference orders that no longer exist.
            log.warn("[saga] order={} not found -- ignoring '{}'", orderId, reason);
            return null;
        }

        if (order.getStatus() != expected) {
            log.warn("[saga] order={} status={} -- ignoring '{}' (expected {})",
                    orderId, order.getStatus(), reason, expected);
            return null;
        }

        order.setStatus(next);
        log.info("[saga] order={} {} -> {} ({})", orderId, expected, next, reason);
        return order;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OrderDto getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found with id: " + id));
        return mapToDto(order);
    }

    private String generateTempOrderNumber() {
        return "TMP-" + UUID.randomUUID().toString().substring(0, 18);
    }

    private String generateOrderNumber(Long orderId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return String.format("ORD-%s-%04d", dateStr, orderId);
    }

    private OrderDto mapToDto(Order o) {
        var itemDtos = o.getItems().stream()
                .map(i -> new OrderItemDto(i.getProductId(), i.getProductName(), i.getQuantity(), i.getUnitPrice()))
                .toList();

        return new OrderDto(
                o.getId(),
                o.getOrderNumber(),
                o.getCustomerId(),
                o.getStatus(),
                o.getTotalAmount(),
                itemDtos,
                o.getCreatedAt()
        );
    }
}
