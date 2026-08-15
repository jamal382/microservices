package com.finalearth.catalog.config;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

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
 *
 * <p>All five of the patterns on {@code InventoryClient} report here. Reading the log for
 * a single request id top to bottom gives the whole decision chain: which layer refused,
 * how many times it tried, when it stopped trying, and what the caller finally got.
 */
@Configuration
public class ResilienceEventLogger {

    private static final Logger log = LoggerFactory.getLogger("r4j");

    @Bean
    public RegistryEventConsumer<CircuitBreaker> circuitBreakerLogger() {
        return onEachInstance(ResilienceEventLogger::attach);
    }

    @Bean
    public RegistryEventConsumer<Retry> retryLogger() {
        return onEachInstance(ResilienceEventLogger::attach);
    }

    @Bean
    public RegistryEventConsumer<RateLimiter> rateLimiterLogger() {
        return onEachInstance(ResilienceEventLogger::attach);
    }

    @Bean
    public RegistryEventConsumer<ThreadPoolBulkhead> threadPoolBulkheadLogger() {
        return onEachInstance(ResilienceEventLogger::attach);
    }

    @Bean
    public RegistryEventConsumer<TimeLimiter> timeLimiterLogger() {
        return onEachInstance(ResilienceEventLogger::attach);
    }

    /**
     * The registry hook is the same three methods every time — an instance appears, or is
     * replaced, and we attach listeners to it. Only the attach step differs per pattern.
     */
    private static <E> RegistryEventConsumer<E> onEachInstance(Consumer<E> attach) {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<E> event) {
                attach.accept(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<E> event) {
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<E> event) {
                attach.accept(event.getNewEntry());
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

        // Business outcomes (a 404) and self-inflicted rejections (rate limiter, bulkhead)
        // both land here. Neither is evidence about the dependency's health, which is the
        // whole reason they are ignored rather than left to count as successes.
        cb.getEventPublisher().onIgnoredError(e -> log.info(
                "[r4j] CIRCUIT '{}' IGNORED {} -- not counted as a failure or a success",
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

    private static void attach(RateLimiter limiter) {
        String name = limiter.getName();

        // Only the refusals are logged. A rate limiter permits the overwhelming majority
        // of calls, and a line per permitted call would be one line per request -- noise
        // that would bury everything else in this file.
        limiter.getEventPublisher().onFailure(e -> log.warn(
                "[r4j] RATELIMITER '{}' REJECTED a call -- no permit available "
                        + "(availablePermissions={} waitingThreads={})",
                name,
                limiter.getMetrics().getAvailablePermissions(),
                limiter.getMetrics().getNumberOfWaitingThreads()));
    }

    private static void attach(ThreadPoolBulkhead bulkhead) {
        String name = bulkhead.getName();

        // Rejection means both the pool and its queue were full. The metrics say which
        // resource ran out, which is the difference between "raise the pool size" and
        // "the dependency is too slow and the queue is just hiding it".
        bulkhead.getEventPublisher().onCallRejected(e -> log.warn(
                "[r4j] BULKHEAD '{}' REJECTED a call -- pool and queue are full "
                        + "(threads={}/{} queueDepth={}/{})",
                name,
                bulkhead.getMetrics().getActiveThreadCount(),
                bulkhead.getMetrics().getMaximumThreadPoolSize(),
                bulkhead.getMetrics().getQueueDepth(),
                bulkhead.getMetrics().getQueueCapacity()));

        // Logged at debug: useful when watching saturation build up in real time, far too
        // chatty to leave on. Enable with `logging.level.r4j=DEBUG`.
        bulkhead.getEventPublisher().onCallPermitted(e -> log.debug(
                "[r4j] BULKHEAD '{}' permitted a call (threads={}/{} queueDepth={}/{})",
                name,
                bulkhead.getMetrics().getActiveThreadCount(),
                bulkhead.getMetrics().getMaximumThreadPoolSize(),
                bulkhead.getMetrics().getQueueDepth(),
                bulkhead.getMetrics().getQueueCapacity()));
    }

    private static void attach(TimeLimiter limiter) {
        String name = limiter.getName();

        // The deadline fired. Worth stating plainly that the work was abandoned rather
        // than stopped: a thread blocked in a socket read cannot be interrupted, so it
        // stays busy until the RestClient read timeout releases it.
        limiter.getEventPublisher().onTimeout(e -> log.warn(
                "[r4j] TIMELIMITER '{}' DEADLINE EXCEEDED -- the caller stopped waiting; "
                        + "the attempt may still be running on a bulkhead thread", name));

        limiter.getEventPublisher().onError(e -> log.warn(
                "[r4j] TIMELIMITER '{}' saw the call fail before the deadline: {}",
                name, e.getThrowable().getClass().getSimpleName()));
    }

    /** Resilience4j reports -1 when the sliding window has not filled yet. */
    private static String pct(float rate) {
        return rate < 0 ? "n/a" : String.format("%.1f%%", rate);
    }
}
