package com.finalearth.sales.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * A dependency answered, and the answer was "no".
 *
 * <p>The distinction this type draws is the one the whole Resilience4j configuration
 * rests on. "Product 999 does not exist", "there is not enough stock", "the card was
 * declined" are all <em>successful</em> conversations with a <em>healthy</em> service —
 * the request arrived, was understood, and was answered correctly. They are failures of
 * the order, not of the system.
 *
 * <p>Treating them as system failures does real damage in both directions: retrying a
 * declined card just declines it again more expensively, and letting declines accumulate
 * in a circuit breaker's window will eventually cut off a payment provider that never
 * had anything wrong with it — turning a bad afternoon for a few customers into an
 * outage for all of them.
 *
 * <p>Resilience4j is therefore configured with an explicit {@code record-exceptions}
 * allowlist rather than an ignore list. Only genuine transport failures count; this type
 * is not among them, so it passes straight through to the caller with its original
 * status.
 */
public class DependencyBusinessException extends RuntimeException {

    private final HttpStatusCode status;

    public DependencyBusinessException(HttpStatusCode status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatusCode getStatus() {
        return status == null ? HttpStatus.BAD_GATEWAY : status;
    }
}
