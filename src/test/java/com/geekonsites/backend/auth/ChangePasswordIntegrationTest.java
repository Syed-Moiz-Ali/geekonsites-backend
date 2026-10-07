package com.geekonsites.backend.auth;

import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.jwt.JwtService;
import com.geekonsites.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:changepassword;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=change-password-test-secret-key-with-at-least-32-characters",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
@AutoConfigureMockMvc
class ChangePasswordIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private User technician;
    private String token;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        technician = new User();
        technician.setFullName("Password Technician");
        technician.setEmail("password-tech@geekonsites.com");
        technician.setPassword(passwordEncoder.encode("CurrentPass123!"));
        technician.setCountry("UK");
        technician.setRole(Role.TECHNICIAN);
        technician = users.save(technician);
        token = jwtService.generateToken(technician);
    }

    @Test
    void authenticatedUserCanChangePassword() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CurrentPass123!", "NewSecurePass456!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully."));

        String savedHash = users.findByEmailIgnoreCase(technician.getEmail()).orElseThrow().getPassword();
        assertFalse(passwordEncoder.matches("CurrentPass123!", savedHash));
        assertTrue(passwordEncoder.matches("NewSecurePass456!", savedHash));
    }

    @Test
    void wrongCurrentPasswordIsRejectedWithoutChangingHash() throws Exception {
        String originalHash = technician.getPassword();
        mvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WrongPass123!", "NewSecurePass456!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect."));
        assertTrue(passwordEncoder.matches("CurrentPass123!",
                users.findByEmailIgnoreCase(technician.getEmail()).orElseThrow().getPassword()));
        assertTrue(originalHash.equals(users.findByEmailIgnoreCase(technician.getEmail()).orElseThrow().getPassword()));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        // PHASE 7: an unauthenticated request is 401 (standardized).
        mvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CurrentPass123!", "NewSecurePass456!")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordCannotBeChangedToCurrentPassword() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CurrentPass123!", "CurrentPass123!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password must be different from the current password."));
        assertTrue(passwordEncoder.matches("CurrentPass123!",
                users.findByEmailIgnoreCase(technician.getEmail()).orElseThrow().getPassword()));
    }

    private String body(String currentPassword, String newPassword) {
        return "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }
}
