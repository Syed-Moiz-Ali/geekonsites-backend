# GeekOnSites Backend Audit

> Senior Java/Spring Boot Backend Architecture, Code, API, Database, Security & Production-Readiness Audit
> Audited artifact: `geekonsites-backend-main`
> Client document: `GeekOnSites_Full_Project_Overview_Architecture.pdf`
> Scope: backend only (frontend/React/Tailwind/Framer excluded)

---

## 1. EXECUTIVE SUMMARY

**Verdict: PARTIALLY READY — core flow works, but not production-safe until P0 items are fixed.**

The backend is far more than a scaffold. The customer -> payment -> assignment -> tracking -> completion -> invoice -> rating journey is genuinely implemented and, unusually for a project this size, has real integration tests and a well-engineered Stripe + refund subsystem. However:

- The **booking state machine is not centrally enforced** and can be bypassed by multiple endpoints (some AGENT-reachable), and several write flows are **not transactional**.
- **Payment state can be set manually** without Stripe verification via admin-only endpoints, contradicting the PDF's explicit "payment validated server-side" requirement.
- The PDF's **Service entity / service-catalog management does not exist** — services and prices are hardcoded in Java, so Admin cannot "manage services" as required.
- The stated stack (**MySQL/Railway**) does not match the implementation (**PostgreSQL/Render**).
- Several business rules (quotes, no-fix/no-fee, parts billing, emergency pricing, cancellation) are undefined in the PDF and unimplemented.

**Estimated completion: ~65%.**

Method: weighted each backend capability the PDF actually requires, scored by what was verified end-to-end in code, weighted by business criticality (auth/payment/booking/lifecycle ~60% of weight; technician/remote/tracking/notifications ~25%; invoices/ratings/admin/agent/business-rules ~15%).

- Auth & RBAC ~95%
- Stripe checkout + webhook + refunds ~90%
- Booking lifecycle & tracking ~70%
- Technician verify/assign/availability ~85%
- Remote session (Google Meet) ~85%
- Notifications (in-app/push/email) ~85%
- Invoice ~60% (duplicated, inconsistent)
- Rating/review ~60% (duplicates allowed, dual source of truth)
- **Service catalog & admin service management ~10% (hardcoded)**
- Business pricing rules (fees/quotes/no-fix/no-fee/parts/emergency/cancellation) ~20%
- Production readiness (migrations/transactions/monitoring) ~55%

Combined -> ~65%.

---

## 2. CLIENT BACKEND REQUIREMENT SUMMARY

Driven only by the PDF (backend-relevant), with classification:

| # | Requirement | Class |
|---|---|---|
| R1 | JWT auth + role-based authorization | Explicit |
| R2 | Roles: CUSTOMER, TECHNICIAN, AGENT, ADMIN | Explicit |
| R3 | Service discovery + transparent USD/GBP pricing | Explicit |
| R4 | Booking creation and backend-controlled lifecycle (payment, assignment, progress, completion synchronized) | Explicit |
| R5 | Stripe Checkout + webhook; payment validated server-side | Explicit |
| R6 | Technician registration, verification, availability, assignment | Explicit + implied |
| R7 | Remote support via shared Google Meet session | Explicit |
| R8 | Location tracking for on-site services | Explicit |
| R9 | Notifications | Explicit |
| R10 | Invoices | Explicit in journey; **not** in the entity list -> ambiguous |
| R11 | Ratings/reviews | Explicit |
| R12 | Contact/customer messages | Explicit |
| R13 | Admin central control over users/services/bookings/techs/agents/payments/messages | Explicit (includes **service management**) |
| R14 | Agent: support, booking coordination | Explicit |
| R15 | US/UK only; USD/GBP; platform/service fees | Explicit |
| R16 | Free quotes / no-fix-no-fee / parts billed separately / emergency pricing | Explicit but **unspecified** |
| R17 | Security: env secrets, server-side validation, webhook verification, DB constraints | Explicit |
| R18 | Key entities incl. **Service**, Session/Meeting, Rating | Explicit |
| R19 | Automated technician matching | **Future** (do not treat as missing) |
| R20 | Scalability for more services/notifications/mobile | Future/architecture |

**Backend Requirement Baseline (concise):** authenticated multi-role API; service catalog with US/UK pricing; booking lifecycle state machine; Stripe payment + webhook; technician onboarding/verification/availability/assignment; remote-session provisioning; on-site tracking; notifications; invoices; ratings; contact messages; admin/agent operations; server-side validation and env-based secrets.

---

## 3. CURRENT JAVA BACKEND ARCHITECTURE

**Stack verified from code (not assumed):**

- Java 17, Spring Boot **3.3.5**, Maven (`pom.xml`).
- Spring Web, Data JPA, Security, Validation, Mail.
- **PostgreSQL** (runtime) + H2 (local/tests). `application.properties:5-9`. PDF said MySQL.
- JWT: `jjwt 0.12.6` — HS256, 7-day expiry (`JwtService.java:31`).
- Stripe Java `29.1.0`; Google Calendar API; Firebase Admin; springdoc-openapi.
- **No Flyway/Liquibase.** Schema via `spring.jpa.hibernate.ddl-auto=${JPA_DDL_AUTO:update}` (`application.properties:18`) + ad-hoc SQL under `database/migrations/` that is **never executed automatically**.

