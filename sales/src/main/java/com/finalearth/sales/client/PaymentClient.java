package com.finalearth.sales.client;

import com.finalearth.sales.exception.DependencyBusinessException;
import com.finalearth.sales.exception.DependencyUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
 * Card authorisation against {@code payment}.
 *
 * <p><strong>Retries: no. Fallback: no.</strong> The strictest policy of the three, and
 * the clearest illustration that resilience is not a set of features you turn all the way
 * up.
 *
 * <p>A retry on a lost payment response risks charging a customer twice — the worst
 * outcome available to this system, and worse than the failure it would be papering over.
 * {@code payment} does deduplicate by {@code orderId}, so a retry would in fact be safe
 * today; the annotation is still omitted, because that safety is a property of the
 * callee's current implementation rather than a guarantee of its contract, and a retry
 * configured on that basis silently becomes a double-charge the day the implementation
 * changes.
 *
 * <p>A fallback is equally out of the question: there is no degraded version of "money
 * moved". Returning a fake success would confirm an order that was never paid for.
 * Resilience here means failing quickly and truthfully, so the circuit breaker stands
 * alone — it stops {@code sales} from queueing threads against a dead payment provider,
 * and nothing more.
 */
@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestClient restClient;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record PaymentRequest(Long orderId, BigDecimal amount, String method) {}

    @CircuitBreaker(name = "payment", fallbackMethod = "unavailable")
    public void processPayment(Long orderId, BigDecimal amount, String method) {
        try {
            restClient.post()
                    .uri("/api/payments")
                    .body(new PaymentRequest(orderId, amount, method))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // A decline is a healthy provider doing its job. It must not be retried and
            // must not count toward the breaker -- a run of declined cards says nothing
            // about whether the provider is up.
            if (e.getStatusCode() == HttpStatus.PAYMENT_REQUIRED) {
                throw new DependencyBusinessException(
                        HttpStatus.PAYMENT_REQUIRED, "Payment declined for order " + orderId);
            }
            throw new DependencyBusinessException(e.getStatusCode(), "Payment failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private void unavailable(Long orderId, BigDecimal amount, String method, CallNotPermittedException e) {
        log.warn("[r4j] FALLBACK payment order={} reason=circuit-open -- no charge was attempted", orderId);
        throw new DependencyUnavailableException("payment", true, "circuit is open");
    }

    @SuppressWarnings("unused") // resolved by name by Resilience4j
    private void unavailable(Long orderId, BigDecimal amount, String method, RestClientException e) {
        log.warn("[r4j] FALLBACK payment order={} reason=call-failed detail=\"{}\" -- "
                        + "charge state on the provider is UNKNOWN, do not assume it did not happen",
                orderId, e.getClass().getSimpleName());
        throw new DependencyUnavailableException("payment", false, e.getClass().getSimpleName());
    }
}
