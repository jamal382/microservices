package com.finalearth.sales.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@Component
public class CatalogClient {

    private final RestClient restClient;

    public CatalogClient(@Qualifier("catalogRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        Boolean active
    ) {}

    public ProductResponse getProductById(Long productId) {
        try {
            return restClient.get()
                    .uri("/api/products/{id}", productId)
                    .retrieve()
                    .body(ProductResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product " + productId + " not found in catalog");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog service unavailable: " + e.getMessage());
        }
    }
}
