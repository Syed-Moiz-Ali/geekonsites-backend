package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — expected booking-lifecycle contract.
 *
 * <p>These tests encode the transitions the client architecture requires the backend
 * to reject. The arbitrary status override endpoint has been removed (Phase 2) and
 * booking closure now requires a genuinely completed service state, so all of these
 * contracts are satisfied by the central {@code BookingStateMachine}.
 */
class BookingLifecycleIntegrationTest extends Phase0IntegrationTestSupport {

    private User agent() {
        return saveUser(Role.AGENT, "lifecycle-agent-" + System.nanoTime() + "@geekonsites.com", "US");
    }

    @Test
    void unpaidBookingCannotBeForcedToServiceCompleted() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/SERVICE_COMPLETED")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

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

    @Test
    void completedBookingCannotRegressToTechnicianAssigned() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PAID", BookingStatus.SERVICE_COMPLETED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/TECHNICIAN_ASSIGNED")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void cancelledBookingCannotReturnToAnActiveState() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PAID", BookingStatus.CANCELLED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/SERVICE_STARTED")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }
}
