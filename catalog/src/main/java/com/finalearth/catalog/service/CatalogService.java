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

        // Never throws for a dependency failure: InventoryClient's fallbacks convert every
        // one of them into a degraded lookup. The product half of this response is ours
        // and is always available, so answering with it beats failing the whole request
        // because a different service is unwell -- the entire argument for a fallback.
        //
        // The call returns a future because the time limiter and the thread-pool bulkhead
        // require one; this method is still synchronous and blocks here. `await` is static
        // on the client rather than an instance method precisely so that the join cannot
        // be moved inside the bean, where self-invocation would bypass the proxy and strip
        // all five patterns off the call. StockNotFoundException is unwrapped and rethrown
        // by it, and travels on to the exception handler as a 404.
        InventoryClient.StockLookup stock = InventoryClient.await(inventoryClient.getStockByProductId(id));

        if (stock.degraded()) {
            return new ProductStockDto(
                    product.getId(),
                    product.getSku(),
                    product.getName(),
                    product.getPrice(),
                    product.getActive(),
                    null,
                    null,
                    null,
                    ProductStockDto.StockStatus.UNAVAILABLE,
                    stock.reason());
        }

        boolean inStock = stock.quantityAvailable() != null && stock.quantityAvailable() > 0;

        return new ProductStockDto(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPrice(),
                product.getActive(),
                stock.quantityAvailable(),
                stock.quantityReserved(),
                inStock,
                inStock ? ProductStockDto.StockStatus.AVAILABLE : ProductStockDto.StockStatus.OUT_OF_STOCK,
                null);
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
