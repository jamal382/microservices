package com.finalearth.sales.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Outbound clients, all resolved from configured addresses.
 *
 * <p>Every address here comes from config and is resolved by Docker's embedded DNS.
 * {@code catalog} and {@code payment} are single containers, so their address is just a
 * container name. {@code inventory} is two containers sharing a network alias, so
 * {@code http://inventory:8082} resolves to two A records that Docker's DNS rotates.
 *
 * <p><strong>Every client is bounded.</strong> {@code sales} is the orchestrator: one
 * order touches three services in sequence, so an unbounded wait on any of them holds the
 * request — and its thread — hostage for as long as the slowest dependency feels like
 * taking. Timeouts convert that into a bounded, countable failure.
 *
 * <p>Payment gets a longer read timeout than the others. A card authorisation legitimately
 * takes longer than a database read, and a timeout set below a dependency's normal
 * response time is not resilience — it is a self-inflicted outage that also risks
 * abandoning work the callee actually completed.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient catalogRestClient(
            @Value("${catalog.service.url}") String url,
            @Value("${clients.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${clients.read-timeout-ms:2000}") long readTimeoutMs) {
        return build(url, connectTimeoutMs, readTimeoutMs);
    }

    private static RestClient build(String url, long connectTimeoutMs, long readTimeoutMs) {
        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
                // Carry this request's id to the callee so one order can be followed
                // through sales, catalog, inventory and payment with a single grep.
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
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }
}
