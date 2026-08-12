package com.finalearth.sales.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Outbound clients, all resolved from configured addresses.
 *
 * <p>Every address here comes from config and is resolved by Docker's embedded DNS.
 * {@code catalog} and {@code payment} are single containers, so their address is just a
 * container name. {@code inventory} is two containers sharing a network alias, so
 * {@code http://inventory:8082} resolves to two A records that Docker's DNS rotates —
 * load balancing without a proxy in the path.
 *
 * <p>These are <em>east–west</em> calls and deliberately do not go through HAProxy.
 * Routing them through the gateway would add a hop through one shared process for
 * traffic that never leaves the network, and would make the gateway a single point of
 * failure for internal traffic as well as external. The cost of keeping them direct is
 * that DNS balances across whatever answers the alias without knowing whether it is
 * <em>ready</em> — it reports existence, not health. HAProxy health-checks north–south
 * traffic; this hop trusts the callee.
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
