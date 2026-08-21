package com.finalearth.catalog.config;

import io.github.resilience4j.core.ContextPropagator;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Carries the SLF4J {@link MDC} across the thread-pool bulkhead boundary.
 *
 * <h2>Why this is needed at all</h2>
 * {@code MDC} is backed by a {@link ThreadLocal}. A semaphore bulkhead runs the call on the
 * caller's own thread, so the MDC is simply still there. A <em>thread-pool</em> bulkhead
 * does not: it hands the work to a thread from its own pool, and that thread has never
 * seen this request. Without this class the {@code reqId} in
 * {@code logging.pattern.correlation} comes out empty for every line the inventory call
 * produces — which is exactly the lines you most want to correlate, since a failed call
 * emits the error, each retry, the breaker transition and the fallback.
 *
 * <p>Resilience4j instantiates this by class name from
 * {@code resilience4j.thread-pool-bulkhead.instances.inventory.context-propagators[0]}, so
 * it needs a public no-arg constructor and must not be a Spring bean.
 *
 * <h2>The three phases, and why one of them does nothing</h2>
 * {@link #retrieve()} runs on the <em>calling</em> thread before the task is submitted;
 * {@link #copy()} and {@link #clear()} run on the <em>pool</em> thread, around the task.
 *
 * <p>The obvious implementation of {@link #clear()} — wipe the MDC when the task ends — is
 * wrong here, and the reason is worth understanding. Resilience4j runs it <em>before</em>
 * the future completes, and the aspects stacked outside the bulkhead do their work in that
 * future's completion callbacks: the circuit breaker records the outcome there, the time
 * limiter fires its events there, the retry decides whether to go again there. Those
 * callbacks run on this same pool thread, after the clear. Wiping the MDC therefore
 * removes the request id from precisely the lines the id exists for — the ones explaining
 * what the library decided.
 *
 * <p>So the context is left in place and {@link #copy()} is made responsible for hygiene
 * instead: it clears unconditionally before installing the incoming context, so a pool
 * thread can never start a task wearing the previous one's identity. Between tasks the
 * stale map simply sits there, bounded by the pool size, labelling the callbacks that
 * genuinely belong to it.
 */
public class MdcContextPropagator implements ContextPropagator<Map<String, String>> {

    @Override
    public Supplier<Optional<Map<String, String>>> retrieve() {
        return () -> Optional.ofNullable(MDC.getCopyOfContextMap());
    }

    @Override
    public Consumer<Optional<Map<String, String>>> copy() {
        // Clears unconditionally, including when the caller had no context to give us.
        // Guarding this behind ifPresent would leave the previous task's request id on the
        // thread for a task that has none -- the one case where the id would be an
        // outright lie rather than merely missing.
        return context -> {
            MDC.clear();
            context.ifPresent(MDC::setContextMap);
        };
    }

    @Override
    public Consumer<Optional<Map<String, String>>> clear() {
        // Intentionally empty -- see the class javadoc. Resilience4j calls this before the
        // future completes, so clearing here would strip the request id from the circuit
        // breaker, time limiter and retry events that fire in the completion callbacks.
        return context -> { };
    }
}
