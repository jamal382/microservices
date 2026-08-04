package com.finalearth.inventory.service;

import com.finalearth.inventory.dto.*;
import com.finalearth.inventory.entity.MovementType;
import com.finalearth.inventory.entity.StockItem;
import com.finalearth.inventory.entity.StockMovement;
import com.finalearth.inventory.exception.InsufficientStockException;
import com.finalearth.inventory.repository.StockItemRepository;
import com.finalearth.inventory.repository.StockMovementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository stockMovementRepository;

    public InventoryService(StockItemRepository stockItemRepository, StockMovementRepository stockMovementRepository) {
        this.stockItemRepository = stockItemRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    public StockItemDto getStockByProductId(Long productId) {
        StockItem item = stockItemRepository.findByProductId(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock item not found for product ID: " + productId));
        return mapToItemDto(item);
    }

    @Transactional
    public ReservationResultDto reserveStock(ReserveStockRequest req) {
        // Step 1: Validate stock for ALL items first (all-or-nothing check)
        List<StockItem> itemsToReserve = new ArrayList<>();
        for (ReservationItemRequest itemReq : req.items()) {
            StockItem item = stockItemRepository.findByProductId(itemReq.productId())
                    .orElseThrow(() -> new InsufficientStockException(itemReq.productId(), 0, itemReq.quantity()));

            if (item.getQuantityAvailable() < itemReq.quantity()) {
                throw new InsufficientStockException(itemReq.productId(), item.getQuantityAvailable(), itemReq.quantity());
            }
            itemsToReserve.add(item);
        }

        // Step 2: Apply reservations and record movements
        List<ReservationDetailDto> reservationDetails = new ArrayList<>();
        for (int i = 0; i < req.items().size(); i++) {
            ReservationItemRequest itemReq = req.items().get(i);
            StockItem item = itemsToReserve.get(i);

            item.setQuantityAvailable(item.getQuantityAvailable() - itemReq.quantity());
            item.setQuantityReserved(item.getQuantityReserved() + itemReq.quantity());
            stockItemRepository.save(item);

            StockMovement movement = new StockMovement();
            movement.setStockItem(item);
            movement.setOrderId(req.orderId());
            movement.setMovementType(MovementType.RESERVE);
            movement.setQuantity(itemReq.quantity());
            stockMovementRepository.save(movement);

            reservationDetails.add(new ReservationDetailDto(item.getProductId(), itemReq.quantity(), item.getQuantityAvailable()));
        }

        return new ReservationResultDto(req.orderId(), "RESERVED", reservationDetails);
    }

    private StockItemDto mapToItemDto(StockItem item) {
        return new StockItemDto(
                item.getId(),
                item.getProductId(),
                item.getQuantityAvailable(),
                item.getQuantityReserved(),
                item.getUpdatedAt()
        );
    }
}
