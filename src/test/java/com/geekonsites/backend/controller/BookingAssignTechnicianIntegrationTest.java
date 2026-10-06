package com.geekonsites.backend.controller;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.jwt.JwtService;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the "Agent Assign Technician" service-mode mismatch bug:
 * production logs showed BookingService.assignTechnician throwing for a
 * technician not approved for the booking's service mode, and that
 * expected business validation was surfacing to callers (the Agent UI
 * included) as a generic HTTP 500 instead of a 4xx.
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:assigntechnician;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=assign-technician-test-secret-key-with-at-least-32-characters",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
@AutoConfigureMockMvc
class BookingAssignTechnicianIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired BookingRepository bookings;
    @Autowired TechnicianRepository technicians;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwordEncoder;

    private String agentToken;

    @BeforeEach
    void setUp() {
        bookings.deleteAll();
        technicians.deleteAll();
        users.deleteAll();

        User agent = new User();
        agent.setFullName("Assign Technician Agent");
        agent.setEmail("assign-agent@geekonsites.com");
        agent.setPassword(passwordEncoder.encode("Password123!"));
        agent.setCountry("UK");
        agent.setRole(Role.AGENT);
        users.save(agent);
        agentToken = jwt.generateToken(agent);
    }

    private Booking paidBooking(ServiceMode mode) {
        Booking booking = new Booking();
        booking.setCustomerId(1L);
        booking.setCustomerName("Test Customer");
        booking.setServiceType("Laptop repair");
        booking.setServiceMode(mode);
        booking.setPaymentStatus("PAID");
        booking.setBookingStatus(BookingStatus.PAYMENT_COMPLETED);
        return bookings.save(booking);
    }

    private Technician technician(String serviceMode) {
        Technician technician = new Technician();
        technician.setName("Test Technician");
        technician.setEmail("tech-" + System.nanoTime() + "@geekonsites.com");
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setServiceMode(serviceMode);
        return technicians.save(technician);
    }

    @Test
    void onsiteOnlyTechnicianIsRejectedForRemoteBookingWithBadRequestNotServerError() throws Exception {
        Booking booking = paidBooking(ServiceMode.REMOTE);
        Technician onsiteOnly = technician("ONSITE_ONLY");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + onsiteOnly.getId())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This technician is not approved for the booking's service mode"));
    }

    @Test
    void remoteOnlyTechnicianIsRejectedForOnsiteBookingWithBadRequestNotServerError() throws Exception {
        Booking booking = paidBooking(ServiceMode.ONSITE);
        Technician remoteOnly = technician("REMOTE_ONLY");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + remoteOnly.getId())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This technician is not approved for the booking's service mode"));
    }

    @Test
    void remoteOnlyTechnicianIsAssignedSuccessfullyForRemoteBooking() throws Exception {
        Booking booking = paidBooking(ServiceMode.REMOTE);
        Technician remoteOnly = technician("REMOTE_ONLY");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + remoteOnly.getId())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicianId").value(remoteOnly.getId()))
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ASSIGNED"));
    }

    @Test
    void technicianApprovedForBothModesIsAllowedOnEitherBookingMode() throws Exception {
        Booking remoteBooking = paidBooking(ServiceMode.REMOTE);
        Booking onsiteBooking = paidBooking(ServiceMode.ONSITE);
        Technician both = technician("REMOTE_AND_ONSITE");

        mvc.perform(put("/api/bookings/" + remoteBooking.getId() + "/assign-technician/" + both.getId())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk());

        // Free the technician back up for the second assignment.
        both.setAvailabilityStatus("AVAILABLE");
        technicians.save(both);

        mvc.perform(put("/api/bookings/" + onsiteBooking.getId() + "/assign-technician/" + both.getId())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk());
    }

    @Test
    void technicianWithNoServiceModeSetDefaultsToBothAndIsAllowed() throws Exception {
        Booking booking = paidBooking(ServiceMode.ONSITE);
        Technician noModeSet = technician(null);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + noModeSet.getId())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk());
    }

    @Test
    void unknownTechnicianReturnsNotFoundNotServerError() throws Exception {
        Booking booking = paidBooking(ServiceMode.REMOTE);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/999999")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Technician not found"));
    }

    @Test
    void unapprovedTechnicianReturnsBadRequestNotServerError() throws Exception {
        Booking booking = paidBooking(ServiceMode.REMOTE);
        Technician unapproved = technician("REMOTE_AND_ONSITE");
        unapproved.setVerificationStatus("PENDING");
        technicians.save(unapproved);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + unapproved.getId())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only approved technicians can be assigned"));
    }

    @Test
    void unpaidBookingReturnsBadRequestNotServerError() throws Exception {
        Booking booking = paidBooking(ServiceMode.REMOTE);
        booking.setPaymentStatus("PENDING");
        bookings.save(booking);
        Technician eligible = technician("REMOTE_AND_ONSITE");

        mvc.perform(put("/api/bookings/" + booking.getId() + "/assign-technician/" + eligible.getId())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A technician can only be assigned after the required payment is confirmed"));
    }
}