**Request flow (typical):** `SecurityFilterChain` (`config/SecurityConfig.java:35`) -> `JwtAuthenticationFilter` (`jwt/JwtAuthenticationFilter.java:22`) -> Controller -> Service -> Repository (`JpaRepository`) -> Postgres -> JSON. The principal is a `User` entity.

**Cross-cutting flows:**

- **Auth:** `AuthController.login` (`auth/AuthController.java:108`) -> `UserRepository.findByEmailIgnoreCase` -> BCrypt `matches` -> `JwtService.generateToken` -> subsequent requests validated and re-loaded from DB by the filter (role changes take effect immediately).
- **Booking -> Payment -> Stripe -> webhook -> booking update:** `PaymentController.createCheckoutSession` (`PaymentController.java:21`) -> `PaymentService.createCheckoutSession` (`PaymentService.java:58`) -> amount from `booking` server state -> `Session.create` -> user pays -> `PaymentController.handleStripeWebhook` (`PaymentController.java:46`) -> `PaymentService.handleWebhook` (`PaymentService.java:132`) verifies signature -> `applyCompletedCheckoutSession` (`PaymentService.java:190`) verifies amount/currency -> `finalizePaidBooking` -> `InvoiceService.generateInvoiceFromBooking` + `RemoteSessionProvisioningService.provisionAfterPayment`.
- Two JWT filters exist. `JwtFilter` (`jwt/JwtFilter.java`) is **never referenced by SecurityConfig** — but because it is a `@Component OncePerRequestFilter`, Spring Boot auto-registers it as a servlet filter. See BUG-09.

---

## 4. REQUIREMENT VS IMPLEMENTATION MATRIX

| Req | Client expectation | Current implementation | Relevant code | Status | Severity | Problem / Required change |
|---|---|---|---|---|---|---|
| R1 | JWT auth | BCrypt + HS256 JWT, DB-loaded principal, role gate | `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig:53-99` | COMPLETE | — | Add refresh/lockout/rate-limit later |
| R2 | 4 roles | `Role` enum + `SecurityConfig` role rules | `enums/Role.java` | COMPLETE | — | — |
| R3 | Service catalog US/UK | **Hardcoded** `Map` of 40 services + 70 addons | `TrustedPricingService.java:20-58` | INCORRECT | HIGH | No Service entity, no `/api/services`, no admin CRUD |
| R4 | Lifecycle controlled by backend | Enforced per-technician transitions BUT arbitrary override endpoint | `BookingService.java:680`, `BookingController.java:403` | PARTIAL | CRITICAL | Central state machine missing; see BUG-01 |
| R5 | Stripe, server-side validated | Checkout built server-side; amount/currency re-verified; webhook signature verified; idempotent | `PaymentService.java:91-121,278-293,210-214` | COMPLETE | — | Good. Manual override endpoints undermine it (BUG-02) |
| R6 | Technician verify/avail/assign | Enforced (`APPROVED`, `AVAILABLE`, mode compat) | `BookingService.java:151-212` | PARTIAL | HIGH | Reassignment leaves old tech BUSY; no status guard (BUG-03) |
| R7 | Shared Google Meet | Auto-provisioned from one calendar account; attendees = customer + tech; graceful if unconfigured | `GoogleCalendarService.java`, `RemoteSessionProvisioningService.java` | COMPLETE | — | Single shared account is per-PDF acceptable |
| R8 | Location tracking | Technician GPS updates + customer read with ownership checks | `BookingService.updateTechnicianLocation:346` | PARTIAL | MEDIUM | No retention/privacy control; GPS still writable after completion (BUG-11) |
| R9 | Notifications | In-app (customer/tech/agent/admin) + Firebase push + email | `NotificationService.java` | COMPLETE | — | Idempotency only on payment |
| R10 | Invoices | Two competing generators, two number formats | `BookingService.java:564`, `InvoiceService.java:55` | INCORRECT | MEDIUM | Duplicate/divergent; hardcoded year (BUG-06) |
| R11 | Ratings/reviews | Two sources of truth; no dedupe | `RatingController.java:34`, `BookingService.rateBooking:626` | PARTIAL | HIGH | Duplicate reviews; technician avg only updated in one path (BUG-04) |
| R12 | Contact messages | Full CRUD, role-gated, email notifications | `ContactController.java`, `ContactService.java` | COMPLETE | — | — |
| R13 | Admin manage services | **Not possible** | — | MISSING | HIGH | Must add Service entity + admin CRUD |
| R14 | Agent ops | CRM, queue, notes, follow-ups, assignment | `AgentCrmService`, `AgentOperationsService` | COMPLETE | — | N+1/in-memory filters (BUG-08) |
| R15 | US/UK, USD/GBP, fees | Country-normalized currency; snapshot on booking; flat $12/£12 fee | `TrustedPricingService.java:63-85` | PARTIAL | MEDIUM | Fee unconditional; protection plan never priced |
| R16 | Quotes/no-fix-no-fee/parts/emergency | Not implemented | — | NEEDS CLIENT CLARIFICATION | — | PDF under-specifies; do not invent |
| R17 | Security controls | Env secrets, CORS, webhook verify — but business errors -> 500, no rate limit | `SecurityConfig`, `application*.properties` | PARTIAL | HIGH | See section 18 |
| R18 | Service entity | Absent | — | MISSING | HIGH | See R3 |
| R19 | Automated matching | Absent | — | FUTURE REQUIREMENT | — | Correctly not required now |
| R20 | Scalability | Monolith, in-memory filtering in some places | `AgentCrmService.java:33-39` | PARTIAL | LOW | Not a current blocker |

