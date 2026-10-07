package com.geekonsites.backend.phase7;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 7 — one standard error contract, predictable statuses, field-level validation,
 * and no business mutation on invalid input.
 */
class ApiErrorContractIntegrationTest extends Phase0IntegrationTestSupport {

    @Test
    void validationErrorUsesStandardContract() throws Exception {
        long before = users.count();
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"\",\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"))
                .andExpect(jsonPath("$.fieldErrors[*].field", Matchers.hasItem("email")))
                .andExpect(jsonPath("$.fieldErrors[*].field", Matchers.hasItem("password")));
        assertEquals(before, users.count(), "invalid registration must not create a user");
    }

    @Test
    void malformedJsonReturnsStandard400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidEnumInBodyReturns400() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "p7-enum-" + System.nanoTime() + "@example.com", "US");
        mvc.perform(post("/api/bookings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceType\":\"PC Health Check & Diagnosis\",\"serviceMode\":\"BANANA\",\"country\":\"US\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownServiceRejectedWithNoBookingCreated() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "p7-unknown-" + System.nanoTime() + "@example.com", "US");
        long before = bookings.count();
        mvc.perform(post("/api/bookings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceType\":\"No Such Service\",\"serviceMode\":\"REMOTE\",\"country\":\"US\","
                                + "\"address\":\"1 Main St\",\"city\":\"NY\",\"state\":\"NY\",\"postalCode\":\"10001\","
                                + "\"bookingDate\":\"2026-12-01\",\"timeSlot\":\"10:00 AM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(before, bookings.count(), "an invalid booking must not be persisted");
    }

    @Test
    void notFoundUsesStandardContract() throws Exception {
        mvc.perform(get("/api/services/NO_SUCH_SERVICE_CODE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unauthenticatedUses401Contract() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void forbiddenUses403Contract() throws Exception {
        User agent = saveUser(Role.AGENT, "p7-forbidden-" + System.nanoTime() + "@example.com", "US");
        mvc.perform(post("/api/bookings")
                        .header("Authorization", bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceType\":\"PC Health Check & Diagnosis\",\"serviceMode\":\"REMOTE\",\"country\":\"US\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void outOfRangeCoordinatesAreRejectedWithFieldErrorsAndNoMutation() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "p7-loc-" + System.nanoTime() + "@example.com", "US");
        Booking booking = saveBooking(customer.getId(), ServiceMode.ONSITE, "PENDING", BookingStatus.PENDING);

        mvc.perform(put("/api/bookings/" + booking.getId() + "/customer-location")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":999.0,\"longitude\":0.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[*].field", Matchers.hasItem("latitude")));

        assertNull(bookings.findById(booking.getId()).orElseThrow().getCustomerLatitude(),
                "an invalid location must not be persisted");
    }

    @Test
    void outOfRangeRatingIsRejectedWithNoReviewCreated() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "p7-rating-" + System.nanoTime() + "@example.com", "US");
        Technician technician = saveTechnician("p7-rating-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        Booking booking = saveBookingForCustomer(customer.getId(), technician.getId(),
                ServiceMode.REMOTE, "PAID", BookingStatus.SERVICE_COMPLETED);

        mvc.perform(post("/api/ratings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + booking.getId() + ",\"rating\":9,\"review\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[*].field", Matchers.hasItem("rating")));

        assertFalse(ratings.existsByBookingId(booking.getId()),
                "an invalid rating must not be persisted");
    }

    @Test
    void duplicateServiceCodeReturnsConflictContract() throws Exception {
        User admin = saveUser(Role.ADMIN, "p7-svc-" + System.nanoTime() + "@example.com", "US");
        String code = "P7_DUP_" + System.nanoTime();
        String body = "{\"code\":\"" + code + "\",\"name\":\"Dup\",\"serviceMode\":\"REMOTE\",\"usdPrice\":10,\"gbpPrice\":8}";
        mvc.perform(post("/api/admin/services").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/services").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void invalidServicePriceReturnsValidationContract() throws Exception {
        User admin = saveUser(Role.ADMIN, "p7-price-" + System.nanoTime() + "@example.com", "US");
        mvc.perform(post("/api/admin/services").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"P7_BADPRICE_" + System.nanoTime() + "\",\"name\":\"Bad\",\"serviceMode\":\"REMOTE\",\"usdPrice\":0,\"gbpPrice\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidBookingTransitionUsesStandardContract() throws Exception {
        Technician technician = saveTechnician("p7-trans-tech-" + System.nanoTime() + "@example.com",
                "APPROVED", "BUSY", "ONSITE_ONLY");
        User technicianUser = users.findByEmail(technician.getPersonalEmail()).orElseThrow();
        Booking booking = saveBookingForCustomer(999L, technician.getId(),
                ServiceMode.ONSITE, "PAID", BookingStatus.TECHNICIAN_ASSIGNED);

        // Completing an assigned (not started) booking is an invalid lifecycle transition.
        mvc.perform(put("/api/bookings/" + booking.getId() + "/technician/complete-service")
                        .header("Authorization", bearer(technicianUser)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BOOKING_TRANSITION"));
    }
}
