package com.finalearth.payment.service;

import com.finalearth.payment.dto.*;
import com.finalearth.payment.entity.Payment;
import com.finalearth.payment.entity.PaymentStatus;
import com.finalearth.payment.exception.PaymentDeclinedException;
import com.finalearth.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentDto processPayment(ProcessPaymentRequest req) {
        // Idempotency check for existing payment on orderId
        var existing = paymentRepository.findByOrderId(req.orderId());
        if (existing.isPresent()) {
            Payment p = existing.get();
            if (p.getStatus() == PaymentStatus.FAILED) {
                throw new PaymentDeclinedException(p.getOrderId(), p.getFailureReason());
            }
            return mapToDto(p);
        }

        Payment payment = new Payment();
        payment.setOrderId(req.orderId());
        payment.setAmount(req.amount());
        payment.setMethod(req.method());

        // Simulation rule: amounts ending in .13 are declined with 402 and failureReason: "Insufficient funds"
        boolean isDeclined = req.amount().remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.13")) == 0;

        if (isDeclined) {
            payment.setPaymentRef("PAY-DECLINED-" + UUID.randomUUID().toString().substring(0, 8));
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Insufficient funds");
            paymentRepository.save(payment);
            throw new PaymentDeclinedException(req.orderId(), "Insufficient funds");
        } else {
            payment.setPaymentRef("PAY-" + System.currentTimeMillis() + "-" + (req.orderId() % 10000));
            payment.setStatus(PaymentStatus.COMPLETED);
            Payment saved = paymentRepository.save(payment);
            return mapToDto(saved);
        }
    }

    public PaymentDto getPaymentByOrderId(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found for order id: " + orderId));
        return mapToDto(payment);
    }

    private PaymentDto mapToDto(Payment p) {
        return new PaymentDto(
                p.getId(),
                p.getPaymentRef(),
                p.getOrderId(),
                p.getAmount(),
                p.getStatus(),
                p.getMethod(),
                p.getFailureReason(),
                p.getCreatedAt()
        );
    }
}
