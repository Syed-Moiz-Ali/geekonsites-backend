package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — regression guard for the audit's arbitrary-status endpoint
 * ({@code BookingController.updateStatus} -> {@code BookingService.updateStatus},
 * audit C1 / BUG-01).
 *
 * <p>The endpoint currently accepts any {@link BookingStatus} value from an AGENT or
 * ADMIN. The client architecture requires lifecycle changes to pass through named,
 * validated transitions only. These tests express that contract and currently fail
 * until Phase 2 introduces the booking state machine.
 */
class ArbitraryStatusEndpointRegressionTest extends Phase0IntegrationTestSupport {

    private User agent() {
        return saveUser(Role.AGENT, "status-agent-" + System.nanoTime() + "@geekonsites.com", "US");
    }

    @Tag("expected-failure")
    @Test
    void agentCannotForceUnpaidBookingStraightToCompleted() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PENDING", BookingStatus.PENDING);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/SERVICE_COMPLETED")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void agentCannotSetAnArbitraryStatusOnAnAlreadyCompletedBooking() throws Exception {
        User agent = agent();
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PAID", BookingStatus.SERVICE_COMPLETED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/status/TECHNICIAN_ON_THE_WAY")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }
}
