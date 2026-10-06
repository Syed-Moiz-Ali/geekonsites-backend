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
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — rating/review contract (audit H7 / BUG-13).
 *
 * <p>There is no uniqueness guarantee on {@code ratings.booking_id}, so a customer can
 * post unlimited reviews and repeatedly inflate the technician aggregate. The required
 * contract is one review per completed booking. The failing test must pass after
 * Phase 1. (Cannot-rate-another-customers-booking and cannot-rate-before-completion
 * are already covered by {@code RatingSubmissionIntegrationTest} and are not
 * duplicated.)
 */
class RatingRegressionTest extends Phase0IntegrationTestSupport {

    @Tag("expected-failure")
    @Test
    void secondRatingForTheSameBookingMustBeRejected() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "rating-customer-" + System.nanoTime() + "@example.com", "US");
        Technician technician = saveTechnician("rating-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        Booking booking = saveBookingForCustomer(customer.getId(), technician.getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.SERVICE_COMPLETED);

        String body = "{\"bookingId\":" + booking.getId() + ",\"rating\":5,\"review\":\"Great\"}";

        mvc.perform(post("/api/ratings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mvc.perform(post("/api/ratings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void technicianCannotSubmitACustomerRating() throws Exception {
        Technician technician = saveTechnician("rating-tech-2-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        User technicianUser = users.findByEmail(technician.getPersonalEmail()).orElseThrow();
        Booking booking = saveBookingForCustomer(999L, technician.getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.SERVICE_COMPLETED);

        mvc.perform(post("/api/ratings")
                        .header("Authorization", bearer(technicianUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + booking.getId() + ",\"rating\":5,\"review\":\"Self\"}"))
                .andExpect(status().isForbidden());
    }
}
