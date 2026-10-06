package com.geekonsites.backend.controller;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.jwt.JwtService;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the previously-missing piece of the rating requirement: submitting
 * a rating must update the technician's persisted aggregate rating
 * (Technician.rating), not just feed the on-demand average returned by
 * GET /api/ratings/technician/{id}. Technician.rating was previously set
 * once at registration (0.0) and never touched again - it's shown directly
 * on the technician's own profile, so it was permanently stale.
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:ratingaggregate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=rating-aggregate-test-secret-key-with-32-characters",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
@AutoConfigureMockMvc
class RatingSubmissionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TechnicianRepository technicians;
    @Autowired BookingRepository bookings;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        bookings.deleteAll();
        technicians.deleteAll();
        users.deleteAll();
    }

    @Test
    void submittingARatingUpdatesTheTechniciansPersistedAggregateRating() throws Exception {
        Technician technician = new Technician();
        technician.setName("Rated Technician");
        technician.setPersonalEmail("rated-tech@example.com");
        technician.setEmail("rated-tech@example.com");
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        technician.setRating(0.0);
        Long technicianId = technicians.save(technician).getId();

        User customer = new User();
        customer.setFullName("Rating Customer");
        customer.setEmail("rating-customer@example.com");
        customer.setPassword(passwordEncoder.encode("Password123!"));
        customer.setCountry("US");
        customer.setRole(Role.CUSTOMER);
        Long customerId = users.save(customer).getId();
        String customerToken = jwt.generateToken(customer);

        Booking booking = new Booking();
        booking.setCustomerId(customerId);
        booking.setTechnicianId(technicianId);
        booking.setServiceMode(ServiceMode.ONSITE);
        booking.setPaymentStatus("PAID");
        booking.setBookingStatus(BookingStatus.SERVICE_COMPLETED);
        Long bookingId = bookings.save(booking).getId();

        mvc.perform(post("/api/ratings")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + bookingId + ",\"rating\":5,\"review\":\"Great work\"}"))
                .andExpect(status().isOk());

        assertEquals(5.0, technicians.findById(technicianId).orElseThrow().getRating());
    }

    @Test
    void unrelatedCustomerCannotRateSomeoneElsesBooking() throws Exception {
        Technician technician = new Technician();
        technician.setName("Rated Technician");
        technician.setPersonalEmail("rated-tech-2@example.com");
        technician.setEmail("rated-tech-2@example.com");
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        technician.setRating(0.0);
        Long technicianId = technicians.save(technician).getId();

        User owner = new User();
        owner.setFullName("Owner Customer");
        owner.setEmail("owner-customer@example.com");
        owner.setPassword(passwordEncoder.encode("Password123!"));
        owner.setCountry("US");
        owner.setRole(Role.CUSTOMER);
        Long ownerId = users.save(owner).getId();

        User stranger = new User();
        stranger.setFullName("Unrelated Customer");
        stranger.setEmail("unrelated-customer@example.com");
        stranger.setPassword(passwordEncoder.encode("Password123!"));
        stranger.setCountry("US");
        stranger.setRole(Role.CUSTOMER);
        users.save(stranger);
        String strangerToken = jwt.generateToken(stranger);

        Booking booking = new Booking();
        booking.setCustomerId(ownerId);
        booking.setTechnicianId(technicianId);
        booking.setServiceMode(ServiceMode.ONSITE);
        booking.setPaymentStatus("PAID");
        booking.setBookingStatus(BookingStatus.SERVICE_COMPLETED);
        Long bookingId = bookings.save(booking).getId();

        mvc.perform(post("/api/ratings")
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + bookingId + ",\"rating\":5,\"review\":\"Not mine\"}"))
                .andExpect(status().isForbidden());

        assertEquals(0.0, technicians.findById(technicianId).orElseThrow().getRating(), "an unauthorized rating attempt must not touch the aggregate");
    }

    @Test
    void ratingBeforeServiceCompletionIsRejectedAsBadRequestNotServerError() throws Exception {
        Technician technician = new Technician();
        technician.setName("Rated Technician");
        technician.setPersonalEmail("rated-tech-3@example.com");
        technician.setEmail("rated-tech-3@example.com");
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        Long technicianId = technicians.save(technician).getId();

        User customer = new User();
        customer.setFullName("Early Rater");
        customer.setEmail("early-rater@example.com");
        customer.setPassword(passwordEncoder.encode("Password123!"));
        customer.setCountry("US");
        customer.setRole(Role.CUSTOMER);
        Long customerId = users.save(customer).getId();
        String customerToken = jwt.generateToken(customer);

        Booking booking = new Booking();
        booking.setCustomerId(customerId);
        booking.setTechnicianId(technicianId);
        booking.setServiceMode(ServiceMode.ONSITE);
        booking.setPaymentStatus("PARTIALLY_PAID");
        // Still in progress - not eligible for rating yet.
        booking.setBookingStatus(BookingStatus.SERVICE_STARTED);
        Long bookingId = bookings.save(booking).getId();

        mvc.perform(post("/api/ratings")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + bookingId + ",\"rating\":5,\"review\":\"Too soon\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A booking can be rated only after service completion"));
    }
}
