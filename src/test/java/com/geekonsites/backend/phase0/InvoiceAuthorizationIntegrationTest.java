package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — invoice authorization-before-mutation contract (audit H9 / BUG-15).
 *
 * <p>{@code InvoiceController.generateInvoiceFromBooking} authorizes only the generic
 * booking read (which allows the assigned technician), mutates/creates the invoice,
 * and only afterward runs {@code verifyAccess} (which rejects technicians). The
 * required contract is that authorization happens before any mutation. The failing
 * tests assert the invoice was not created; the passing tests document that
 * unauthorized customers/technicians are stopped, and that the owner may generate.
 */
class InvoiceAuthorizationIntegrationTest extends Phase0IntegrationTestSupport {

    private record Tech(Technician technician, String token) {}

    private Tech technician(String email) {
        Technician technician = saveTechnician(email, "APPROVED", "BUSY", "REMOTE_AND_ONSITE");
        User user = users.findByEmail(email).orElseThrow();
        return new Tech(technician, bearer(user));
    }

    @Tag("expected-failure")
    @Test
    void assignedTechnicianMustNotCreateOrMutateAnInvoice() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "invoice-owner-" + System.nanoTime() + "@example.com", "US");
        Tech tech = technician("invoice-tech-" + System.nanoTime() + "@example.com");
        Booking booking = saveBookingForCustomer(owner.getId(), tech.technician().getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);

        mvc.perform(post("/api/invoices/booking/" + booking.getId())
                        .header("Authorization", tech.token()))
                .andExpect(status().is4xxClientError());

        assertTrue(invoices.findFirstByBookingIdOrderByIdAsc(booking.getId()).isEmpty(),
                "an unauthorized technician must not cause an invoice row to be persisted");
    }

    @Tag("expected-failure")
    @Test
    void assignedTechnicianMustNotTriggerInvoiceGenerationViaBookingEndpoint() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "invoice-owner-2-" + System.nanoTime() + "@example.com", "US");
        Tech tech = technician("invoice-tech-2-" + System.nanoTime() + "@example.com");
        Booking booking = saveBookingForCustomer(owner.getId(), tech.technician().getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/generate-invoice")
                        .header("Authorization", tech.token()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unrelatedCustomerCannotGenerateAnInvoice() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "invoice-owner-3-" + System.nanoTime() + "@example.com", "US");
        User stranger = saveUser(Role.CUSTOMER, "invoice-stranger-" + System.nanoTime() + "@example.com", "US");
        Booking booking = saveBooking(owner.getId(), ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);

        mvc.perform(post("/api/invoices/booking/" + booking.getId())
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());

        assertTrue(invoices.findFirstByBookingIdOrderByIdAsc(booking.getId()).isEmpty(),
                "a stranger must not create an invoice");
    }

    @Test
    void unassignedTechnicianCannotGenerateAnInvoice() throws Exception {
        User owner = saveUser(Role.CUSTOMER, "invoice-owner-4-" + System.nanoTime() + "@example.com", "US");
        Tech unrelatedTech = technician("invoice-tech-3-" + System.nanoTime() + "@example.com");
        Booking booking = saveBooking(owner.getId(), ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);

        mvc.perform(post("/api/invoices/booking/" + booking.getId())
                        .header("Authorization", unrelatedTech.token()))
                .andExpect(status().isForbidden());

        assertTrue(invoices.findFirstByBookingIdOrderByIdAsc(booking.getId()).isEmpty(),
                "an unassigned technician must not create an invoice");
    }
}
