package com.finalearth.inventory.lab;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable, in-memory switch that makes this replica misbehave on demand.
 *
 * <p>Purely a teaching device. Real systems do not ship an endpoint that breaks them —
 * but without one there is nothing here that can open a circuit breaker. Stopping a
 * container is not a substitute: a stopped container refuses connections instantly, so
 * the caller re-resolves DNS and lands on the healthy replica without ever failing. The
 * failures that actually matter — slow replies, intermittent 500s, a process that is up
 * but not working — have no equivalent in {@code docker compose}.
 *
 * <p>State is per-JVM and deliberately not shared between replicas. {@code inventory1}
 * and {@code inventory2} publish separate host ports, so you can poison one and leave the
 * other healthy, which is what makes partial-failure scenarios reproducible.
 */
public class FaultState {

    public enum Mode {
        /** Behave normally. */
        OK,
        /** Fail every request with 500 before it reaches the controller. */
        ERROR,
        /** Answer correctly, but only after {@code delayMs}. */
        SLOW,
        /** Fail a {@code failureRate} percentage of requests, at random. */
        FLAKY
    }

    public record Settings(Mode mode, long delayMs, int failureRate) {

        public static Settings ok() {
            return new Settings(Mode.OK, 0, 0);
        }

        /** True when this request should be failed, re-rolled per call for FLAKY. */
        boolean shouldFail() {
            return switch (mode) {
                case ERROR -> true;
                case FLAKY -> ThreadLocalRandom.current().nextInt(100) < failureRate;
                case OK, SLOW -> false;
            };
        }
    }

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.ok());

    public Settings get() {
        return settings.get();
    }

    public void set(Settings next) {
        settings.set(next);
    }

    public void reset() {
        settings.set(Settings.ok());
    }
}
