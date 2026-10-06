# PHASE 0 — Backend Baseline, Regression Safety & Architecture Freeze

> **Scope:** GeekOnSites Java / Spring Boot backend only. No production code changed.
> **Reference:** `GeekOnSites_Backend_Audit_Report_Updated.md` (technical) + `GeekOnSites_Full_Project_Overview_Architecture.pdf` (business).
> **Goal:** make the current backend measurable/testable before critical booking/payment changes.

---

## PHASE 0 IMPLEMENTATION SUMMARY

### 1. Files inspected

Production (read-only): full `src/main/java` tree — config (`SecurityConfig`, `AppConfig`, `ApplicationConfig`, `StripeConfig`, `AdminAccountInitializer`), `auth/*`, `jwt/*` (both `JwtAuthenticationFilter` and dead `JwtFilter`), all controllers/services/repositories/entities/enums/DTOs, `application*.properties`, `pom.xml`, `Dockerfile`, `render.yaml`, `database/migrations/*`, `src/main/resources/db/migration/*`.

Tests (read-only, for convention reuse): `BookingAssignTechnicianIntegrationTest`, `RatingSubmissionIntegrationTest`, `OnsiteTrackingLifecycleIntegrationTest`, `AuthenticationLoginTest`, `PaymentServiceTest`, `ProductionRuntimeContextTest`, full test inventory (32 classes).

### 2. Actual stack confirmed (from code, not the report)

| Item | Confirmed value |
|---|---|
| Java | 17 (`maven-compiler-plugin`, source/target 17) |
| Spring Boot | 3.3.5 (`pom.xml:9`) |
| Build | Maven 3.9.16 (installed in this environment) |
| DB runtime | PostgreSQL (`application.properties:5-9`); H2 for local/tests |
| Security | `spring-boot-starter-security`, `SecurityConfig` stateless JWT |
| JWT | `jjwt 0.12.6`, HS256, 7-day expiry (`JwtService.java:31`) |
| Stripe | `stripe-java 29.1.0` |
| Google | Calendar API `v3-rev20250404` + oauth-client-jetty |
| Firebase | `firebase-admin 9.10.0` |
| JPA | `spring-boot-starter-data-jpa`, Hibernate, `ddl-auto=${JPA_DDL_AUTO:update}` |
| Validation | `spring-boot-starter-validation` present, inconsistently applied |
| Test deps | `spring-boot-starter-test` (JUnit 5, Mockito, AssertJ, MockMvc) |
| Profiles | `application.properties` + `application-local.properties` + `application-production.properties` |
| Env vars | `JWT_SECRET`, `ADMIN_*`, `STRIPE_*`, `MAIL_*`, `RESEND_API_KEY`, `GOOGLE_CALENDAR_*`, `FIREBASE_*`, `DB_*`, `CORS_ALLOWED_ORIGINS` |
| Render | `render.yaml` free plan, docker, `/api/health` |
| ddl-auto | production `update` (no Flyway/Liquibase) |
| migrations | ad-hoc SQL in `database/migrations/` + `db/migration/` (manual, not auto-executed) |

### 3. Tests existing before changes

- **171 tests / 32 classes / 0 failures / 0 errors / 0 skipped** — run before any modification (`mvn clean test`, BUILD SUCCESS).
- Integration coverage already present: auth login, change-password, password reset, technician login/authorization, technician registration, assignment (service-mode), on-site tracking lifecycle, rating aggregate, agent CRM security, production runtime context.
- Gaps (now addressed by Phase 0): arbitrary status, manual payment endpoints, split payment/refund, tracking lifecycle mutation, remote-session completion bypass, premature close, invoice auth-before-mutation, booking-creation role, duplicate rating, country validation.

### 4. Tests added

**55 new tests across 14 classes + 1 shared test-support class.** No production class was modified.

