package com.finalearth.sales.exception;

/**
 * A dependency could not be reached, or was reached and could not answer.
 *
 * <p>This is what the caller sees once Resilience4j has finished doing everything it can:
 * retries exhausted, or the circuit refusing the call outright. It carries which
 * dependency failed and which of those two happened, because the two demand different
 * responses from whoever is reading the log — an exhausted retry means the dependency is
 * struggling right now, an open circuit means it has been struggling for a while and
 * {@code sales} has stopped asking.
 */
public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;
    private final boolean circuitOpen;

    public DependencyUnavailableException(String dependency, boolean circuitOpen, String detail) {
        super(dependency + " unavailable" + (circuitOpen ? " (circuit open)" : "") + ": " + detail);
        this.dependency = dependency;
        this.circuitOpen = circuitOpen;
    }

    public String getDependency() {
        return dependency;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }
}
