package com.finalearth.sales.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Outbound clients, all resolved from configured addresses.
 *
 * <p>Sales is deliberately <em>not</em> a registry client. Only {@code catalog} looks
 * services up through Eureka; everything here uses an address supplied by config.
 * {@code catalog} and {@code payment} are single containers, so their address is a
 * container name. {@code inventory} is two containers sharing a Docker network alias,
 * so {@code http://inventory:8082} resolves to two A records that Docker's DNS
 * rotates — load balancing without a registry, and without a proxy.
 *
 * <p>The contrast with {@code catalog}'s config is the lesson: Docker DNS balances
 * across whatever answers the alias, but it cannot tell a started container from a
 * ready one, and a client that has cached the A record will keep using it. A registry
 * knows about readiness and status; DNS only knows about existence.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient catalogRestClient(@Value("${catalog.service.url}") String url) {
        return RestClient.builder().baseUrl(url).build();
    }

    @Bean
    public RestClient inventoryRestClient(@Value("${inventory.service.url}") String url) {
        return RestClient.builder().baseUrl(url).build();
    }

    @Bean
    public RestClient paymentRestClient(@Value("${payment.service.url}") String url) {
        return RestClient.builder().baseUrl(url).build();
    }
}
