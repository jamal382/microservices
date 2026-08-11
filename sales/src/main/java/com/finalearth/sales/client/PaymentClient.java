package com.finalearth.sales.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record PaymentRequest(Long orderId, BigDecimal amount, String method) {}

    public void processPayment(Long orderId, BigDecimal amount, String method) {
        try {
            restClient.post()
                    .uri("/api/payments")
                    .body(new PaymentRequest(orderId, amount, method))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.PAYMENT_REQUIRED) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Payment declined for order " + orderId);
            }
            throw new ResponseStatusException(e.getStatusCode(), "Payment failed: " + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment service unavailable: " + e.getMessage());
        }
    }
}
