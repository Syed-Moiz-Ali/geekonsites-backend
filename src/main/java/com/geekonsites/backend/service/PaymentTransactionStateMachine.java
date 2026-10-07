package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * PHASE 3 — the single authority for {@link PaymentTransaction} provider-state
 * transitions.
 *
 * <p>Legal transitions and precedence:
 * <ul>
 *   <li>{@code INITIATED -> CHECKOUT_CREATED} (Stripe session created)</li>
 *   <li>{@code INITIATED/CHECKOUT_CREATED -> FAILED} (permanent creation failure)</li>
 *   <li>{@code INITIATED/CHECKOUT_CREATED -> EXPIRED} (Stripe session expired)</li>
 *   <li>{@code INITIATED/CHECKOUT_CREATED/EXPIRED/FAILED -> SUCCEEDED} (Stripe reports
 *       the payment as paid — provider truth wins for a genuine capture)</li>
 *   <li>{@code SUCCEEDED} is terminal: stale/late events can never downgrade it</li>
 * </ul>
 *
 * <p>The provider PaymentIntent id is immutable once set: a later event claiming a
 * different PaymentIntent for the same transaction is rejected.
 */
@Service
public class PaymentTransactionStateMachine {

    public void markCheckoutCreated(PaymentTransaction transaction, String checkoutSessionId,
                                    String checkoutUrl, LocalDateTime checkoutExpiresAt) {
        if (transaction.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
            throw new InvalidPaymentStateException("A succeeded payment transaction cannot be reset to checkout-created");
        }
        transaction.setCheckoutSessionId(checkoutSessionId);
        transaction.setCheckoutUrl(checkoutUrl);
        transaction.setCheckoutExpiresAt(checkoutExpiresAt);
        transaction.setStatus(PaymentTransactionStatus.CHECKOUT_CREATED);
    }

    /** Marks capture success. Idempotent; never rewrites an existing PaymentIntent. */
    public boolean markSucceeded(PaymentTransaction transaction, String paymentIntentId) {
        if (transaction.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
            return false;
        }
        if (paymentIntentId != null && transaction.getPaymentIntentId() != null
                && !transaction.getPaymentIntentId().equals(paymentIntentId)) {
            throw new InvalidPaymentStateException("The Stripe PaymentIntent for this transaction cannot change");
        }
        if (paymentIntentId != null) {
            transaction.setPaymentIntentId(paymentIntentId);
        }
        transaction.setStatus(PaymentTransactionStatus.SUCCEEDED);
        if (transaction.getCompletedAt() == null) {
            transaction.setCompletedAt(LocalDateTime.now());
        }
        return true;
    }

    /** Marks expiry. Never downgrades a succeeded transaction; idempotent. */
    public boolean markExpired(PaymentTransaction transaction) {
        if (transaction.getStatus() == PaymentTransactionStatus.SUCCEEDED
                || transaction.getStatus() == PaymentTransactionStatus.EXPIRED) {
            return false;
        }
        transaction.setStatus(PaymentTransactionStatus.EXPIRED);
        return true;
    }

    /** Marks a permanent failure. Never downgrades a succeeded transaction; idempotent. */
    public boolean markFailed(PaymentTransaction transaction) {
        if (transaction.getStatus() == PaymentTransactionStatus.SUCCEEDED
                || transaction.getStatus() == PaymentTransactionStatus.FAILED) {
            return false;
        }
        transaction.setStatus(PaymentTransactionStatus.FAILED);
        return true;
    }

    /**
     * Flags a genuinely captured transaction as exceeding the booking obligation.
     * The row must already be SUCCEEDED (provider truth) and is preserved as history.
     */
    public void markExcess(PaymentTransaction transaction) {
        if (transaction.getStatus() != PaymentTransactionStatus.SUCCEEDED) {
            throw new InvalidPaymentStateException("Only a succeeded transaction can be flagged as excess");
        }
        transaction.setExcess(true);
    }
}
