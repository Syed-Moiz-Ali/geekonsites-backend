package com.geekonsites.backend.phase9;

import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 9 local-E2E regression: a genuinely missing resource must use the standard
 * {@code ApiErrorResponse} contract with 404 RESOURCE_NOT_FOUND, never a 500.
 *
 * <p>Found during live HTTP verification: {@code GET /api/bookings/{missing}} and
 * {@code GET /api/contact/{missing}} previously surfaced a generic RuntimeException as 500.
 */
class MissingResourceContractIntegrationTest extends Phase0IntegrationTestSupport {

    private User admin() {
        return saveUser(Role.ADMIN, "missing-resource-admin-" + System.nanoTime() + "@example.test", "US");
    }

    @Test
    void missingBookingReturns404StandardErrorContract() throws Exception {
        mvc.perform(get("/api/bookings/99999999").header("Authorization", bearer(admin())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void missingContactMessageReturns404StandardErrorContract() throws Exception {
        mvc.perform(get("/api/contact/99999999").header("Authorization", bearer(admin())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
