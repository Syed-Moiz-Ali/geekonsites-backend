# PHASE 5 — Authorization, Ownership & Domain Integrity Hardening

> Scope: GeekOnSites Java / Spring Boot backend — the 4 remaining defect areas.
> References: audit + Phase 0–4 reports, client PDF, live repository.
> No frontend changes. No Service Catalog (Phase 6).

---

## 1. State entering Phase 5

289 tests total, 279 passing, **10 intentionally expected-failure** tests across four
suites: `InvoiceAuthorizationIntegrationTest` (2), `BookingCreationRoleRegressionTest` (3),
`RatingRegressionTest` (1), `RegistrationCountryRegressionTest` (4). Phases 1–4 delivered the
payment ledger, the booking state machine, Stripe hardening, and transaction/concurrency
protection.

## 2. Previous 10 expected failures

- Invoice authorization happened **after** mutation (`generateInvoiceFromBooking` mutated, then `verifyAccess`).
- `POST /api/bookings` was only `authenticated()`, so technicians/agents/admins could become a booking's customer.
- No uniqueness on `ratings.booking_id`; two rating write paths; aggregate updated inconsistently.
- Registration silently coerced any non-UK country to `US` (India/XYZ/blank/null → US).

## 3. Invoice authorization defect

`InvoiceController.generateInvoiceFromBooking` called `bookingService.getBookingForCurrentUser`
(which permits an **assigned technician**), then `invoiceService.generateInvoiceFromBooking`
(mutation), and only **afterwards** `verifyAccess` (which rejects technicians). An assigned
technician could therefore trigger a real invoice mutation and still receive 403.
`BookingController.generateInvoice` (`PUT /api/bookings/{id}/generate-invoice`) had the same
problem.

## 4. Invoice authorization architecture after fix

New `BookingService.authorizeInvoiceAction(bookingId, user)` is called **before** any mutation:
allowed = `ADMIN`, `AGENT`, or the owning `CUSTOMER`; `TECHNICIAN` is rejected. Both controllers
now do `authenticate → load → authorize → mutate → save`. Internal automatic generation
(`PaymentService` → `InvoiceService`) is a trusted service call and is unaffected.

## 5. Authorization-before-mutation verification

`InvoiceAuthorizationIntegrationTest` asserts both the HTTP result **and** that the invoice
count/booking invoice state is unchanged after a forbidden attempt. All 4 tests pass:
assigned technician (POST + booking endpoint) rejected with **no** invoice row persisted;
unrelated customer and unassigned technician rejected before mutation.

## 6. Invoice role/ownership matrix

| Operation | CUSTOMER | TECHNICIAN | AGENT | ADMIN | Internal |
|---|---|---|---|---|---|
| generate invoice (manual) | own only | ✗ | ✓ | ✓ | ✓ |
| read invoice | own only | ✗ | ✓ | ✓ | n/a |

## 7. Booking creation role defect

`POST /api/bookings` was `authenticated()` and wrote the caller into `customerId`, so
operational accounts could silently become customers.

## 8. Booking creation rules after fix

- `SecurityConfig`: `POST /api/bookings` now `.hasRole("CUSTOMER")`.
- `BookingController.createBooking`: defense-in-depth role check (non-customer → 403).
- Customer identity is always taken from the authenticated principal; a spoofed
  `customerId` in the body is ignored (verified by test).

## 9. Assisted-booking decision/deferment

Creating a booking on behalf of a customer (agent/assisted flow) is **not** implemented or
invented in Phase 5. A future assisted-booking API must carry a real customer identity and
an audit actor. Deferred.

## 10. Previous rating architecture

Two competing paths: `RatingController.submitRating` (called `BookingService.rateBooking`,
then inserted a `Rating` and updated the technician average) and `BookingService.rateBooking`
(also via `PUT /api/bookings/{id}/rating`). No uniqueness on `ratings.booking_id`, so
duplicate reviews could inflate the aggregate; the two paths could also diverge.

## 11. Final rating architecture

A single `RatingService.submitRating(bookingId, rating, review, customer)` owns submission:
authenticate/ownership → lifecycle guard → validate value → app duplicate check → persist →
recalculate technician aggregate. `RatingController` and `BookingController.rateBooking` both
delegate to it. `BookingService.rateBooking` was removed.

## 12. Rating single source of truth

`RatingService` is the only rating write authority. `RatingController.getTechnicianRating`
remains read-only.

## 13. Duplicate prevention

Application check (`existsByBookingId`) returns **409 Conflict**; the database
`uq_ratings_booking` unique constraint is the final authority. A concurrent losing insert
(`DataIntegrityViolationException`) is mapped to 409 with no duplicate row.

## 14. Rating DB constraint

`Rating` now declares `@Column(name="booking_id", nullable=false, unique=true)` with a named
`@UniqueConstraint(name="uq_ratings_booking")`. (PostgreSQL is authoritative; H2 ddl-auto
also creates it for tests.)

## 15. Rating ownership rules

Only an authenticated `CUSTOMER` whose id equals `booking.customerId` may submit. Request
`customerId`/`technicianId` are ignored — the technician is derived from the booking.
Technicians/agents/admins cannot submit a customer review (controller/security + service guard).

## 16. Rating lifecycle requirements

Only post-service states are reviewable: `SERVICE_COMPLETED`, `REMAINING_PAYMENT_PENDING`,
`FULLY_PAID`, `INVOICE_GENERATED`, `BOOKING_CLOSED`. Pre-service states are rejected with 400
(message preserved: “A booking can be rated only after service completion”).

