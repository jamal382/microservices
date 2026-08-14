package com.finalearth.catalog.client;

import com.finalearth.catalog.exception.StockNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

/**
 * Calls {@code inventory} for stock levels, and decides what {@code catalog} does when
 * that call does not work.
 *
 * <h2>How the two annotations stack</h2>
 * Resilience4j applies its aspects in a fixed order, and {@code @Retry} is the outermost
 * one. The nesting is therefore:
 *
 * <pre>
 *   Retry {
 *       CircuitBreaker {
 *           actual HTTP call
 *       }
 *   }
 * </pre>
 *
 * Two consequences follow, and both are easy to get wrong. First, <em>every retry attempt
 * passes through the breaker and is recorded by it</em> — three attempts against a dead
 * replica put three failures into the sliding window, not one, so a retrying caller trips
 * its own breaker roughly three times faster than an inspection of the config suggests.
 * Second, once the breaker is open it throws {@link CallNotPermittedException} instantly,
 * and without the {@code ignore-exceptions} entry in the properties the retry layer would
 * dutifully retry <em>that</em> — burning the full retry budget on a call that was never
 * going to leave the process.
 *
 * <p>The fallback lives on {@code @Retry} because the outermost aspect is the one that
 * sees every way this can end: retries exhausted, or the breaker refusing outright.
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
    public StockLookup getStockByProductId(Long productId) {
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
            // breaker are both configured to ignore, so it never trips anything.
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

        return StockLookup.of(response.getBody());
    }

    /**
     * The breaker is open, so this call never left the process.
     *
     * <p>Separate from the exhausted-retries fallback purely so the log line can say
     * which of the two happened — from the caller's side they are indistinguishable in
     * the response, but they mean very different things. This one is the system
     * protecting a dependency it has already decided is unhealthy, and it costs
     * microseconds rather than the full timeout budget.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private StockLookup stockUnavailable(Long productId, CallNotPermittedException e) {
        log.warn("[r4j] FALLBACK product={} reason=circuit-open detail=\"{}\" "
                        + "-- call rejected without attempting inventory",
                productId, e.getMessage());
        return StockLookup.degraded("inventory circuit is open");
    }

    /**
     * Every attempt failed. {@code e} is the last one; the retry event log holds the rest.
     */
    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private StockLookup stockUnavailable(Long productId, RestClientException e) {
        log.warn("[r4j] FALLBACK product={} reason=call-failed detail=\"{}: {}\" "
                        + "-- all attempts exhausted",
                productId, e.getClass().getSimpleName(), rootMessage(e));
        return StockLookup.degraded("inventory did not respond");
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
