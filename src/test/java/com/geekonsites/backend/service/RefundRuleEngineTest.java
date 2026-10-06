package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class RefundRuleEngineTest {
    private final RefundRuleEngine rules = new RefundRuleEngine();

    @Test
    void usaBeforeServiceKeepsAllUnrefundedPaymentInCeilingIncludingPlatformFee() {
        Booking booking = booking("US", 112.0);
        booking.setPlatformFee(12.0);
        var result = rules.assess(booking, BigDecimal.ZERO);
        assertEquals(new BigDecimal("112.00"), result.maximumRefundableAmount());
        assertTrue(result.ruleContext().contains("generally refundable"));
    }

    @Test
    void usaAfterServiceRequiresDocumentedProportionateReview() {
        Booking booking = booking("US", 100.0);
        booking.setServiceStartedAt(LocalDateTime.now());
        assertTrue(rules.assess(booking, BigDecimal.ZERO).ruleContext().contains("documented service"));
    }

    @Test
    void ukWithinFourteenDaysUsesStoredEarlyServiceConsentContext() {
        Booking booking = booking("UK", 100.0);
        booking.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        booking.setBookingDate(LocalDate.of(2026, 8, 10));
        booking.setServiceStartedAt(LocalDateTime.of(2026, 8, 10, 10, 0));
        booking.setUkEarlyServiceConsent(true);
        assertTrue(rules.assess(booking, BigDecimal.ZERO).ruleContext().contains("recorded consent"));
    }

    @Test
    void alreadyRefundedAmountReducesMaximumAndPreventsOverRefund() {
        var result = rules.assess(booking("US", 100.0), new BigDecimal("65.00"));
        assertEquals(new BigDecimal("35.00"), result.maximumRefundableAmount());
    }

    private Booking booking(String country, double paid) {
        Booking booking = new Booking();
        booking.setCountry(country);
        booking.setCurrency(country.equals("UK") ? "GBP" : "USD");
        booking.setPaidAmount(paid);
        return booking;
    }
}
