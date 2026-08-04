package com.finalearth.inventory.repository;

import com.finalearth.inventory.entity.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StockItemRepository extends JpaRepository<StockItem, Long> {
    Optional<StockItem> findByProductId(Long productId);
    boolean existsByProductId(Long productId);
}
