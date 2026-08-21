package com.finalearth.inventory.service;

import com.finalearth.inventory.dto.*;
import com.finalearth.inventory.entity.MovementType;
import com.finalearth.inventory.entity.StockItem;
import com.finalearth.inventory.entity.StockMovement;
import com.finalearth.inventory.exception.InsufficientStockException;
import com.finalearth.inventory.repository.StockItemRepository;
import com.finalearth.inventory.repository.StockMovementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

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

    // ------------------------------------------------------------------
    // The event path -- same stock, different contract
    // ------------------------------------------------------------------

    /**
     * Reserve stock for an order that arrived on {@code order.placed}.
     *
     * <p>The rules are identical to the HTTP path — all-or-nothing across every line, same
     * movements recorded — and only the failure contract differs: this one reports a
     * shortfall by returning it rather than by throwing, so the caller's transaction stays
     * usable and the rejection can be written to the outbox. See {@link ReservationOutcome}.
     *
     * <p>No de-duplication happens here. The listener has already checked the inbox inside
     * this same transaction, which is the right place for it: idempotency is a property of
     * <em>having consumed the event</em>, not of the stock operation, and every consumer in
     * this system enforces it identically rather than each inventing its own scheme.
     */
    @Transactional
    public ReservationOutcome reserveForOrder(Long orderId, List<ReservationItemRequest> items) {
        List<StockItem> itemsToReserve = new ArrayList<>();
        for (ReservationItemRequest itemReq : items) {
            StockItem item = stockItemRepository.findByProductId(itemReq.productId()).orElse(null);
            if (item == null) {
                return ReservationOutcome.rejected(
                        "No stock record for product " + itemReq.productId());
            }
            if (item.getQuantityAvailable() < itemReq.quantity()) {
                return ReservationOutcome.rejected(String.format(
                        "Product %d has %d available, %d requested",
                        itemReq.productId(), item.getQuantityAvailable(), itemReq.quantity()));
            }
            itemsToReserve.add(item);
        }

        for (int i = 0; i < items.size(); i++) {
            applyMovement(itemsToReserve.get(i), orderId, items.get(i).quantity(), MovementType.RESERVE);
        }

        log.info("[stock] order={} RESERVED {} line(s)", orderId, items.size());
        return ReservationOutcome.success();
    }

    /**
     * The compensating transaction: give back everything this order reserved.
     *
     * <p>It works off the recorded {@code RESERVE} movements rather than off the original
     * order, because the movements are this service's own account of what it actually did.
     * Reversing what you recorded is the only version of this that stays correct when the
     * order has since been edited, partially filled, or is simply no longer available to
     * ask.
     *
     * <p><strong>The release is also a movement, not an erasure.</strong> The {@code
     * RESERVE} row stays exactly where it was and a {@code RELEASE} row is added beside it.
     * Compensation is a new business fact, not an undo — the history has to show that the
     * units were held and then given back, because that is what happened, and a stock count
     * that quietly loses the episode is a stock count nobody can audit.
     *
     * @return false if this order had nothing reserved, or was already released.
     */
    @Transactional
    public boolean releaseForOrder(Long orderId) {
        List<StockMovement> reserved =
                stockMovementRepository.findByOrderIdAndMovementType(orderId, MovementType.RESERVE);
        if (reserved.isEmpty()) {
            log.warn("[stock] order={} has no RESERVE movements -- nothing to release", orderId);
            return false;
        }

        // Belt-and-braces against double compensation. The listener's inbox already stops
        // a redelivered order.cancelled, but a second cancellation carrying a *different*
        // event id -- a bug upstream, or a replay of the topic from offset zero -- would
        // slip past the inbox and invent stock out of nothing. This check is about the
        // effect rather than the message, so it catches both.
        if (!stockMovementRepository.findByOrderIdAndMovementType(orderId, MovementType.RELEASE).isEmpty()) {
            log.warn("[stock] order={} already released -- refusing to release twice", orderId);
            return false;
        }

        for (StockMovement movement : reserved) {
            applyMovement(movement.getStockItem(), orderId, movement.getQuantity(), MovementType.RELEASE);
        }

        log.info("[stock] order={} RELEASED {} line(s) -- compensation complete", orderId, reserved.size());
        return true;
    }

    /**
     * Moves units between available and reserved, and records why. {@code RESERVE} takes
     * them out of circulation, {@code RELEASE} puts them back; the totals are conserved
     * either way.
     */
    private void applyMovement(StockItem item, Long orderId, int quantity, MovementType type) {
        int direction = (type == MovementType.RESERVE) ? -1 : 1;
        item.setQuantityAvailable(item.getQuantityAvailable() + (direction * quantity));
        item.setQuantityReserved(item.getQuantityReserved() - (direction * quantity));
        stockItemRepository.save(item);

        StockMovement movement = new StockMovement();
        movement.setStockItem(item);
        movement.setOrderId(orderId);
        movement.setMovementType(type);
        movement.setQuantity(quantity);
        stockMovementRepository.save(movement);
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
