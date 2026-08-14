package com.finalearth.inventory.lab;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the current {@link FaultState} to inbound business requests.
 *
 * <p>Runs ahead of everything else so a fault looks like an infrastructure problem rather
 * than an application one — which is the point, since that is what a caller's circuit
 * breaker is supposed to react to.
 *
 * <p>Two paths are deliberately exempt. {@code /actuator/**} stays healthy so the
 * container's own health check does not fail and take the replica out of Compose's view;
 * the interesting scenario is a service that looks alive and answers wrongly. And
 * {@code /api/lab/**} stays reachable so you can always switch the fault back off.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FaultInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionFilter.class);

    private final FaultState faultState;

    public FaultInjectionFilter(FaultState faultState) {
        this.faultState = faultState;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator") || uri.startsWith("/api/lab");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        FaultState.Settings settings = faultState.get();

        if (settings.mode() == FaultState.Mode.SLOW && settings.delayMs() > 0) {
            log.warn("[fault-injection] delaying {} {} by {} ms",
                    request.getMethod(), request.getRequestURI(), settings.delayMs());
            try {
                Thread.sleep(settings.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while injecting delay", e);
            }
        }

        if (settings.shouldFail()) {
            log.warn("[fault-injection] failing {} {} with 500 (mode={})",
                    request.getMethod(), request.getRequestURI(), settings.mode());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"title\":\"Injected fault\",\"status\":500,"
                    + "\"detail\":\"inventory is failing on purpose (mode=" + settings.mode() + ")\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
