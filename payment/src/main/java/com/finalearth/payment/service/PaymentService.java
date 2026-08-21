package com.finalearth.payment.service;

import com.finalearth.payment.dto.*;
import com.finalearth.payment.entity.Payment;
import com.finalearth.payment.entity.PaymentMethod;
import com.finalearth.payment.entity.PaymentStatus;
import com.finalearth.payment.exception.PaymentDeclinedException;
import com.finalearth.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

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

    /**
     * Charge an order that arrived on {@code stock.reserved}. Reports its outcome by
     * returning it, never by throwing — see {@link PaymentOutcome}.
     *
     * <p><strong>Idempotency is enforced twice, on purpose, because the stakes are
     * asymmetric.</strong> The listener's inbox stops a redelivery of the same event, and
     * that covers the ordinary case. This method additionally refuses to charge an order
     * that already has a payment row, whatever event asked — which catches a replayed topic,
     * a duplicate emitted upstream with a fresh event id, and the manual re-run somebody
     * does at 3am. Everywhere else in this system a duplicate would cost a wrong number in a
     * table; here it takes money from a real person twice, so the cheap redundant check is
     * obviously worth it.
     *
     * <p>Note that a prior FAILED payment reports its original decline rather than being
     * retried. Re-attempting a card because an event was redelivered would be inventing a
     * business decision out of a transport artefact.
     */
    @Transactional
    public PaymentOutcome chargeForOrder(Long orderId, BigDecimal amount, String method) {
        var existing = paymentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            Payment p = existing.get();
            log.warn("[payment] order={} already has payment ref={} status={} -- not charging again",
                    orderId, p.getPaymentRef(), p.getStatus());
            return p.getStatus() == PaymentStatus.COMPLETED
                    ? PaymentOutcome.completed(p.getPaymentRef())
                    : PaymentOutcome.declined(p.getFailureReason());
        }

        // sales validates paymentMethod only as non-blank, so an unrecognised value can
        // legitimately reach this far. Declining is the right answer, and dead-lettering is
        // not: a dead-lettered record is never answered, so sales would hold the order at
        // STOCK_RESERVED forever with the units still held. A decline travels back as
        // payment.failed and triggers the compensation that releases them. Prefer the
        // failure that keeps the saga moving over the one that leaves it stuck.
        PaymentMethod parsedMethod;
        try {
            parsedMethod = PaymentMethod.valueOf(method);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("[payment] order={} DECLINED reason=\"unsupported method {}\"", orderId, method);
            // No payment row is written: `method` is an enum column and this value is not
            // one of its constants, so there is nothing valid to store. The listener's
            // inbox is what keeps a redelivery from re-running this branch.
            return PaymentOutcome.declined("Unsupported payment method: " + method);
        }

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setAmount(amount);
        payment.setMethod(parsedMethod);

        // Same simulation rule as the HTTP path: amounts ending in .13 are declined.
        boolean declined = amount.remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.13")) == 0;

        if (declined) {
            payment.setPaymentRef("PAY-DECLINED-" + UUID.randomUUID().toString().substring(0, 8));
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Insufficient funds");
            paymentRepository.save(payment);
            log.warn("[payment] order={} DECLINED amount={} reason=\"Insufficient funds\"", orderId, amount);
            return PaymentOutcome.declined("Insufficient funds");
        }

        payment.setPaymentRef("PAY-" + System.currentTimeMillis() + "-" + (orderId % 10000));
        payment.setStatus(PaymentStatus.COMPLETED);
        Payment saved = paymentRepository.save(payment);
        log.info("[payment] order={} COMPLETED ref={} amount={}", orderId, saved.getPaymentRef(), amount);
        return PaymentOutcome.completed(saved.getPaymentRef());
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
