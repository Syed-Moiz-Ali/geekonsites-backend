package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PHASE 0 — newly discovered payment-path defect (not in the updated audit).
 *
 * <p>{@code PaymentService.finalizePaidBooking} routes every PAID booking through
 * {@code RemoteSessionProvisioningService.provisionAfterPayment}, which throws
 * "Remote session provisioning is available only for remote bookings" for an on-site
 * booking. The REMAINING payment of an on-site booking therefore marks the booking
 * PAID and then throws (Stripe webhooks would retry and keep failing). This test uses
 * the REAL collaborators (no mocks), which is why the existing mocked
 * {@code PaymentServiceTest} did not catch it.
 *
 * <p>Required future contract: settling an on-site booking must not attempt remote
 * provisioning. Tagged {@code expected-failure} — to be fixed in Phase 1.
 */
class OnsiteRemainingPaymentRegressionTest extends Phase0IntegrationTestSupport {

    @Autowired PaymentService paymentService;

    @Tag("expected-failure")
    @Test
    void settlingAnOnsiteBookingMustNotAttemptRemoteProvisioning() {
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "BALANCE_PENDING",
                BookingStatus.REMAINING_PAYMENT_PENDING);
        booking.setAdvanceAmount(30.0);
        booking.setRemainingAmount(70.0);
        booking.setTotalAmount(100.0);
        booking.setPaidAmount(30.0);
        Booking saved = bookings.save(booking);

        Session session = new Session();
        session.setId("sess_onsite_remaining");
        session.setPaymentStatus("paid");
        session.setAmountTotal(7000L);
        session.setCurrency("usd");
        session.setMetadata(Map.of(
                "bookingId", String.valueOf(saved.getId()),
                "paymentType", "REMAINING"));

        assertDoesNotThrow(() -> paymentService.applyCompletedCheckoutSession(session, null),
                "an on-site remaining payment must settle without remote-session provisioning");

        assertEquals("PAID", bookings.findById(saved.getId()).orElseThrow().getPaymentStatus());
    }
}
