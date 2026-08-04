package com.finalearth.catalog.controller;

import com.finalearth.catalog.dto.*;
import com.finalearth.catalog.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ProductDto> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getProductById(id));
    }

    @GetMapping("/products/{id}/stock")
    public ResponseEntity<ProductStockDto> getProductStock(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getProductStock(id));
    }
}


