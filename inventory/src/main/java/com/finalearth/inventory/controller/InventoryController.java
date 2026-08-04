package com.finalearth.inventory.controller;

import com.finalearth.inventory.dto.*;
import com.finalearth.inventory.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stock")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<StockItemDto> getStock(@PathVariable Long productId) {
        return ResponseEntity.ok(inventoryService.getStockByProductId(productId));
    }

    @PostMapping("/reserve")
    public ResponseEntity<ReservationResultDto> reserveStock(@Valid @RequestBody ReserveStockRequest req) {
        return ResponseEntity.ok(inventoryService.reserveStock(req));
    }
}


