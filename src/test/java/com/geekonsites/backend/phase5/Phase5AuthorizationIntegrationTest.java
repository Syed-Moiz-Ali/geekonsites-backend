package com.geekonsites.backend.phase5;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Rating;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.service.RatingService;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 5 — additional authorization/domain-integrity coverage.
 */
class Phase5AuthorizationIntegrationTest extends Phase0IntegrationTestSupport {

    @Autowired RatingService ratingService;

    private String registrationBody(String email, String country, String extra) {
        return "{"
                + "\"fullName\":\"Phase Five\","
                + "\"email\":\"" + email + "\","
                + "\"password\":\"Password123!\","
                + "\"phone\":\"+15550000000\""
                + (country == null ? "" : ",\"country\":\"" + country + "\"")
                + (extra == null ? "" : extra)
                + "}";
    }

    @Test
    void publicRegistrationCannotGrantPrivilegedRole() throws Exception {
        long stamp = System.nanoTime();
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("role-attempt-" + stamp + "@example.com", "US", ",\"role\":\"ADMIN\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        assertEquals(Role.CUSTOMER, users.findByEmail("role-attempt-" + stamp + "@example.com").orElseThrow().getRole());
    }

    @Test
    void caseVariantDuplicateEmailIsRejected() throws Exception {
        long stamp = System.nanoTime();
        String email = "case-" + stamp + "@Example.com";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody(email, "US", null)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody(email.toLowerCase(), "UK", null)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void customerCreationCannotSpoofAnotherCustomer() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "spoof-customer-" + System.nanoTime() + "@example.com", "US");
        String body = "{"
                + "\"customerId\":999999,"
                + "\"serviceType\":\"PC Health Check & Diagnosis\","
                + "\"serviceMode\":\"REMOTE\","
                + "\"issueDescription\":\"slow\","
                + "\"address\":\"1 Main St\",\"city\":\"NY\",\"state\":\"NY\","
                + "\"country\":\"US\",\"postalCode\":\"10001\","
                + "\"bookingDate\":\"2026-12-01\",\"timeSlot\":\"10:00 AM\""
                + "}";

        mvc.perform(post("/api/bookings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customer.getId()));
    }

    @Test
    void concurrentDuplicateRatingStoresExactlyOne() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "conc-rating-" + System.nanoTime() + "@example.com", "US");
        Technician technician = saveTechnician("conc-rating-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        Booking booking = saveBookingForCustomer(customer.getId(), technician.getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.SERVICE_COMPLETED);
        Long bookingId = booking.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<?>> tasks = List.of(
                () -> submitAfterBarrier(barrier, bookingId, customer),
                () -> submitAfterBarrier(barrier, bookingId, customer));
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) futures.add(pool.submit(task));
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // a losing race may throw the 409 conflict (RuntimeException) here
            }
        }
        pool.shutdownNow();

        List<Rating> stored = ratings.findByTechnicianId(technician.getId());
        assertEquals(1, stored.size(), "exactly one rating row must persist under concurrency");
        long forBooking = ratings.findAll().stream().filter(r -> bookingId.equals(r.getBookingId())).count();
        assertEquals(1, forBooking);
        // Aggregate reflects a single review.
        assertEquals(5.0, technicians.findById(technician.getId()).orElseThrow().getRating());
    }

    private Object submitAfterBarrier(CyclicBarrier barrier, Long bookingId, User customer) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return ratingService.submitRating(bookingId, 5, "Great", customer);
    }

    @Test
    void completedBookingRatingUpdatesTechnicianAggregateOnce() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "agg-rating-" + System.nanoTime() + "@example.com", "US");
        Technician technician = saveTechnician("agg-rating-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        Booking booking = saveBookingForCustomer(customer.getId(), technician.getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.SERVICE_COMPLETED);

        mvc.perform(post("/api/ratings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + booking.getId() + ",\"rating\":4,\"review\":\"Good\"}"))
                .andExpect(status().isOk());

        assertEquals(4.0, technicians.findById(technician.getId()).orElseThrow().getRating());
        assertTrue(ratings.existsByBookingId(booking.getId()));
    }
}
