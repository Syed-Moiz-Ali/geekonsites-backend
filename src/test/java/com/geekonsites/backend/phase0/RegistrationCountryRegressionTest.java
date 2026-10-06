package com.geekonsites.backend.phase0;

import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 0 — registration country contract (audit H12 / BUG-20).
 *
 * <p>{@code AuthController.register} silently coerces any non-UK country value to
 * {@code "US"}. For a US/UK-only platform, unsupported values must be rejected rather
 * than silently rewritten. The unsupported/blank/null tests currently fail and must
 * pass after Phase 1 validation.
 */
class RegistrationCountryRegressionTest extends Phase0IntegrationTestSupport {

    private String body(String email, String countryJsonFragment) {
        return "{"
                + "\"fullName\":\"Country Tester\","
                + "\"email\":\"" + email + "\","
                + "\"password\":\"Password123!\","
                + "\"phone\":\"+15550000000\""
                + countryJsonFragment
                + "}";
    }

    @Test
    void unitedStatesRegistrationIsAccepted() throws Exception {
        String email = "country-us-" + System.nanoTime() + "@example.com";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, ",\"country\":\"US\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("US"));
    }

    @Test
    void unitedKingdomRegistrationIsAccepted() throws Exception {
        String email = "country-uk-" + System.nanoTime() + "@example.com";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, ",\"country\":\"UK\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("UK"));
    }

    @Tag("expected-failure")
    @Test
    void unsupportedCountryIsRejectedNotSilentlyConvertedToUs() throws Exception {
        String email = "country-india-" + System.nanoTime() + "@example.com";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, ",\"country\":\"India\"")))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void unknownCountryValueIsRejected() throws Exception {
        String email = "country-xyz-" + System.nanoTime() + "@example.com";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, ",\"country\":\"XYZ\"")))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void blankCountryIsRejected() throws Exception {
        String email = "country-blank-" + System.nanoTime() + "@example.com";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, ",\"country\":\"\"")))
                .andExpect(status().is4xxClientError());
    }

    @Tag("expected-failure")
    @Test
    void missingCountryIsRejected() throws Exception {
        String email = "country-null-" + System.nanoTime() + "@example.com";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "")))
                .andExpect(status().is4xxClientError());
    }
}
