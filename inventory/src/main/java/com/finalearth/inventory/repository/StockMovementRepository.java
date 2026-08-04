package com.finalearth.inventory.repository;

import com.finalearth.inventory.entity.MovementType;
import com.finalearth.inventory.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
    List<StockMovement> findByStockItemIdOrderByCreatedAtDesc(Long stockItemId);
    List<StockMovement> findByOrderId(Long orderId);
    List<StockMovement> findByOrderIdAndMovementType(Long orderId, MovementType movementType);
}
