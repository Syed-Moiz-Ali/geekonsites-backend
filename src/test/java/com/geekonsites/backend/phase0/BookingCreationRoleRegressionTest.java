package com.geekonsites.backend.phase0;

import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — booking-creation role contract (audit H10 / BUG-16).
 *
 * <p>{@code POST /api/bookings} is only {@code authenticated()} and writes the calling
 * account into the booking as the customer. Operational roles (technician/agent/admin)
 * can therefore silently become a booking's customer. The required contract: a normal
 * customer booking is created by a CUSTOMER; operational accounts need a separate,
 * explicit assisted-booking API. The non-customer tests currently fail (Phase 1).
 */
class BookingCreationRoleRegressionTest extends Phase0IntegrationTestSupport {

    private static final String VALID_BOOKING = "{"
            + "\"serviceType\":\"PC Health Check & Diagnosis\","
            + "\"serviceMode\":\"REMOTE\","
            + "\"issueDescription\":\"PC is slow\","
            + "\"address\":\"1 Main St\","
            + "\"city\":\"New York\","
            + "\"state\":\"NY\","
            + "\"country\":\"US\","
            + "\"postalCode\":\"10001\","
            + "\"bookingDate\":\"2026-12-01\","
            + "\"timeSlot\":\"10:00 AM\""
            + "}";

    private String tokenFor(Role role) {
        User user = saveUser(role, "creator-" + role.name().toLowerCase() + "-" + System.nanoTime() + "@example.com", "US");
        return bearer(user);
    }

    @Test
    void customerCanCreateANormalBooking() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "creator-customer-" + System.nanoTime() + "@example.com", "US");

        mvc.perform(post("/api/bookings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BOOKING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customer.getId()));
    }

    @Tag("expected-failure")
    @Test
    void technicianMustNotSilentlyCreateACustomerBooking() throws Exception {
        mvc.perform(post("/api/bookings")
                        .header("Authorization", tokenFor(Role.TECHNICIAN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BOOKING))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void agentMustNotSilentlyCreateACustomerBooking() throws Exception {
        mvc.perform(post("/api/bookings")
                        .header("Authorization", tokenFor(Role.AGENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BOOKING))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void adminMustNotSilentlyCreateACustomerBooking() throws Exception {
        mvc.perform(post("/api/bookings")
                        .header("Authorization", tokenFor(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BOOKING))
                .andExpect(status().is4xxClientError());
    }
}
