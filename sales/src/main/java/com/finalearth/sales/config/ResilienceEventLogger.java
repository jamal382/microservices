package com.finalearth.sales.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns Resilience4j's internal decisions into log lines.
 *
 * <p>Without this, resilience is invisible: a retried call and a first-time success look
 * identical from outside, and the moment a circuit opens is buried in a metrics endpoint
 * nobody is watching. Every line below is emitted by Resilience4j itself as it decides
 * what to do, so the log reads as a running commentary on the library's reasoning rather
 * than a reconstruction after the fact.
 *
 * <p>Attached through a {@link RegistryEventConsumer}, which fires as each named instance
 * is created. Registering listeners on a specific instance at startup would miss the ones
 * Resilience4j creates lazily on first use.
 */
@Configuration
public class ResilienceEventLogger {

    private static final Logger log = LoggerFactory.getLogger("r4j");

    @Bean
    public RegistryEventConsumer<CircuitBreaker> circuitBreakerLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> event) {
                attach(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> event) {
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> event) {
                attach(event.getNewEntry());
            }
        };
    }

    @Bean
    public RegistryEventConsumer<Retry> retryLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<Retry> event) {
                attach(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<Retry> event) {
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<Retry> event) {
                attach(event.getNewEntry());
            }
        };
    }

    private static void attach(CircuitBreaker cb) {
        String name = cb.getName();

        // The state transition is the headline event: the exact moment the breaker
        // stopped trusting the dependency, with the failure rate that convinced it.
        cb.getEventPublisher().onStateTransition(e -> log.warn(
                "[r4j] CIRCUIT '{}' {} -> {} (failureRate={} slowCallRate={} buffered={})",
                name,
                e.getStateTransition().getFromState(),
                e.getStateTransition().getToState(),
                pct(cb.getMetrics().getFailureRate()),
                pct(cb.getMetrics().getSlowCallRate()),
                cb.getMetrics().getNumberOfBufferedCalls()));

        // Deliberately does not print a running failure count: Resilience4j updates the
        // sliding window *after* this listener returns, so any ratio read here is a call
        // behind and reads as an off-by-one. The authoritative numbers arrive with the
        // state transition below, and live at /actuator/circuitbreakers.
        cb.getEventPublisher().onError(e -> log.warn(
                "[r4j] CIRCUIT '{}' recorded a FAILURE after {} ms: {}",
                name,
                e.getElapsedDuration().toMillis(),
                e.getThrowable().getClass().getSimpleName()));

        // Fires when the breaker is open and refuses to let a call through at all.
        cb.getEventPublisher().onCallNotPermitted(e -> log.warn(
                "[r4j] CIRCUIT '{}' REJECTED a call -- state=OPEN, inventory was not contacted", name));

        cb.getEventPublisher().onIgnoredError(e -> log.info(
                "[r4j] CIRCUIT '{}' IGNORED {} -- business outcome, not counted as a failure",
                name, e.getThrowable().getClass().getSimpleName()));
    }

    private static void attach(Retry retry) {
        String name = retry.getName();

        // The count is what makes "how many retries happened" answerable. Attempt
        // numbering is 1-based and counts *retries*, so attempt 1 is the second call.
        retry.getEventPublisher().onRetry(e -> log.warn(
                "[r4j] RETRY '{}' attempt {} after {}: waiting {} ms before trying again",
                name,
                e.getNumberOfRetryAttempts(),
                e.getLastThrowable() == null ? "unknown" : e.getLastThrowable().getClass().getSimpleName(),
                e.getWaitInterval().toMillis()));

        retry.getEventPublisher().onError(e -> log.warn(
                "[r4j] RETRY '{}' GAVE UP after {} attempts: {}",
                name,
                e.getNumberOfRetryAttempts(),
                e.getLastThrowable() == null ? "unknown" : e.getLastThrowable().toString()));

        retry.getEventPublisher().onSuccess(e -> log.warn(
                "[r4j] RETRY '{}' SUCCEEDED on retry {} -- the failure was transient",
                name, e.getNumberOfRetryAttempts()));
    }

    /** Resilience4j reports -1 when the sliding window has not filled yet. */
    private static String pct(float rate) {
        return rate < 0 ? "n/a" : String.format("%.1f%%", rate);
    }
}
