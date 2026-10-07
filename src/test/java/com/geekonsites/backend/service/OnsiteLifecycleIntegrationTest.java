package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 2 — full ON-SITE lifecycle integration (Steps 34 + 36).
 *
 * <p>Drives the real API/domain path: advance payment (real payment finalization),
 * assignment, acceptance, travel, tracking, arrival, service start, completion with a
 * remaining balance, remaining payment, and closure. The only external dependency
 * (Stripe) is supplied through the real payment-finalization method; no booking status
 * is set directly to advance the happy path. Verifies the Phase 1 ledger still holds
 * both captures.
 */
class OnsiteLifecycleIntegrationTest extends Phase0IntegrationTestSupport {

    @Autowired PaymentService paymentService;

    private Session session(String id, long amountTotal, Long bookingId, String paymentType) {
        Session session = new Session();
        session.setId(id);
        session.setPaymentStatus("paid");
        session.setAmountTotal(amountTotal);
        session.setCurrency("usd");
        session.setMetadata(Map.of("bookingId", String.valueOf(bookingId), "paymentType", paymentType));
        return session;
    }

    private Booking reload(Long bookingId) {
        return bookings.findById(bookingId).orElseThrow();
    }

    @Test
    void fullOnsiteLifecycleWithSplitPayment() throws Exception {
        long stamp = System.nanoTime();
        User agent = saveUser(Role.AGENT, "onsite-agent-" + stamp + "@example.com", "US");
        Technician technician = saveTechnician("onsite-tech-" + stamp + "@example.com",
                "APPROVED", "AVAILABLE", "ONSITE_ONLY");
        User technicianUser = users.findByEmail(technician.getPersonalEmail()).orElseThrow();

        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PENDING", BookingStatus.PENDING);
        booking.setAdvanceAmount(30.0);
        booking.setRemainingAmount(70.0);
        booking.setTotalAmount(100.0);
        booking.setPaidAmount(0.0);
        booking = bookings.save(booking);
        Long id = booking.getId();

        // 1. Required advance payment (real verified payment finalization path).
        paymentService.applyCompletedCheckoutSession(session("cs_adv_" + stamp, 3000L, id, "ADVANCE"), null);
        assertEquals(BookingStatus.ASSIGNMENT_PENDING, reload(id).getBookingStatus());
        assertEquals("PARTIALLY_PAID", reload(id).getPaymentStatus());

        // 2. Assignment (agent).
        mvc.perform(put("/api/bookings/" + id + "/assign-technician/" + technician.getId())
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ASSIGNED"));

        // 3. Acceptance.
        mvc.perform(put("/api/bookings/" + id + "/technician/accept")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ACCEPTED"));

        // 4. On the way.
        mvc.perform(put("/api/bookings/" + id + "/technician/on-the-way")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ON_THE_WAY"));

        // 5. Tracking update must not change lifecycle.
        mvc.perform(put("/api/bookings/" + id + "/technician/location")
                        .header("Authorization", bearer(technicianUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.77,\"longitude\":-122.41,\"etaMinutes\":8,"
                                + "\"remainingDistanceKm\":3.0,\"liveTrackingStatus\":\"ON_THE_WAY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ON_THE_WAY"));

        // 6. Arrival.
        mvc.perform(put("/api/bookings/" + id + "/technician/arrived")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ARRIVED"));

        // 7. Start service.
        mvc.perform(put("/api/bookings/" + id + "/technician/start-service")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("SERVICE_STARTED"));

        // 8. Complete service -> balance due.
        mvc.perform(put("/api/bookings/" + id + "/technician/complete-service")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("REMAINING_PAYMENT_PENDING"));
        assertEquals("AVAILABLE", technicians.findById(technician.getId()).orElseThrow().getAvailabilityStatus());

        // 9. Remaining payment (real verified payment finalization path).
        paymentService.applyCompletedCheckoutSession(session("cs_rem_" + stamp, 7000L, id, "REMAINING"), null);
        assertEquals(BookingStatus.SERVICE_COMPLETED, reload(id).getBookingStatus());
        assertEquals("PAID", reload(id).getPaymentStatus());

        // 10. Phase 1 ledger still holds both captures.
        assertEquals(2, paymentTransactions.findByBookingIdOrderByCreatedAtAsc(id).size());
        assertEquals(10000L, paymentTransactions.sumSuccessfulAmountMinorByBookingId(id));

        // 11. Closure requires completed + settled + invoice.
        mvc.perform(put("/api/bookings/" + id + "/close")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("BOOKING_CLOSED"));
    }
}
