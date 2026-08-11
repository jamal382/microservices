package com.finalearth.catalog.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the outbound inventory client on top of a {@link LoadBalanced} builder.
 *
 * <p>{@code http://inventory} is a <em>service id</em>, not a hostname — there is no
 * DNS entry for it. The load-balancer interceptor recognises the single-segment
 * host, asks its cached copy of the Eureka registry for the live instances of that
 * application, picks one (round-robin by default) and rewrites the URL to a real
 * {@code ip:port} before the request leaves the JVM. With two inventory replicas
 * registered, successive calls land on different ones — visible in the
 * {@code X-Instance-Id} header each reply carries.
 *
 * <p>This replaces the Phase 1 {@code inventory.service.url} property.
 */
@Configuration
public class RestClientConfig {

    /**
     * {@code defaultCandidate = false} is load-bearing. The Eureka client's own HTTP
     * transport injects whatever {@code RestClient.Builder} it can find by type; if it
     * finds this one it tries to resolve the registry's address <em>through the
     * registry</em>, and startup fails with "No servers available for service: eureka".
     * Excluding the bean from by-type resolution leaves Eureka the plain
     * auto-configured builder, while injection points that ask for it by the
     * {@code @LoadBalanced} qualifier still get it.
     */
    @Bean(defaultCandidate = false)
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    // clone() because baseUrl mutates the builder in place.
    @Bean
    public RestClient inventoryRestClient(@LoadBalanced RestClient.Builder builder) {
        return builder.clone().baseUrl("http://inventory").build();
    }
}
