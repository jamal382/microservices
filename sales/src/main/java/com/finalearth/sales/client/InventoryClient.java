package com.finalearth.sales.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class InventoryClient {

    private final RestClient restClient;

    public InventoryClient(@Value("${inventory.service.url:http://localhost:8082}") String inventoryUrl) {
        this.restClient = RestClient.builder().baseUrl(inventoryUrl).build();
    }

    public record ReserveItem(Long productId, Integer quantity) {}
    public record ReserveRequest(Long orderId, List<ReserveItem> items) {}

    public void reserveStock(Long orderId, List<ReserveItem> items) {
        try {
            restClient.post()
                    .uri("/api/stock/reserve")
                    .body(new ReserveRequest(orderId, items))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient stock for order " + orderId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory service unavailable: " + e.getMessage());
        }
    }
}