## 17. Technician rating aggregate behavior

After each successful submission the aggregate is **recalculated from stored ratings**
(`findByTechnicianId(...).average()`), not incremented — so it cannot drift and cannot be
inflated by duplicates.

## 18. Registration country defect

`"UK".equalsIgnoreCase(country) ? "UK" : "US"` silently mapped India/XYZ/blank/null to `US`.

## 19. Accepted country values

`US`, `USA`, `United States`, `United States of America` → `US`; `UK`, `GB`, `GBR`,
`United Kingdom`, `Great Britain` → `UK`. Case-insensitive; underscores normalized. (Same
alias set already used by pricing.)

## 20. Country normalization/validation

New `CountrySupport.normalize` returns canonical `US`/`UK` or throws **400** for
null/blank/unsupported. Called **before** account persistence, so no invalid country reaches
the DB.

## 21. Country/currency relationship

Country is validated before persistence, so an unknown value can never enter and default to
USD pricing. Server-authoritative pricing (US→USD, UK→GBP) is unchanged.

## 22. Email normalization findings/fix

Registration used case-sensitive `findByEmail` while login used `findByEmailIgnoreCase` — a
case-variant could register a second account. Registration now normalizes `trim + lowercase(Locale.ROOT)`
and checks `existsByEmailIgnoreCase` (conflict → 409). Login already uses
`findByEmailIgnoreCase`.

## 23. Public-registration role-field treatment

`RegisterRequest.role` was removed. Public registration always creates `CUSTOMER`; a
caller-supplied `"role":"ADMIN"` is ignored by Jackson and cannot grant privilege (verified by
test).

## 24. Focused mutating-endpoint audit findings

- `POST /api/bookings` — fixed to CUSTOMER-only (role + ownership via principal).
- `PUT /api/bookings/{id}/generate-invoice`, `POST /api/invoices/booking/{id}` — fixed
  authorization-before-mutation.
- `POST /api/ratings` — single service, ownership + duplicate enforced.
- `PUT /api/bookings/{id}/rating` — now delegates to `RatingService`.
- Existing ownership checks (cross-customer booking/invoice/chat, agent scope) remain intact
  (`AuthorizationOwnershipIntegrationTest` green).

## 25. Additional authorization issues discovered

No new HIGH-severity IDOR discovered beyond the audited defects. `getBookingForCurrentUser`
and `verifyAccess` already enforce ownership for reads; agent/admin operational scope is
unchanged.

## 26. SecurityConfig changes

Added `.requestMatchers(HttpMethod.POST, "/api/bookings").hasRole("CUSTOMER")` before the
generic `/api/bookings/**` rule. `POST /api/ratings` remains `hasRole("CUSTOMER")`; public
registration remains permit-all; invoice routes remain authenticated with controller-level
authorization. No overlapping/precedence surprises.

## 27. Files created

```
src/main/java/com/geekonsites/backend/service/CountrySupport.java
src/main/java/com/geekonsites/backend/service/RatingService.java
database/migrations/20260828_case_insensitive_email.sql
src/test/java/com/geekonsites/backend/phase5/Phase5AuthorizationIntegrationTest.java
```

## 28. Files modified

```
src/main/java/com/geekonsites/backend/controller/InvoiceController.java
src/main/java/com/geekonsites/backend/controller/RatingController.java
src/main/java/com/geekonsites/backend/controller/BookingController.java
src/main/java/com/geekonsites/backend/service/BookingService.java
src/main/java/com/geekonsites/backend/config/SecurityConfig.java
src/main/java/com/geekonsites/backend/auth/AuthController.java
src/main/java/com/geekonsites/backend/dto/RegisterRequest.java
src/main/java/com/geekonsites/backend/entity/Rating.java
src/main/java/com/geekonsites/backend/repository/RatingRepository.java
```

## 29. Tests added/modified

- Added `Phase5AuthorizationIntegrationTest` (5): privileged-role registration rejected as
  CUSTOMER, case-variant duplicate email rejected, booking customerId cannot be spoofed,
  concurrent duplicate rating stores exactly one, aggregate updated once.
- Un-tagged and passing: `InvoiceAuthorizationIntegrationTest` (2),
  `BookingCreationRoleRegressionTest` (3), `RatingRegressionTest` (1),
  `RegistrationCountryRegressionTest` (4).
- Aligned the rating lifecycle error message with the existing contract.

## 30. Expected-failure tags before/after

- Before: 10 `@Tag("expected-failure")`.
- After: **0** (repository-wide search returns NONE).

## 31. Full `mvn test` result

```
mvn -B clean test → Tests run: 294, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

## 32. `mvn verify` result

```
mvn -B verify → Tests run: 294, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```
(An offline first attempt failed only on fetching a plugin artifact; the online run passed.
Tests were green in both.)

## 33. New defects discovered

None. The rating lifecycle message mismatch was a wording difference, not a defect, and was
aligned.

## 34. Deferred issues

- Assisted/agent-on-behalf booking API (explicitly deferred; must carry real customer identity + audit actor).
- Email `lower(email)` unique index is a PostgreSQL migration to fold into the future Flyway baseline; H2 functional-index support varies.
- Full global error contract, DTO validation overhaul, Service Catalog (Phase 6).

## 35. Phase 6 readiness

**READY.** All four defect areas are fixed, the 10 expected-failure tags are cleared, and
`mvn clean test`/`verify` are green with 0 unexpected failures. Payment ledger and
`BookingStateMachine` authority remain untouched.