- `support/Phase0IntegrationTestSupport` — one deterministic H2 context (Firebase/Google disabled), DB reset, factories (`saveUser`, `saveTechnician`, `saveBooking`, `saveBookingForCustomer`, `bearer`, `statusOf`).
- `phase0/BookingLifecycleIntegrationTest` (5)
- `phase0/ArbitraryStatusEndpointRegressionTest` (2)
- `phase0/PaymentSecurityRegressionTest` (2)
- `service/SplitPaymentRegressionTest` (2)
- `service/OnsiteRemainingPaymentRegressionTest` (1)
- `phase0/TechnicianAssignmentIntegrationTest` (5)
- `phase0/TrackingLifecycleRegressionTest` (6)
- `phase0/RemoteSessionLifecycleRegressionTest` (5)
- `phase0/BookingCloseRegressionTest` (5)
- `phase0/InvoiceAuthorizationIntegrationTest` (4)
- `phase0/BookingCreationRoleRegressionTest` (4)
- `phase0/RatingRegressionTest` (2)
- `phase0/RegistrationCountryRegressionTest` (6)
- `phase0/AuthorizationOwnershipIntegrationTest` (6)

Tests that encode required future behaviour but fail on today's code are tagged `@Tag("expected-failure")` (not disabled, not weakened).

### 5. Tests currently passing

- **187 / 226** pass: all **171 original** tests + **16 new** contract/invariant tests.
- New tests that already pass (existing behaviour is correct):
  - unverified / unavailable technician cannot be assigned
  - completing a paid+invoiced+completed booking is allowed
  - unrelated customer / unassigned technician cannot generate an invoice
  - a CUSTOMER can create a booking
  - a TECHNICIAN cannot submit a customer rating (role-gated)
  - US and UK registrations accepted
  - cross-customer booking/invoice/chat access blocked; cross-customer refund creates no refund row; unassigned technician blocked; agent can list bookings
  - non-remote booking cannot enter remote-session started

### 6. Regression tests currently failing (39, all `expected-failure`)

ArbitraryStatus (2), BookingLifecycle (5), BookingClose (4), TechnicianAssignment (3), Tracking (6), RemoteSession (4), InvoiceAuthorization (2), BookingCreationRole (3), Rating (1), RegistrationCountry (4), PaymentSecurity (2), SplitPayment (2), OnsiteRemainingPayment (1).

### 7. Each failure mapped to the updated audit issue / target phase

| Test (class.method) | Audit ref | Target phase |
|---|---|---|
| ArbitraryStatusEndpointRegressionTest.agentCannotForceUnpaidBookingStraightToCompleted | C1 / BUG-01 | Phase 2 |
| ArbitraryStatusEndpointRegressionTest.agentCannotSetAnArbitraryStatusOnAnAlreadyCompletedBooking | C1 / BUG-01 | Phase 2 |
| BookingLifecycleIntegrationTest.unpaidBookingCannotBeForcedToServiceCompleted | C1 / BUG-01 | Phase 2 |
| BookingLifecycleIntegrationTest.unpaidBookingCannotBeClosed | H6 / BUG-11 | Phase 2 |
| BookingLifecycleIntegrationTest.closedBookingCannotRestartService | C1 / BUG-01 | Phase 2 |
| BookingLifecycleIntegrationTest.completedBookingCannotRegressToTechnicianAssigned | C1 / BUG-01 | Phase 2 |
| BookingLifecycleIntegrationTest.cancelledBookingCannotReturnToAnActiveState | C1 / BUG-01 | Phase 2 |
| BookingCloseRegressionTest (4 tests) | H6 / BUG-11 | Phase 2 |
| TechnicianAssignmentIntegrationTest.reassigningTechnicianMustReleaseThePreviousTechnician | H2 / BUG-06 | Phase 1 |
| TechnicianAssignmentIntegrationTest.completedBookingCannotBeAssigned | C1 / BUG-01 | Phase 2 |
| TechnicianAssignmentIntegrationTest.cancelledBookingCannotBeAssigned | C1 / BUG-01 | Phase 2 |
| TrackingLifecycleRegressionTest (6 tests) | H3/H4 / BUG-07/08, BUG-26 | Phase 2 |
| RemoteSessionLifecycleRegressionTest (4 tests) | H5 / BUG-09/10 | Phase 1–2 |
| InvoiceAuthorizationIntegrationTest.assignedTechnicianMustNotCreateOrMutateAnInvoice | H9 / BUG-15 | Phase 1 |
| InvoiceAuthorizationIntegrationTest.assignedTechnicianMustNotTriggerInvoiceGenerationViaBookingEndpoint | H9 / BUG-15 | Phase 1 |
| BookingCreationRoleRegressionTest (3 tests) | H10 / BUG-16 | Phase 1 |
| RatingRegressionTest.secondRatingForTheSameBookingMustBeRejected | H7 / BUG-13 | Phase 1 |
| RegistrationCountryRegressionTest (4 tests) | H12 / BUG-20 | Phase 1 |
| PaymentSecurityRegressionTest (2 tests) | C2 / BUG-02 | Phase 1 |
| SplitPaymentRegressionTest (2 tests) | C3/C4 / BUG-03/04 | Phase 1 |
| OnsiteRemainingPaymentRegressionTest.settlingAnOnsiteBookingMustNotAttemptRemoteProvisioning | **NEW (not in audit)** | Phase 1 |

