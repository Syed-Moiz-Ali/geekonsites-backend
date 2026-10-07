package com.geekonsites.backend.entity;

import com.geekonsites.backend.service.PaymentMoney;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PHASE 8 — exact-money guarantees for the persisted Booking/Invoice snapshots.
 *
 * <p>Verifies that (a) the canonical conversion helper round-trips every representable
 * minor amount without drift, (b) resolution prefers the exact authority over the
 * deprecated Double mirror, and (c) persisting a legacy Double-only Booking/Invoice
 * back-fills the exact minor columns so a stored row can never be left without an
 * exact financial authority.
 */
class MoneyExactnessTest {

    @Test
    void minorConversionsRoundTripWithoutDrift() {
        long[] amounts = {0L, 1L, 29L, 41L, 100L, 1200L, 9999L, 123456789L};
        for (long minor : amounts) {
            assertEquals(minor, PaymentMoney.toMinor(PaymentMoney.toMajor(minor)),
                    "major<->minor must round-trip exactly for " + minor);
            assertEquals(BigDecimal.valueOf(minor, 2), PaymentMoney.toMajorMoney(minor));
        }
    }

    @Test
    void resolveMinorPrefersExactAuthorityThenFallsBackToLegacyMirror() {
        assertEquals(4100L, PaymentMoney.resolveMinor(4100L, 999.99));
        assertEquals(123L, PaymentMoney.resolveMinor(null, 1.23));
        assertEquals(0L, PaymentMoney.resolveMinor(null, null));
    }

    @Test
    void bookingPersistBackfillsExactAuthorityFromLegacyMirrors() {
        Booking booking = new Booking();
        booking.setBaseAmount(29.0);
        booking.setAddonsAmount(0.0);
        booking.setProtectionAmount(0.0);
        booking.setPlatformFee(12.0);
        booking.setTotalAmount(41.0);
        booking.setAdvanceAmount(0.0);
        booking.setRemainingAmount(0.0);
        booking.setPaidAmount(41.0);

        booking.onCreate();

        assertEquals(2900L, booking.getBaseAmountMinor());
        assertEquals(1200L, booking.getPlatformFeeMinor());
        assertEquals(4100L, booking.getTotalAmountMinor());
        assertEquals(4100L, booking.getPaidAmountMinor());
    }

    @Test
    void invoicePersistBackfillsExactAuthorityFromLegacyMirrors() {
        Invoice invoice = new Invoice();
        invoice.setAmount(300.0);
        invoice.setPaidAmount(90.0);

        invoice.onCreate();

        assertEquals(30000L, invoice.getAmountMinor());
        assertEquals(9000L, invoice.getPaidAmountMinor());
    }
}
