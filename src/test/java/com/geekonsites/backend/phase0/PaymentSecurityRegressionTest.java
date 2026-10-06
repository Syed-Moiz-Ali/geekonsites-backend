package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — payment security contract.
 *
 * <p>Audit C2 / BUG-02: the manual {@code payment-success} and
 * {@code remaining-payment-success} endpoints accept a caller-supplied transaction id
 * and mark a booking paid without any Stripe verification. The client architecture
 * requires payment state to originate only from verified Stripe processing.
 *
 * <p>These tests assert the required contract (a supplied transaction id must NOT be
 * able to move an unpaid booking to paid) and currently fail. Amount mismatch,
 * currency mismatch, wrong booking metadata and duplicate-webhook idempotency are
 * already covered by {@code service/PaymentServiceTest} and are deliberately not
 * duplicated here.
 */
class PaymentSecurityRegressionTest extends Phase0IntegrationTestSupport {

    private User admin() {
        return saveUser(Role.ADMIN, "payment-admin-" + System.nanoTime() + "@geekonsites.com", "US");
    }

    @Tag("expected-failure")
    @Test
    void suppliedTransactionIdMustNotMarkAnUnpaidBookingPaid() throws Exception {
        User admin = admin();
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/payment-success/FAKE-TXN-123")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().is4xxClientError());

        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertNotEquals("PAID", reloaded.getPaymentStatus(),
                "an unverified transaction id must never move a booking to PAID");
    }

    @Tag("expected-failure")
    @Test
    void suppliedTransactionIdMustNotSettleRemainingBalanceWithoutStripe() throws Exception {
        User admin = admin();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "BALANCE_PENDING", BookingStatus.REMAINING_PAYMENT_PENDING);
        booking.setRemainingAmount(70.0);
        bookings.save(booking);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/remaining-payment-success/FAKE-TXN-456")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().is4xxClientError());
    }
}
