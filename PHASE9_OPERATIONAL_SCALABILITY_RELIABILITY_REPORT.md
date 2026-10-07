# PHASE 9 — Operational Scalability, Reliability & Domain Cleanup Report

Status: **implemented and green (`mvn clean test` + `mvn verify` BUILD SUCCESS), awaiting review.**
Stop condition respected: **Phase 10 was NOT started.**

Entering baseline: 326 tests / 0 failures / 0 errors / 1 skipped. Final: **337 tests / 0 failures /
0 errors / 1 skipped / 0 expected-failure tags.**

---

## 1. Remaining audit findings entering Phase 9

| AUDIT ISSUE | ORIGINAL SEVERITY | STATUS ENTERING P9 | FIXED IN | P9 ACTION |
| --- | --- | --- | --- | --- |
| Unbounded list APIs | High | Open | — | **Fixed** for notifications, admin customers, contacts, refunds, bookings |
| Agent CRM loads whole domain + in-memory filter | High | Open | — | **Fixed** (DB-side + batched) |
| Agent CRM N+1 (per-customer notes query) | High | Open | — | **Fixed** |
| Duplicate/divergent invoice generators | High | Partially (auth/concurrency) | — | **Fixed** (one authority) |
| Invoice numbering (hardcoded 2026 / two schemes) | Medium | Open | — | **Fixed** |
| Notification type inferred from title substring | Medium | Open | — | **Fixed** (`NotificationType`) |
| Notification retry duplication beyond payments | Medium | Partial | — | **Fixed** (dedupe keys + DB unique) |
| Notification list unbounded | Medium | Open | — | **Fixed** (paginated) |
| Remote provisioning failure recovery | High | Partial (P4 state) | — | **Fixed** (scheduled worker) |
| Excess reversal failure recovery | High | Partial (P4 state) | — | **Fixed** (scheduled worker) |
| Stripe refund call inside transaction | High | Deferred (P4) | — | **Fixed** (two-phase) |
| Entity exposure on operational lists | Medium | Open | — | **Partial** (admin customers/contacts/refunds → DTO; Booking still exposed — documented) |
| Dead code (TestController / EmailController / CustomerController / 2nd JwtFilter) | Low | Open | — | **Fixed** |
| Support email inconsistency | Low | Open | — | **Fixed** |
| Technician free-text statuses → enums | Low | Deferred (P8) | — | Still deferred (documented) |

---

## 2. List-endpoint audit (summary)

| ENDPOINT | BEFORE | BOUNDED? | DB-FILTER | ENTITY | AFTER |
| --- | --- | --- | --- | --- | --- |
| `GET /api/notifications/my-notifications` | unbounded List | No | No | Notification | **PageResponse**, `read` filter, page cap |
| `GET /api/notifications/{customerId}` | unbounded List | No | No | Notification | **PageResponse** (deprecated) |
| `GET /api/admin/customers` | `findAll().filter` | No | No | User | **PageResponse<AdminCustomerResponse>**, search |
| `GET /api/contact` | unbounded List | No | No | ContactMessage | **PageResponse<ContactMessageResponse>** |
| `GET /api/admin/refunds` | unbounded List | No | No | RefundRequest | **PageResponse<RefundResponse>** |
| `GET /api/refunds/my-refunds` | unbounded List | No | No | RefundRequest | **PageResponse<RefundResponse>** |
| `GET /api/bookings` | `findAll()` | No | No | Booking | bounded (100) + **`/page`** |
| `GET /api/bookings/my-bookings` | unbounded | No | No | Booking | bounded (100) + **`/my-bookings/page`** |
| `GET /api/bookings/customer|technician|agent/{id}` | unbounded | No | No | Booking | bounded (100) |
| `GET /api/agent-crm/customers` | in-memory page | Page DTO | No | DTO | **DB-side page + filters** |
| `GET /api/agent-crm/enquiries` | `findAll` users+contacts | No | No | DTO | bounded (200) + email lookup batch |
| `GET /api/agents/booking-queue` | already paginated | Yes | Yes | projection | unchanged |

---

## 3. Pagination standard

* `PageResponse<T>` (`dto/PageResponse.java`): `content, page, size, totalElements, totalPages,
  first, last`. Does **not** serialize Spring `Page`/`Pageable` internals.
* `PageRequestParams` (`dto/PageRequestParams.java`): `page<0 → 0`, `size<=0 → 20`,
  `size>100 → 100`. Prevents `size=1000000`.
* New pagination errors flow through the existing Phase 7 `GlobalExceptionHandler`/`ApiErrorResponse`
  (no bespoke error bodies).

## 4. Paginated endpoints
Notifications (`/my-notifications`, legacy `/{customerId}`), admin customers, contacts, admin +
customer refunds, bookings (`/page`, `/my-bookings/page`). Agent CRM `customers` already returned a
page DTO and is now DB-backed.

## 5. Compatibility / deprecation decisions
* No list endpoint was silently re-shaped where a test depends on an array; the notification inbox
  and admin/contact/refund lists had no array-shape test, so they became `PageResponse`.
