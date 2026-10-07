package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.PaymentReversalStatus;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * PHASE 4 — automatic technical reversal of a quarantined excess capture.
 *
 * <p>This is NOT a discretionary business refund: an excess capture is money the backend
 * determined was not owed (a stale/duplicate Stripe capture). The reversal targets
 * exactly that {@link PaymentTransaction}, uses a deterministic provider idempotency key,
 * and preserves a persisted PENDING/SUCCEEDED/FAILED state so it is retryable and never
 * double-issued.
 *
 * <p>Provider call happens OUTSIDE the DB transaction (reserve → Stripe → record), so no
 * lock is held during the network call.
 */
@Service
public class ExcessReversalService {

    private static final Logger log = LoggerFactory.getLogger(ExcessReversalService.class);
    private static final String IDEMPOTENCY_PREFIX = "gos-excess-reversal-";

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final StripeRefundGateway stripeRefundGateway;
    private final TransactionTemplate transactionTemplate;

    public ExcessReversalService(
            PaymentTransactionRepository paymentTransactionRepository,
            StripeRefundGateway stripeRefundGateway,
            PlatformTransactionManager transactionManager
    ) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.stripeRefundGateway = stripeRefundGateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Runs from an AFTER_COMMIT callback; each step needs its own transaction.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void reverse(Long paymentTransactionId) {
        PaymentTransaction reserved = transactionTemplate.execute(status -> reserve(paymentTransactionId));
        if (reserved == null || reserved.getPaymentIntentId() == null) {
            return;
        }
        try {
            StripeRefundGateway.StripeRefundResult result = stripeRefundGateway.refundPaymentTransaction(
                    reserved, reserved.getAmountMinor(), IDEMPOTENCY_PREFIX + paymentTransactionId);
            transactionTemplate.executeWithoutResult(status -> recordSuccess(paymentTransactionId, result.refundId()));
            log.info("Excess capture reversed for paymentTransactionId={} refundId={}", paymentTransactionId, result.refundId());
        } catch (RuntimeException exception) {
            log.warn("Excess capture reversal failed for paymentTransactionId={}", paymentTransactionId, exception);
            transactionTemplate.executeWithoutResult(status -> recordFailure(paymentTransactionId, exception));
        }
    }

    private PaymentTransaction reserve(Long id) {
        PaymentTransaction transaction = paymentTransactionRepository.findByIdForUpdate(id).orElse(null);
        if (transaction == null || !transaction.isExcess()) {
            return null;
        }
        if (transaction.getReversalStatus() == PaymentReversalStatus.SUCCEEDED) {
            return null; // already reversed
        }
        // PENDING (set at detection), FAILED (retry) and NOT_REQUIRED all proceed. Concurrent
        // workers are serialized by the row lock, and the deterministic provider idempotency
        // key guarantees a repeated provider call cannot issue a second reversal.
        if (transaction.getPaymentIntentId() == null || transaction.getAmountMinor() == null) {
            transaction.setReversalStatus(PaymentReversalStatus.FAILED);
            transaction.setReversalError("Excess capture has no PaymentIntent/amount to reverse");
            transaction.setReversalAttemptedAt(LocalDateTime.now());
            paymentTransactionRepository.save(transaction);
            return null;
        }
        transaction.setReversalStatus(PaymentReversalStatus.PENDING);
        transaction.setReversalAttemptedAt(LocalDateTime.now());
        return paymentTransactionRepository.save(transaction);
    }

    private void recordSuccess(Long id, String refundId) {
        paymentTransactionRepository.findByIdForUpdate(id).ifPresent(transaction -> {
            transaction.setReversalStatus(PaymentReversalStatus.SUCCEEDED);
            transaction.setReversalRefundId(refundId);
            transaction.setReversalError(null);
            paymentTransactionRepository.save(transaction);
        });
    }

    private void recordFailure(Long id, RuntimeException exception) {
        paymentTransactionRepository.findByIdForUpdate(id).ifPresent(transaction -> {
            transaction.setReversalStatus(PaymentReversalStatus.FAILED);
            String message = exception.getMessage() == null ? "Excess reversal failed" : exception.getMessage();
            transaction.setReversalError(message.length() > 500 ? message.substring(0, 500) : message);
            paymentTransactionRepository.save(transaction);
        });
    }
}
