package com.geekonsites.backend.auth;

import com.geekonsites.backend.entity.PasswordResetToken;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.PasswordResetTokenRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:passwordresetlifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=password-reset-lifecycle-test-secret-key-32-chars",
        "app.password-reset.allowed-origins=https://geekonsites.com",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
@AutoConfigureMockMvc
class PasswordResetLifecycleIntegrationTest {
    private static final String OLD_PASSWORD = "OldPassword1!";
    private static final String NEW_PASSWORD = "NewPassword2@";
    private static final String SECOND_PASSWORD = "SecondPass3#";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TechnicianRepository technicians;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean EmailService emailService;

    @BeforeEach
    void setUp() {
        resetTokens.deleteAll();
        technicians.deleteAll();
        users.deleteAll();
    }

    @Test
    void customerFreshEmailedTokenResetsPasswordCannotBeReusedAndASecondResetWorks() throws Exception {
        String email = "reset-customer@example.com";
        createUser(email, Role.CUSTOMER);

        requestReset(email);
        String firstToken = capturedToken(email, 1);
        reset(firstToken, NEW_PASSWORD).andExpect(status().isOk());

        login(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, OLD_PASSWORD).andExpect(status().isUnauthorized());
        reset(firstToken, SECOND_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Reset link is invalid, expired, or already used."));

        requestReset(email);
        String secondToken = capturedToken(email, 2);
        reset(secondToken, SECOND_PASSWORD).andExpect(status().isOk());
        login(email, SECOND_PASSWORD).andExpect(status().isOk());
        login(email, NEW_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void approvedTechnicianFreshEmailedTokenResetsPasswordAndLogsIn() throws Exception {
        String email = "reset-technician@example.com";
        createApprovedTechnician(email);

        requestReset(email);
        String token = capturedToken(email, 1);
        reset(token, NEW_PASSWORD).andExpect(status().isOk());
        login(email, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TECHNICIAN"));
        login(email, OLD_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAndMalformedTokensReturnControlledBadRequest() throws Exception {
        User user = createUser("invalid-reset@example.com", Role.CUSTOMER);
        byte[] controlledBytes = new byte[32];
        java.util.Arrays.fill(controlledBytes, (byte) 7);
        String expiredRawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(controlledBytes);

        PasswordResetToken expired = new PasswordResetToken();
        expired.setUser(user);
        expired.setTokenHash(sha256(expiredRawToken));
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        resetTokens.saveAndFlush(expired);

        reset(expiredRawToken, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Reset link is invalid, expired, or already used."));
        reset("not-a-valid-token", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Reset link is invalid, expired, or already used."));
    }

    private User createUser(String email, Role role) {
        User user = new User();
        user.setFullName(role == Role.TECHNICIAN ? "Reset Technician" : "Reset Customer");
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        user.setCountry("US");
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private void createApprovedTechnician(String email) {
        Technician technician = new Technician();
        technician.setName("Reset Technician");
        technician.setEmail(email);
        technician.setPersonalEmail(email);
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technicians.saveAndFlush(technician);
        createUser(email, Role.TECHNICIAN);
    }

    private void requestReset(String email) throws Exception {
        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"resetUrl\":\"https://geekonsites.com/reset-password\"}"))
                .andExpect(status().isOk());
    }

    private String capturedToken(String email, int invocationCount) {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(invocationCount)).sendPasswordResetEmail(eq(email), link.capture());
        String token = UriComponentsBuilder.fromUriString(link.getAllValues().get(invocationCount - 1))
                .build().getQueryParams().getFirst("token");
        org.junit.jupiter.api.Assertions.assertNotNull(token);
        org.junit.jupiter.api.Assertions.assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        return token;
    }

    private org.springframework.test.web.servlet.ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private String sha256(String token) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
