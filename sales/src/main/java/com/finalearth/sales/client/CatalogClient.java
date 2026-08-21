package com.finalearth.sales.client;

import com.finalearth.sales.exception.DependencyBusinessException;
import com.finalearth.sales.exception.DependencyUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;

/**
 * Price lookups against {@code catalog}.
 *
 * <p><strong>Retries: yes.</strong> This is a GET. Asking twice costs a little latency
 * and changes nothing on the other side, so a retry is close to free and genuinely
 * useful — a single dropped packet or a replica restarting mid-request turns into a
 * short pause instead of a failed order.
 *
 * <p><strong>Fallback: no.</strong> There is no honest degraded answer to "what does this
 * product cost". Guessing a price, using a stale one, or defaulting to zero would each
 * produce an order at the wrong amount, which is far worse than not producing one at all.
 * When a fallback would have to invent data the caller will act on, the right move is to
 * fail — so this one converts the failure into a clean 503 and stops. The "fallback"
 * methods below are named for the annotation that resolves them, but every one of them
 * throws; their only job is to record <em>why</em> the call failed before it does.
 *
 * <p><strong>Bulkhead: semaphore, not thread pool.</strong> Unlike {@code catalog}'s call
 * to {@code inventory}, nothing here needs to abandon a call mid-flight, so there is no
 * reason to pay for a thread hand-off. A semaphore bulkhead is a counter: it runs the call
 * on the caller's own thread and simply refuses to start more than {@code
 * max-concurrent-calls} of them at once. Cheaper, and it keeps the MDC and the transaction
 * context intact for free.
 *
 * <p>Every order line hits this method, so its limits are the loosest of the three
 * clients.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(@Qualifier("catalogRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        Boolean active
    ) {}

    @Retry(name = "catalog", fallbackMethod = "unavailable")
    @CircuitBreaker(name = "catalog")
    @RateLimiter(name = "catalog")
    @Bulkhead(name = "catalog")
    public ProductResponse getProductById(Long productId) {
        try {
            return restClient.get()
                    .uri("/api/products/{id}", productId)
                    .retrieve()
                    .body(ProductResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            // Catalog is healthy and says this product does not exist. A business
            // answer: never retried, never counted against the breaker.
            throw new DependencyBusinessException(
                    HttpStatus.NOT_FOUND, "Product " + productId + " not found in catalog");
        }
    }

    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private ProductResponse unavailable(Long productId, CallNotPermittedException e) {
        log.warn("[r4j] FALLBACK catalog product={} reason=circuit-open -- order cannot be priced", productId);
        throw new DependencyUnavailableException("catalog", true, "circuit is open");
    }

    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private ProductResponse unavailable(Long productId, RestClientException e) {
        log.warn("[r4j] FALLBACK catalog product={} reason=call-failed detail=\"{}\" -- order cannot be priced",
                productId, e.getClass().getSimpleName());
        throw new DependencyUnavailableException("catalog", false, e.getClass().getSimpleName());
    }

    /**
     * Refused by our own rate limiter or our own bulkhead. {@code catalog} may be in
     * perfect health — this is {@code sales} declining to add to whatever is already
     * going on, and the log says so rather than blaming the dependency.
     *
     * <p>Both share one method because the caller's outcome is identical: a 503 with a
     * {@code Retry-After}, which is the truthful answer in both cases. Only the log line
     * distinguishes them.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private ProductResponse unavailable(Long productId, RequestNotPermitted e) {
        log.warn("[r4j] FALLBACK catalog product={} reason=rate-limited -- self-imposed limit, "
                        + "catalog was not contacted", productId);
        throw new DependencyUnavailableException("catalog", false, "rate limited by sales");
    }

    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private ProductResponse unavailable(Long productId, BulkheadFullException e) {
        log.warn("[r4j] FALLBACK catalog product={} reason=bulkhead-full detail=\"{}\" -- "
                        + "concurrency cap reached, catalog was not contacted",
                productId, e.getMessage());
        throw new DependencyUnavailableException("catalog", false, "concurrency limit reached in sales");
    }
}
