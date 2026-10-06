package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — tracking must not mutate the booking lifecycle (audit H3/H4, BUG-07/08).
 *
 * <p>{@code BookingService.updateTechnicianLocation} currently forces
 * {@code TECHNICIAN_ON_THE_WAY}, which can implicitly accept a job and can regress
 * {@code TECHNICIAN_ARRIVED} backward. It also still writes coordinates onto
 * completed/closed/cancelled bookings. These tests encode the required contract and
 * currently fail until the Phase 2 lifecycle/tracking fix.
 */
class TrackingLifecycleRegressionTest extends Phase0IntegrationTestSupport {

    private record Actor(Technician technician, String token) {}

    private Actor assignedTechnician() {
        String email = "tracking-tech-" + System.nanoTime() + "@example.com";
        Technician technician = saveTechnician(email, "APPROVED", "BUSY", "ONSITE_ONLY");
        User user = users.findByEmail(email).orElseThrow();
        return new Actor(technician, bearer(user));
    }

    private String locationBody(double latitude, double longitude, Double remainingDistanceKm) {
        return "{"
                + "\"latitude\":" + latitude + ","
                + "\"longitude\":" + longitude + ","
                + "\"etaMinutes\":10,"
                + "\"remainingDistanceKm\":" + remainingDistanceKm + ","
                + "\"speed\":30.0,"
                + "\"heading\":90.0,"
                + "\"currentRoad\":\"Main St\","
                + "\"liveTrackingStatus\":\"ON_THE_WAY\""
                + "}";
    }

    @Tag("expected-failure")
    @Test
    void aLocationUpdateMustNotImplicitlyAcceptAnAssignedJob() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.TECHNICIAN_ASSIGNED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/technician/location")
                        .header("Authorization", actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody(37.77, -122.41, 5.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ASSIGNED"));
    }

    @Tag("expected-failure")
    @Test
    void aLocationUpdateMustNotRegressArrivedBackToOnTheWay() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.TECHNICIAN_ARRIVED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/technician/location")
                        .header("Authorization", actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody(37.77, -122.41, 5.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("TECHNICIAN_ARRIVED"));
    }

    @Tag("expected-failure")
    @Test
    void aLocationUpdateMustNotMutateACompletedBooking() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.SERVICE_COMPLETED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/technician/location")
                        .header("Authorization", actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody(12.34, 56.78, 1.0)))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void aLocationUpdateMustNotMutateAClosedBooking() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.BOOKING_CLOSED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/technician/location")
                        .header("Authorization", actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody(12.34, 56.78, 1.0)))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void aLocationUpdateMustNotMutateACancelledBooking() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.CANCELLED);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/technician/location")
                        .header("Authorization", actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody(12.34, 56.78, 1.0)))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void outOfRangeCoordinatesMustBeRejected() throws Exception {
        Actor actor = assignedTechnician();
        Booking booking = saveBookingForCustomer(999L, actor.technician().getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.TECHNICIAN_ON_THE_WAY);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/technician/location")
                        .header("Authorization", actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody(999.0, 999.0, 1.0)))
                .andExpect(status().is4xxClientError());
    }
}
