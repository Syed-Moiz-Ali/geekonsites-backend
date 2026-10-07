-- =============================================================================
-- PHASE 5 — case-insensitive user email uniqueness
-- =============================================================================
-- PostgreSQL: enforce LOWER(email) uniqueness in addition to the existing exact
-- unique constraint on users.email. Application code also normalizes email to
-- trim+lowercase at the registration/login boundary.
--
-- H2 note: this file is production-targeted and is not executed by the test suite
-- (tests use JPA ddl-auto). Functional indexes on LOWER() may not be supported by
-- every H2 version; the application-level normalization + existsByEmailIgnoreCase
-- check is what the tests exercise.
--
-- TODO(Phase 6 Flyway baseline): incorporate this index into the versioned schema.
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON users (LOWER(email));