* Legacy unbounded list methods (`BookingService.getAllBookings()` etc.) remain but are **bounded to
  100 newest** rows via repository `Pageable`; new `/page` endpoints are the long-term API.
* `GET /api/notifications/{customerId}` marked `@Deprecated` (thin delegate to the paginated service).

## 6–7. Agent CRM — before / after
**Before:** `bookings.findAll()`, `users.findAll()`, `contacts.findAll()`, `followUps.findAll()`
mapped into memory; per-customer notes query (N+1); `summary()` loaded whole tables.
**After:** `fetchCrmCustomers(...)` filters search/country/latest-booking-status/service-mode/follow-up
timing and paginates **in the database**; exactly **four batched aggregate queries** per page
(bookings, follow-ups, last-contact, last-note) regardless of page size; `summary()` uses COUNT
queries only. Ordering changed from "most recent interaction" to a stable `id DESC` (aggregate not
orderable in portable JPQL) — documented.

## 8–9. N+1 / repository & projection changes
* New projections/records: `repository/projection/CustomerActivity`.
* New batch queries: `BookingRepository.findByCustomerIdInOrderByCreatedAtDesc`,
  `CrmFollowUpRepository.findByCustomerIdIn`, `Contacts.lastContactByCustomer`,
  `CrmNoteRepository.lastActivityByCustomer`.
* Bulk `markAll*Read` via `@Modifying` (no full-list load).

## 10. Query / performance verification
* `AgentCrmServiceTest.customerListIssuesAFixedNumberOfQueriesRegardlessOfPageSize` asserts one
  aggregate query each and **no `findAll()`** for a 3-customer page.
* `ExternalOperationRecoveryWorkerTest.recoveryScansAreBoundedByBatchSize` asserts bounded scans.
* `PageRequestParamsTest` asserts the size cap.

## 11–14. Invoice architecture
**Before:** `BookingService.generateInvoice` used `GOS-2026-%06d` (hardcoded year, no Invoice row);
`InvoiceService` used `GOS-US-INV-{id}` (second scheme, Invoice row). Two independent generators.
**After:** `InvoiceService` is the single generation authority (idempotent one-per-booking,
concurrency-safe via unique `booking_id` + `saveAndFlush` race handling, exact booking snapshot).
`BookingService.generateInvoice` is a **thin delegate** that only performs the lifecycle transition
and the deduped notification. Numbering: **`GOS-{market}-{year}-{bookingId}`** with the year from the
injected `Clock` (tests prove 2026/2027/2030). Invoice uses the booking's exact `*_minor` snapshot,
never live `ServicePrice`.

## 15–19. Notifications
* **Typing:** new `enums/NotificationType`; every call site passes an explicit type; the previous
  title-substring `notificationType(...)` inference was removed.
* **Idempotency:** deterministic dedupe keys (`TYPE:subject:recipient`) stored in the existing unique
  `notifications.idempotency_key`, with an `existsByIdempotencyKey` fast path and the DB unique index
  as the concurrency backstop.
* **Pagination/ownership:** `/my-notifications` is paginated, supports `read`, and derives the
  recipient from the authenticated principal (never a path id). ADMIN inbox is supported; mark-read
  for ADMIN returns a deliberate 403 (no fall-through to 500).
* **Delivery vs record:** the durable in-app row is written first; push is best-effort and its failure
  is swallowed so the record survives.

## 20–26. Recovery design
* **Refund provider call:** two-phase — `reserve` (short tx: lock request, allocate, persist PENDING
  rows with stable idempotency keys, commit) → **Stripe call with no open tx/lock** → `record`
  (short tx per allocation) → `finalize` (short tx). Phase 4 over-allocation protection preserved
  (PENDING rows consume capacity). `RefundAllocationTest`/`RefundServiceTest`/`SplitPaymentRegressionTest`
  remain green.
* **Remote provisioning retry:** scheduled worker re-drives `FAILED`/stuck `PROVISIONING` remote
  paid bookings; `provisionAfterPayment` is idempotent (returns existing event/link) so a retry cannot
  create a duplicate Meet/Calendar event.
* **Excess reversal retry:** scheduled worker re-drives `PENDING`/`FAILED` excess reversals through
  `ExcessReversalService` (deterministic `gos-excess-reversal-{id}` key ⇒ no duplicate reversal).
* **Worker architecture:** bounded batch (`app.recovery.batch-size`), row-locked candidate selection,
  `attempts`, `nextAttemptAt`, exponential backoff capped at 1h, and parking at `attempts >= max` with
  a manual-review marker. Disabled by default; enabled in `production`.
* **Multi-instance safety:** `PESSIMISTIC_WRITE` row locks during reservation + deterministic provider
  idempotency keys; no in-memory/`synchronized` locking.
* **Failure classification:** exhausted/permanent retries are parked for manual review rather than
  looped forever.

## 27. Admin visibility
`GET /api/admin/operations/failures` (ADMIN-only) lists excess-reversal, remote-provisioning and
pending-refund counts (secret-free). `POST /api/admin/operations/retry/{type}/{id}` calls the **same
idempotent services** as the worker.

