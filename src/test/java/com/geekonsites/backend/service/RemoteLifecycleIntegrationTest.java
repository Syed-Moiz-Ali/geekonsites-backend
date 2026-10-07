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
 * PHASE 2 — full REMOTE lifecycle integration (Step 35).
 *
 * <p>Drives the real API/domain path: full payment (real payment finalization),
 * assignment, acceptance, meeting-link save, remote-session start and end, and closure.
 * No booking status is set directly to advance the happy path.
 */
class RemoteLifecycleIntegrationTest extends Phase0IntegrationTestSupport {

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
    void fullRemoteLifecycle() throws Exception {
        long stamp = System.nanoTime();
        User agent = saveUser(Role.AGENT, "remote-agent-" + stamp + "@example.com", "US");
        Technician technician = saveTechnician("remote-tech-" + stamp + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_ONLY");
        User technicianUser = users.findByEmail(technician.getPersonalEmail()).orElseThrow();

        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);
        booking.setTotalAmount(100.0);
        booking.setPaidAmount(0.0);
        booking = bookings.save(booking);
        Long id = booking.getId();

        // 1. Full payment (real verified payment finalization path).
        paymentService.applyCompletedCheckoutSession(session("cs_full_" + stamp, 10000L, id, "FULL"), null);
        assertEquals(BookingStatus.PAYMENT_COMPLETED, reload(id).getBookingStatus());
        assertEquals("PAID", reload(id).getPaymentStatus());

        // 2. Assignment.
        mvc.perform(put("/api/bookings/" + id + "/assign-technician/" + technician.getId())
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ASSIGNED"));

        // 3. Acceptance.
        mvc.perform(put("/api/bookings/" + id + "/technician/accept")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ACCEPTED"));

        // 4. Save the meeting link.
        mvc.perform(put("/api/bookings/" + id + "/meeting-link")
                        .header("Authorization", bearer(technicianUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingLink\":\"https://meet.google.com/abc-defg-hij\"}"))
                .andExpect(status().isOk());

        // 5. Start the remote session.
        mvc.perform(put("/api/remote-sessions/booking/" + id + "/start")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("REMOTE_SESSION_STARTED"));

        // 6. End the remote session -> service completed.
        mvc.perform(put("/api/remote-sessions/booking/" + id + "/end")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("SERVICE_COMPLETED"));

        // 7. Close (completed + paid + invoice).
        mvc.perform(put("/api/bookings/" + id + "/close")
                        .header("Authorization", bearer(agent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("BOOKING_CLOSED"));
    }
}
