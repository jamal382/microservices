package com.finalearth.catalog.service;

import com.finalearth.catalog.client.InventoryClient;
import com.finalearth.catalog.dto.*;
import com.finalearth.catalog.entity.Product;
import com.finalearth.catalog.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final ProductRepository productRepository;
    private final InventoryClient inventoryClient;

    public CatalogService(ProductRepository productRepository, InventoryClient inventoryClient) {
        this.productRepository = productRepository;
        this.inventoryClient = inventoryClient;
    }

    public ProductDto getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found with id: " + id));
        return mapToDto(product);
    }

    // No transaction: the inventory call is remote, and we must not hold a DB
    // connection open for the duration of an HTTP round-trip.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductStockDto getProductStock(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found with id: " + id));

        InventoryClient.StockResponse stock = inventoryClient.getStockByProductId(id);

        return new ProductStockDto(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPrice(),
                product.getActive(),
                stock.quantityAvailable(),
                stock.quantityReserved(),
                stock.quantityAvailable() != null && stock.quantityAvailable() > 0
        );
    }

    private ProductDto mapToDto(Product p) {
        return new ProductDto(
                p.getId(),
                p.getSku(),
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getCategory().getId(),
                p.getCategory().getName(),
                p.getActive(),
                p.getCreatedAt()
        );
    }
}
