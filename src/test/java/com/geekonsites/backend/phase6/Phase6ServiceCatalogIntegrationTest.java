package com.geekonsites.backend.phase6;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.service.PaymentService;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 6 — public catalog, admin management, booking integration, and the price-snapshot
 * invariant. Tests create their own services so they never mutate the seeded catalog.
 */
class Phase6ServiceCatalogIntegrationTest extends Phase0IntegrationTestSupport {

    @Autowired PaymentService paymentService;

    private User admin() {
        return saveUser(Role.ADMIN, "svc-admin-" + System.nanoTime() + "@example.com", "US");
    }

    private String createServiceBody(String code, double usd, double gbp, String mode) {
        return "{"
                + "\"code\":\"" + code + "\","
                + "\"name\":\"Test Service " + code + "\","
                + "\"description\":\"desc\","
                + "\"serviceMode\":\"" + mode + "\","
                + "\"usdPrice\":" + usd + ","
                + "\"gbpPrice\":" + gbp
                + "}";
    }

    private Long bookingIdOf(User customer) {
        return bookings.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).get(0).getId();
    }

    private void postBooking(User customer, String serviceType, String serviceMode, String extra) throws Exception {
        String body = "{"
                + "\"serviceType\":\"" + serviceType + "\","
                + "\"serviceMode\":\"" + serviceMode + "\","
                + "\"country\":\"US\","
                + "\"address\":\"1 Main St\",\"city\":\"NY\",\"state\":\"NY\",\"postalCode\":\"10001\","
                + "\"bookingDate\":\"2026-12-01\",\"timeSlot\":\"10:00 AM\""
                + (extra == null ? "" : "," + extra)
                + "}";
        mvc.perform(post("/api/bookings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- public API

    @Test
    void publicCatalogReturnsUsdForUsMarket() throws Exception {
        mvc.perform(get("/api/services/PC_HEALTH_CHECK_DIAGNOSIS?market=US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PC_HEALTH_CHECK_DIAGNOSIS"))
                .andExpect(jsonPath("$.serviceMode").value("REMOTE"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.price").value(29.00));
    }

    @Test
    void publicCatalogReturnsGbpForUkMarket() throws Exception {
        mvc.perform(get("/api/services/PC_HEALTH_CHECK_DIAGNOSIS?market=UK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.price").value(25.00));
    }

    @Test
    void unsupportedMarketIsRejected() throws Exception {
        mvc.perform(get("/api/services?market=India")).andExpect(status().isBadRequest());
    }

    @Test
    void inactiveServiceIsNotPublic() throws Exception {
        User admin = admin();
        String code = "P6_INACTIVE_" + System.nanoTime();
        mvc.perform(post("/api/admin/services")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createServiceBody(code, 50, 40, "REMOTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
        Long id = serviceIdByAdminList(admin, code);

        mvc.perform(patch("/api/admin/services/" + id + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/services/" + code + "?market=US")).andExpect(status().isNotFound());
        String list = mvc.perform(get("/api/services?market=US")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(code), "inactive service must not appear in public discovery");
    }

    private Long serviceIdByAdminList(User admin, String code) throws Exception {
        String listing = mvc.perform(get("/api/admin/services")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // robust enough for tests: find the id preceding the code
        int codeIdx = listing.indexOf("\"code\":\"" + code + "\"");
        int idIdx = listing.lastIndexOf("\"id\":", codeIdx);
        String idText = listing.substring(idIdx + 5, listing.indexOf(',', idIdx));
        return Long.parseLong(idText.trim());
    }

    // -------------------------------------------------------------- admin API

    @Test
    void adminCanCreateAndUpdateService() throws Exception {
        User admin = admin();
        String code = "P6_ADMIN_" + System.nanoTime();
        mvc.perform(post("/api/admin/services")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createServiceBody(code, 100, 80, "REMOTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usdPrice").value(100.00));

        Long id = serviceIdByAdminList(admin, code);
        mvc.perform(put("/api/admin/services/" + id)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usdPrice\":120.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usdPrice").value(120.00))
                .andExpect(jsonPath("$.gbpPrice").value(80.00));
    }

    @Test
    void nonAdminCannotManageServices() throws Exception {
        String body = createServiceBody("P6_FORBIDDEN_" + System.nanoTime(), 10, 8, "REMOTE");
        for (Role role : new Role[]{Role.CUSTOMER, Role.TECHNICIAN, Role.AGENT}) {
            User user = saveUser(role, "p6-" + role.name().toLowerCase() + "-" + System.nanoTime() + "@example.com", "US");
            mvc.perform(post("/api/admin/services")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void duplicateCodeAndInvalidPriceAreRejected() throws Exception {
        User admin = admin();
        String code = "P6_DUP_" + System.nanoTime();
        String body = createServiceBody(code, 100, 80, "REMOTE");
        mvc.perform(post("/api/admin/services").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/services").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/services").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createServiceBody("P6_BADPRICE_" + System.nanoTime(), 0, 8, "REMOTE")))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------- booking wiring

    @Test
    void bookingResolvesDbServiceAndSnapshotsServerPrice() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "p6-booking-" + System.nanoTime() + "@example.com", "US");
        // Client-supplied amounts are ignored; server derives from the catalog.
        postBooking(customer, "PC Health Check & Diagnosis", "REMOTE", "\"baseAmount\":0.01,\"totalAmount\":0.01");
        Long id = bookingIdOf(customer);

        Booking booking = bookings.findById(id).orElseThrow();
        assertNotNull(booking.getServiceId());
        assertEquals("PC_HEALTH_CHECK_DIAGNOSIS", booking.getServiceCodeSnapshot());
        assertEquals("REMOTE", booking.getServiceModeSnapshot());
        assertEquals("USD", booking.getCurrency());
        assertEquals(29.0, booking.getBaseAmount());
        assertEquals(41.0, booking.getTotalAmount());
    }

    @Test
    void bookingCannotOverrideServiceMode() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "p6-mode-" + System.nanoTime() + "@example.com", "US");
        // PC Health Check is REMOTE; claiming ONSITE must be rejected.
        String body = "{"
                + "\"serviceType\":\"PC Health Check & Diagnosis\",\"serviceMode\":\"ONSITE\",\"country\":\"US\","
                + "\"address\":\"1 Main St\",\"city\":\"NY\",\"state\":\"NY\",\"postalCode\":\"10001\","
                + "\"bookingDate\":\"2026-12-01\",\"timeSlot\":\"10:00 AM\"}";
        mvc.perform(post("/api/bookings")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void priceChangeDoesNotAlterHistoricalBookingButAppliesToNewBooking() throws Exception {
        User admin = admin();
        User customer = saveUser(Role.CUSTOMER, "p6-history-" + System.nanoTime() + "@example.com", "US");
        String code = "P6_HISTORY_" + System.nanoTime();
        mvc.perform(post("/api/admin/services").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createServiceBody(code, 100, 80, "REMOTE")))
                .andExpect(status().isOk());
        Long serviceId = serviceIdByAdminList(admin, code);

        postBooking(customer, code, "REMOTE", null);
        Long bookingA = bookingIdOf(customer);
        assertEquals(112.0, bookings.findById(bookingA).orElseThrow().getTotalAmount()); // 100 + 12 fee

        mvc.perform(put("/api/admin/services/" + serviceId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usdPrice\":120.00}"))
                .andExpect(status().isOk());

        // Historical booking unchanged.
        assertEquals(112.0, bookings.findById(bookingA).orElseThrow().getTotalAmount());

        // New booking uses the updated price.
        postBooking(customer, code, "REMOTE", null);
        Booking newBooking = bookings.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).get(0);
        assertEquals(132.0, newBooking.getTotalAmount()); // 120 + 12 fee

        // Payment for booking A still uses its snapshotted amount (112.00 → 11200 minor).
        Session session = new Session();
        session.setId("cs_p6_" + System.nanoTime());
        session.setPaymentStatus("paid");
        session.setAmountTotal(11200L);
        session.setCurrency("usd");
        session.setMetadata(Map.of("bookingId", String.valueOf(bookingA), "paymentType", "FULL"));
        paymentService.applyCompletedCheckoutSession(session, null);
        assertEquals("PAID", bookings.findById(bookingA).orElseThrow().getPaymentStatus());
    }

    @Test
    void inactiveServiceCannotBeBookedButExistingBookingSurvives() throws Exception {
        User admin = admin();
        User customer = saveUser(Role.CUSTOMER, "p6-inactivebooking-" + System.nanoTime() + "@example.com", "US");
        String code = "P6_DEACT_" + System.nanoTime();
        mvc.perform(post("/api/admin/services").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createServiceBody(code, 100, 80, "REMOTE")))
                .andExpect(status().isOk());
        Long serviceId = serviceIdByAdminList(admin, code);

        postBooking(customer, code, "REMOTE", null);
        Long existingBooking = bookingIdOf(customer);

        mvc.perform(patch("/api/admin/services/" + serviceId + "/status").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());

        // New booking rejected.
        String body = "{"
                + "\"serviceType\":\"" + code + "\",\"serviceMode\":\"REMOTE\",\"country\":\"US\","
                + "\"address\":\"1 Main St\",\"city\":\"NY\",\"state\":\"NY\",\"postalCode\":\"10001\","
                + "\"bookingDate\":\"2026-12-01\",\"timeSlot\":\"10:00 AM\"}";
        mvc.perform(post("/api/bookings").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        // Existing booking survives and can still be paid from its snapshot.
        Booking booking = bookings.findById(existingBooking).orElseThrow();
        assertTrue(booking.getServiceId().equals(serviceId));
        Session session = new Session();
        session.setId("cs_p6b_" + System.nanoTime());
        session.setPaymentStatus("paid");
        session.setAmountTotal(11200L);
        session.setCurrency("usd");
        session.setMetadata(Map.of("bookingId", String.valueOf(existingBooking), "paymentType", "FULL"));
        paymentService.applyCompletedCheckoutSession(session, null);
        assertEquals("PAID", bookings.findById(existingBooking).orElseThrow().getPaymentStatus());
    }
}