### 8. Discrepancies discovered between report and repository

1. **Maven “not installed” / suite “not executed” (updated report §20) — inaccurate for this environment.** Maven 3.9.16 and JDK 17.0.12 are present; the suite runs. Baseline was green (171/171) before any change.
2. **New defect not in the updated audit:** `PaymentService.finalizePaidBooking` routes the REMAINING payment of an **on-site** booking through `RemoteSessionProvisioningService.provisionAfterPayment`, which throws `"Remote session provisioning is available only for remote bookings"` **after** marking the booking PAID. Reproduced with real collaborators (`OnsiteRemainingPaymentRegressionTest`). The existing mocked `PaymentServiceTest` masked it.
3. All other stack/module claims in the updated audit match the repository (including C4’s split-payment overwrite example, verified line-for-line).
4. Test-suite project has **no `src/test/resources` and no test profile**; each integration class sets properties inline. Phase 0 introduces one shared harness rather than adding a profile (safe, minimal).

### 9. Files created / modified

**Created (test only):**
```
src/test/java/com/geekonsites/backend/support/Phase0IntegrationTestSupport.java
src/test/java/com/geekonsites/backend/phase0/BookingLifecycleIntegrationTest.java
src/test/java/com/geekonsites/backend/phase0/ArbitraryStatusEndpointRegressionTest.java
src/test/java/com/geekonsites/backend/phase0/PaymentSecurityRegressionTest.java
src/test/java/com/geekonsites/backend/phase0/TechnicianAssignmentIntegrationTest.java
src/test/java/com/geekonsites/backend/phase0/TrackingLifecycleRegressionTest.java
src/test/java/com/geekonsites/backend/phase0/RemoteSessionLifecycleRegressionTest.java
src/test/java/com/geekonsites/backend/phase0/BookingCloseRegressionTest.java
src/test/java/com/geekonsites/backend/phase0/InvoiceAuthorizationIntegrationTest.java
src/test/java/com/geekonsites/backend/phase0/BookingCreationRoleRegressionTest.java
src/test/java/com/geekonsites/backend/phase0/RatingRegressionTest.java
src/test/java/com/geekonsites/backend/phase0/RegistrationCountryRegressionTest.java
src/test/java/com/geekonsites/backend/phase0/AuthorizationOwnershipIntegrationTest.java
src/test/java/com/geekonsites/backend/service/SplitPaymentRegressionTest.java
src/test/java/com/geekonsites/backend/service/OnsiteRemainingPaymentRegressionTest.java
```
**Modified (production): none. Modified (tests): none of the pre-existing tests.**
**Documentation added:** this file; `BACKEND_AUDIT_REPORT.md` (earlier deliverable).

### 10. Commands executed

```
mvn -B clean test                         # baseline (pre-change): 171 pass
mvn -B -o test-compile                    # compile new tests
mvn -B -o test -Dtest=<new classes>       # observe actual behaviour
mvn -B -o clean test                      # final: 226 run / 39 fail (all expected)
mvn -B -o test -DexcludedGroups=expected-failure   # green subset: 187 / 0 fail
```

### 11. Final `mvn test` result (honest)

```
Tests run: 226, Failures: 39, Errors: 0, Skipped: 0
```
- All 39 failures are `@Tag("expected-failure")` and represent **required future behaviour the current code violates**. None are unexpected.
- Green subset (`-DexcludedGroups=expected-failure`): **187 tests, 0 failures, 0 errors** → BUILD SUCCESS.

### 12. Risks before Phase 1

