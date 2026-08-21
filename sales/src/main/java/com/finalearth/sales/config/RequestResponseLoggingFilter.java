package com.finalearth.sales.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Logs one line per HTTP request: service name, instance, endpoint, status and
 * response body. Actuator traffic is skipped so health-check polling does not
 * drown out the interesting lines.
 *
 * <p>Also stamps a short request id into the logging {@link MDC}, which the log pattern
 * prints on every line. One failed call can produce a dozen lines — the failure, each
 * retry, the state transition, the fallback, the response — and without a shared id
 * there is no way to tell which of several concurrent requests each line belongs to.
 * Incoming {@code X-Request-Id} is honoured so a caller's id carries across the hop.
 */
@Component
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "reqId";

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private static final int MAX_BODY_CHARS = 2000;
    private static final int MAX_REQUEST_ID_CHARS = 64;
    /** Anything outside this set is stripped before the id reaches the log. */
    private static final Pattern SAFE_ID = Pattern.compile("[^A-Za-z0-9._:-]");

    private final String serviceName;
    private final String instanceId;

    public RequestResponseLoggingFilter(@Value("${spring.application.name}") String serviceName) {
        this.serviceName = serviceName;
        // In a container this is the short container id, which is what
        // `docker compose ps` shows; on the host it is just the machine name.
        this.instanceId = System.getenv().getOrDefault("HOSTNAME", "local");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        MDC.put(REQUEST_ID_MDC_KEY, resolveRequestId(request));

        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, wrapped);
        } finally {
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            String query = request.getQueryString();
            log.info("[{}@{}] {} {}{} -> {} ({} ms) response={}",
                    serviceName,
                    instanceId,
                    request.getMethod(),
                    request.getRequestURI(),
                    query == null ? "" : "?" + query,
                    wrapped.getStatus(),
                    millis,
                    bodyOf(wrapped));
            // Must run last: the cached body is only written to the real
            // response here, so skipping this returns an empty payload.
            wrapped.copyBodyToResponse();
            // Tomcat reuses threads, so leaving this set would mislabel the next
            // request that happens to land on this one.
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    /**
     * An inbound id is kept as the caller sent it. Shortening it would be a quiet
     * betrayal: the whole point of a correlation id is that the same string identifies
     * the request on both sides of the hop, and an id trimmed here no longer matches the
     * one the caller is searching its own logs for.
     *
     * <p>It is still bounded and filtered, because this value is attacker-controlled and
     * ends up in log output — a newline in a header would otherwise let a caller forge
     * whole log lines.
     */
    private static String resolveRequestId(HttpServletRequest request) {
        String inbound = request.getHeader(REQUEST_ID_HEADER);
        if (inbound != null && !inbound.isBlank()) {
            String safe = SAFE_ID.matcher(inbound).replaceAll("");
            if (!safe.isEmpty()) {
                return safe.length() > MAX_REQUEST_ID_CHARS ? safe.substring(0, MAX_REQUEST_ID_CHARS) : safe;
            }
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String bodyOf(ContentCachingResponseWrapper response) {
        byte[] content = response.getContentAsByteArray();
        if (content.length == 0) {
            return "<empty>";
        }
        String body = new String(content, StandardCharsets.UTF_8);
        return body.length() > MAX_BODY_CHARS
                ? body.substring(0, MAX_BODY_CHARS) + "...<truncated>"
                : body;
    }
}