---

## 5. CRITICAL ISSUES

**C1. Booking state machine is bypassable through multiple entry points.**

- `BookingController.updateStatus` (`BookingController.java:403`) -> `BookingService.updateStatus` (`BookingService.java:680`) sets **any** `BookingStatus` with **no validation, no timestamps, no side effects** (doesn't free the technician, doesn't set `serviceCompletedAt`, doesn't sync payment). Reachable by **AGENT** and **ADMIN** (`SecurityConfig.java:71`). An agent can set `COMPLETED` on an unpaid booking, resurrect `CANCELLED -> SERVICE_STARTED`, etc.
- `RemoteSessionService.endRemoteSession` (`RemoteSessionService.java:54`) sets `SERVICE_COMPLETED` with **no** status/payment check and no notification — a second, weaker completion path than `BookingService.completeService`.
- `RemoteSessionService.startRemoteSession` (`RemoteSessionService.java:38`) skips the `TECHNICIAN_ACCEPTED` requirement that `BookingService.startRemoteSession` enforces (`BookingService.java:451`).
- **Impact:** the PDF's core promise ("lifecycle controlled by the backend so payment, assignment, progress and completion remain synchronized") is not guaranteed. **Fix:** single `BookingStateMachine` service; remove `updateStatus`; route remote start/end through it.

**C2. Manual payment forgery endpoints.** `PUT /api/bookings/{id}/payment-success/{transactionId}` -> `BookingService.paymentSuccess` (`BookingService.java:214`) and `remainingPaymentSuccess` (`BookingService.java:596`) mark a booking `PAID`/`PARTIALLY_PAID` using a **client-supplied path `transactionId`**, with no Stripe lookup and no amount check. Admin-only (`SecurityConfig.java:69-70`), but any admin-token compromise or bug marks unpaid bookings as paid, directly contradicting R5. **Fix:** remove or make them call `confirmCheckoutSession`.

**C3. No `@Transactional` on the core booking/payment/invoice write flows.** `BookingService`, `PaymentService`, `RefundService`, `InvoiceService`, `AgentService`, `AdminService` have **no** transactional boundaries on their multi-write methods (grep confirms only `TechnicianService`, `PasswordResetService`, `AgentCrmService`, `RemoteSessionProvisioningService`, `UkEarlyServiceConsentService` are annotated). E.g. `assignTechnician` writes booking + technician + calendar + 2 notifications; a failure mid-way leaves partial state. **Fix:** annotate service write methods; keep external calls (Stripe/Google/email) outside or compensate.

---

## 6. HIGH PRIORITY ISSUES

- **H1. No Service entity / admin service management (R3, R13, R18).** Prices and catalog are `static final Map` in `TrustedPricingService.java`. Admin cannot add/edit/deactivate services; every price change is a redeploy.
- **H2. Technician reassignment leaks BUSY state.** `assignTechnician` (`BookingService.java:189-194`) sets the new technician `BUSY` but never restores the previously-assigned technician to `AVAILABLE`, and never checks the booking's current status. A cancelled/completed booking can be reassigned; a replaced technician is stuck busy forever. (BUG-03)
- **H3. Duplicate/inconsistent ratings.** `RatingController.submitRating` (`RatingController.java:34`) has no "already reviewed" check (no unique constraint on `ratings.booking_id`), and `BookingService.rateBooking` (`BookingService.java:626`) is a second rating path. A customer can post unlimited reviews and inflate the technician average.
- **H4. Duplicate/divergent invoicing.** `BookingService.generateInvoice` (`BookingService.java:564`) vs `InvoiceService.generateInvoiceFromBooking` produce different numbers (`GOS-2026-000001` vs `GOS-US-INV-1`), and the hardcoded `"GOS-2026"` year will be wrong in 2027.
- **H5. Non-transactional + race in `completeService`/`remaining`.**
  - `completeService` first sets `SERVICE_COMPLETED` then overwrites to `REMAINING_PAYMENT_PENDING` if balance > 0 (`BookingService.java:525-535`) — convoluted and not atomic.
  - `PaymentService.applyCompletedCheckoutSession` for `REMAINING` sets status `SERVICE_COMPLETED` (`PaymentService.java:232`) while `BookingService.remainingPaymentSuccess` sets `FULLY_PAID` + closes the booking (`BookingService.java:605-617`). Same business event, two end states.
- **H6. API docs and root endpoints are public.** springdoc UI (`/swagger-ui/**`, `/v3/api-docs/**`) and `/` (`TestController`) fall through `anyRequest().permitAll()` (`SecurityConfig.java:99`). Exposes the full API surface in production.
- **H7. Business validation surfaces as HTTP 500.** `PaymentController.createCheckoutSession` catches **all** exceptions and returns 500 with a generic message (`PaymentController.java:39-43`), so legitimate 400s (e.g. "Remote services require full payment") become 500. Much of `BookingService` throws `RuntimeException` too. No global `@ControllerAdvice` exists (grep: only two local `@ExceptionHandler` methods).
- **H8. Duplicate request filters.** `JwtFilter` (`jwt/JwtFilter.java`) is dead but is auto-registered as a servlet filter because it is `@Component`. If ordering ever changes, it sets a `String` principal and breaks every controller doing `getPrincipal() instanceof User` (`PaymentController.java:27`, `InvoiceController.java:62`, `RefundController.java:39`, etc.).

---

## 7. MEDIUM PRIORITY ISSUES

- **M1. Money stored as `Double`** across `Booking` (`baseAmount/totalAmount/paidAmount/...`) and `Invoice.amount`; only `RefundRequest` uses `BigDecimal`. Risk of floating-point drift; use `BigDecimal`/minor units.
- **M2. Denormalized flat IDs, no JPA relationships, no DB foreign keys** in `Booking` (`customerId`, `technicianId`, `agentId` as bare `Long`). Orphaned records possible; no referential integrity except self-authored SQL. PDF expects linked entities.
- **M3. `AgentCrmService` loads all users/bookings/contacts/follow-ups into memory and filters in Java; `row()` runs an extra query per customer (N+1).** `AgentCrmService.java:33-39,123`.
- **M4. No schema baseline migration.** Production relies on `ddl-auto=update`; `database/migrations/*.sql` and `db/migration/V20260822_01__create_agent_crm.sql` are manual and not wired to Flyway/Liquibase. `POSTGRESQL.md` admits "Schema migrations will be versioned before production data is introduced."
- **M5. Email account mismatch.** `render.yaml` sets `SUPPORT_EMAIL=support@gos.com` while `application.properties`/templates use `support@geekonsites.com`; `EmailController` targets a hardcoded personal Gmail (and has `//@RestController`).
- **M6. Flat, unconditional platform fee `12.0`** added to every booking including a $19 product-recommendation service (`TrustedPricingService.java:70,77`); `protectionAmount` is always forced to 0 so protection plans are free.
- **M7. `RegisterRequest.role` is accepted but ignored.** Not exploitable now (`AuthController.java:93` hardcodes CUSTOMER) but a mass-assignment landmine.
- **M8. `generateInvoice` reachable by the assigned technician** (`BookingController.java:347` -> `getBookingForCurrentUser`), blurring role boundaries.
- **M9. `getAllBookings` / `AdminController.getCustomers` are unpaginated** and return full entities (`BookingController.java:61`, `AdminController.java:46`).
- **M10. No cancellation flow.** `BookingStatus.CANCELLED` exists only via the arbitrary `updateStatus`; refunds never set it (`RefundService`).
- **M11. Public technician registration (`POST /api/technicians` permitAll)** stores up to ~7 base64 documents (<=5 MB each) in DB columns; no rate limiting, no antivirus/Content-Type trust beyond a prefix check (`TechnicianService.java:167-184`).

---

## 8. LOW PRIORITY / CODE QUALITY ISSUES

- Dead code: `PricingService` (unused, and less safe than `TrustedPricingService`), `JwtFilter`, `CustomerController` (placeholder string), `EmailController` (disabled), `TestController`, `bookingRepository.findByBookingStatusOrderByCreatedAtDesc` etc.
- Duplicated Google-Meet regex in three places (`BookingService.java:737`, `RemoteSessionService.java:88`, plus start-guard).
- Fragile notification typing by substring: `NotificationService.notificationType` (`:210`) — e.g. any title containing "started" maps to `SERVICE_STARTED`.
- `catch (Exception)` used broadly (e.g. `PaymentService.createCheckoutSession:127`); swallowed push errors (acceptable by design).
- Invoice number hardcodes year `2026` (`BookingService.java:573,613`).
- `AgentOperationsService.countPeriod` JPQL hardcodes a `0` for one projection field (`BookingRepository.java:67`).
- No `README`; `POSTGRESQL.md` is good but describes the stack deviation only in passing.

---

## 9. MISSING BACKEND FEATURES (genuine requirements only)

1. **Service catalog entity + Admin service CRUD** (R3/R13/R18). Critical for "Admin manages services".
2. **Central booking state machine / validation** (R4).
3. **Booking cancellation** (implied by refund/cancel journey; PDF unspecified -> partly clarification).
4. **Duplicate-review prevention** (R11).
5. **`/api/services` discovery endpoint** with localized USD/GBP prices for the frontend.
6. Free quote / no-fix-no-fee / separate parts billing / emergency pricing — **do not implement until clarified** (R16).

---

## 10. API GAP REPORT

| Method | Path | Purpose | Authorization | Request | Response |
|---|---|---|---|---|---|
| GET | `/api/services` | List active services + USD/GBP price | public | — | `[{id,name,mode,usdPrice,gbpPrice,active}]` |
| GET | `/api/services/{id}` | Service detail | public | — | Service |
| POST/PUT/DELETE | `/api/admin/services` | Admin CRUD services/pricing | ADMIN | Service DTO | Service |
| PUT | `/api/bookings/{id}/cancel` | Customer/agent cancel with refund linkage | CUSTOMER(owner)/AGENT/ADMIN | `{reason}` | Booking |
| DELETE | `/api/admin/bookings/{id}/status-override` (remove) | — | — | — | — |
| PUT | `/api/bookings/{id}/payment-success/...` (remove) | — | — | — | — |

Also: no global error contract; business errors return 500 instead of 400/409; no pagination on list endpoints; `/api/bookings/customer/{id}`, `/api/bookings/agent/{id}` are role-gated but expose full entities.

---

## 11. DATABASE & ENTITY ISSUES

- **No `Service` entity** (R18).
- **No relationships/FKs** in `Booking` — flat `Long` IDs, duplicated denormalized names/phone/email (`Booking.java:25-48`). Ripe for drift/orphans.
- **`Double` money** (`Booking.java:81-93`, `Invoice.java:32,42`).
- **No unique constraint** on `ratings.booking_id` -> duplicate reviews (`entity/Rating.java`).
- **`Invoice.bookingId` is unique** (good), but invoice rows can still diverge from `Booking.invoiceNumber`.
- **No indexes** on `bookings.customer_id`, `bookings.technician_id`, `bookings.booking_status` despite frequent queries (`BookingRepository.java:24-37`). `refund_requests`, `remote_chat_messages`, `password_reset_tokens`, `push_device_tokens` do define indexes.
- **`technician.verificationStatus`/`availabilityStatus` are free-text `String`**, not enums — invalid values possible.
- No cascade/orphanRemoval concerns because there are almost no associations (design choice, but limits integrity).

---

## 12. AUTH & SECURITY ISSUES

- Good: BCrypt, env-only secrets, JWT min-length enforced (`JwtService.java:19`), login enumeration guarded (`AuthController.java:114-123`), admin login isolated (`AdminAuthController.java`), password-reset tokens hashed/single-use/expiring/URL-allowlisted (`PasswordResetService.java`), technician onboarding tokens hashed (`TechnicianService.java:302`), CORS allowlist, CSRF disabled appropriately for stateless JWT.
- **Issues:** swagger/root public (H6); dead `JwtFilter` auto-registered (H8); no login/register/reset rate limiting or lockout; JWT 7-day, no revocation; `RegisterRequest.role` accepted; assignment/invoice ownership not uniformly enforced; no `@PreAuthorize` (all gateway-based, which is fine, but `updateStatus` shows the danger).
- **Positive:** ownership checks are actually present in `getBookingForCurrentUser` (`BookingService.java:132`), `getBookingsByTechnicianId` (`BookingController.java:124-136`), `AgentController.authorizeAgentScope` (`:137`), `InvoiceController.verifyAccess` (`:68`), `RemoteChatService.authorizePaidParticipant` (`:50`), and are covered by tests.

---

## 13. BOOKING LIFECYCLE ISSUES

**Real state machine extracted from code:** `PENDING -> (payment) PAYMENT_COMPLETED | ASSIGNMENT_PENDING -> TECHNICIAN_ASSIGNED -> TECHNICIAN_ACCEPTED -> TECHNICIAN_ON_THE_WAY -> TECHNICIAN_ARRIVED -> SERVICE_STARTED | REMOTE_SESSION_STARTED -> SERVICE_COMPLETED -> (balance) REMAINING_PAYMENT_PENDING -> FULLY_PAID -> BOOKING_CLOSED`; plus `TECHNICIAN_REJECTED` (returns to `ASSIGNMENT_PENDING` implicitly) and `CANCELLED` (orphan).

- Transitions via `BookingService` technician methods are validated (good, tested). However the arbitrary `updateStatus` (BUG-01) allows: `completed->assigned`, `cancelled->in-progress`, `unpaid->service_started`, `failed->confirmed`, `completed->cancelled`. No transition guards, no audit trail.
- `assignTechnician` has no booking-status guard (can assign completed/cancelled bookings).
- `completeService` sets `SERVICE_COMPLETED` then conditionally overwrites; `RemoteSessionService.endRemoteSession` bypasses it entirely.
- Status column is not DB-constrained.

---

## 14. STRIPE PAYMENT ISSUES

- **Strong:** checkout session created server-side with server-computed amount (`PaymentService.java:85,95`); metadata binds `bookingId`+`paymentType`; webhook signature verified via `Webhook.constructEvent` (`:134`); amount & currency re-checked against booking (`:278-293`); idempotent replay by session id (`:210-214`); authenticated confirm fallback re-checks customer ownership (`:202`); refunds verify payment intent, booking metadata, currency and remaining captured amount, with idempotency keys (`StripeRefundGatewayImpl.java:29-51`).
- **Weaknesses:** manual `payment-success`/`remaining-payment-success` endpoints forge paid state (C2); `createCheckoutSession` returns 500 for business rejections (H7); `REMAINING` webhook path and manual path end in different booking states (H5); no `charge.refunded`/`checkout.session.expired` handling; concurrent webhook+confirm can both run `finalizePaidBooking` (mitigated by `findByIdForUpdate` only inside provisioning, not around the booking mutation itself).

---

## 15. TECHNICIAN / ASSIGNMENT ISSUES

- Verification + availability + service-mode compatibility are correctly enforced before assignment (`BookingService.java:165-187`, tested).
- Reassignment leak + no status guard (H2/BUG-03).
- `RemoteSessionService.endRemoteSession` gives technicians an unvalidated completion path (C1).
- `availabilityStatus` free-text (M).
- No dedicated "assignment history" entity — history is only implicit in booking timestamps (`technicianAcceptedAt/RejectedAt`); PDF says "assignment history" — currently PARTIAL.

---

## 16. NOTIFICATION / SESSION / TRACKING ISSUES

- **Notifications:** in-app + push + email; per-role scoping correct; payment notifications idempotent (`NotificationService.java:52-96`); push degrades gracefully. Gaps: most non-payment notifications are non-idempotent; `NotificationController` rejects ADMIN with a 500 (`:70-79`).
- **Sessions:** Google Meet auto-provisioned, attendee sync, link hidden until `PAID` via getter (`Booking.java:170-173`); two competing start/end services (C1). PDF doesn't specify generation mechanics -> the chosen central-calendar approach is reasonable, but persistence/expiry semantics are **NEEDS CLIENT CLARIFICATION**.
- **Tracking:** assignment-scoped writes, ownership-scoped reads, ETA/distance/heading persisted; no retention policy, no coordinate validation (`TechnicianLocationRequest` unvalidated), still writable post-completion; cross-booking leakage prevented by assignment check (tested).

---

## 17. US/UK PRICING & BUSINESS-RULE ISSUES

- Country normalization and USD/GBP selection work; currency snapshotted on booking (good for historical pricing) (`TrustedPricingService.java:63-67`).
- Flat `12.0` platform fee lacks a defined policy (PDF says "according to the defined pricing policy" — **undefined**).
- Protection plan accepted but never priced.
- No quote, no-fix/no-fee, separate parts billing, or emergency pricing.
- Refund rule engine is a genuine strength: UK 14-day/Consumer Rights Act context, US started/not-started logic, ceiling = captured - already refunded (`RefundRuleEngine.java`). Over-engineered relative to the PDF, but correct and tested.

---

## 18. BUG REPORT

**BUG-01 — Arbitrary booking status change**
- Severity: CRITICAL · Requirement: R4
- File/Class/Method: `controller/BookingController.java:403 updateStatus` -> `service/BookingService.java:680 updateStatus`; gate `config/SecurityConfig.java:71`
- Current: any AGENT/ADMIN sets any `BookingStatus` with no validation/side effects.
- Expected: only legal transitions, with timestamps, notifications, payment sync.
- Root cause: leftover generic setter, no state machine.
- Fix: delete endpoint; add `BookingStateMachine` + transition table.

**BUG-02 — Manual payment state forgery**
- Severity: CRITICAL · Requirement: R5
- `BookingService.java:214 paymentSuccess`, `:596 remainingPaymentSuccess`
- Current: marks paid from a path `transactionId`, no Stripe verification/amount check.
- Expected: only `confirmCheckoutSession` (Stripe-verified) sets paid.
- Root cause: pre-Stripe admin helper retained.
- Fix: remove endpoints or delegate to `PaymentService.confirmCheckoutSession`.

**BUG-03 — Technician reassignment leaks BUSY state / no status guard**
- Severity: HIGH · Requirement: R6
- `BookingService.java:151 assignTechnician`
- Current: sets new tech BUSY; never frees old tech; no booking-status check; can assign completed/cancelled bookings.
- Expected: free previous tech, only assign eligible bookings.
- Fix: if existing `technicianId != null && != new`, set old `AVAILABLE`; guard status in {`PAYMENT_COMPLETED`,`ASSIGNMENT_PENDING`,`TECHNICIAN_REJECTED`}.

**BUG-04 — Duplicate reviews allowed**
- Severity: HIGH · Requirement: R11
- `RatingController.java:34`, `entity/Rating.java`
- Current: unlimited ratings per booking; two rating paths; only `RatingController` updates technician average.
- Expected: one review per completed booking; single source.
- Fix: unique index on `ratings.booking_id`; deprecate `BookingService.rateBooking` or make it delegate.

**BUG-05 — Divergent remaining-payment end states**
- Severity: HIGH · Requirement: R4
- `PaymentService.java:228-232` vs `BookingService.java:596-617`
- Current: webhook sets `SERVICE_COMPLETED`; manual sets `FULLY_PAID` + closes.
- Expected: one consistent terminal state.
- Fix: unify via state machine/service.

**BUG-06 — Duplicate / hardcoded invoice generation**
- Severity: MEDIUM · Requirement: R10
- `BookingService.java:564-575`, `InvoiceService.java:100-110`
- Current: two numbering schemes; `"GOS-2026"` hardcoded.
- Expected: single generator, year from clock (the `Clock` bean exists).
- Fix: keep `InvoiceService`; derive year dynamically.

**BUG-07 — Business validation returns HTTP 500**
- Severity: MEDIUM · Requirement: R17
- `PaymentController.java:39-43`; `BookingService` `RuntimeException`s
- Current: valid rejections become 500 with generic message.
- Expected: 400/409 with reason.
- Fix: add `@RestControllerAdvice` mapping `ResponseStatusException`/domain exceptions; stop catching-all in checkout.

**BUG-08 — In-memory CRM aggregation / N+1**
- Severity: MEDIUM · Requirement: R14
- `AgentCrmService.java:33-39,123`
- Current: loads all tables, per-customer note query.
- Expected: paginated queries.
- Fix: repository-level pagination/search.

**BUG-09 — Dead `JwtFilter` auto-registered as servlet filter**
- Severity: MEDIUM · Requirement: R1/R17
- `jwt/JwtFilter.java`
- Current: unused yet `@Component` -> runs outside security chain; would set `String` principal.
- Expected: removed.
- Fix: delete class.

**BUG-10 — Public Swagger/root**
- Severity: MEDIUM · Requirement: R17
- `SecurityConfig.java:98-99`
- Current: `/swagger-ui/**`, `/v3/api-docs/**`, `/` public.
- Expected: restricted/disabled in production.
- Fix: `permitAll` only in dev, or disable springdoc in prod.

**BUG-11 — Tracking writable after completion; unvalidated coordinates**
- Severity: LOW · Requirement: R8
- `BookingService.java:346 updateTechnicianLocation`, `dto/TechnicianLocationRequest.java`
- Current: GPS updates persist after `SERVICE_COMPLETED`; no lat/long bounds.
- Expected: stop updates once closed; validate ranges.
- Fix: status guard + `@Min/@Max` validation.

**BUG-12 — Login/register email case mismatch**
- Severity: LOW · Requirement: R1
- `AuthController.java:80` (`findByEmail`) vs `:114` (`findByEmailIgnoreCase`); `entity/User.java:25`
- Current: case-variant emails can create two accounts; login may match the "wrong" one.
- Expected: case-insensitive uniqueness.
- Fix: normalize email on write + functional unique index on `lower(email)`.

**BUG-13 — `NotificationController` 500 for ADMIN**
- Severity: LOW · Requirement: R9
- `NotificationController.java:70-79`
- Current: ADMIN hits `IllegalArgumentException` -> 500.
- Expected: 403 or admin-supported response.
- Fix: handle ADMIN explicitly.

---

## 19. DEAD / DUPLICATED / UNNECESSARY CODE

- `service/PricingService.java` — dead, and trusts client amounts (superseded by `TrustedPricingService`).
- `jwt/JwtFilter.java` — dead but auto-registered (BUG-09).
- `controller/CustomerController.java` — placeholder string.
- `controller/EmailController.java` — disabled (`//@RestController`), hardcoded personal email.
- `controller/TestController.java` — root banner.
- Duplicate Meet-link regex x3; duplicate rating paths; duplicate invoice generators; duplicate remote-session start/end logic (`BookingService` vs `RemoteSessionService`).
- `test/service/PaymentServiceTest` etc. show intent to keep `PricingService` out — consistent with it being dead.

---

## 20. CLIENT CLARIFICATIONS REQUIRED

1. **Invoice architecture** — PDF lists invoices in the journey but not as an entity; confirm what an invoice must contain (tax/VAT, line items, parts, both currencies, PDF delivery?).
2. **Platform/service-fee policy** — the flat $12/£12 and whether protection plans are paid.
3. **Free quote / no-fix-no-fee** — exact trigger, who decides, effect on payment/refund.
4. **Hardware/parts billing** — must parts be addable *after* diagnosis (post-booking invoice line) or only at booking time?
5. **Emergency-service pricing** — definition, multiplier, availability.
6. **Cancellation rules** — who may cancel at which status, and automatic refund vs ticket (a refund engine exists but booking isn't set `CANCELLED`).
7. **Technician verification process** — what evidence and who approves (current implementation is a defensible guess, not a spec).
8. **Technician assignment rules** — manual only for now? Reassignment policy? (Automated matching is explicitly future.)
9. **Meeting-link ownership/expiry** — PDF says "shared Google Meet"; confirm central-account generation and post-service retention.
10. **Location-tracking retention/privacy** — how long GPS is retained and whether customers may disable it.
11. **Agent permission boundaries** — should agents be able to change booking status at all (currently yes, dangerously)?
12. **Stack decision** — confirm PostgreSQL/Render replacing MySQL/Railway.

---

## 21. PRODUCTION READINESS ASSESSMENT

- Env-based secrets OK; `/api/health` + `render.yaml` health check OK; Docker build runs tests (`mvn clean verify`) OK; graceful shutdown OK; Hikari tuned OK; CORS allowlist OK; webhook verification OK; password reset via HTTPS (Resend) because Render blocks SMTP OK.
- **Blockers:** `ddl-auto=update` with no versioned migration baseline (M4), non-transactional core flows (C3), bypassable lifecycle/payment state (C1/C2), public API docs (H6), errors returning 500 (H7), no rate limiting, Render **free plan** DB explicitly noted as expiring/no backups (`POSTGRESQL.md`). Schema drift risk is real.

---

## 22. RECOMMENDED BACKEND ARCHITECTURE CHANGES (justified only)

1. **Add `Service` (+`ServicePrice`/`Addon`) entities** and `ServiceCatalog`/`AdminServiceController`; move `TrustedPricingService` maps into DB with a cached loader. Backs R3/R13/R18.
2. **Introduce `BookingStateMachine`**; delete `updateStatus`; route all lifecycle paths through it; add status transition tests.
3. **Add `@Transactional`** to `BookingService`/`PaymentService`/`RefundService`/`InvoiceService`/`AgentService` write methods; move Stripe/Google/email side-effects to after-commit events (pattern already used for technician emails).
4. **Add a global `@RestControllerAdvice`** with a consistent error contract; stop catching-all in `PaymentController`.
5. **Unify rating and invoice paths;** add `ratings.booking_id` unique constraint.
6. **Adopt `BigDecimal`/minor units** for money.
7. **Add Flyway/Liquibase baseline** and turn off `ddl-auto` in production.
8. Add DB indexes (`bookings.customer_id`, `technician_id`, `booking_status`), paginate list endpoints.
9. Remove dead code (`PricingService`, `JwtFilter`, placeholder controllers).

(No microservices/abstraction recommended — the monolith is appropriate for this scale.)

---

## 23. PRIORITIZED IMPLEMENTATION PLAN

**P0 — Critical (security / payment / integrity)**

- **T1** Remove arbitrary status endpoint; add state machine. Files: `BookingController`, `BookingService`, new `BookingStateMachine`. Dep: T2. Result: only legal transitions, side effects guaranteed.
- **T2** Remove/replace manual payment endpoints with Stripe-verified flow. Files: `BookingController`, `BookingService`, `PaymentService`. Result: paid state cannot be forged.
- **T3** Add `@Transactional` to core write flows + move external calls to after-commit. Files: `BookingService`, `PaymentService`, `RefundService`, `InvoiceService`. Result: no partial writes.
- **T4** Reassignment fix + booking-status guard in `assignTechnician`. Files: `BookingService`. Result: no stuck-BUSY techs, no assigning closed bookings.
- **T5** Restrict/disable Swagger & root in production; delete `JwtFilter`. Files: `SecurityConfig`, `jwt/JwtFilter`. Result: no info leak/latent auth bug.

**P1 — Required business functionality**

- **T6** Service entity + `/api/services` + admin CRUD; migrate hardcoded catalog. Files: new entities/repo/controller/service, `TrustedPricingService`, `BookingService`.
- **T7** Rating dedupe + single source; invoice unification + dynamic year. Files: `RatingController`, `BookingService`, `InvoiceService`.
- **T8** Global exception handler + correct 4xx. Files: new advice; `PaymentController`.
- **T9** Customer cancellation endpoint wired to refund engine; set `CANCELLED`. Files: `BookingController`, `BookingService`, `RefundService`.
- **T10** Normalize emails + unique lower(email) index. Files: `AuthController`, `User`, migration.

**P2 — Architecture/API/DB**

- **T11** Flyway baseline, disable `ddl-auto=update` in prod, add indexes, paginate lists. Files: `pom.xml`, resources, repositories.
- **T12** Money -> `BigDecimal`. Files: `Booking`, `Invoice`, services.
- **T13** Resolve duplicate remote-session service into the state machine.

**P3 — Cleanup/testing**

- **T14** Remove dead code; fix `SUPPORT_EMAIL`; fix CRM N+1.
- **T15** Add tests for: arbitrary-status regression, manual-payment removal, duplicate rating, webhook signature/idempotency, cross-user access on invoices/refunds, reassignment, cancellation. Existing tests (`PaymentServiceTest`, `OnsiteTrackingLifecycleIntegrationTest`, `AgentCrmSecurityIntegrationTest`, `RefundServiceTest`) are a good base.

---

## 24. FINAL VERDICT

**Does the current backend support the GeekOnSites concept?**
Largely yes for the *core* journey — discovery (hardcoded) -> booking -> Stripe -> assignment -> tracking/remote -> completion -> invoice -> rating, with real code and tests. It does **not** yet satisfy the PDF's "backend-controlled lifecycle", "server-side payment validation", or "Admin manages services" guarantees in full.

**Genuinely complete: ~65%** (methodology in section 1).

**Biggest blockers:**

1. Bypassable booking state machine (BUG-01, C1).
2. Manual payment-state forgery endpoints (BUG-02, C2).
3. Non-transactional booking/payment/invoice flows (C3).
4. Missing Service catalog + admin service management (R3/R13/R18).
5. No versioned migrations; `ddl-auto=update` in production (M4).

**Must be done before frontend integration/production:** P0 (T1–T5) and P1 (T6–T10), plus Flyway baseline (T11). These fix the security and data-integrity guarantees the PDF explicitly requires.

**Can remain unchanged:** JWT/BCrypt auth, password-reset & onboarding token design, Stripe checkout+webhook+refund internals, Google Meet provisioning, notification/push framework, ownership checks, and the overall package/controller-service-repository structure and test approach — all are sound and appropriately sized for this product.
