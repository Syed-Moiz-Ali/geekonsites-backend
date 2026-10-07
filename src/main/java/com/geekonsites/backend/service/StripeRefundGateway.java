package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.PaymentTransaction;

/**
 * PHASE 1 — refunds a specific captured payment transaction.
 *
 * <p>Replaces the previous booking-scoped refund that could only see the single
 * (possibly overwritten) {@code Booking.paymentTransactionId}. Allocation across
 * multiple captures is decided by {@link RefundService}; this gateway only executes
 * one refund against one transaction and never exceeds what that transaction captured.
 */
public interface StripeRefundGateway {

    StripeRefundResult refundPaymentTransaction(PaymentTransaction transaction, long amountMinor, String idempotencyKey);

    record StripeRefundResult(String paymentIntentId, String refundId, String status) {}
}
