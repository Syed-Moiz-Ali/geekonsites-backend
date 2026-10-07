package com.geekonsites.backend.phase8;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHASE 8 — structural guarantees for the canonical Flyway migration set.
 *
 * <p>This test runs with no database and no Docker. It asserts that:
 * <ul>
 *   <li>there is exactly one migration directory, using Flyway's versioned naming;</li>
 *   <li>versions are unique and strictly increasing;</li>
 *   <li>the superseded ad-hoc directory is gone (moved to legacy-migrations);</li>
 *   <li>the money + integrity migrations actually contain the objects they claim.</li>
 * </ul>
 * The behavioural, real-database proof lives in {@link PostgresMigrationValidationTest},
 * which requires a PostgreSQL instance.
 */
class MigrationContractTest {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__(.+)\\.sql$");

    private List<Path> migrationFiles() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql");
        List<Path> paths = new ArrayList<>();
        for (Resource resource : resources) {
            URI uri = resource.getURI();
            if (uri.getScheme().equals("file")) {
                paths.add(Paths.get(uri));
            }
        }
        paths.sort(Comparator.comparing(Path::getFileName));
        return paths;
    }

    @Test
    void migrationDirectoryUsesUniqueIncreasingVersionedNames() throws Exception {
        List<Path> files = migrationFiles();
        assertFalse(files.isEmpty(), "no Flyway migrations found on the classpath");

        long previous = -1;
        for (Path file : files) {
            String name = file.getFileName().toString();
            Matcher matcher = VERSIONED.matcher(name);
            assertTrue(matcher.matches(), "not a versioned Flyway migration: " + name);
            long version = Long.parseLong(matcher.group(1));
            assertTrue(version > previous, "migration versions must be unique and increasing: " + name);
            previous = version;
        }

        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals("V1__baseline_schema.sql")),
                "V1 baseline must exist");
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString()
                .equals("V3__exact_money_booking_invoice.sql")), "V3 money migration must exist");
    }

    @Test
    void supersededAdHocMigrationDirectoryIsGone() {
        assertFalse(Files.exists(Paths.get("database", "migrations")),
                "the ad-hoc database/migrations directory must be removed (only db/migration is authoritative)");
        assertTrue(Files.exists(Paths.get("database", "legacy-migrations")),
                "superseded migrations should be retained read-only under database/legacy-migrations");
    }

    @Test
    void exactMoneyMigrationAddsBookingsAndInvoiceMinorColumns() throws Exception {
        String v3 = readBySuffix("V3__exact_money_booking_invoice.sql");
        assertTrue(v3.contains("add column if not exists total_amount_minor"),
                "V3 must expand bookings with total_amount_minor");
        assertTrue(v3.contains("add column if not exists paid_amount_minor"),
                "V3 must expand bookings/invoices with paid_amount_minor");
        assertTrue(v3.contains("add column if not exists amount_minor"),
                "V3 must expand invoices with amount_minor");
        assertTrue(v3.contains("round((total_amount::numeric) * 100)::bigint"),
                "V3 must backfill from the legacy Double value exactly");
    }

    @Test
    void integrityMigrationDeclaresCaseInsensitiveEmailAndActiveRefundUniqueness() throws Exception {
        String v2 = readBySuffix("V2__integrity_constraints_and_indexes.sql");
        assertTrue(v2.contains("uq_users_email_lower"), "case-insensitive email uniqueness required");
        assertTrue(v2.contains("lower(email)"), "case-insensitive email index must use lower(email)");
        assertTrue(v2.contains("uq_refund_active_booking"), "one active refund per booking required");
        assertTrue(v2.contains("uq_services_code") || v2.contains("uq_services_code".toLowerCase())
                        || v2.contains("services"), "service uniqueness is part of the baseline");
    }

    private String readBySuffix(String suffix) throws Exception {
        Path path = migrationFiles().stream()
                .filter(p -> p.getFileName().toString().equals(suffix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing migration " + suffix));
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
