package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Invoice;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.RefundRequestRepository;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — cross-role ownership regression coverage (audit R1/R2, section 12/16).
 *
 * <p>These confirm the ownership checks that already exist keep working, so later
 * phases that introduce the booking state machine / payment ledger cannot regress
 * them. All tests here currently pass.
 */
class AuthorizationOwnershipIntegrationTest extends Phase0IntegrationTestSupport {

    @Autowired RefundRequestRepository refundRequests;

    @Test
    void customerCannotReadAnotherCustomersBooking() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "own-booking-owner-" + System.nanoTime() + "@example.com", "US");
        User stranger = saveUser(Role.CUSTOMER, "own-booking-stranger-" + System.nanoTime() + "@example.com", "US");
        Booking booking = saveBooking(owner.getId(), ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);

        mvc.perform(get("/api/bookings/" + booking.getId())
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotReadAnotherCustomersInvoice() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "own-invoice-owner-" + System.nanoTime() + "@example.com", "US");
        User stranger = saveUser(Role.CUSTOMER, "own-invoice-stranger-" + System.nanoTime() + "@example.com", "US");
        Booking booking = saveBooking(owner.getId(), ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("GOS-US-INV-" + booking.getId());
        invoice.setBookingId(booking.getId());
        invoice.setCustomerId(owner.getId());
        invoice.setAmount(41.0);
        invoice.setCurrency("USD");
        invoice.setPaymentStatus("PAID");
        Long invoiceId = invoices.save(invoice).getId();

        mvc.perform(get("/api/invoices/" + invoiceId)
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotRefundAnotherCustomersBooking() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "own-refund-owner-" + System.nanoTime() + "@example.com", "US");
        User stranger = saveUser(Role.CUSTOMER, "own-refund-stranger-" + System.nanoTime() + "@example.com", "US");
        Booking booking = saveBooking(owner.getId(), ServiceMode.ONSITE, "PAID", BookingStatus.SERVICE_COMPLETED);

        int status = statusOf(post("/api/refunds/bookings/" + booking.getId())
                .header("Authorization", bearer(stranger))
                .contentType("application/json")
                .content("{\"reason\":\"I want a refund\"}"));

        assertTrue(status >= 400, "a stranger's refund request must not succeed");
        assertEquals(0L, refundRequests.count(),
                "a stranger must not create a refund request for another customer's booking");
    }

    @Test
    void customerCannotAccessAnotherCustomersRemoteChat() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "own-chat-owner-" + System.nanoTime() + "@example.com", "US");
        User stranger = saveUser(Role.CUSTOMER, "own-chat-stranger-" + System.nanoTime() + "@example.com", "US");
        Booking booking = saveBooking(owner.getId(), ServiceMode.REMOTE, "PAID", BookingStatus.REMOTE_SESSION_STARTED);

        mvc.perform(get("/api/remote-session-chat/" + booking.getId() + "/messages")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unassignedTechnicianCannotReadAnotherTechniciansBooking() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "own-techbook-owner-" + System.nanoTime() + "@example.com", "US");
        Technician assigned = saveTechnician("own-techbook-assigned-" + System.nanoTime() + "@example.com",
                "APPROVED", "BUSY", "REMOTE_AND_ONSITE");
        String otherEmail = "own-techbook-other-" + System.nanoTime() + "@example.com";
        saveTechnician(otherEmail, "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        User otherTechUser = users.findByEmail(otherEmail).orElseThrow();

        Booking booking = saveBookingForCustomer(owner.getId(), assigned.getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.TECHNICIAN_ASSIGNED);

        mvc.perform(get("/api/bookings/" + booking.getId())
                        .header("Authorization", bearer(otherTechUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCanListBookingsForOperations() throws Exception {
        User agent = saveUser(Role.AGENT, "own-list-agent-" + System.nanoTime() + "@example.com", "US");

        mvc.perform(get("/api/bookings")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isOk());
    }
}
