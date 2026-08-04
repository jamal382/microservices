package com.finalearth.payment.repository;

import com.finalearth.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    Optional<Payment> findByPaymentRef(String paymentRef);
    boolean existsByOrderId(Long orderId);
}
