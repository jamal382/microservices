package com.finalearth.sales.client;

import com.finalearth.sales.exception.DependencyBusinessException;
import com.finalearth.sales.exception.DependencyUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Stock reservations against {@code inventory}.
 *
 * <p><strong>Retries: no — and this is the interesting one.</strong> {@code POST
 * /api/stock/reserve} decrements stock. It is not idempotent: two identical calls reserve
 * the units twice. That matters most in precisely the situation a retry exists for — a
 * read timeout means the response was lost, <em>not</em> that the request was. The
 * reservation may well have been committed on the other side, and retrying it silently
 * double-reserves; the customer's order then holds stock nobody can sell, and the
 * discrepancy surfaces days later in a stock count with no trace of where it came from.
 *
 * <p>Retrying safely here would need inventory to accept an idempotency key and deduplicate
 * on it, which it does not. Absent that, the honest configuration is a circuit breaker with
 * no retry: still protected against a dead dependency, without inventing an at-least-once
 * write on top of an API that cannot support one. {@code payment} makes the same trade for
 * the same reason.
 *
 * <p><strong>Rejection here is the safest outcome in the system.</strong> That is the
 * argument for putting a rate limiter and a bulkhead in front of a call that must not be
 * retried. Both refuse <em>before</em> the request is sent, which makes them the only
 * failure mode on this path where the state of the callee is not in doubt: nothing was
 * reserved, because nothing was asked. Compare the read-timeout case in {@code
 * unavailable} below, where the reservation may well have been committed and only the
 * response lost. A limit that turns an ambiguous failure into a definite one is worth
 * having.
 *
 * <p>Its concurrency and rate caps are tighter than {@code catalog}'s: reservations mutate
 * shared state, they contend on the same rows, and one order produces exactly one of them
 * against several price lookups.
 *
 * <p>Note also that the breaker is shared with no one — a breaker is per named instance,
 * so {@code inventory} failing does not affect the {@code catalog} or {@code payment}
 * breakers. That per-dependency isolation is the same idea as a bulkhead, applied to the
 * failure accounting rather than to the threads.
 */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient restClient;

    public InventoryClient(@Qualifier("inventoryRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record ReserveItem(Long productId, Integer quantity) {}
    public record ReserveRequest(Long orderId, List<ReserveItem> items) {}

    @CircuitBreaker(name = "inventory", fallbackMethod = "unavailable")
    @RateLimiter(name = "inventory")
    @Bulkhead(name = "inventory")
    public void reserveStock(Long orderId, List<ReserveItem> items) {
        try {
            restClient.post()
                    .uri("/api/stock/reserve")
                    .body(new ReserveRequest(orderId, items))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            // Inventory is healthy and is refusing on the merits: not enough stock.
            // A business answer, so it neither retries nor counts toward the breaker.
            throw new DependencyBusinessException(
                    HttpStatus.CONFLICT, "Insufficient stock for order " + orderId);
        }
    }

    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private void unavailable(Long orderId, List<ReserveItem> items, CallNotPermittedException e) {
        log.warn("[r4j] FALLBACK inventory order={} reason=circuit-open -- reservation not attempted", orderId);
        throw new DependencyUnavailableException("inventory", true, "circuit is open");
    }

    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private void unavailable(Long orderId, List<ReserveItem> items, RestClientException e) {
        log.warn("[r4j] FALLBACK inventory order={} reason=call-failed detail=\"{}\" -- "
                        + "reservation state on the callee is UNKNOWN",
                orderId, e.getClass().getSimpleName());
        throw new DependencyUnavailableException("inventory", false, e.getClass().getSimpleName());
    }

    /**
     * Refused by our own limits before the request was sent. Unlike every other failure on
     * this path, this one is unambiguous: no reservation was made, because no call was
     * made. The log says so explicitly — it is the one line here that lets an operator
     * stop worrying about orphaned stock.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private void unavailable(Long orderId, List<ReserveItem> items, RequestNotPermitted e) {
        log.warn("[r4j] FALLBACK inventory order={} reason=rate-limited -- self-imposed limit, "
                        + "NOTHING was reserved", orderId);
        throw new DependencyUnavailableException("inventory", false, "rate limited by sales");
    }

    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private void unavailable(Long orderId, List<ReserveItem> items, BulkheadFullException e) {
        log.warn("[r4j] FALLBACK inventory order={} reason=bulkhead-full detail=\"{}\" -- "
                        + "concurrency cap reached, NOTHING was reserved",
                orderId, e.getMessage());
        throw new DependencyUnavailableException("inventory", false, "concurrency limit reached in sales");
    }
}
