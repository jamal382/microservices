package com.finalearth.catalog.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Outbound clients, resolved from configured addresses.
 *
 * <p>{@code inventory.service.url} points at {@code http://inventory:8082}, and that is a
 * real hostname: {@code inventory1} and {@code inventory2} share the {@code inventory}
 * network alias, so Docker's embedded DNS returns both containers' A records under that
 * one name and rotates them between lookups.
 *
 * <p><strong>Timeouts are the foundation everything else rests on.</strong> A
 * {@code RestClient} with no timeout will wait forever, and "forever" is the worst
 * possible failure mode: the calling thread is held, Tomcat's pool drains, and
 * {@code catalog} stops serving requests that have nothing to do with inventory. A
 * circuit breaker cannot save you here either — it only counts calls that <em>finish</em>,
 * so a call that never returns is never recorded as a failure and the circuit stays
 * closed while the service dies. Bounding the wait is what converts an unbounded hang
 * into an ordinary error the breaker can see and count.
 *
 * <p>The read timeout is deliberately shorter than the retry budget: three attempts at
 * two seconds each is a six-second worst case, which is a delay a caller can absorb.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient inventoryRestClient(
            @Value("${inventory.service.url}") String url,
            @Value("${clients.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${clients.read-timeout-ms:2000}") long readTimeoutMs) {

        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
                // Carry this request's id to the callee so both services' logs can be
                // filtered by the same string.
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestResponseLoggingFilter.REQUEST_ID_MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().add(RequestResponseLoggingFilter.REQUEST_ID_HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(long connectTimeoutMs, long readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Connect: how long to wait for a TCP handshake. Short — on this network a
        // reachable container answers in single-digit milliseconds, so anything
        // slower means the address is wrong or the host is gone.
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        // Read: how long to wait for the response once connected. This is the one
        // that catches a replica which accepted the socket and then stalled.
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }
}
