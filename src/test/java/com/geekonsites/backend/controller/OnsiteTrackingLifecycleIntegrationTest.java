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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the real-GPS ONSITE tracking lifecycle end to end against the
 * actual HTTP + database stack: accept -> on-the-way -> location updates
 * -> arrived -> start service -> complete service, plus the ownership
 * checks that make live tracking safe (only the assigned technician may
 * push location, only the booking's own customer may read tracking).
 * None of this was covered by an existing test before.
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:onsitetracking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=onsite-tracking-test-secret-key-with-32-characters",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
@AutoConfigureMockMvc
class OnsiteTrackingLifecycleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TechnicianRepository technicians;
    @Autowired BookingRepository bookings;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwordEncoder;

    private Long technicianId;
    private String technicianToken;
    private String otherTechnicianToken;
    private Long customerId;
    private String customerToken;
    private String otherCustomerToken;
    private Long bookingId;

    @BeforeEach
    void setUp() {
        bookings.deleteAll();
        technicians.deleteAll();
        users.deleteAll();

        technicianId = technician("tech@example.com");
        technicianToken = loginToken("tech@example.com");
        technician("other-tech@example.com");
        otherTechnicianToken = loginToken("other-tech@example.com");

        User customer = customer("customer@example.com");
        customerId = customer.getId();
        customerToken = loginToken("customer@example.com");
        customer("other-customer@example.com");
        otherCustomerToken = loginToken("other-customer@example.com");

        Booking booking = new Booking();
        booking.setCustomerId(customerId);
        booking.setCustomerName("Test Customer");
        booking.setServiceType("Network setup");
        booking.setServiceMode(ServiceMode.ONSITE);
        booking.setPaymentStatus("PAID");
        booking.setTechnicianId(technicianId);
        booking.setTechnicianName("Test Technician");
        booking.setBookingStatus(BookingStatus.TECHNICIAN_ASSIGNED);
        bookingId = bookings.save(booking).getId();
    }

    private Long technician(String email) {
        Technician technician = new Technician();
        technician.setName("Technician " + email);
        technician.setPersonalEmail(email);
        technician.setEmail(email);
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("BUSY");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        Long id = technicians.save(technician).getId();

        User user = new User();
        user.setFullName("Technician " + email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Password123!"));
        user.setCountry("US");
        user.setRole(Role.TECHNICIAN);
        users.save(user);
        return id;
    }

    private User customer(String email) {
        User user = new User();
        user.setFullName("Customer " + email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Password123!"));
        user.setCountry("US");
        user.setRole(Role.CUSTOMER);
        return users.save(user);
    }

    private String loginToken(String email) {
        User user = users.findByEmail(email).orElseThrow();
        return jwt.generateToken(user);
    }

    private String auth(String token) {
        return "Bearer " + token;
    }

    @Test
    void assignedTechnicianCanAcceptJob() throws Exception {
        mvc.perform(put("/api/bookings/" + bookingId + "/technician/accept")
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ACCEPTED"));
    }

    @Test
    void wrongTechnicianCannotAcceptJob() throws Exception {
        mvc.perform(put("/api/bookings/" + bookingId + "/technician/accept")
                        .header("Authorization", auth(otherTechnicianToken)))
                .andExpect(status().isForbidden());

        assertEquals(BookingStatus.TECHNICIAN_ASSIGNED, bookings.findById(bookingId).orElseThrow().getBookingStatus());
    }

    @Test
    void assignedTechnicianCanGoOnTheWayAfterAccepting() throws Exception {
        accept();

        mvc.perform(put("/api/bookings/" + bookingId + "/technician/on-the-way")
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ON_THE_WAY"));
    }

    @Test
    void assignedTechnicianLocationUpdateSucceedsOnceOnTheWay() throws Exception {
        accept();
        onTheWay();

        mvc.perform(put("/api/bookings/" + bookingId + "/technician/location")
                        .header("Authorization", auth(technicianToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.7749,\"longitude\":-122.4194,\"etaMinutes\":12,\"remainingDistanceKm\":5.2,\"liveTrackingStatus\":\"ON_THE_WAY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicianLatitude").value(37.7749))
                .andExpect(jsonPath("$.technicianLongitude").value(-122.4194));
    }

    @Test
    void locationUpdateByAnUnassignedTechnicianIsRejected() throws Exception {
        accept();
        onTheWay();

        mvc.perform(put("/api/bookings/" + bookingId + "/technician/location")
                        .header("Authorization", auth(otherTechnicianToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":1.0,\"longitude\":1.0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bookingCustomerCanFetchTracking() throws Exception {
        accept();
        onTheWay();

        mvc.perform(get("/api/bookings/" + bookingId + "/tracking")
                        .header("Authorization", auth(customerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bookingId));
    }

    @Test
    void unrelatedCustomerCannotFetchTracking() throws Exception {
        accept();
        onTheWay();

        mvc.perform(get("/api/bookings/" + bookingId + "/tracking")
                        .header("Authorization", auth(otherCustomerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void technicianCanListOwnBookings() throws Exception {
        mvc.perform(get("/api/bookings/technician/" + technicianId)
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isOk());
    }

    @Test
    void technicianCannotListAnotherTechniciansBookings() throws Exception {
        // Previously fell through to a bare "authenticated()" security rule
        // with no ownership check at the controller level either, so any
        // authenticated technician could list another technician's full
        // booking history including live GPS coordinates.
        mvc.perform(get("/api/bookings/technician/" + technicianId)
                        .header("Authorization", auth(otherTechnicianToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotListAnyTechniciansBookings() throws Exception {
        mvc.perform(get("/api/bookings/technician/" + technicianId)
                        .header("Authorization", auth(customerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void arrivedStopsTheJourneyState() throws Exception {
        accept();
        onTheWay();

        mvc.perform(put("/api/bookings/" + bookingId + "/technician/arrived")
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ARRIVED"))
                .andExpect(jsonPath("$.technicianArrived").value(true))
                .andExpect(jsonPath("$.trackingEnabled").value(false));
    }

    @Test
    void startServiceRequiresArrivedFirst() throws Exception {
        accept();
        onTheWay();
        // Deliberately skip "arrived" and try to start service directly.

        mvc.perform(put("/api/bookings/" + bookingId + "/technician/start-service")
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The technician must arrive before starting an on-site service"));

        assertEquals(BookingStatus.TECHNICIAN_ON_THE_WAY, bookings.findById(bookingId).orElseThrow().getBookingStatus());
    }

    @Test
    void startServiceSucceedsAfterArrived() throws Exception {
        accept();
        onTheWay();
        arrived();

        mvc.perform(put("/api/bookings/" + bookingId + "/technician/start-service")
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("SERVICE_STARTED"));
    }

    @Test
    void completeServiceRequiresServiceStartedFirst() throws Exception {
        accept();
        onTheWay();
        arrived();
        // Deliberately skip "start service".

        mvc.perform(put("/api/bookings/" + bookingId + "/technician/complete-service")
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Service cannot be completed now"));

        assertEquals(BookingStatus.TECHNICIAN_ARRIVED, bookings.findById(bookingId).orElseThrow().getBookingStatus());
    }

    @Test
    void completeServiceSucceedsAfterServiceStarted() throws Exception {
        accept();
        onTheWay();
        arrived();
        mvc.perform(put("/api/bookings/" + bookingId + "/technician/start-service")
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/bookings/" + bookingId + "/technician/complete-service")
                        .header("Authorization", auth(technicianToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("SERVICE_COMPLETED"));

        assertEquals("AVAILABLE", technicians.findById(technicianId).orElseThrow().getAvailabilityStatus());
    }

    private void accept() throws Exception {
        mvc.perform(put("/api/bookings/" + bookingId + "/technician/accept")
                .header("Authorization", auth(technicianToken))).andExpect(status().isOk());
    }

    private void onTheWay() throws Exception {
        mvc.perform(put("/api/bookings/" + bookingId + "/technician/on-the-way")
                .header("Authorization", auth(technicianToken))).andExpect(status().isOk());
    }

    private void arrived() throws Exception {
        mvc.perform(put("/api/bookings/" + bookingId + "/technician/arrived")
                .header("Authorization", auth(technicianToken))).andExpect(status().isOk());
    }
}
