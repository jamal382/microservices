package com.finalearth.sales.client;

import com.finalearth.sales.exception.DependencyBusinessException;
import com.finalearth.sales.exception.DependencyUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
 * fail — so this one converts the failure into a clean 503 and stops.
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
}
