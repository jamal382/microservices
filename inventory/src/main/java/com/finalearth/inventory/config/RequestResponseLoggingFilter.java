package com.finalearth.inventory.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Logs one line per HTTP request: service name, instance, endpoint, status and
 * response body. Actuator traffic is skipped so health-check polling does not
 * drown out the interesting lines.
 *
 * <p>Inventory is the service that gets scaled to several replicas, so it also
 * stamps its instance id onto every response as {@code X-Instance-Id}. Neither
 * balancing path in front of it — HAProxy for external callers, Docker DNS for
 * internal ones — tells the caller which replica it landed on, so this header is
 * the only way to tell the two apart from the client side.
 */
@Component
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    public static final String INSTANCE_HEADER = "X-Instance-Id";

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private static final int MAX_BODY_CHARS = 2000;

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
        // Set before the chain runs: once the response commits, headers are frozen.
        wrapped.setHeader(INSTANCE_HEADER, instanceId);

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
        }
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
