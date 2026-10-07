package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * PHASE 1 — payment finalization side effects (audit H6-adjacent / Phase 0 discovery).
 *
 * <p>Remote-session provisioning must run only for REMOTE bookings. Settling an
 * on-site booking must never fail because remote provisioning is unsupported, while
 * remote bookings must still attempt provisioning (so the integration is not disabled).
 */
class OnsiteRemainingPaymentRegressionTest extends Phase0IntegrationTestSupport {

    @Autowired PaymentService paymentService;

    private Session session(String id, long amountTotal, String currency, Long bookingId, String paymentType) {
        Session session = new Session();
        session.setId(id);
        session.setPaymentStatus("paid");
        session.setAmountTotal(amountTotal);
        session.setCurrency(currency);
        session.setMetadata(Map.of("bookingId", String.valueOf(bookingId), "paymentType", paymentType));
        return session;
    }

    @Test
    void settlingAnOnsiteBookingMustNotAttemptRemoteProvisioning() {
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "BALANCE_PENDING",
                BookingStatus.REMAINING_PAYMENT_PENDING);
        booking.setAdvanceAmount(30.0);
        booking.setRemainingAmount(70.0);
        booking.setTotalAmount(100.0);
        booking.setPaidAmount(30.0);
        Booking saved = bookings.save(booking);

        assertDoesNotThrow(() -> paymentService.applyCompletedCheckoutSession(
                        session("sess_onsite_remaining", 7000L, "usd", saved.getId(), "REMAINING"), null),
                "an on-site remaining payment must settle without remote-session provisioning");

        Booking reloaded = bookings.findById(saved.getId()).orElseThrow();
        assertEquals("PAID", reloaded.getPaymentStatus());
        assertEquals(null, reloaded.getRemoteSessionStatus(),
                "on-site settlement must not touch remote-session state");
    }

    @Test
    void remoteFullPaymentStillAttemptsRemoteProvisioning() {
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);
        booking.setTotalAmount(100.0);
        booking.setPaidAmount(0.0);
        Booking saved = bookings.save(booking);

        assertDoesNotThrow(() -> paymentService.applyCompletedCheckoutSession(
                        session("sess_remote_full", 10000L, "usd", saved.getId(), "FULL"), null),
                "a remote booking must still attempt remote-session provisioning");

        Booking reloaded = bookings.findById(saved.getId()).orElseThrow();
        assertEquals("PAID", reloaded.getPaymentStatus());
        assertNotNull(reloaded.getRemoteSessionStatus(),
                "remote provisioning must have been attempted for a remote booking");
        assertNotEquals("PAYMENT_PENDING", reloaded.getRemoteSessionStatus());
    }
}
