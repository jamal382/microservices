package com.finalearth.catalog.client;

import com.finalearth.catalog.exception.StockNotFoundException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Calls {@code inventory} for stock levels, and decides what {@code catalog} does when
 * that call does not work.
 *
 * <p>This one method carries all five Resilience4j patterns, which makes it the reference
 * example for the whole project — see {@code docs/labs/05-resilience-patterns.md}. It can
 * carry them because it is a <em>read</em>: idempotent, abandonable, and with an honest
 * degraded answer available. The three call paths in {@code sales} are deliberately more
 * conservative, and the doc explains why for each.
 *
 * <h2>How the five annotations stack</h2>
 * Resilience4j orders its aspects by {@code Ordered} value, not by the order you write
 * them, and the nesting is fixed:
 *
 * <pre>
 *   Retry {                            ← outermost: sees every way the call can end
 *       CircuitBreaker {               ← counts outcomes, refuses when open
 *           RateLimiter {              ← spends a permit
 *               TimeLimiter {          ← starts the wall-clock deadline
 *                   Bulkhead {         ← innermost: hands work to its own pool
 *                       the HTTP call  ← bounded by the RestClient socket timeouts
 *                   }
 *               }
 *           }
 *       }
 *   }
 * </pre>
 *
 * Several consequences follow, and each of them is a trap:
 *
 * <ul>
 *   <li><b>Every retry attempt passes through the breaker.</b> Three attempts against a
 *       dead replica put three failures into the sliding window, not one, so a retrying
 *       caller trips its own breaker roughly three times faster than the config suggests.
 *   <li><b>The breaker sits outside the rate limiter and the bulkhead</b>, so
 *       {@link RequestNotPermitted} and {@link BulkheadFullException} pass straight
 *       through its accounting on the way out. Both are listed in {@code ignore-exceptions}
 *       in the properties. Left unlisted they would fall through to the default and be
 *       counted as <em>successes</em> — self-inflicted rejections quietly padding the
 *       success rate and masking real failures happening alongside them.
 *   <li><b>Once the breaker is open it throws instantly</b>, so retrying that just burns
 *       the budget on a call that never leaves the process. Also ignored by the retry.
 *   <li><b>The rate limiter spends its permit before the bulkhead is consulted.</b> A call
 *       rejected by a full bulkhead has already cost a permit it never used. Harmless at
 *       these limits, but it is why the limiter is the looser of the two.
 * </ul>
 *
 * <h2>Why this method returns a future</h2>
 * {@code @TimeLimiter} and a {@code THREADPOOL} bulkhead both require a
 * {@code CompletionStage} return type — that is what lets the deadline be enforced from
 * outside the call rather than by the call itself. The body is still ordinary blocking
 * code; the bulkhead aspect runs it on a pool thread and joins it, so
 * {@code completedFuture} here wraps work that has already happened rather than starting
 * anything asynchronous.
 *
 * <p><strong>The deadline does not free the thread.</strong> When the time limiter fires
 * it completes the future exceptionally and cancels the task, but a thread blocked in a
 * socket read is not interruptible — it stays parked on the bulkhead pool until the
 * {@code RestClient} read timeout finally releases it. That is precisely why the socket
 * timeouts remain the real bound and the time limiter is a backstop on top of them, and
 * why the two are configured with the limiter deadline (1.5s) safely below the read
 * timeout (2s): the limiter is what the <em>caller</em> waits for, the socket timeout is
 * what the <em>worker</em> waits for.
 */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private static final String INVENTORY_HOST = "inventory";

    private final RestClient restClient;

    public InventoryClient(@Qualifier("inventoryRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record StockResponse(
        Long id,
        Long productId,
        Integer quantityAvailable,
        Integer quantityReserved,
        Instant updatedAt
    ) {}

    /**
     * Stock for one product, plus whether the number can be trusted.
     *
     * <p>{@code degraded} is the honest part of the contract: when inventory could not be
     * reached, the quantities are {@code null} rather than a guess, and the caller is told
     * why. Inventing a plausible number would be the worse failure — a caller cannot tell
     * a fabricated zero from a real one.
     */
    public record StockLookup(
        Integer quantityAvailable,
        Integer quantityReserved,
        boolean degraded,
        String reason
    ) {
        static StockLookup of(StockResponse r) {
            return new StockLookup(r.quantityAvailable(), r.quantityReserved(), false, null);
        }

        static StockLookup degraded(String reason) {
            return new StockLookup(null, null, true, reason);
        }
    }

    @Retry(name = "inventory", fallbackMethod = "stockUnavailable")
    @CircuitBreaker(name = "inventory")
    @RateLimiter(name = "inventory")
    @TimeLimiter(name = "inventory")
    @Bulkhead(name = "inventory", type = Bulkhead.Type.THREADPOOL)
    public CompletableFuture<StockLookup> getStockByProductId(Long productId) {
        long startNanos = System.nanoTime();

        ResponseEntity<StockResponse> response;
        try {
            response = restClient.get()
                    .uri("/api/stock/{productId}", productId)
                    .retrieve()
                    .toEntity(StockResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            // A correct answer, not a failure: inventory is healthy and is telling us
            // this product has no stock row. Converted to a type that the retry and the
            // breaker are both configured to ignore, and that no fallback below accepts,
            // so it travels all the way out to the exception handler as a plain 404.
            throw new StockNotFoundException(productId);
        }

        long millis = (System.nanoTime() - startNanos) / 1_000_000;

        // X-Instance-Id is stamped by inventory's logging filter. The host logged here is
        // the network alias we asked for, not the replica we reached -- Docker's DNS
        // picked that per lookup -- so this header is how the caller sees which of the
        // two containers actually served the call.
        log.info("[catalog->inventory] GET http://{}/api/stock/{} -> {} ({} ms) served-by={}",
                INVENTORY_HOST, productId, response.getStatusCode().value(), millis,
                response.getHeaders().getFirst("X-Instance-Id"));

        return CompletableFuture.completedFuture(StockLookup.of(response.getBody()));
    }

    /**
     * Blocks on the future and unwraps the {@link CompletionException} the async chain
     * wraps every failure in, so callers see {@link StockNotFoundException} itself rather
     * than a wrapper around it.
     *
     * <p><strong>Static on purpose.</strong> The obvious alternative — a synchronous
     * instance method here that calls {@code getStockByProductId} — would silently defeat
     * the whole class: an internal {@code this.} call does not go through the Spring
     * proxy, so every one of the five aspects above would be skipped and the call would
     * run completely unprotected. Nothing would fail, or even warn; the resilience would
     * just quietly not be there. Keeping the join outside the bean makes that impossible.
     */
    public static StockLookup await(CompletableFuture<StockLookup> future) {
        try {
            return future.join();
        } catch (CompletionException | CancellationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    /**
     * The breaker is open, so this call never left the process.
     *
     * <p>Separate from the other fallbacks purely so the log line can say which of them
     * happened — from the caller's side they are indistinguishable in the response, but
     * they mean very different things. This one is the system protecting a dependency it
     * has already decided is unhealthy, and it costs microseconds rather than the full
     * timeout budget.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private CompletableFuture<StockLookup> stockUnavailable(Long productId, CallNotPermittedException e) {
        log.warn("[r4j] FALLBACK product={} reason=circuit-open detail=\"{}\" "
                        + "-- call rejected without attempting inventory",
                productId, e.getMessage());
        return CompletableFuture.completedFuture(StockLookup.degraded("inventory circuit is open"));
    }

    /**
     * We are calling inventory faster than the agreed rate. Nothing is wrong with
     * inventory — this is {@code catalog} throttling itself, and the degraded answer says
     * so rather than blaming the dependency.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private CompletableFuture<StockLookup> stockUnavailable(Long productId, RequestNotPermitted e) {
        log.warn("[r4j] FALLBACK product={} reason=rate-limited -- no permit available this period, "
                        + "inventory was not contacted", productId);
        return CompletableFuture.completedFuture(StockLookup.degraded("stock lookups are rate limited"));
    }

    /**
     * Every bulkhead thread is busy and the queue is full. Also self-inflicted, and also
     * the point: the alternative is an unbounded pile-up of threads waiting on a
     * dependency that cannot keep up, which is how one slow service takes down a caller
     * that was perfectly healthy.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private CompletableFuture<StockLookup> stockUnavailable(Long productId, BulkheadFullException e) {
        log.warn("[r4j] FALLBACK product={} reason=bulkhead-full detail=\"{}\" "
                        + "-- concurrency cap reached, inventory was not contacted",
                productId, e.getMessage());
        return CompletableFuture.completedFuture(StockLookup.degraded("too many in-flight stock lookups"));
    }

    /**
     * The wall-clock deadline expired. Note this is a failure of <em>our</em> patience,
     * not necessarily of inventory — the call may still be in flight on a bulkhead thread
     * and may yet succeed, unobserved. It is recorded against the breaker anyway, because
     * a dependency too slow to be useful is a dependency that is failing.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private CompletableFuture<StockLookup> stockUnavailable(Long productId, TimeoutException e) {
        log.warn("[r4j] FALLBACK product={} reason=deadline-exceeded -- inventory did not answer in time; "
                        + "the attempt may still be running", productId);
        return CompletableFuture.completedFuture(StockLookup.degraded("inventory did not answer in time"));
    }

    /**
     * Every attempt failed. {@code e} is the last one; the retry event log holds the rest.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private CompletableFuture<StockLookup> stockUnavailable(Long productId, RestClientException e) {
        log.warn("[r4j] FALLBACK product={} reason=call-failed detail=\"{}: {}\" "
                        + "-- all attempts exhausted",
                productId, e.getClass().getSimpleName(), rootMessage(e));
        return CompletableFuture.completedFuture(StockLookup.degraded("inventory did not respond"));
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
