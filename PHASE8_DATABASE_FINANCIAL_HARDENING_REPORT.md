# PHASE 8 — Database & Financial Hardening Report

Status: **implemented, `mvn clean test` and `mvn verify` BUILD SUCCESS**, awaiting review.
Stop condition respected: **Phase 9 was NOT started.**

---

## 1. What Phase 8 changed

### 1.1 Flyway becomes the schema authority

* Added `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql`
  (versions managed by Spring Boot 3.3.5) to `pom.xml`.
* Canonical migration directory: **`src/main/resources/db/migration/`**.
* Production (`application-production.properties`) now runs Flyway and switches Hibernate
  to **`ddl-auto=validate`** (`JPA_DDL_AUTO:validate`). Hibernate can no longer mutate the
  production schema; a failed migration or schema mismatch aborts startup.
* Default (`application.properties`, dev/test) keeps Flyway **off** and Hibernate
  `ddl-auto=update`, so the existing H2 test suite is unchanged.
* The superseded hand-written SQL in `database/migrations/` and the stray
  `V20260822_01__create_agent_crm.sql` were removed from any executable path and moved,
  unchanged, to **`database/legacy-migrations/`**. There is now exactly one migration
  directory and no ambiguity.

### 1.2 The migration set

| File | Purpose |
| --- | --- |
| `V1__baseline_schema.sql` | Full PostgreSQL schema as of end of Phase 7. **Generated from the JPA entity model** with Hibernate's `PostgreSQLDialect` + `CamelCaseToUnderscoresNamingStrategy`, then made idempotent (`create table if not exists`, catalog-guarded FKs). Mirrors exactly what Hibernate `validate` expects. |
| `V2__integrity_constraints_and_indexes.sql` | Functional/partial unique indexes, repository query indexes, important FKs (`NOT VALID`), financial non-negativity checks. Fully idempotent (`if not exists` / `pg_constraint` guards). |
| `V3__exact_money_booking_invoice.sql` | Adds authoritative `*_minor BIGINT` columns to `bookings`/`invoices` and back-fills every existing row from the legacy `Double` value (exact round-half-up). |

### 1.3 Is the V1 baseline trustworthy?

Because no PostgreSQL/Docker is available here (see §4), hand-writing a baseline risks a
`validate` mismatch. Instead V1 was **produced mechanically from Hibernate itself**: a
throw-away `@SpringBootTest` ran with `PostgreSQLDialect` and
`jakarta.persistence.schema-generation.scripts.action=create` to emit the exact DDL
Hibernate expects, which was then transcribed into V1 and made idempotent. The generator
test was removed after use.

### 1.4 Baseline adoption strategy (fresh vs existing DB)

* **Fresh empty DB** → Flyway runs V1, V2, V3.
* **Existing Hibernate DB (current production)** → schema is non-empty with no Flyway
  history, so `baseline-on-migrate` records a baseline at **version 1** and applies only
  V2 + V3. V1 is also idempotent as belt-and-braces. Nothing is dropped; no existing row is
  touched by V1/V2; V3 only adds and back-fills columns.

Full operator procedure: **`DATABASE_MIGRATION_RUNBOOK.md`**.

### 1.5 Exact money for Booking / Invoice

* New authoritative columns: `bookings.{base,addons,protection,platform_fee,total,advance,remaining,paid}_amount_minor`
  and `invoices.{amount,paid_amount}_minor`.
* The legacy `Double` columns are **retained, deprecated, read-only mirrors**. Paired
  entity setters keep the exact value and the mirror in lock-step, so older code/tests that
  still write a `Double` cannot desynchronise the authority. The `Double` columns are not
  dropped (Phase 8 safety rule).
* All financial decision paths now use exact minor units via `PaymentMoney`:
  * `PaymentService` obligation/excess checks, payment aggregation (paid + remaining),
    expected payment amounts;
  * `BookingService` remaining-balance "balance due" check;
  * `InvoiceService` invoice amount/paid snapshots;
  * `RefundRuleEngine` and `RefundService` refund ceilings/status;
  * `AdminService` dashboard revenue sum (no floating-point accumulation).
* Conversion boundary remains a single helper, `PaymentMoney` (added `resolveMinor(Long, Double)`).

### 1.6 DB integrity added in V2

