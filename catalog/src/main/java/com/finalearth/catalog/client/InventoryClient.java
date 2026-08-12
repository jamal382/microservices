package com.finalearth.catalog.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private static final String INVENTORY_HOST = "inventory";

    private final RestClient restClient;

    public InventoryClient(@Qualifier("inventoryRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record StockResponse(
        Long id,
        Long productId,
        Integer quantityAvailable,
        Integer quantityReserved,
        Instant updatedAt
    ) {}

    public StockResponse getStockByProductId(Long productId) {
        try {
            long startNanos = System.nanoTime();
            ResponseEntity<StockResponse> response = restClient.get()
                    .uri("/api/stock/{productId}", productId)
                    .retrieve()
                    .toEntity(StockResponse.class);
            long millis = (System.nanoTime() - startNanos) / 1_000_000;

            // X-Instance-Id is stamped by inventory's logging filter. The host logged
            // here is the network alias we asked for, not the replica we reached --
            // Docker's DNS picked that per lookup -- so this header is how the caller
            // sees which of the two containers actually served the call.
            log.info("[catalog->inventory] GET http://{}/api/stock/{} -> {} ({} ms) served-by={} response={}",
                    INVENTORY_HOST, productId, response.getStatusCode().value(), millis,
                    response.getHeaders().getFirst("X-Instance-Id"), response.getBody());

            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No stock record for product " + productId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory service unavailable: " + e.getMessage());
        }
    }
}
