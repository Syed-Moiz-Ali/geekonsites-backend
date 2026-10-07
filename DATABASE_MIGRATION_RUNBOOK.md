# GeekOnSites Database Migration Runbook

Phase 8 introduced **Flyway** as the single source of truth for the PostgreSQL schema.
Hibernate no longer mutates production; it only validates.

---

## 1. Where migrations live

The **only** authoritative migration directory is:

```
src/main/resources/db/migration/
├── V1__baseline_schema.sql                # full schema as of end of Phase 7 (idempotent)
├── V2__integrity_constraints_and_indexes.sql  # FKs, unique/partial indexes, checks
├── V3__exact_money_booking_invoice.sql    # exact minor-unit money + backfill
└── V4__external_operation_recovery.sql    # Phase 9: retry state for critical side effects
```

The superseded hand-written scripts were moved, unchanged, to
`database/legacy-migrations/` (for history only — they are **never executed**). Do not
add new SQL there. There is no longer any ambiguity about which directory runs.

Naming: `V<version>__<description>.sql`. Versions are unique and strictly increasing.

---

## 2. Configuration by profile

| Setting | default / dev / test | `production` profile |
| --- | --- | --- |
| `spring.flyway.enabled` | `false` (`FLYWAY_ENABLED`) | `true` |
| `spring.jpa.hibernate.ddl-auto` | `update` (`JPA_DDL_AUTO`) | **`validate`** |
| `spring.flyway.baseline-on-migrate` | `true` | `true` |
| `spring.flyway.baseline-version` | `1` | `1` |

Notes:

* Dev/test keep the historical Hibernate behaviour (`ddl-auto=update`, Flyway off). The
  H2 test suite is unaffected.
* Production runs Flyway at startup, then Hibernate **validates** that the entities match
  the migrated schema. A failed migration or a schema mismatch aborts startup before the
  app serves traffic.
* Optional env overrides: `FLYWAY_ENABLED`, `FLYWAY_BASELINE_ON_MIGRATE`,
  `FLYWAY_BASELINE_VERSION`, `JPA_DDL_AUTO`.

---

## 3. How the baseline handles the two database shapes

### A fresh, empty database (e.g. a brand-new Render DB)

Flyway sees an empty schema, so `baseline-on-migrate` does nothing and all migrations
run in order: **V1 creates the whole schema**, then V2 and V3 harden it.

### An existing database created by Hibernate (the current production DB)

The schema is non-empty and has no `flyway_schema_history`, so `baseline-on-migrate`
records a baseline at **version 1** (this represents "schema already present") and then
applies **V2 and V3 only**. Because V1 is also written idempotently
(`create table if not exists ...`), even a misconfiguration that runs it would be a
no-op. Nothing is dropped, and no existing row is modified by V1 or V2.

V3 **does** write to existing rows — it back-fills the new exact-money columns from the
legacy `Double` values (exact round-half-up to the nearest minor unit). This is the only
data-touching migration and it is additive.

---

## 4. Pre-deploy checklist (production)

1. **Take a logical backup** (`pg_dump`) and confirm it restores into a scratch DB.
2. Rehearse on a **staging copy** of production (restore the dump, point the app at it,
   start it). Confirm the log shows Flyway applying V2/V3 and then Hibernate validating
   with no errors.
3. Confirm the production service has `SPRING_PROFILES_ACTIVE=production` and that
   `JPA_DDL_AUTO` is **not** overridden to `update`/`create`.
4. Deploy. Flyway runs automatically on startup inside a transaction per migration; a
   failure stops the boot.
5. Verify `GET /api/health` and spot-check a booking/invoice.

### Manual / off-band application (optional)

```powershell
# Against a scratch database only:
$env:GOS_POSTGRES_TEST_URL="jdbc:postgresql://localhost:5432/geekonsites"
$env:GOS_POSTGRES_TEST_USER="geekonsites"
$env:GOS_POSTGRES_TEST_PASSWORD="..."
mvn -B -o -Dtest=PostgresMigrationValidationTest test
```

The test cleans a dedicated schema (`phase8_migration_validation`), migrates from scratch
and asserts the resulting tables, exact-money columns, unique indexes and checks. It is
automatically skipped when `GOS_POSTGRES_TEST_URL` is unset.

---

## 5. Financial data safety model

* `payment_transactions`, `payment_refunds`, `service_prices`, `service_addons` already
  store **exact integer minor units** (unchanged).
* `bookings` and `invoices` now also have authoritative `*_minor BIGINT` columns
  (migration V3). The legacy `Double` columns are **retained, read-only/deprecated
  mirrors**. They are kept in lock-step by paired entity setters, so a reader of either
  value sees a consistent number, but financial decisions use the exact minor value.
* Nothing is dropped by Phase 8. A future **contract migration** may drop the Double
  columns once the app has run on the new columns for a release and the drop is
  separately reviewed. Do not drop them by hand.

### Foreign keys and running `VALIDATE CONSTRAINT`

V2 adds the important foreign keys (booking/payment/refund/rating/invoice relationships)
as **`NOT VALID`**: they are enforced for all new rows immediately, but pre-existing rows
are not scanned, so the migration cannot fail on legacy orphan data. Financial rows are
referenced with the default `NO ACTION` rule, so deleting a booking that has payment or
refund history is refused — historical financial data is protected from cascade deletion.

After you have reconciled any orphan rows (query below), you may validate the constraints
so PostgreSQL also guarantees the historical data:

```sql
-- find orphans before validating, e.g. for payments:
SELECT pt.id, pt.booking_id
FROM payment_transactions pt
LEFT JOIN bookings b ON b.id = pt.booking_id
WHERE b.id IS NULL;

-- then, per constraint, once the query returns no rows:
ALTER TABLE payment_transactions VALIDATE CONSTRAINT payment_transactions_booking_id_fkey;
```

Constraint names follow PostgreSQL defaults
(`<table>_<column>_fkey`) plus the named checks/indexes
(`ck_*`, `uq_*`, `idx_*`).

---

## 6. Rollback strategy

Flyway Community has no automatic undo, by design.

* **A migration failed / is unacceptable:** restore the pre-deploy backup, fix the
  migration additively, and redeploy. Never edit an already-applied `V*` file — its
  checksum is recorded and `validate-on-migrate` will reject the change.
* **Application bug (not schema):** deploy the previous application version. It is
  compatible, because Phase 8 is additive (the old code ignores the new columns).
* **Schema change needed later:** always add a new `V4__...sql`. Forward-only.

---

## 7. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `FlywayValidateException: checksum mismatch` | A `V*` file was edited after being applied. Restore the original file; add a new migration for the change. |
| `relation "..." already exists` on a fresh DB | Schema was not actually empty (or V1 accidentally ran twice). V1 is idempotent; check `flyway_schema_history`. |
| Startup fails with `Schema-validation: missing column ...` | Migrations did not run before Hibernate. Confirm `spring.flyway.enabled=true` and profile `production`. |
| FKs fail to add on legacy data | Expected only if you force validation; the shipped FKs are `NOT VALID` precisely to avoid this. Reconcile then `VALIDATE CONSTRAINT`. |
| Need to see what ran | `SELECT * FROM flyway_schema_history ORDER BY installed_rank;` |
