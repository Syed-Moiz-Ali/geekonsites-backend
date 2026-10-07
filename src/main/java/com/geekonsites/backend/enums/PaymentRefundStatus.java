package com.geekonsites.backend.enums;

/**
 * Lifecycle of a single refund execution against one payment transaction.
 *
 * <p>A {@code RefundRequest} may produce several {@code PaymentRefund} rows (one per
 * captured payment transaction it is allocated across), so refund execution state is
 * tracked here rather than only on the request.
 */
public enum PaymentRefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
