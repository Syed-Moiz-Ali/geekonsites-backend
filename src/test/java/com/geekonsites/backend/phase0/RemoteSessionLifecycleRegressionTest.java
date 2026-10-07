package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — remote-session lifecycle contract (audit H5, BUG-09/10).
 *
 * <p>{@code RemoteSessionService} is a second, weaker lifecycle path:
 * {@code endRemoteSession()} can set {@code SERVICE_COMPLETED} on an on-site, unpaid,
 * or merely-assigned booking, and {@code startRemoteSession()} skips the
 * {@code TECHNICIAN_ACCEPTED} requirement. These tests encode the required contract
 * and currently fail until the remote path is folded into the central lifecycle
 * (Phase 1/2).
 */
class RemoteSessionLifecycleRegressionTest extends Phase0IntegrationTestSupport {

    private static final String MEET_LINK = "https://meet.google.com/abc-defg-hij";

    private record Actor(Technician technician, String token) {}

    private Actor assignedTechnician() {
        String email = "remote-tech-" + System.nanoTime() + "@example.com";
        Technician technician = saveTechnician(email, "APPROVED", "BUSY", "REMOTE_ONLY");
        User user = users.findByEmail(email).orElseThrow();
        return new Actor(technician, bearer(user));
    }

    @Test
    void remoteSessionEndMustNotCompleteAnOnsiteBooking() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.TECHNICIAN_ACCEPTED);

        mvc.perform(put("/api/remote-sessions/booking/" + booking.getId() + "/end")
                        .header("Authorization", actor.token()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void remoteSessionEndMustNotCompleteAPaidButNotAcceptedBooking() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.TECHNICIAN_ASSIGNED);
        booking.setRemoteSessionLink(MEET_LINK);
        bookings.save(booking);

        mvc.perform(put("/api/remote-sessions/booking/" + booking.getId() + "/end")
                        .header("Authorization", actor.token()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void remoteSessionEndMustNotCompleteAnUnpaidBooking() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.REMOTE, "PENDING", BookingStatus.TECHNICIAN_ACCEPTED);
        booking.setRemoteSessionLink(MEET_LINK);
        bookings.save(booking);

        mvc.perform(put("/api/remote-sessions/booking/" + booking.getId() + "/end")
                        .header("Authorization", actor.token()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void remoteSessionStartMustRequireTechnicianAcceptance() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.TECHNICIAN_ASSIGNED);
        booking.setRemoteSessionLink(MEET_LINK);
        bookings.save(booking);

        mvc.perform(put("/api/remote-sessions/booking/" + booking.getId() + "/start")
                        .header("Authorization", actor.token()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void remoteSessionStartOnAnOnsiteBookingDoesNotStartASession() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.TECHNICIAN_ACCEPTED);
        booking.setRemoteSessionLink(MEET_LINK);
        bookings.save(booking);

        statusOf(put("/api/remote-sessions/booking/" + booking.getId() + "/start")
                .header("Authorization", actor.token()));

        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertNotEquals(BookingStatus.REMOTE_SESSION_STARTED, reloaded.getBookingStatus(),
                "a non-remote booking must never enter REMOTE_SESSION_STARTED");
    }
}
