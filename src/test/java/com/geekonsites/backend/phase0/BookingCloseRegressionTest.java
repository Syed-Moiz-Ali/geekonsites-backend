package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — booking-close prerequisite contract (audit H6 / BUG-11).
 *
 * <p>{@code BookingService.closeBooking} currently requires only
 * {@code paymentStatus == PAID} and {@code invoiceGenerated == true}. Because an
 * invoice is generated automatically after payment, a fully paid booking can be closed
 * before any technician is assigned or any service is performed. These tests encode
 * the required contract (closure only from a legally completed/settled state) and
 * currently fail until Phase 2.
 */
class BookingCloseRegressionTest extends Phase0IntegrationTestSupport {

    private User agent() {
        return saveUser(Role.AGENT, "close-agent-" + System.nanoTime() + "@geekonsites.com", "US");
    }

    private Booking paidInvoiceBooking(BookingStatus status) {
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PAID", status);
        booking.setInvoiceGenerated(true);
        booking.setInvoiceNumber("GOS-US-INV-" + booking.getId());
        return bookings.save(booking);
    }

    @Test
    void paidInvoiceButUnassignedBookingMustNotClose() throws Exception {
        User agent = agent();
        Booking booking = paidInvoiceBooking(BookingStatus.PAYMENT_COMPLETED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/close")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void paidInvoiceButOnlyAssignedBookingMustNotClose() throws Exception {
        User agent = agent();
        Booking booking = paidInvoiceBooking(BookingStatus.TECHNICIAN_ASSIGNED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/close")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void paidInvoiceButOnlyAcceptedBookingMustNotClose() throws Exception {
        User agent = agent();
        Booking booking = paidInvoiceBooking(BookingStatus.TECHNICIAN_ACCEPTED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/close")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void paidInvoiceButServiceStartedBookingMustNotClose() throws Exception {
        User agent = agent();
        Booking booking = paidInvoiceBooking(BookingStatus.SERVICE_STARTED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/close")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void completedPaidInvoiceBookingMayClose() throws Exception {
        User agent = agent();
        Booking booking = paidInvoiceBooking(BookingStatus.SERVICE_COMPLETED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/close")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isOk());
    }
}
