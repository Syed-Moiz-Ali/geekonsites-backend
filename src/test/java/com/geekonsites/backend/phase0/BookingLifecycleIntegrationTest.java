package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — expected booking-lifecycle contract.
 *
 * <p>These tests encode the transitions the client architecture requires the backend
 * to reject. They intentionally exercise the CURRENT bypass mechanisms (the arbitrary
 * status override endpoint and the invoice/payment-only close path). Tests tagged
 * {@code expected-failure} currently fail because the state machine does not exist yet
 * (audit C1 / BUG-01 / BUG-11) and must pass after Phase 2.
 */
class BookingLifecycleIntegrationTest extends Phase0IntegrationTestSupport {

    private User agent() {
        return saveUser(Role.AGENT, "lifecycle-agent-" + System.nanoTime() + "@geekonsites.com", "US");
    }

    @Tag("expected-failure")
    @Test
    void unpaidBookingCannotBeForcedToServiceCompleted() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/SERVICE_COMPLETED")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void unpaidBookingCannotBeClosed() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PENDING", BookingStatus.PENDING);
        booking.setInvoiceGenerated(true);
        bookings.save(booking);

        int status = statusOf(put("/api/bookings/" + booking.getId() + "/close")
                .header("Authorization", bearer(agent)));
        assertTrue(status >= 400 && status < 500,
                "an unpaid booking must not be closable; expected 4xx but got " + status);
    }

    @Tag("expected-failure")
    @Test
    void closedBookingCannotRestartService() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PAID", BookingStatus.BOOKING_CLOSED);
        booking.setInvoiceGenerated(true);
        bookings.save(booking);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/SERVICE_STARTED")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void completedBookingCannotRegressToTechnicianAssigned() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PAID", BookingStatus.SERVICE_COMPLETED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/TECHNICIAN_ASSIGNED")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void cancelledBookingCannotReturnToAnActiveState() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PAID", BookingStatus.CANCELLED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/SERVICE_STARTED")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }
}
