package com.finalearth.sales.service;

import com.finalearth.sales.client.CatalogClient;
import com.finalearth.sales.client.InventoryClient;
import com.finalearth.sales.client.PaymentClient;
import com.finalearth.sales.dto.*;
import com.finalearth.sales.entity.Order;
import com.finalearth.sales.entity.OrderItem;
import com.finalearth.sales.entity.OrderStatus;
import com.finalearth.sales.exception.DependencyBusinessException;
import com.finalearth.sales.exception.DependencyUnavailableException;
import com.finalearth.sales.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class SalesService {

    private static final Logger log = LoggerFactory.getLogger(SalesService.class);

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;

    public SalesService(OrderRepository orderRepository,
                        CatalogClient catalogClient,
                        InventoryClient inventoryClient,
                        PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
    }

    public OrderDto placeOrder(CreateOrderRequest req) {
        // Step 1 & 2: Fetch products from catalog & compute totalAmount
        BigDecimal totalAmount = BigDecimal.ZERO;
        Order order = new Order();
        order.setCustomerId(req.customerId());
        order.setStatus(OrderStatus.PENDING);
        order.setOrderNumber(generateTempOrderNumber());

        for (OrderItemRequest itemReq : req.items()) {
            CatalogClient.ProductResponse product = catalogClient.getProductById(itemReq.productId());
            BigDecimal itemTotal = product.price().multiply(BigDecimal.valueOf(itemReq.quantity()));
            totalAmount = totalAmount.add(itemTotal);

            OrderItem item = new OrderItem();
            item.setProductId(product.id());
            item.setProductName(product.name());
            item.setQuantity(itemReq.quantity());
            item.setUnitPrice(product.price());
            order.addItem(item);
        }

        order.setTotalAmount(totalAmount);

        // Save initially to generate Order ID
        Order savedOrder = saveOrder(order);
        savedOrder.setOrderNumber(generateOrderNumber(savedOrder.getId()));
        savedOrder = saveOrder(savedOrder);

        // Step 5: Reserve stock. This call is final in this trimmed scope — inventory
        // exposes no confirm/release endpoint, so a reservation is never explicitly
        // rolled back or converted over HTTP.
        try {
            var reserveItems = req.items().stream()
                    .map(i -> new InventoryClient.ReserveItem(i.productId(), i.quantity()))
                    .toList();
            inventoryClient.reserveStock(savedOrder.getId(), reserveItems);
            savedOrder.setStatus(OrderStatus.STOCK_RESERVED);
            savedOrder = saveOrder(savedOrder);
        } catch (DependencyBusinessException e) {
            // Inventory said no on the merits (not enough stock). A definite answer,
            // so the order is definitely rejected.
            savedOrder.setStatus(OrderStatus.REJECTED);
            saveOrder(savedOrder);
            throw e;
        } catch (DependencyUnavailableException e) {
            // We never got an answer. If the circuit was open the request certainly
            // never left this process; otherwise it may or may not have been applied.
            // Either way nothing was confirmed, so the order stops here rather than
            // proceeding to charge a customer for stock we cannot vouch for.
            savedOrder.setStatus(OrderStatus.REJECTED);
            saveOrder(savedOrder);
            throw e;
        }

        // Step 6: Process Payment. On decline, the order is terminal here —
        // reserved stock is not released back (known limitation of this scope).
        try {
            paymentClient.processPayment(savedOrder.getId(), savedOrder.getTotalAmount(), req.paymentMethod());
        } catch (DependencyBusinessException e) {
            // The provider answered: declined. An authoritative "no money moved".
            savedOrder.setStatus(OrderStatus.PAYMENT_FAILED);
            saveOrder(savedOrder);
            throw e;
        } catch (DependencyUnavailableException e) {
            // Deliberately NOT marked PAYMENT_FAILED. That status asserts the charge
            // did not happen, and outside an open circuit we do not know that: the
            // request may have been authorised and only the response lost. The order
            // is left at STOCK_RESERVED — visibly unfinished — because a truthful
            // "unresolved" beats a tidy status that might be a lie. Reconciling it is
            // exactly the job a saga or an outbox would take on.
            log.error("[order] order={} left at {} — payment outcome UNKNOWN ({})",
                    savedOrder.getId(), savedOrder.getStatus(), e.getMessage());
            throw e;
        }

        savedOrder.setStatus(OrderStatus.CONFIRMED);
        savedOrder = saveOrder(savedOrder);

        return mapToDto(savedOrder);
    }

    @Transactional
    public Order saveOrder(Order order) {
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public OrderDto getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found with id: " + id));
        return mapToDto(order);
    }

    private String generateTempOrderNumber() {
        return "TMP-" + UUID.randomUUID().toString().substring(0, 18);
    }

    private String generateOrderNumber(Long orderId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return String.format("ORD-%s-%04d", dateStr, orderId);
    }

    private OrderDto mapToDto(Order o) {
        var itemDtos = o.getItems().stream()
                .map(i -> new OrderItemDto(i.getProductId(), i.getProductName(), i.getQuantity(), i.getUnitPrice()))
                .toList();

        return new OrderDto(
                o.getId(),
                o.getOrderNumber(),
                o.getCustomerId(),
                o.getStatus(),
                o.getTotalAmount(),
                itemDtos,
                o.getCreatedAt()
        );
    }
}
