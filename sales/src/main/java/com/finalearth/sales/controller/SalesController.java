package com.finalearth.sales.controller;

import com.finalearth.sales.dto.*;
import com.finalearth.sales.service.SalesService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
public class SalesController {

    private final SalesService salesService;

    public SalesController(SalesService salesService) {
        this.salesService = salesService;
    }

    /**
     * Accepts an order. Does not complete one.
     *
     * <p><strong>202, not 201, and the difference is not pedantry.</strong> 201 Created
     * promises the thing exists in its final form; this order exists but its outcome does
     * not. Stock has not been checked and the card has not been charged — both happen on
     * the topics, typically within a second, occasionally not. Returning 201 here would be
     * telling the caller the order succeeded at the exact moment nobody knows whether it
     * will.
     *
     * <p>What the caller does get is a durable order id and a {@code Location} header, and
     * the guarantee that the saga will run: the order and its {@code order.placed} event
     * were committed in one transaction, so there is no failure after this point that
     * quietly drops it. Poll {@code GET /api/orders/{id}} for the outcome — {@code
     * CONFIRMED}, {@code REJECTED}, {@code PAYMENT_FAILED} or {@code CANCELLED} are the
     * terminal states.
     */
    @PostMapping
    public ResponseEntity<OrderDto> placeOrder(@Valid @RequestBody CreateOrderRequest req) {
        OrderDto accepted = salesService.placeOrder(req);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(accepted.id())
                .toUri();
        return ResponseEntity.accepted().location(location).body(accepted);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(salesService.getOrderById(id));
    }
}