* **Uniqueness:** `lower(email)` on users; `lower(company_email)` on technicians (partial);
  notification idempotency (partial); one active refund per booking (partial);
  one open Stripe session/payment-intent per ledger row (partial); provider refund id
  (partial). (`ratings.booking_id`, `invoices.booking_id`, `services.code`,
  `service_addons.code`, `service_prices(service_id,currency)`, `push_device_tokens.token`
  are already unique in V1.)
* **Foreign keys:** booking→user/technician/agent/service, invoice→booking,
  rating→booking, payment_transaction→booking/user, payment_refund→refund_request/
  payment_transaction, refund_request→booking/user/admin, chat→booking/user,
  notification→booking. Added `NOT VALID` so legacy orphan rows cannot fail the
  migration; new rows are enforced immediately. No `ON DELETE CASCADE` on financial
  relationships — historical financial data is protected from cascade deletion.
* **Checks:** non-negative money on `payment_transactions`, `payment_refunds`,
  `service_prices`, `service_addons` (V2) and on the new booking/invoice minor columns (V3).
* **Indexes:** customer/technician/agent/service/status/payment_status/created_at on
  bookings; customer/technician on invoices and ratings; notification and chat lookups;
  technician availability/verification/personal_email; refund status.

---

## 2. Success-criteria checklist (from `phase.txt`)

| Criterion | Result |
| --- | --- |
| Versioned migration tooling exists | **Yes** — Flyway core + postgres |
| Fresh DB can be built from migrations | **Yes by construction** (V1 generated from Hibernate); executed only where PostgreSQL is available (§4) |
| Existing DB can adopt migrations safely | **Yes** — baseline-on-migrate @ V1, additive V2/V3, never destructive |
| Migration failure prevents unsafe startup | **Yes** — Flyway runs before JPA; failure aborts boot |
| Production Hibernate validates, never mutates | **Yes** — `ddl-auto=validate` in `production` |
| At least one migration-driven integration test exists | **Yes** — `MigrationContractTest` (runs everywhere) + `PostgresMigrationValidationTest` (real DB) |
| PostgreSQL-specific verification where environment permits | **Present, skipped here** — no Docker/PostgreSQL in this environment (§4) |
| Persisted financial money no longer depends on Double/float | **Yes** for Booking/Invoice (exact `*_minor` authority); ledger/catalog already exact |
| Booking snapshot / paid / remaining use exact money | **Yes** |
| Invoice values use exact money | **Yes** |
| PaymentTransaction / ServicePrice remain exact minor | **Yes** (unchanged) |
| No epsilon/floating comparisons | **Yes** — integer minor comparisons |
| Historical booking prices unchanged | **Yes** — back-fill converts, does not re-price |
| Payment amount still derives from booking snapshot | **Yes** — `expectedPaymentAmountMinor` from snapshot |
| Refund calculations remain correct | **Yes** — full refund suite green |
| Legacy financial data has a safe conversion strategy | **Yes** — V3 back-fill + retained Doubles |
| No historical money silently corrupted by rounding | **Yes** — `round(numeric)::bigint`, half-up; no destructive step |
| Important relationships have DB FKs where safe | **Yes** — `NOT VALID` FKs |
| Historical financial data protected from cascade deletion | **Yes** — no cascade FKs to/from financial tables |
| Frequently queried columns indexed | **Yes** |
| Critical uniqueness in DB | **Yes** |
| Case-insensitive email uniqueness in PostgreSQL | **Yes** — `uq_users_email_lower` |
| Rating booking uniqueness | **Yes** |
| Invoice booking uniqueness | **Yes** |
| Service code / price uniqueness | **Yes** |
| Stripe provider id uniqueness | **Yes** — partial unique indexes |
| Technician free-text statuses → enums if applicable | **Not converted by design** — see §3 |
| Enum persistence uses STRING not ordinal | **Yes** — all `@Enumerated(EnumType.STRING)` (unchanged, verified) |
| Old migration directories no longer ambiguous | **Yes** — `database/legacy-migrations/` (non-executable) + `db/migration/` only |
| State machines / catalogs / exception handler remain authorities | **Yes** (untouched) |
| Phase 1–7 tests green | **Yes** |
| `mvn clean test` BUILD SUCCESS | **Yes** — 326 tests, 0 failures, 0 errors, 1 skipped |
| `mvn verify` BUILD SUCCESS | **Yes** |
| 0 expected-failure tests | **Yes** |
| `DATABASE_MIGRATION_RUNBOOK.md` exists | **Yes** |
| `PHASE8_DATABASE_FINANCIAL_HARDENING_REPORT.md` exists | **Yes** (this file) |

