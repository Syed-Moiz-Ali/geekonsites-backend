package com.geekonsites.backend.auth;

import com.geekonsites.backend.entity.PasswordResetToken;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.repository.PasswordResetTokenRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.service.ResendEmailClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the production data-migration bug this test suite backs: an
 * already-approved technician whose users.email was the legacy
 * "@gos.com" company email (not their personal email) returned 401 on
 * login and got no Forgot Password email. These tests exercise the real
 * HTTP + database stack, in the exact shape production data has AFTER the
 * personal-email backfill migration runs: users.email = personal_email,
 * and technicians.company_email is deliberately RETAINED (not nulled) as
 * metadata, to prove authentication no longer depends on it being absent.
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:technicianpersonalemail;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=technician-personal-email-test-secret-key-32-chars",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
@AutoConfigureMockMvc
class TechnicianPersonalEmailLoginIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TechnicianRepository technicians;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean ResendEmailClient resendEmailClient;

    private static final String PERSONAL_EMAIL = "subbareddysanaga65@gmail.com";
    private static final String OLD_COMPANY_EMAIL = "jack@gos.com";
    private static final String PASSWORD = "CorrectPass123!";

    @BeforeEach
    void setUp() {
        resetTokens.deleteAll();
        technicians.deleteAll();
        users.deleteAll();
        when(resendEmailClient.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    /** Simulates the exact post-migration shape production data will have. */
    private User migratedTechnician() {
        Technician technician = new Technician();
        technician.setName("Jack Technician");
        technician.setEmail(PERSONAL_EMAIL);
        technician.setPersonalEmail(PERSONAL_EMAIL);
        // Retained as metadata only - must never be usable for auth again.
        technician.setCompanyEmail(OLD_COMPANY_EMAIL);
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        technicians.save(technician);

        User user = new User();
        user.setFullName("Jack Technician");
        // Post-migration: users.email is the personal email, not the old
        // company email.
        user.setEmail(PERSONAL_EMAIL);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setCountry("US");
        user.setRole(Role.TECHNICIAN);
        return users.save(user);
    }

    @Test
    void technicianLogsInWithPersonalEmailAfterMigration() throws Exception {
        User user = migratedTechnician();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(PERSONAL_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(PERSONAL_EMAIL))
                .andExpect(jsonPath("$.role").value("TECHNICIAN"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        assertEquals(PERSONAL_EMAIL, users.findById(user.getId()).orElseThrow().getEmail());
    }

    @Test
    void oldCompanyEmailNoLongerAuthenticatesAfterMigration() throws Exception {
        migratedTechnician();

        // No users row exists for the old @gos.com email post-migration, so
        // this must fail exactly like any other unknown email: 401, not a
        // 500, and not a hint that the account exists under another email.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(OLD_COMPANY_EMAIL, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(status().reason("Invalid email or password."));
    }

    @Test
    void wrongPasswordReturnsUnauthorizedNotServerError() throws Exception {
        migratedTechnician();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(PERSONAL_EMAIL, "WrongPassword1!")))
                .andExpect(status().isUnauthorized())
                .andExpect(status().reason("Invalid email or password."));
    }

    @Test
    void unknownTechnicianEmailReturnsUnauthorizedNotServerError() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(status().reason("Invalid email or password."));
    }

    @Test
    void customerLoginStillWorksUnaffectedByTechnicianEmailChanges() throws Exception {
        User customer = new User();
        customer.setFullName("Regular Customer");
        customer.setEmail("customer@example.com");
        customer.setPassword(passwordEncoder.encode(PASSWORD));
        customer.setCountry("US");
        customer.setRole(Role.CUSTOMER);
        users.save(customer);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("customer@example.com", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void technicianForgotPasswordWithPersonalEmailCreatesResetTokenAndAttemptsDelivery() throws Exception {
        User user = migratedTechnician();

        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + PERSONAL_EMAIL + "\"}"))
                .andExpect(status().isOk());

        Optional<PasswordResetToken> token = resetTokens.findFirstByUserOrderByCreatedAtDesc(user);
        assertTrue(token.isPresent(), "Forgot Password to the technician's personal email must create a reset token");
        assertFalse(token.get().isUsed());

        // Confirms delivery was actually attempted (previously this silently
        // no-op'd because the old company_email-first lookup found no
        // technician access record for the personal email).
        verify(resendEmailClient).send(
                org.mockito.ArgumentMatchers.eq("GeekOnSites Support <support@geekonsites.com>"),
                org.mockito.ArgumentMatchers.eq(PERSONAL_EMAIL),
                anyString(), anyString());
    }

    @Test
    void customerForgotPasswordStillWorks() throws Exception {
        User customer = new User();
        customer.setFullName("Regular Customer");
        customer.setEmail("customer-forgot@example.com");
        customer.setPassword(passwordEncoder.encode(PASSWORD));
        customer.setCountry("US");
        customer.setRole(Role.CUSTOMER);
        users.save(customer);

        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer-forgot@example.com\"}"))
                .andExpect(status().isOk());

        Optional<PasswordResetToken> token = resetTokens.findFirstByUserOrderByCreatedAtDesc(customer);
        assertTrue(token.isPresent());
    }

    private String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
