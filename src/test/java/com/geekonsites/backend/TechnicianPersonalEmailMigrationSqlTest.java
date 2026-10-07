package com.geekonsites.backend;

import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Executes the actual production migration file -
 * database/migrations/20260824_technician_personal_email_login.sql -
 * against a real (H2, PostgreSQL-mode) database seeded to look like the
 * production data described in the task: an APPROVED technician whose
 * users row still carries the old company_email as its login identity.
 *
 * This does not mock or reimplement the migration logic - it reads and
 * runs the literal .sql file, so a change to the file is what this test
 * actually verifies.
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:technicianmigration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=migration-sql-test-secret-key-with-32-characters",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
class TechnicianPersonalEmailMigrationSqlTest {

    // PHASE 8 — the ad-hoc migration was superseded by the canonical Flyway set and
    // moved, unchanged, to database/legacy-migrations for historical reference.
    private static final Path MIGRATION_FILE =
            Path.of("database", "legacy-migrations", "20260824_technician_personal_email_login.sql");

    @Autowired UserRepository users;
    @Autowired TechnicianRepository technicians;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        technicians.deleteAll();
        users.deleteAll();
    }

    private User user(String email, Role role, String passwordHash) {
        User user = new User();
        user.setFullName("Test " + role);
        user.setEmail(email);
        user.setPassword(passwordHash);
        user.setCountry("US");
        user.setRole(role);
        return users.save(user);
    }

    private Technician technician(String email, String personalEmail, String companyEmail) {
        Technician technician = new Technician();
        technician.setName("Technician " + email);
        technician.setEmail(email);
        technician.setPersonalEmail(personalEmail);
        technician.setCompanyEmail(companyEmail);
        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        return technicians.save(technician);
    }

    private void runMigration() throws IOException {
        // Strip comment lines FIRST (a comment sentence may itself contain a
        // semicolon), then split the remaining SQL-only text into
        // statements. This runs the file's real, uncommented statements
        // exactly as a DBA applying it with `psql -f` would.
        String withoutComments = Files.readString(MIGRATION_FILE).lines()
                .filter(line -> !line.strip().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        for (String statement : withoutComments.split(";")) {
            String trimmed = statement.strip();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
            }
        }
    }

    @Test
    void technicianWithLegacyCompanyEmailIsMigratedToPersonalEmail() throws IOException {
        User user = user("jack@gos.com", Role.TECHNICIAN, "bcrypt-hash-jack");
        Long userId = user.getId();
        technician("jack@gos.com", "subbareddysanaga65@gmail.com", "jack@gos.com");

        runMigration();

        User migrated = users.findById(userId).orElseThrow();
        assertEquals("subbareddysanaga65@gmail.com", migrated.getEmail());
        assertEquals(userId, migrated.getId(), "users.id must be preserved");
        assertEquals("bcrypt-hash-jack", migrated.getPassword(), "password hash must be preserved");
        assertEquals(Role.TECHNICIAN, migrated.getRole());
    }

    @Test
    void technicianWithoutCompanyEmailIsMigratedViaLegacyTechnicianEmailColumn() throws IOException {
        // No company_email was ever issued; technicians.email itself holds
        // the legacy identity that users.email is still keyed to.
        User user = user("legacy-tech@geekonsites.com", Role.TECHNICIAN, "bcrypt-hash-legacy");
        Long userId = user.getId();
        technician("legacy-tech@geekonsites.com", "real.person@example.com", null);

        runMigration();

        User migrated = users.findById(userId).orElseThrow();
        assertEquals("real.person@example.com", migrated.getEmail());
        assertEquals("bcrypt-hash-legacy", migrated.getPassword());
    }

    @Test
    void technicianIsSkippedSafelyWhenPersonalEmailAlreadyBelongsToAnotherAccount() throws IOException {
        // Another, unrelated account already owns this email address.
        User existingOwner = user("shared@example.com", Role.CUSTOMER, "bcrypt-hash-owner");
        User conflictedTechnicianUser = user("conflict@gos.com", Role.TECHNICIAN, "bcrypt-hash-conflict");
        Long conflictedUserId = conflictedTechnicianUser.getId();
        technician("conflict@gos.com", "shared@example.com", "conflict@gos.com");

        runMigration();

        // Nothing was overwritten on either side of the conflict.
        User untouchedTechnicianUser = users.findById(conflictedUserId).orElseThrow();
        assertEquals("conflict@gos.com", untouchedTechnicianUser.getEmail(), "conflicted technician user must be left untouched");
        User untouchedOwner = users.findById(existingOwner.getId()).orElseThrow();
        assertEquals("shared@example.com", untouchedOwner.getEmail(), "the account that already owns the email must be untouched");
    }

    @Test
    void customerAgentAndAdminUsersAreNeverTouched() throws IOException {
        User customer = user("customer@example.com", Role.CUSTOMER, "bcrypt-hash-customer");
        User agent = user("agent@example.com", Role.AGENT, "bcrypt-hash-agent");
        User admin = user("admin@example.com", Role.ADMIN, "bcrypt-hash-admin");
        // A technician row happens to exist too, to prove the migration's
        // WHERE clause (role = 'TECHNICIAN') is what protects the others,
        // not merely the absence of any technician data.
        user("jack@gos.com", Role.TECHNICIAN, "bcrypt-hash-jack");
        technician("jack@gos.com", "subbareddysanaga65@gmail.com", "jack@gos.com");

        runMigration();

        assertEquals("customer@example.com", users.findById(customer.getId()).orElseThrow().getEmail());
        assertEquals("agent@example.com", users.findById(agent.getId()).orElseThrow().getEmail());
        assertEquals("admin@example.com", users.findById(admin.getId()).orElseThrow().getEmail());
    }

    @Test
    void migrationIsIdempotentWhenRunTwice() throws IOException {
        User user = user("jack@gos.com", Role.TECHNICIAN, "bcrypt-hash-jack");
        Long userId = user.getId();
        technician("jack@gos.com", "subbareddysanaga65@gmail.com", "jack@gos.com");

        runMigration();
        String afterFirstRun = users.findById(userId).orElseThrow().getEmail();

        runMigration();
        String afterSecondRun = users.findById(userId).orElseThrow().getEmail();

        assertEquals("subbareddysanaga65@gmail.com", afterFirstRun);
        assertEquals(afterFirstRun, afterSecondRun, "running the migration twice must not change the outcome");
    }
}
