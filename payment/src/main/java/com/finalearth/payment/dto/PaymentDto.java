package com.finalearth.payment.dto;

import com.finalearth.payment.entity.PaymentMethod;
import com.finalearth.payment.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentDto(
    Long id,
    String paymentRef,
    Long orderId,
    BigDecimal amount,
    PaymentStatus status,
    PaymentMethod method,
    String failureReason,
    Instant createdAt
) {}
