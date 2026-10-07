package com.geekonsites.backend.phase9;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 9 local-E2E regression: defects found by the full live endpoint matrix where a
 * client/domain error surfaced as a 500 instead of the correct 4xx contract.
 */
class ErrorContractFixesIntegrationTest extends Phase0IntegrationTestSupport {

    private User admin() {
        return saveUser(Role.ADMIN, "ecfix-admin-" + System.nanoTime() + "@example.test", "US");
    }

    @Test
    void pendingTechniciansWithDocumentDataReturns200NotServerError() throws Exception {
        Technician technician = new Technician();
        technician.setName("LOB Technician");
        technician.setEmail("lob-" + System.nanoTime() + "@example.test");
        technician.setPersonalEmail(technician.getEmail());
        technician.setVerificationStatus("PENDING");
        technician.setAvailabilityStatus("UNAVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.NOT_STARTED);
        technician.setIdentityDocumentData("data:image/png;base64,AAAA");
        technician.setLivePhotoData("data:image/png;base64,AAAA");
        technicians.save(technician);

        mvc.perform(get("/api/technicians/pending").header("Authorization", bearer(admin())))
                .andExpect(status().isOk());
    }

    @Test
    void missingRefundRequestReturns404() throws Exception {
        mvc.perform(put("/api/admin/refunds/999999/review").header("Authorization", bearer(admin())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void invalidOnboardingTokenReturns400() throws Exception {
        mvc.perform(post("/api/technicians/onboarding/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-real-token\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void remoteProvisioningOnUnpaidBookingReturns409() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "ecfix-cust-" + System.nanoTime() + "@example.test", "US");
        Booking booking = saveBooking(customer.getId(), ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);

        mvc.perform(post("/api/bookings/" + booking.getId() + "/remote-session/provision")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void webhookWithoutSignatureHeaderReturns400() throws Exception {
        mvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void refundOnUnpaidBookingReturns400() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "ecfix-refund-" + System.nanoTime() + "@example.test", "US");
        Booking booking = saveBooking(customer.getId(), ServiceMode.ONSITE, "PENDING", BookingStatus.PENDING);

        mvc.perform(post("/api/refunds/bookings/" + booking.getId())
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\",\"message\":\"please\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
