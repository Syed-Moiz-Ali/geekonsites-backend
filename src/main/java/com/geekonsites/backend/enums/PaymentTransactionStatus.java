package com.geekonsites.backend.enums;

/**
 * Lifecycle of one payment transaction record.
 *
 * <p>Deliberately separate from {@link BookingStatus} and the legacy
 * {@code Booking.paymentStatus} string:
 *
 * <ul>
 *   <li>INITIATED — ledger row created before the provider session exists</li>
 *   <li>CHECKOUT_CREATED — provider checkout session created and linked</li>
 *   <li>SUCCEEDED — payment captured and verified server-side</li>
 *   <li>FAILED — provider/checkout creation or capture failed</li>
 *   <li>CANCELLED — customer aborted checkout</li>
 *   <li>EXPIRED — provider session expired before completion</li>
 * </ul>
 */
public enum PaymentTransactionStatus {
    INITIATED,
    CHECKOUT_CREATED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
}
