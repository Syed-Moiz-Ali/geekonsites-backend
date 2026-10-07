package com.geekonsites.backend.phase8;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PHASE 8 — real-PostgreSQL verification that the canonical migrations apply cleanly
 * to an empty database and produce the expected schema.
 *
 * <p>This environment has no Docker, so it is NOT skipped via Testcontainers but via an
 * explicit connection contract: set {@code GOS_POSTGRES_TEST_URL} (optionally
 * {@code GOS_POSTGRES_TEST_USER} / {@code GOS_POSTGRES_TEST_PASSWORD}) to point at any
 * throwaway PostgreSQL database, and this test will clean+migrate a dedicated schema
 * and assert the result. When unset, it is reported as skipped.
 *
 * <p>Run example:
 * <pre>
 *   GOS_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/postgres \
 *   GOS_POSTGRES_TEST_USER=postgres GOS_POSTGRES_TEST_PASSWORD=postgres mvn -B test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "GOS_POSTGRES_TEST_URL", matches = ".+")
class PostgresMigrationValidationTest {

    private static final String SCHEMA = "phase8_migration_validation";
    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void requireDatabase() {
        url = firstNonBlank(System.getenv("GOS_POSTGRES_TEST_URL"), System.getProperty("GOS_POSTGRES_TEST_URL"));
        user = firstNonBlank(System.getenv("GOS_POSTGRES_TEST_USER"),
                System.getProperty("GOS_POSTGRES_TEST_USER"), "postgres");
        password = firstNonBlank(System.getenv("GOS_POSTGRES_TEST_PASSWORD"),
                System.getProperty("GOS_POSTGRES_TEST_PASSWORD"), "");
        assumeTrue(url != null && !url.isBlank(),
                "No GOS_POSTGRES_TEST_URL set; PostgreSQL migration validation skipped (Docker unavailable here).");
    }

    @Test
    void migrationsApplyCleanlyAndCreateExpectedSchema() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        MigrateResult result = flyway.migrate();
        assertTrue(result.success, "Flyway migrate must succeed");
        assertTrue(result.migrationsExecuted >= 3, "V1..V3 must all be applied");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertTrue(tableExists(connection, "bookings"), "bookings table must exist");
            assertTrue(tableExists(connection, "invoices"), "invoices table must exist");
            assertTrue(tableExists(connection, "payment_transactions"), "payment_transactions must exist");
            assertTrue(tableExists(connection, "refund_requests"), "refund_requests must exist");
            assertTrue(tableExists(connection, "services"), "services must exist");

            assertTrue(columnExists(connection, "bookings", "total_amount_minor"),
                    "bookings.total_amount_minor (exact money) must exist");
            assertTrue(columnExists(connection, "bookings", "paid_amount_minor"),
                    "bookings.paid_amount_minor must exist");
            assertTrue(columnExists(connection, "invoices", "amount_minor"),
                    "invoices.amount_minor must exist");
            assertTrue(columnExists(connection, "invoices", "paid_amount_minor"),
                    "invoices.paid_amount_minor must exist");

            assertTrue(indexExists(connection, "uq_users_email_lower"),
                    "case-insensitive email unique index must exist");
            assertTrue(indexExists(connection, "uq_refund_active_booking"),
                    "active-refund partial unique index must exist");
            assertTrue(constraintExists(connection, "ck_bookings_money_nonneg"),
                    "bookings non-negative money check must exist");
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        return exists(connection,
                "select 1 from information_schema.tables where table_schema = ? and table_name = ?", table);
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from information_schema.columns where table_schema = ? and table_name = ? and column_name = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean indexExists(Connection connection, String indexName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from pg_indexes where schemaname = ? and indexname = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, indexName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean constraintExists(Connection connection, String constraintName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from pg_constraint where conname = ?")) {
            statement.setString(1, constraintName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean exists(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, value);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
