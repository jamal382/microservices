package com.finalearth.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumed from {@code inventory}: stock is held, so this order may now be charged.
 *
 * <p><strong>Payment takes its instruction from inventory, not from sales.</strong> That
 * looks odd until you notice it is what enforces the ordering: money can only move after
 * units have actually been set aside, and no coordinator has to remember to check. The
 * dependency is on the event, not on the service — {@code payment} has no address for
 * {@code inventory} and never calls it.
 *
 * <p>The amount and method are carried in the event rather than fetched. Reading them from
 * a call back to {@code sales} would put a synchronous dependency in the middle of an
 * asynchronous flow and reintroduce every failure mode this design removed — including the
 * one where {@code sales} is briefly down and a charge that was ready to go cannot proceed.
 */
public record StockReservedEvent(
        UUID eventId,
        Long orderId,
        BigDecimal totalAmount,
        String paymentMethod,
        Instant occurredAt) implements DomainEvent {}