---

## 3. Deliberately deferred / documented gaps

1. **Technician `verificationStatus` / `availabilityStatus` stay free-text Strings.** They
   are used as string literals across many booking/assignment flows; converting them to
   Java enums is a behavioural change (not schema-hardening) and would risk the Phase 2/5
   lifecycle contracts. They are listed as throttled strings, not ordinals, so the
   "STRING not ordinal" requirement holds. Recommended as a dedicated future change.
2. **Legacy `Double` money columns are retained.** This is the explicitly-endorsed safe
   intermediate state. A future **contract migration** (drop the mirrors) is required once
   the app has run on `*_minor` for a release; it is intentionally not done here.
3. **V2 FKs are `NOT VALID`.** This keeps adoption safe on legacy data. Running
   `VALIDATE CONSTRAINT` (runbook §5) is the follow-up to make historical rows enforced too.
4. **`technician_onboarding_tokens`/`password_reset_tokens` FK names** are kept identical to
   Hibernate's generated names so V1 and a Hibernate-created DB agree.

---

## 4. Honest verification note (what was NOT run here)

* **Docker is not installed** in this environment, and no PostgreSQL server is reachable,
  so **Testcontainers was not used** and the real-PostgreSQL migration/validation could
  **not be executed here**. No claim is made that it was.
* To keep the guarantee verifiable anyway, `PostgresMigrationValidationTest` is included
  and is **automatically skipped** unless `GOS_POSTGRES_TEST_URL` is set. It uses the
  Flyway API to `clean()` + `migrate()` a dedicated schema and asserts the tables,
  exact-money columns, unique indexes and checks. Point it at any scratch PostgreSQL
  (or run it in CI / against staging) to get the end-to-end proof.
* `MigrationContractTest` (no DB) verifies the migration set structurally and **does** run
  in this suite.

Test totals: **326 tests, 0 failures, 0 errors, 1 skipped (the env-gated PostgreSQL test),
0 `@Tag("expected-failure")`.** `mvn clean test` and `mvn verify` are BUILD SUCCESS.

---

## 5. Files changed / added

**Added**
* `src/main/resources/db/migration/V1__baseline_schema.sql`
* `src/main/resources/db/migration/V2__integrity_constraints_and_indexes.sql`
* `src/main/resources/db/migration/V3__exact_money_booking_invoice.sql`
* `src/test/java/com/geekonsites/backend/phase8/MigrationContractTest.java`
* `src/test/java/com/geekonsites/backend/phase8/PostgresMigrationValidationTest.java`
* `src/test/java/com/geekonsites/backend/entity/MoneyExactnessTest.java`
* `DATABASE_MIGRATION_RUNBOOK.md`, `PHASE8_DATABASE_FINANCIAL_HARDENING_REPORT.md`
* `database/legacy-migrations/` (moved historical scripts, non-executable)

**Changed**
* `pom.xml` (Flyway dependencies)
* `application.properties`, `application-production.properties` (Flyway + ddl-auto policy)
* `entity/Booking.java`, `entity/Invoice.java` (exact minor authority + synced mirrors)
* `service/PaymentMoney.java` (`resolveMinor`)
* `service/PaymentService.java`, `BookingService.java`, `InvoiceService.java`,
  `AdminService.java`, `RefundRuleEngine.java`, `RefundService.java` (exact minor)
* `test/.../support/Phase0IntegrationTestSupport.java` (seed exact minor),
  `test/.../TechnicianPersonalEmailMigrationSqlTest.java` (legacy path)

**Removed**
* `src/main/resources/db/migration/V20260822_01__create_agent_crm.sql` (superseded by V1/V2)
* `database/migrations/` (moved to `database/legacy-migrations/`)

---

## 6. Authorities preserved

`BookingStateMachine` (sole lifecycle authority), `PaymentTransaction`/`PaymentRefund`
ledgers, `PaymentTransactionStateMachine`/`PaymentRefundStateMachine`, DB
`Service`/`ServicePrice` catalog, booking price snapshot, `RatingService`,
`GlobalExceptionHandler`, and the Phase 4 transaction/locking/after-commit event
architecture are all **untouched**. Phase 8 only added schema, exact storage, and
conversion-free money decisions.
