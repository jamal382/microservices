package com.finalearth.payment.messaging;

/**
 * Every topic on the order saga, in the order the saga travels them.
 *
 * <p>The names are duplicated in {@code inventory} and {@code payment} rather than shared
 * through a common module. That is the same call the rest of this project makes about DTOs:
 * there is no parent POM and no shared jar, because a shared jar is a compile-time coupling
 * between services that are supposed to be independently deployable. The topic name is the
 * contract, and a contract you can only satisfy by depending on someone else's build is a
 * distributed monolith with extra steps.
 */
public final class Topics {

    private Topics() {}

    /** sales -> inventory. The saga starts here. */
    public static final String ORDER_PLACED = "order.placed";

    /** sales -> inventory. The compensating command. */
    public static final String ORDER_CANCELLED = "order.cancelled";

    /** inventory -> sales AND payment. Two consumer groups, one topic. */
    public static final String STOCK_RESERVED = "stock.reserved";

    /** inventory -> sales. Terminal: the order cannot be filled. */
    public static final String STOCK_REJECTED = "stock.rejected";

    /** inventory -> sales. Confirms the compensation actually ran. */
    public static final String STOCK_RELEASED = "stock.released";

    /** payment -> sales. */
    public static final String PAYMENT_COMPLETED = "payment.completed";

    /** payment -> sales. An authoritative decline, not an unreachable service. */
    public static final String PAYMENT_FAILED = "payment.failed";

    /**
     * Where this service's poison messages go.
     *
     * <p>The dead-letter topic belongs to the <em>consumer</em>, not to the topic it was
     * reading. {@code stock.reserved} is consumed by two different groups doing two
     * different jobs; a record that {@code payment} chokes on may be perfectly consumable
     * by {@code sales}, so a single {@code stock.reserved.DLT} would say nothing about
     * which side actually failed.
     */
    public static final String DLT = "payment.dlt";
}