## 28. Dead code removed
`TestController` (root banner), `CustomerController` (placeholder), `EmailController` (dead, hardcoded
personal address), and the obsolete second `JwtFilter` (auto-registered, skipped `/api/bookings`);
`SecurityConfig` uses only `JwtAuthenticationFilter`. Security regression tests green.

## 29. Support email
`EmailService` now uses the configured `app.mail.support-address` everywhere (two hardcoded
`support@geekonsites.com` literals removed); `render.yaml` `SUPPORT_EMAIL` aligned to
`support@geekonsites.com`. Deployment must set `SUPPORT_EMAIL`.

## 30. Exact-money compliance
All Phase 9-modified financial paths use exact minor units (`PaymentMoney.resolveMinor`): refund
allocation/decision, invoice snapshot, admin revenue sum. No newly-modified path reads the legacy
`Double` mirrors as authority (they remain only as Phase 8 compatibility mirrors).

## 31. Flyway migrations added
`V4__external_operation_recovery.sql` — additive, idempotent retry columns
(`payment_transactions.reversal_attempts/reversal_next_attempt_at`,
`bookings.remote_provisioning_attempts/remote_provisioning_next_attempt_at`) + partial recovery
indexes. No other schema change. Canonical directory only.

## 32. PostgreSQL verification result
**Not executed here** — Docker/PostgreSQL are unavailable in this environment, so
`PostgresMigrationValidationTest` remains honestly **skipped** (it needs `GOS_POSTGRES_TEST_URL`). This
is a production gate; V1–V4 must be run against a real PostgreSQL before deploy. No claim of real PG
validation is made.

## 33–35. Files created / modified / deleted
**Created:** `dto/PageResponse`, `dto/PageRequestParams`, `dto/AdminCustomerResponse`,
`dto/ContactMessageResponse`, `dto/RefundResponse`, `dto/AdminOperationsDtos`,
`enums/NotificationType`, `repository/projection/CustomerActivity`,
`service/ExternalOperationRecoveryWorker`, `controller/AdminOperationsController`,
`db/migration/V4__external_operation_recovery.sql`, and tests
(`ExternalOperationRecoveryWorkerTest`, `PageRequestParamsTest`, `InvoiceNumberingTest`,
rewritten `AgentCrmServiceTest`), plus this report.
**Modified:** `NotificationService/Repository/Controller`, `AgentCrmService`, `AdminService/Controller`,
`ContactService/Controller`, `RefundService`, `RefundController`, `AdminRefundController`,
`PaymentService` (notification types via callers), `BookingService`, `BookingController`,
`InvoiceService`, `ExternalSideEffectListener`, `RemoteSessionProvisioningService`, `AgentService`,
`EmailService`, `SecurityConfig`, `AppConfig`, `Booking`/`PaymentTransaction` entities,
repositories (`User`, `Booking`, `Contact`, `CrmNote`, `CrmFollowUp`, `RefundRequest`,
`PaymentTransaction`, `PaymentRefund`), `application.properties`, `application-production.properties`,
`render.yaml`, `ProductionRuntimeContextTest`, `RemoteSessionPaymentGateTest`, `InvoiceServiceTest`,
`RefundAllocationTest`, `RefundServiceTest`.
**Deleted:** `TestController`, `CustomerController`, `EmailController`, `JwtFilter`.

## 36–37. Tests added / final counts
Added CRM DB + query-count tests, recovery-worker tests, pagination-params tests, invoice numbering
tests. **Final: 337 tests, 0 failures, 0 errors, 1 skipped, 0 expected-failure.**
`mvn clean test` and `mvn verify` are **BUILD SUCCESS**.

## 38. New defects discovered
* The two invoice generators produced **different numbers for the same booking** — resolved by the
  single-authority consolidation.
* `NotificationController` mapped ADMIN to the customer branch (would 500 via
  `IllegalArgumentException`) — fixed with explicit role handling.

## 39. Remaining production risks (honest)
1. **PostgreSQL migration validation not run here** (no Docker/Postgres). Run before deploy.
2. **Entity exposure is only partially reduced.** `Booking` is still returned directly by booking
   list/detail endpoints; a Booking DTO is a follow-up.
3. **Automated recovery of `PENDING` refund allocations** is not wired into the worker. The two-phase
   reservation already persists PENDING allocations with stable idempotency keys, so a future worker
   hook can safely re-drive them; crash windows (reserve→crash) currently need manual retry.
4. `AdminService.getRemoteSessions()` and CRM `enquiries()` are **bounded** but not fully paginated.
5. Technician `verificationStatus`/`availabilityStatus` remain free-text Strings (deferred from P8).
6. New notifications store enum-name `type`; pre-existing rows keep legacy type strings (display only).

## 40. Phase 10 readiness
Pagination, notification typing/idempotency, invoice consolidation, refund transaction boundaries and
bounded recovery are in place. Phase 10 (rate limiting, Swagger production exposure, security headers,
JWT/session policy, upload abuse controls, secret/config production checks) is untouched and ready to
start.
