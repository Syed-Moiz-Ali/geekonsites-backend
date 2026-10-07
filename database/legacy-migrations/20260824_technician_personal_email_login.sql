-- =============================================================================
-- Technician personal-email login backfill
-- =============================================================================
--
-- Final rule this migration enforces for EXISTING data:
--   THE TECHNICIAN'S MAIN REGISTERED PERSONAL EMAIL IS THE ONLY LOGIN EMAIL.
--
-- Background: technicians used to be issued a second "@gos.com" company
-- email as their login identity (technicians.company_email). New
-- registration/approval code no longer does this - a technician signs in
-- with the personal email and password they created at registration - but
-- existing rows in production still have users.email pointing at the old
-- company email (or, for technicians who never got one, at whatever legacy
-- value technicians.email holds). This migration corrects users.email for
-- those existing rows only. It does not change how new technicians are
-- created or approved (that is fixed in application code, not here).
--
-- SCOPE: this migration writes to exactly one column: users.email.
-- Nothing else is modified - not users.id, not users.password (the
-- password hash), not users.role, not any column on technicians, and
-- nothing on bookings/notifications/tokens/any other table. Those tables
-- never reference users.email as a key (they key off ids), so none of
-- those relationships can be affected by changing an email string.
--
-- company_email is intentionally left in place as retained metadata. It is
-- no longer used to authenticate a technician - TechnicianRepository's
-- lookup was changed in application code to resolve technicians by
-- personal_email first - so leaving it populated here is safe, and
-- reversible if some other business feature still reads it.
--
-- ELIGIBILITY - a users row is migrated only when ALL of the following hold:
--   1. users.role = 'TECHNICIAN'.
--   2. The matching technicians row has a non-null, non-blank personal_email.
--   3. That technicians row's OLD identity - company_email if set, otherwise
--      the legacy technicians.email column - equals the users row's current
--      email (case-insensitively). This is how a technicians row is
--      correlated to its users row: there is no foreign key between the two
--      tables, so email equality is the only link, matching how the
--      application's own TechnicianRepository queries already work.
--   4. users.email does not already equal personal_email (idempotency: a
--      row already migrated - by this script running before, or already
--      correct from the newer registration/approval code path - is
--      guaranteed to be a no-op on a second run).
--
-- DUPLICATE / CONFLICT SAFETY - before overwriting a row, this migration
-- verifies personal_email is not already the email of a DIFFERENT users
-- row (which would otherwise violate the UNIQUE constraint on users.email,
-- or worse, silently reassign someone else's login identity). Any
-- technician whose personal_email collides with another account's email is
-- SKIPPED ENTIRELY - left exactly as-is, not touched, not errored - so it
-- can be investigated and resolved by hand. Use the post-migration
-- verification query below to find any such skipped rows.
--
-- CUSTOMER / AGENT / ADMIN users are never matched by the WHERE clause
-- (role = 'TECHNICIAN' is required), so they are guaranteed untouched.
--
-- Non-destructive: no DELETE, no DROP, no row is ever removed.
-- Idempotent and duplicate-safe: safe to run this script multiple times.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- STEP 0 (READ-ONLY) - run this BEFORE applying the migration to preview
-- exactly which rows will be migrated, and which will be skipped as
-- conflicts. This performs no writes and is always safe to run, including
-- directly against production.
-- -----------------------------------------------------------------------------
-- SELECT
--     u.id                                    AS user_id,
--     u.email                                 AS current_login_email,
--     t.id                                    AS technician_id,
--     t.personal_email                        AS will_become_login_email,
--     t.company_email                         AS retained_company_email_metadata,
--     t.verification_status,
--     CASE
--         WHEN EXISTS (
--             SELECT 1 FROM users other
--             WHERE lower(other.email) = lower(t.personal_email)
--               AND other.id <> u.id
--         ) THEN 'SKIP - personal_email already used by another account'
--         WHEN lower(u.email) = lower(t.personal_email) THEN 'NO-OP - already migrated'
--         ELSE 'WILL MIGRATE'
--     END AS migration_outcome
-- FROM users u
-- JOIN technicians t
--   ON lower(u.email) = lower(t.company_email)
--   OR lower(u.email) = lower(t.email)
-- WHERE u.role = 'TECHNICIAN'
--   AND t.personal_email IS NOT NULL
--   AND btrim(t.personal_email) <> ''
-- ORDER BY migration_outcome, u.id;


-- -----------------------------------------------------------------------------
-- STEP 1 - THE MIGRATION (the only statement that writes data)
-- -----------------------------------------------------------------------------
UPDATE users u
SET email = t.personal_email
FROM technicians t
WHERE u.role = 'TECHNICIAN'
  AND t.personal_email IS NOT NULL
  AND btrim(t.personal_email) <> ''
  AND (lower(u.email) = lower(t.company_email) OR lower(u.email) = lower(t.email))
  AND lower(u.email) <> lower(t.personal_email)
  AND NOT EXISTS (
        SELECT 1 FROM users other
        WHERE lower(other.email) = lower(t.personal_email)
          AND other.id <> u.id
  );


-- -----------------------------------------------------------------------------
-- STEP 2 (READ-ONLY) - run this AFTER applying the migration to confirm the
-- result and to list anything that still needs manual attention.
-- -----------------------------------------------------------------------------
-- 2a. Every technician's login email should now equal personal_email
--     (rows here are already fine, whether migrated just now or already
--     correct from the newer registration/approval code path).
-- SELECT
--     u.id AS user_id, u.email AS login_email, u.role,
--     t.id AS technician_id, t.personal_email, t.company_email,
--     (lower(u.email) = lower(t.personal_email)) AS matches_personal_email
-- FROM users u
-- JOIN technicians t
--   ON lower(u.email) = lower(t.personal_email)
--   OR lower(u.email) = lower(t.company_email)
--   OR lower(u.email) = lower(t.email)
-- WHERE u.role = 'TECHNICIAN'
-- ORDER BY user_id;
--
-- 2b. Anything still NOT matching personal_email after the migration ran
--     is a genuine conflict that was safely skipped and needs a manual
--     decision (this should return zero rows on a clean dataset).
-- SELECT
--     u.id AS user_id, u.email AS current_login_email,
--     t.id AS technician_id, t.personal_email AS desired_login_email,
--     t.company_email AS retained_company_email_metadata,
--     (SELECT other.id FROM users other
--       WHERE lower(other.email) = lower(t.personal_email) AND other.id <> u.id
--       LIMIT 1) AS conflicting_user_id
-- FROM users u
-- JOIN technicians t
--   ON lower(u.email) = lower(t.company_email)
--   OR lower(u.email) = lower(t.email)
-- WHERE u.role = 'TECHNICIAN'
--   AND t.personal_email IS NOT NULL
--   AND btrim(t.personal_email) <> ''
--   AND lower(u.email) <> lower(t.personal_email);
--
-- 2c. Sanity check - no CUSTOMER/AGENT/ADMIN row should ever appear here
--     (this migration's WHERE clause never matches them; this query exists
--     purely as an auditable guarantee).
-- SELECT id, email, role FROM users WHERE role <> 'TECHNICIAN'
--   AND id IN (
--     SELECT other.id FROM users other
--     JOIN technicians t2 ON lower(other.email) = lower(t2.personal_email)
--   );
