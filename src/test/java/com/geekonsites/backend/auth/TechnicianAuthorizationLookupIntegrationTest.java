package com.geekonsites.backend.auth;

import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Root cause under test: AuthController.login rejected fully approved,
 * password-set technicians with 403 "could not be verified" regardless of
 * onboarding_status. The actual cause was TechnicianRepository's shared
 * access lookup (findAccessByEmail), not onboarding_status: it matched
 * personalEmail with top priority, but the database backfill migration
 * (database/migrations/20260824_technician_personal_email_login.sql) that
 * points users.email at personal_email for already-approved legacy
 * technicians has never actually been run against production. Until it
 * is, those technicians' users.email row still holds their old
 * company_email - so the lookup found nothing at all, independent of
 * verification_status or onboarding_status, and every legacy technician
 * was locked out with the same generic 403.
 *
 * These tests reproduce that exact "not yet migrated" data shape directly
 * (personalEmail set on the technician row, but users.email still equal to
 * the old company_email) to prove login now succeeds without needing the
 * migration to have run, alongside proving every rejection path
 * (pending/rejected technician, technician row missing entirely, wrong
 * password, unknown email) is unchanged, and customer/agent/admin login is
 * unaffected.
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:technicianauthlookup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=technician-auth-lookup-test-secret-key-32-chars",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
@AutoConfigureMockMvc
class TechnicianAuthorizationLookupIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TechnicianRepository technicians;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "CorrectPass123!";

    @BeforeEach
    void setUp() {
        technicians.deleteAll();
        users.deleteAll();
    }

    private User userRow(String email, Role role) {
        User user = new User();
        user.setFullName("Test " + role);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setCountry("US");
        user.setRole(role);
        return users.save(user);
    }

    private String loginBody(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    @Test
    void legacyCompanyEmailCannotAuthenticateBeforeMigration() throws Exception {
        // The exact production shape reported: verification_status=APPROVED,
        // onboarding_status=PASSWORD_SET, personal_email populated - but the
        // backfill migration has not run, so users.email is still the old
        // company_email. The migration must canonicalize this account before
        // it can authenticate; the legacy identity itself is never accepted.
        Technician technician = new Technician();
        technician.setName("Legacy Approved Technician");
        technician.setPersonalEmail("realperson@example.com");
        technician.setCompanyEmail("jack@gos.com");
        technician.setEmail("jack@gos.com");
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        technicians.save(technician);

        userRow("jack@gos.com", Role.TECHNICIAN); // users.email not yet migrated

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("jack@gos.com")))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    void approvedPasswordSetWithCompletedTimestampLogsInSuccessfullyOnceMigrated() throws Exception {
        Technician technician = new Technician();
        technician.setName("Migrated Technician");
        technician.setPersonalEmail("migrated.tech@example.com");
        technician.setCompanyEmail("old@gos.com"); // retained metadata only
        technician.setEmail("migrated.tech@example.com");
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        technician.setPasswordSetupCompletedAt(Instant.now().minusSeconds(3600));
        technicians.save(technician);

        userRow("migrated.tech@example.com", Role.TECHNICIAN); // already migrated

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("migrated.tech@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TECHNICIAN"));
    }

    @Test
    void pendingTechnicianIsRejectedAwaitingApproval() throws Exception {
        Technician technician = new Technician();
        technician.setName("Pending Technician");
        technician.setPersonalEmail("pending@example.com");
        technician.setEmail("pending@example.com");
        technician.setVerificationStatus("PENDING");
        technician.setAvailabilityStatus("UNAVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.NOT_STARTED);
        technicians.save(technician);
        userRow("pending@example.com", Role.TECHNICIAN);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("pending@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Your technician account is awaiting approval."));
    }

    @Test
    void rejectedTechnicianIsRejectedNotApproved() throws Exception {
        Technician technician = new Technician();
        technician.setName("Rejected Technician");
        technician.setPersonalEmail("rejected@example.com");
        technician.setEmail("rejected@example.com");
        technician.setVerificationStatus("REJECTED");
        technician.setAvailabilityStatus("UNAVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.NOT_STARTED);
        technicians.save(technician);
        userRow("rejected@example.com", Role.TECHNICIAN);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("rejected@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Your technician account is not approved. Please contact support."));
    }

    @Test
    void technicianRoleUserWithNoMatchingTechnicianRowIsRejectedAsUnverified() throws Exception {
        // A TECHNICIAN-role users row that genuinely has no corresponding
        // technicians row at all must still be rejected - the broadened
        // lookup must not accidentally match everything.
        userRow("orphaned@example.com", Role.TECHNICIAN);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("orphaned@example.com")))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    void wrongPasswordStillReturnsUnauthorizedForAnApprovedTechnician() throws Exception {
        Technician technician = new Technician();
        technician.setName("Approved Technician");
        technician.setPersonalEmail("approved@example.com");
        technician.setEmail("approved@example.com");
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        technicians.save(technician);
        userRow("approved@example.com", Role.TECHNICIAN);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"approved@example.com\",\"password\":\"WrongPassword1!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    void unknownEmailReturnsUnauthorizedNotServerError() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@example.com")))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    void customerLoginIsUnaffected() throws Exception {
        userRow("customer@example.com", Role.CUSTOMER);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("customer@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void agentLoginIsUnaffected() throws Exception {
        userRow("agent@example.com", Role.AGENT);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("agent@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AGENT"));
    }

    @Test
    void adminLoginBehaviorIsUnaffected() throws Exception {
        userRow("admin@example.com", Role.ADMIN);

        // /api/auth/login explicitly redirects admins to the dedicated
        // admin portal rather than authenticating them here - unchanged.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Use the dedicated admin portal to sign in."));
    }
}
