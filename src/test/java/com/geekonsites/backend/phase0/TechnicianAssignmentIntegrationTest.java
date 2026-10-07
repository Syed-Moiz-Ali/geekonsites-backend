package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — technician assignment contract (audit R6 / H2 / BUG-06).
 *
 * <p>Verification, availability and service-mode eligibility are already enforced and
 * the tests here confirm that. The remaining tests encode the required contract that
 * is currently missing: closed/completed bookings must not be (re)assigned, and a
 * reassignment must release the previously assigned technician. Those tests currently
 * fail and must pass after the Phase 1/2 assignment fixes.
 */
class TechnicianAssignmentIntegrationTest extends Phase0IntegrationTestSupport {

    private User agent() {
        return saveUser(Role.AGENT, "assign-agent-" + System.nanoTime() + "@geekonsites.com", "US");
    }

    @Test
    void unavailableTechnicianCannotBeAssigned() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);
        Technician technician = saveTechnician("busy-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "UNAVAILABLE", "REMOTE_AND_ONSITE");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + technician.getId())
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unverifiedTechnicianCannotBeAssigned() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);
        Technician technician = saveTechnician("rejected-tech-" + System.nanoTime() + "@example.com",
                "REJECTED", "AVAILABLE", "REMOTE_AND_ONSITE");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + technician.getId())
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completedBookingCannotBeAssigned() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PAID", BookingStatus.SERVICE_COMPLETED);
        Technician technician = saveTechnician("completed-assign-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + technician.getId())
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void cancelledBookingCannotBeAssigned() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PAID", BookingStatus.CANCELLED);
        Technician technician = saveTechnician("cancelled-assign-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + technician.getId())
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void reassigningTechnicianMustReleaseThePreviousTechnician() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);
        Technician first = saveTechnician("first-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        Technician second = saveTechnician("second-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + first.getId())
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isOk());
        assertEquals("BUSY", technicians.findById(first.getId()).orElseThrow().getAvailabilityStatus());

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + second.getId())
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isOk());

        assertEquals("AVAILABLE", technicians.findById(first.getId()).orElseThrow().getAvailabilityStatus(),
                "the replaced technician must be released back to AVAILABLE");
    }
}
