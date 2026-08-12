package com.finalearth.catalog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Outbound clients, resolved from configured addresses.
 *
 * <p>{@code inventory.service.url} points at {@code http://inventory:8082}, and that is a
 * real hostname: {@code inventory1} and {@code inventory2} share the {@code inventory}
 * network alias, so Docker's embedded DNS returns both containers' A records under that
 * one name and rotates them between lookups. Load balancing without a registry and
 * without a proxy in the path — successive calls land on different replicas, visible in
 * the {@code X-Instance-Id} header each reply carries.
 *
 * <p>The trade-off worth knowing: DNS reports existence, not readiness. It cannot tell a
 * started container from one that can actually serve, and a caller that has cached the A
 * record keeps using it until that entry lapses. HAProxy covers north–south traffic with
 * real health checks; this east–west hop has none, which is what makes it the candidate
 * for circuit breaking later.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient inventoryRestClient(@Value("${inventory.service.url}") String url) {
        return RestClient.builder().baseUrl(url).build();
    }
}
