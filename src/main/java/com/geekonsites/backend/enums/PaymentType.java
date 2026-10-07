package com.geekonsites.backend.enums;

/**
 * Payment category for a single payment attempt against a booking.
 *
 * <p>Distinct from {@link BookingStatus} (service lifecycle) and the legacy
 * {@code Booking.paymentStatus} aggregate string. The payment ledger stores this
 * explicitly per transaction so advance/remaining/full payments can never be
 * confused with one another.
 */
public enum PaymentType {
    FULL,
    ADVANCE,
    REMAINING
}