1. `mvn clean test` / `verify` now **fails by design** (39 expected failures). The `Dockerfile` runs `mvn clean verify`, so the container build will fail until Phase 1/2 land. **Do not deploy from this state.** Use `mvn -DexcludedGroups=expected-failure test` for a green CI gate meanwhile.
2. `expected-failure` tests must be **removed/un-tagged as their fixes land** — otherwise the intended-failure set silently grows stale.
3. Split-payment and refund correctness cannot be fully fixed without the `PaymentTransaction` ledger (Phase 1); until then, on-site split refunds remain structurally unsafe.
4. The Phase 0 harness runs against H2 in PostgreSQL mode; PostgreSQL-specific behaviour (e.g. partial unique indexes) is not exercised here.
5. No production/architecture change was made in Phase 0; all root causes remain exactly as audited.

---

## BACKEND FLOW MAP (current, verified)

**AUTH** — `POST /api/auth/register` → `AuthController.register` (role forced CUSTOMER) → `UserRepository.save`. `POST /api/auth/login` → `findByEmailIgnoreCase` → BCrypt `matches` → technician approval gate → `JwtService.generateToken`. Per-request: `JwtAuthenticationFilter` → `extractEmail` → `findByEmail` → `SecurityContext` principal = `User`. Admin: `POST /api/admin/auth/login` → `AdminAuthController`.

**BOOKING** — `POST /api/bookings` (authenticated) → `BookingController.createBooking` → `BookingService.createBooking` → `TrustedPricingService.calculatePricing` → `BookingRepository.save` + notification.

**PAYMENT** — `POST /api/payments/create-checkout-session` → `PaymentService.createCheckoutSession` (server-side amount) → Stripe `Session.create`. Stripe → `POST /api/payments/webhook` (permitAll, signature-verified) → `applyCompletedCheckoutSession` (amount/currency verified) → booking update → `InvoiceService.generateInvoiceFromBooking` → `RemoteSessionProvisioningService.provisionAfterPayment` → notification. Fallback `GET /api/payments/confirm-checkout-session` (authenticated, ownership-checked).

**TECHNICIAN** — `POST /api/technicians` (public) → Technician + User (PENDING). Approve `PUT /api/technicians/{id}/approve` → APPROVED+AVAILABLE. Assignment `PUT /api/bookings/{id}/assign-technician/{techId}` → checks payment/verification/availability/mode → TECHNICIAN_ASSIGNED + tech BUSY. Then accept/reject → on-the-way → arrived → start-service → complete-service.

**REMOTE** — provisioning via `GoogleCalendarService.createGoogleMeetLink`. Start via `BookingService.startRemoteSession` (booking endpoint) **or** `RemoteSessionService.startRemoteSession` (remote-sessions endpoint). End via `RemoteSessionService.endRemoteSession` (second, weaker path).

**REFUND** — `POST /api/refunds/bookings/{id}` (CUSTOMER) → `RefundService.requestRefund` → `RefundRuleEngine.assess`; admin review/execute/reject → `StripeRefundGatewayImpl.refund`.

**INVOICE** — auto via `PaymentService.finalizePaidBooking` → `InvoiceService`; or `PUT /api/bookings/{id}/generate-invoice` → `BookingService.generateInvoice` (duplicate path); or `POST /api/invoices/booking/{id}` → `InvoiceService`.

**RATING** — `POST /api/ratings` (CUSTOMER) → `BookingService.rateBooking` + `RatingRepository.save` → technician aggregate; **or** `PUT /api/bookings/{id}/rating` → `BookingService.rateBooking` only.

---

## PHASE 1 READINESS

**READY.**

Phase 0 success criteria met:
- repository inspected (not assumed) ✔
- existing tests executed (171/171 green pre-change) ✔
- critical flows have regression coverage ✔
- split-payment limitation represented in tests (2 failing specs) ✔
- booking-lifecycle bypasses covered ✔
- tracking lifecycle issue covered ✔
- remote-session completion bypass covered ✔
- premature close covered ✔
- invoice authorization-before-mutation covered ✔
- role-based booking creation covered ✔
- duplicate rating covered ✔
- invalid country handling covered ✔
- authorization/ownership tests exist ✔
- no business feature implemented ✔
- complete test result reported honestly (226 / 39 expected / 0 unexpected) ✔

Proceed to Phase 1 only on review. Do **not** begin Phase 1 (payment ledger, state machine, etc.) automatically.
