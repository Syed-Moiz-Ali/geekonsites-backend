# GeekOnSites Backend Audit Report — Updated / Consolidated

> **Scope:** Java / Spring Boot backend only  
> **Client source of truth:** `GeekOnSites_Full_Project_Overview_Architecture.pdf`  
> **Existing audit reviewed:** `BACKEND_AUDIT_REPORT(1).md`  
> **Codebase reviewed:** `geekonsites-backend-main(1).zip`  
> **Frontend:** intentionally excluded  
> **Audit type:** architecture, APIs, security, lifecycle, payment/refund, database, validation, integration and production-readiness review

---

## 1. Executive Summary

### Overall verdict

**PARTIALLY READY — substantial backend functionality exists, but the current backend is not production-safe yet.**

The project is not a scaffold. It contains real implementations for authentication, booking, technician onboarding/assignment, Stripe Checkout/webhooks, refunds, Google Meet provisioning, notifications, tracking, invoicing, ratings, agent CRM, and admin operations.

However, several central invariants are not enforced consistently. The biggest issue is not simply “missing features”; it is that the same booking/payment lifecycle can be mutated through multiple independent code paths with different rules and side effects.

### Revised readiness estimate

Two separate numbers are more honest than one percentage:

- **Feature-surface implementation:** approximately **65%** of the client-described backend capabilities have some implementation.
- **Production-safe implementation:** approximately **55–60%** ready because multiple implemented features depend on unsafe lifecycle/payment/data-integrity foundations.

This revision is lower than the original audit's ~65% overall readiness because direct source review found additional defects around multi-payment history/refunds, tracking-driven lifecycle mutation, premature booking closure, role boundaries, validation, and country handling.

### Production blockers

The most important blockers are:

1. No single authoritative booking state machine.
2. Manual endpoints can mark payments successful without Stripe verification.
3. No real `Payment` / `PaymentTransaction` entity or payment ledger, even though the client architecture explicitly identifies Payment as a backend entity.
4. Split-payment bookings overwrite the previous Stripe transaction reference, which can make complete refunds impossible.
5. Core multi-write booking/payment/refund/invoice flows lack reliable transaction boundaries.
6. Service catalog/pricing is hardcoded and there is no `Service` entity or Admin service CRUD.
7. Tracking updates can silently move or regress booking status.
8. Remote-session code contains an alternate completion path that bypasses the stronger booking lifecycle checks.
9. `closeBooking()` can close a booking based only on payment + invoice, without requiring completed service.
10. Weak request validation allows malformed dates, coordinates, and other invalid inputs to reach business logic.
11. Production schema management relies on Hibernate `ddl-auto=update` rather than an automatically executed migration baseline.
12. Several duplicate domain paths exist for invoice, rating, payment success, and remote-session lifecycle.

---

## 2. Client Backend Requirement Baseline

The client document defines GeekOnSites as a US/UK technology support and service-booking platform. The backend is expected to own the business lifecycle from service selection and booking through payment, technician assignment, service execution, completion, invoice, and rating.

### Explicit backend requirements

| ID | Requirement | Classification |
|---|---|---|
| R1 | JWT authentication | Explicit |
| R2 | Role-based authorization: CUSTOMER, TECHNICIAN, AGENT, ADMIN | Explicit |
| R3 | Service discovery with transparent USD/GBP pricing | Explicit |
| R4 | Backend-controlled booking lifecycle | Explicit |
| R5 | Stripe Checkout + Stripe webhook + server-side payment validation | Explicit |
| R6 | Technician registration, verification, availability and assignment | Explicit |
| R7 | Remote support via shared Google Meet | Explicit |
| R8 | Location/tracking for applicable on-site jobs | Explicit |
| R9 | Customer notifications | Explicit |
| R10 | Booking history | Explicit |
| R11 | Invoice support | Explicit in journey, entity design underspecified |
| R12 | Rating/review | Explicit |
| R13 | Contact/customer messages | Explicit |
| R14 | Admin manages users, services, bookings, technicians, agents, messages and payments | Explicit |
| R15 | Agent operational support / booking coordination | Explicit |
| R16 | US/UK only; USD/GBP pricing | Explicit |
| R17 | Platform/service fees according to pricing policy | Explicit but policy underspecified |
| R18 | Free quote / no-fix-no-fee / hardware parts / emergency pricing | Explicit concepts, implementation rules underspecified |
| R19 | Server-side validation, DB constraints, production error handling/logging | Explicit |
| R20 | Key backend entities include Service and Payment | Explicit |
| R21 | Automated technician matching | Future, not current blocker |
| R22 | Future richer notifications, mobile, analytics and integrations | Future |

### Backend invariants implied by the client workflow

For the client concept to work correctly, the backend must also guarantee:

- booking state transitions cannot be arbitrarily skipped or reversed;
- payment success cannot be accepted from client-supplied identifiers alone;
- every captured payment remains auditable, particularly when a booking has more than one payment;
- Stripe events are idempotent;
- booking/payment/invoice/refund state remains synchronized;
- only the correct customer can pay/refund/review their booking;
- only the assigned technician can perform technician actions;
- only approved/available technicians can be assigned;
- remote and on-site lifecycles cannot be mixed accidentally;
- customer-facing service pricing comes from backend-controlled catalog/pricing data;
- historical booking pricing remains stable after catalog prices change.

---

## 3. Current Backend Architecture

### Verified stack

- Java 17
- Spring Boot 3.3.5
- Maven
- Spring Web
- Spring Data JPA / Hibernate
- Spring Security
- Bean Validation dependency present, but inconsistently applied
- PostgreSQL runtime database
- H2 for local/tests
- JWT via `jjwt 0.12.6`
- Stripe Java SDK `29.1.0`
- Google Calendar API for Meet provisioning
- Firebase Admin for push notifications
- Spring Mail / Resend-related email support
- springdoc/OpenAPI

### Client stack mismatch

The PDF states MySQL on Railway. The implementation uses PostgreSQL with Render-oriented configuration.

This is not inherently a bad technical decision, but it is a **client architecture deviation that must be explicitly approved** rather than silently treated as equivalent.

### Typical request flow

`HTTP -> SecurityFilterChain -> JwtAuthenticationFilter -> Controller -> Service -> Repository -> PostgreSQL -> response`

### Main package structure

The package structure is a normal Spring monolith and is appropriate for the current product scale. A microservice migration is not justified by the current requirements.

---

## 4. Requirement vs Implementation Matrix

| Req | Client expectation | Current implementation | Status | Severity | Required action |
|---|---|---|---|---|---|
| R1 | JWT auth | BCrypt + JWT + DB-backed principal | COMPLETE | — | Keep; harden lifecycle/rate limiting later |
| R2 | 4 roles | Role enum + SecurityConfig | COMPLETE | — | Keep |
| R3 | Service catalog | Static Java maps in `TrustedPricingService` | INCORRECT | HIGH | Add Service/catalog persistence + public API |
| R4 | Backend lifecycle control | Many validated methods plus multiple bypass paths | BROKEN/PARTIAL | CRITICAL | Central state machine |
| R5 | Stripe server verification | Checkout/webhook good, but manual paid endpoints exist | PARTIAL | CRITICAL | Remove unsafe payment success endpoints |
| R6 | Technician verify/avail/assign | Mostly implemented | PARTIAL | HIGH | Reassignment + lifecycle guards |
| R7 | Google Meet | Implemented | COMPLETE/PARTIAL | MEDIUM | Unify lifecycle paths |
| R8 | On-site tracking | Implemented | PARTIAL | HIGH | Decouple GPS from lifecycle; validate coordinates |
| R9 | Notifications | In-app/push/email | COMPLETE/PARTIAL | MEDIUM | Idempotency/consistency |
| R10 | Booking history | Implemented | COMPLETE | — | Paginate |
| R11 | Invoice | Two competing generation paths | INCORRECT | HIGH | Single invoice service/domain path |
| R12 | Rating/review | Two sources, duplicate reviews possible | PARTIAL | HIGH | One source + unique booking constraint |
| R13 | Contact messages | Implemented | COMPLETE | — | Keep |
| R14 | Admin service/payment management | Service management missing; payment domain incomplete | MISSING/PARTIAL | HIGH | Add both domains/APIs |
| R15 | Agent operations | Implemented | COMPLETE/PARTIAL | MEDIUM | Optimize/limit lifecycle authority |
| R16 | US/UK + USD/GBP | Mostly implemented | PARTIAL | MEDIUM | Validate country/currency strictly |
| R17 | Fees | Hardcoded flat fee | PARTIAL | MEDIUM | Clarify policy before finalizing |
| R18 | Quote/no-fix/parts/emergency | Not implemented | NEEDS CLIENT CLARIFICATION | — | Do not invent |
| R19 | Validation/constraints/logging | Partial | PARTIAL | HIGH | Global validation + migrations + errors |
| R20 | Service + Payment entities | Service absent; Payment entity absent | MISSING | HIGH/CRITICAL | Add persistent domain models |
| R21 | Auto matching | Not implemented | FUTURE | — | Not current scope |
| R22 | Future scalability | Monolith supports growth with cleanup | PARTIAL | LOW | No microservices required |

---

## 5. Critical Findings

### C1 — Booking state machine is not authoritative

**Severity: CRITICAL**

Relevant code:

- `controller/BookingController.java` — `updateStatus(...)`
- `service/BookingService.java` — `updateStatus(...)`
- `service/RemoteSessionService.java` — `startRemoteSession(...)`, `endRemoteSession(...)`
- `service/BookingService.java` — tracking and close methods

`BookingService.updateStatus()` directly assigns any `BookingStatus` and saves it.

Consequences include illegal transitions such as:

- unpaid -> completed
- cancelled -> in progress
- completed -> assigned
- payment pending -> service started
- completed -> cancelled

The problem is broader than the arbitrary status endpoint. Tracking, remote-session and close operations also mutate booking state independently.

**Required fix:** introduce one central `BookingStateMachine` / lifecycle service. All status changes must pass through it. Controllers and domain services must request named transitions, not set enum values directly.

---

### C2 — Manual payment-success endpoints bypass Stripe

**Severity: CRITICAL**

Relevant code:

- `BookingController.paymentSuccess(...)`
- `BookingService.paymentSuccess(...)`
- `BookingController.remainingPaymentSuccess(...)`
- `BookingService.remainingPaymentSuccess(...)`

These endpoints accept a supplied transaction ID and directly mark a booking `PAID` / `PARTIALLY_PAID` without retrieving/validating the Stripe Checkout Session, amount or currency.

Even though the routes are admin-only, they violate the client's explicit server-side payment validation requirement and create an unnecessary payment-forgery path.

**Required fix:** remove these endpoints, or replace them with a Stripe-verified reconciliation operation that retrieves the Stripe object and validates booking ID, amount, currency, payment status and payment type.

---

### C3 — Missing Payment entity / transaction ledger

**Severity: CRITICAL**

The client architecture explicitly identifies `Payment` as a backend entity, but the project has no `Payment` or `PaymentTransaction` JPA entity/repository.

Payment information is compressed into fields on `Booking`:

- `paymentTransactionId`
- `paymentType`
- `paymentStatus`
- `paymentMethod`
- `paidAmount`
- `advanceAmount`
- `remainingAmount`

This is insufficient for a booking that legitimately has multiple payment events.

**Required design:** introduce a persistent payment ledger, for example:

`PaymentTransaction`

- id
- bookingId
- customerId
- paymentType: FULL / ADVANCE / REMAINING
- provider: STRIPE
- checkoutSessionId
- paymentIntentId
- amount
- currency
- status
- capturedAt
- failedAt
- refundedAmount
- createdAt / updatedAt
- provider metadata/reference fields as needed

A booking may have **one-to-many** payment transactions.

`Booking` may retain derived aggregate fields for convenience, but the payment ledger must be the financial source of truth.

---

### C4 — Split-payment Stripe reference is overwritten, breaking complete refundability

**Severity: CRITICAL**

Relevant code:

- `PaymentService.applyCompletedCheckoutSession(...)`
- `Booking.paymentTransactionId`
- `StripeRefundGatewayImpl.refund(...)`
- `RefundService.approveAndExecute(...)`

For an on-site booking:

1. advance payment Stripe Session A completes;
2. `booking.paymentTransactionId = Session A`;
3. remaining payment Stripe Session B completes;
4. `booking.paymentTransactionId = Session B` overwrites Session A.

`StripeRefundGatewayImpl` later retrieves only `booking.getPaymentTransactionId()` and therefore only sees the latest Stripe PaymentIntent.

Example:

- total: 100
- advance: 30 (Stripe PI-A)
- remaining: 70 (Stripe PI-B)
- booking.paidAmount becomes 100
- booking.paymentTransactionId points only to PI-B's Checkout Session

The refund rule engine may approve 100 based on booking aggregate paid amount, while the Stripe refund gateway can only refund against the 70 captured on PI-B. The original 30 payment is no longer addressable through the booking.

This makes the current refund subsystem unsafe for split-payment bookings even though individual Stripe refund validation is otherwise well implemented.

**Required fix:** C3 payment ledger must be implemented before treating refunds as production-ready. Refund execution must allocate refund amounts across the actual captured payment transactions.

---

### C5 — Missing transactional boundaries on critical multi-write flows

**Severity: CRITICAL**

Important services contain multi-step persistence operations without reliable transaction boundaries:

- `BookingService`
- `PaymentService`
- `RefundService`
- `InvoiceService`
- related Admin/Agent write flows

Examples:

- assign technician: update booking + technician + session participant sync + notifications
- payment finalization: update booking + invoice + session provisioning + notifications
- refund: refund request state + Stripe call + final refund state
- invoice: invoice + booking invoice metadata

A failure in the middle can create partial state.

**Required fix:** define transactional boundaries around database work and move non-transactional external side effects to after-commit events / outbox-style processing where justified. Do not hold DB transactions open across slow external APIs unnecessarily.

---

## 6. High-Priority Findings

### H1 — Missing Service entity and Admin service management

`TrustedPricingService` contains hardcoded maps for services and add-ons.

There is no:

- `Service` JPA entity
- service repository
- public `/api/services`
- admin service CRUD
- DB-managed USD/GBP pricing
- clean deactivate/archive workflow

This conflicts with the client's explicit Service entity and Admin service-management requirements.

**Required fix:** persistent service catalog with historical booking snapshots.

---

### H2 — Technician reassignment can leave previous technician BUSY

`BookingService.assignTechnician()` sets the new technician to `BUSY` but does not restore an already assigned previous technician to `AVAILABLE` when replacing them.

It also lacks a strong booking-status guard, so inappropriate bookings may be reassigned.

**Required fix:** central transition guard plus safe reassignment transaction.

---

### H3 — Tracking endpoint mutates lifecycle and can bypass technician acceptance

**Severity upgraded from original tracking finding.**

Relevant code: `BookingService.updateTechnicianLocation(...)`.

After updating GPS fields, the method performs:

- if state is not service started / remote started / service completed / booking closed,
- force state to `TECHNICIAN_ON_THE_WAY`.

Therefore a location update can move `TECHNICIAN_ASSIGNED -> TECHNICIAN_ON_THE_WAY` without calling `technicianAcceptJob()`.

Tracking data should not silently authorize or infer a business lifecycle transition unless that behavior is explicitly designed and validated.

**Required fix:** location updates must require an allowed lifecycle state and must not independently alter booking status.

---

### H4 — Tracking can regress `TECHNICIAN_ARRIVED` back to `TECHNICIAN_ON_THE_WAY`

`TECHNICIAN_ARRIVED` is not excluded from the status-forcing condition inside `updateTechnicianLocation()`.

Flow:

1. technician marks arrived -> booking becomes `TECHNICIAN_ARRIVED`;
2. another GPS update arrives;
3. condition matches;
4. booking becomes `TECHNICIAN_ON_THE_WAY` again.

This is a state regression and proves lifecycle logic is distributed incorrectly.

---

### H5 — RemoteSessionService is a parallel lifecycle implementation

There are two remote-session lifecycle paths:

- stronger logic in `BookingService`
- weaker logic in `RemoteSessionService`

`RemoteSessionService.startRemoteSession()` checks paid + REMOTE + valid Meet link, but does not require the booking to be `TECHNICIAN_ACCEPTED`.

`RemoteSessionService.endRemoteSession()` is more dangerous: it only verifies technician assignment and then sets `SERVICE_COMPLETED`. It does not require:

- REMOTE service mode
- PAID state
- remote session started
- valid current booking state
- expected completion side effects

This can bypass the normal completion flow and leave technician availability, notifications, balance handling and timestamps inconsistent.

**Required fix:** remove duplicate lifecycle implementation; controller should delegate into the central booking state machine/service.

---

### H6 — `closeBooking()` can close service before service completion

Relevant code: `BookingService.closeBooking(...)`.

The method requires only:

- `paymentStatus == PAID`
- `invoiceGenerated == true`

It does **not** require service completion.

`PaymentService.finalizePaidBooking()` generates an invoice after payment, so a fully paid booking can satisfy both conditions before technician assignment or service delivery.

An Agent/Admin can therefore potentially move:

`PAYMENT_COMPLETED -> BOOKING_CLOSED`

without the service ever being performed.

**Required fix:** booking closure must be an explicit legal transition from a completed/fully-settled terminal service state.

---

### H7 — Duplicate rating sources and no unique review constraint

There are two representations:

1. rating fields directly on `Booking`
2. separate `Rating` entity

`RatingController.submitRating()` calls `BookingService.rateBooking()` and then separately creates a `Rating` row.

There is no DB unique constraint on `ratings.booking_id`.

Result:

- duplicate reviews possible
- aggregate technician rating can be inflated
- booking and rating table are separate sources of truth

**Required fix:** choose one authoritative rating domain, use one service, add unique booking constraint and transactional update.

---

### H8 — Duplicate/divergent invoice implementation

There are two invoice paths:

- `BookingService.generateInvoice()`
- `InvoiceService.generateInvoiceFromBooking()`

They produce different invoice formats and different lifecycle behavior.

`BookingService.generateInvoice()` uses hardcoded `GOS-2026-*` numbering.

`InvoiceService` creates a separate `Invoice` row using `GOS-US-INV-*` / `GOS-UK-INV-*` style.

**Required fix:** delete/deprecate booking-level invoice generator and make `InvoiceService` the only domain path.

---

### H9 — Technician can trigger invoice mutation before final invoice-role denial

`InvoiceController.generateInvoiceFromBooking()` first checks generic booking access via `bookingService.getBookingForCurrentUser(...)`. That method permits an assigned technician to access their booking.

The controller then generates/updates the invoice and only afterward calls `verifyAccess(invoice, user)`, where Technician is rejected.

Therefore an assigned technician can cause the invoice mutation and then receive 403.

There is also `BookingController/{bookingId}/generate-invoice`, which uses generic booking access and can directly invoke the duplicate `BookingService.generateInvoice()` path.

**Required fix:** authorize the action itself before any mutation. Invoice creation should be allowed only to explicitly approved roles/workflows.

---

### H10 — Booking creation is authenticated but not CUSTOMER-only

`POST /api/bookings` falls through the generic authenticated booking rule.

`BookingController.createBooking()` takes the current authenticated `User` and writes that user's ID/name/email/phone into the booking as customer fields. It does not verify `Role.CUSTOMER`.

Therefore Agent, Technician or Admin accounts can technically create bookings where their operational account becomes the booking customer.

This pollutes authorization/payment semantics and creates cross-role edge cases.

**Required fix:** CUSTOMER-only booking creation unless the client explicitly wants agent-created bookings on behalf of customers, in which case that must be a separate audited API.

---

### H11 — Validation and error contract are incomplete

Multiple DTOs lack meaningful validation:

- `BookingRequest`
- `TechnicianLocationRequest`
- `CustomerLocationRequest`
- `RatingRequest`
- `LoginRequest`

`BookingController.createBooking()` does not use `@Valid`.

Examples:

- malformed booking date can reach `LocalDate.parse()`
- latitude/longitude are unbounded
- negative ETA/distance/speed values can be accepted
- required service/address/schedule fields can be null
- login `email == null` can fail at `trim()`

Many services throw generic `RuntimeException`; `PaymentController` catches broad exceptions and maps business validation failures to HTTP 500.

**Required fix:** Bean Validation + central `@RestControllerAdvice` + domain exception hierarchy + consistent 4xx/409 responses.

---

### H12 — Invalid registration country silently becomes US

`AuthController.register()` performs:

`UK ? UK : US`

Any non-UK input—including invalid values—becomes US.

Examples:

- `India` -> `US`
- `XYZ` -> `US`
- empty/null -> `US`

For a US/UK-only production system, unsupported country values must be rejected rather than silently rewritten.

**Required fix:** explicit country enum/validator; normalize known aliases only; reject unsupported values.

---

### H13 — Schema management is not production-safe

Production uses Hibernate `ddl-auto=update` and there is no reliably wired Flyway/Liquibase migration lifecycle.

There are SQL files in migration-looking directories, but the project does not have a dependable versioned production schema process.

**Required fix:** Flyway/Liquibase baseline, version all current schema changes, disable Hibernate mutation of production schema.

---

## 7. Medium-Priority Findings

### M1 — Money uses `Double`

`Booking` and `Invoice` use `Double` for monetary values.

Use `BigDecimal` with explicit scale/rounding or integer minor units.

This should be done carefully with a migration because financial values already flow through Stripe/refunds.

### M2 — Booking stores flat IDs rather than FK-backed relationships

Examples:

- `customerId`
- `technicianId`
- `agentId`

This is workable as a deliberate aggregate design only if DB foreign keys are still enforced separately. Currently referential integrity is weak.

Do not blindly convert everything to bidirectional JPA associations; the important requirement is DB integrity and clean query design.

### M3 — Agent CRM performs broad in-memory aggregation / N+1 work

Large user/bookings/contact/follow-up sets are loaded and filtered in Java. This should be moved into paginated repository queries.

### M4 — Flat/unconditional platform fee

Pricing currently applies a flat fee rather than a client-defined policy.

Because the PDF says fees are applied “according to defined pricing policy” but does not define the policy, final behavior requires client clarification.

### M5 — Protection plan pricing appears incomplete

Protection selection exists but protection amount is effectively not modeled as a real configurable price.

### M6 — No explicit booking cancellation workflow

`CANCELLED` exists as a status, but there is no coherent cancel transition tied to refund eligibility and technician release.

Cancellation/refund policy is not sufficiently specified by the PDF, so the architecture should be prepared but final business rules require client confirmation.

### M7 — Public OpenAPI/Swagger and root route in production

This should be disabled or restricted in production.

This is security hardening, but it is lower priority than authorization, lifecycle and payment integrity.

### M8 — Duplicate JWT filter

`JwtFilter` is a `@Component` but the configured Spring Security chain uses `JwtAuthenticationFilter`.

The duplicate filter is unnecessary and can create confusing principal behavior depending on registration/order.

Delete the dead filter.

### M9 — Unpaginated list APIs

Examples include complete booking/admin lists. Pagination/filtering should be added before significant production data volume.

### M10 — Technician registration document storage

Verification documents appear capable of being stored as base64-sized payloads in DB fields. This creates database bloat and operational limits.

For production, consider controlled object storage with metadata/ownership checks after confirming client deployment constraints.

### M11 — Email/support configuration inconsistencies

Support email values are inconsistent across configuration/templates. Normalize through environment-backed configuration.

### M12 — Notification idempotency is inconsistent

Payment notification behavior has some idempotency, but other lifecycle events can duplicate under repeated/retried actions.

---

## 8. Low-Priority / Cleanup Findings

- dead `PricingService` superseded by `TrustedPricingService`
- dead/duplicate `JwtFilter`
- placeholder `CustomerController`
- disabled `EmailController`
- `TestController` root banner
- duplicated Google Meet regex logic
- duplicated invoice logic
- duplicated rating logic
- duplicated remote-session start/end logic
- hardcoded invoice year in `BookingService`
- broad `catch (Exception)` in multiple places
- fragile notification type inference based on title substrings
- no strong project README for backend operations/setup

---

## 9. Detailed Booking Lifecycle Audit

### Intended lifecycle

A simplified target lifecycle should resemble:

#### Remote

`PENDING`
-> payment confirmed
-> `PAYMENT_COMPLETED`
-> `ASSIGNMENT_PENDING`
-> `TECHNICIAN_ASSIGNED`
-> `TECHNICIAN_ACCEPTED`
-> `REMOTE_SESSION_STARTED`
-> `SERVICE_COMPLETED`
-> financial settlement / invoice
-> `BOOKING_CLOSED`

#### On-site split payment

`PENDING`
-> advance payment
-> `ASSIGNMENT_PENDING`
-> `TECHNICIAN_ASSIGNED`
-> `TECHNICIAN_ACCEPTED`
-> `TECHNICIAN_ON_THE_WAY`
-> `TECHNICIAN_ARRIVED`
-> `SERVICE_STARTED`
-> `REMAINING_PAYMENT_PENDING` if balance due
-> remaining payment
-> settled/completed
-> invoice/closure

The exact final status naming should be simplified during implementation; currently there are overlapping concepts such as:

- `SERVICE_COMPLETED`
- `REMAINING_PAYMENT_PENDING`
- `FULLY_PAID`
- `INVOICE_GENERATED`
- `BOOKING_CLOSED`

Some of these represent **business process state**, others represent **payment state** or **document state**. Combining all three into one enum creates unnecessary complexity.

### Architectural recommendation

Separate orthogonal states:

- `BookingStatus` — service/booking lifecycle
- `PaymentStatus` — payment aggregate state
- `InvoiceStatus` — if needed
- `RemoteSessionStatus` — if needed

Do not encode every cross-domain event as a booking state.

---

## 10. Payment and Refund Audit

### What is good

The Stripe implementation contains several strong controls:

- Checkout Session created server-side
- amount computed from backend booking data
- booking/payment type metadata sent to Stripe
- webhook signature verification
- returned amount/currency revalidated against booking
- idempotency check around processed Session ID
- customer ownership checked in authenticated confirmation fallback
- refund gateway verifies Stripe payment association/currency/captured amount
- refund uses Stripe idempotency key

### What prevents production approval

1. manual payment success endpoints bypass all those controls;
2. payment history is overwritten on the booking;
3. split payment cannot be reliably refunded across both captures;
4. concurrent webhook/confirmation processing is not protected by a full financial transaction/idempotency model;
5. `Double` is used for money;
6. no Payment entity exists despite client architecture;
7. payment admin/history APIs are not modeled as a true domain;
8. checkout/session expiry and broader reconciliation are incomplete.

### Required target architecture

`Booking 1 -> N PaymentTransaction`

`PaymentTransaction 1 -> N RefundAllocation` or refund linkage

Refund operations must identify actual captured PaymentIntent(s), not only a single current booking field.

---

## 11. Technician / Assignment Audit

### Good

- verification checked before assignment
- availability checked before assignment
- service-mode compatibility checked
- technician ownership checks exist on technician workflow APIs
- reject flow releases technician availability

### Problems

- reassignment can strand previous technician as BUSY
- assignment lacks a strict booking-state transition guard
- tracking can bypass acceptance
- tracking can regress arrival
- duplicate remote lifecycle bypass exists
- availability/verification are String fields rather than constrained enums
- no durable assignment history/audit entity

### Recommendation

Consider an `Assignment` / `BookingAssignmentHistory` entity only if reassignment/audit history is required operationally. At minimum, record assignment/reassignment events durably.

---

## 12. Invoice Audit

### Current model

There is a real `Invoice` entity, but also invoice metadata directly on `Booking` and a separate invoice-generation implementation in `BookingService`.

### Problems

- two numbering strategies
- hardcoded year in one strategy
- duplicate mutation paths
- role/action boundary issue for technicians
- invoice gets created very early after payment even though client journey describes invoice after service completion
- invoice content requirements are not fully specified in the PDF

### Client clarification needed

Confirm whether invoices require:

- tax/VAT breakdown
- line items
- platform fees
- add-ons
- parts/hardware added later
- partial-payment receipts vs final invoice
- downloadable PDF
- invoice immutable snapshot semantics

---

## 13. Rating / Review Audit

### Current behavior

- rating data can be written to booking fields
- `Rating` entity can also be inserted
- technician aggregate rating is recalculated from rating rows

### Risks

- duplicate reviews per booking
- dual source of truth
- no unique DB constraint
- inconsistent update path

### Target

One `Rating` row per completed booking, backed by DB uniqueness and one transactional service method.

---

## 14. Authentication / Authorization Audit

### Strong areas

- BCrypt
- JWT
- DB-backed principal reload
- public registration forcibly creates CUSTOMER role rather than trusting requested role
- admin login separated
- password reset tokens appear hashed, expiring and single-use
- several ownership checks are correctly implemented

### Gaps

- booking creation not restricted to CUSTOMER
- action-level authorization is sometimes inferred from generic resource read access
- duplicate JWT filter
- no clear production rate limiting/lockout
- JWT has long lifetime without revocation mechanism
- email uniqueness/normalization mismatch exists
- invalid country silently defaults to US

### Email identity issue

Registration uses case-sensitive `findByEmail(...)`; login uses `findByEmailIgnoreCase(...)`.

Normalize email before persistence and enforce case-insensitive uniqueness at DB level.

---

## 15. API Design Audit

### Missing / required APIs

| Method | Path | Purpose | Auth |
|---|---|---|---|
| GET | `/api/services` | public active service catalog with country/currency pricing | Public |
| GET | `/api/services/{id}` | service detail | Public |
| POST | `/api/admin/services` | create service | ADMIN |
| PUT/PATCH | `/api/admin/services/{id}` | edit service/pricing/config | ADMIN |
| DELETE/DEACTIVATE | `/api/admin/services/{id}` | deactivate service safely | ADMIN |
| GET | `/api/admin/payments` | payment transaction history/search | ADMIN |
| GET | `/api/admin/payments/{id}` | transaction detail | ADMIN |
| GET | `/api/bookings/{id}/payments` | authorized payment history | owner/ops |
| PUT/POST | cancellation endpoint | controlled cancel request/transition | owner/ops based on policy |

### Endpoints to remove/deprecate

- arbitrary booking status override endpoint
- manual payment-success endpoint
- manual remaining-payment-success endpoint
- duplicate booking invoice generation endpoint
- duplicate remote lifecycle path after unified state machine

### API contract improvements

- standard error response body
- validation errors with field details
- pagination for list APIs
- explicit role/action rules
- DTO responses rather than exposing full persistence entities everywhere

---

## 16. Database / Entity Audit

### Missing entities

#### Service

Required by client architecture and admin operations.

Potential model:

- Service
- ServicePrice (or per-market pricing columns depending simplicity)
- ServiceAddon

#### PaymentTransaction

Required to preserve financial history and fix split payments/refunds.

### Important constraints/indexes

Add migrations for:

- case-insensitive unique user email
- unique `ratings.booking_id`
- invoice booking uniqueness (already conceptually present; verify DB)
- payment provider reference uniqueness
- indexes on booking customer/technician/status/createdAt
- refund booking/status indexes
- service active/category lookup indexes as needed

### Money

Migrate monetary columns to decimal/numeric types mapped to `BigDecimal`.

### Status fields

Replace unconstrained text statuses where feasible with enums/domain values, while retaining safe migration compatibility.

---

## 17. Validation Audit

Validation should be added without trusting frontend constraints.

### Booking

Validate at minimum:

- service reference/type
- service mode
- booking date format / future date policy where applicable
- required address for on-site
- required schedule/time slot
- allowed country
- allowed currency-country pairing
- issue description lengths
- coordinates if provided

Client-submitted price fields should either be removed from request DTOs or explicitly ignored. Backend catalog/pricing must remain authoritative.

### Tracking

Validate:

- latitude `[-90, 90]`
- longitude `[-180, 180]`
- ETA >= 0
- distance >= 0
- speed >= 0
- heading `[0, 360]` if used

### Rating

- bookingId required
- rating 1..5
- review length
- ignore/remove client-supplied customerId/technicianId; derive from authenticated user + booking

### Auth

- required normalized email
- password required
- country constrained to supported values

---

## 18. Error Handling Audit

The project needs a global `@RestControllerAdvice` and domain-specific exceptions.

Suggested categories:

- 400 invalid request
- 401 unauthenticated
- 403 unauthorized action
- 404 not found
- 409 illegal state transition / duplicate / conflict
- 422 only where appropriate for semantically invalid input
- 500 unexpected server failure

Do not return raw internal exception details in production.

Do not catch every exception in controllers and turn valid business rejections into HTTP 500.

---

## 19. Security Findings

### High impact

- payment state bypass endpoints
- lifecycle bypasses
- action authorization inconsistencies
- booking creation role gap

### Hardening

- restrict Swagger/OpenAPI in production
- remove duplicate JWT filter
- add rate limiting to login/register/password reset/technician public registration
- normalize email identity
- validate file/document uploads strongly
- avoid storing unnecessary sensitive data in logs
- use environment-only secrets

The current source already uses several good security patterns, so remediation should preserve them instead of rewriting authentication unnecessarily.

---

## 20. Production Readiness

### Existing strengths

- environment-based external configuration
- JWT + BCrypt
- Stripe webhook signature verification
- ownership checks in several important paths
- health endpoint
- Render/Docker configuration
- integration/unit tests covering important areas
- Google Meet and push/email integrations degrade reasonably when configured appropriately

### Blocking gaps

- lifecycle integrity
- payment ledger
- split-payment refund correctness
- unsafe manual payment mutation
- missing DB transaction strategy
- missing schema migration baseline
- missing service catalog domain
- validation/error handling
- financial `Double`

### Runtime test note for this consolidated audit

The code and test sources were inspected directly. The Maven test suite was **not executed in this review environment because Maven is not installed**. Existing tests are present and must be run by Codex/CI before and after each implementation phase.

---

## 21. Consolidated Bug Report

### BUG-01 — Arbitrary booking status override

- **Severity:** CRITICAL
- **Files:** `BookingController`, `BookingService`
- **Current:** Agent/Admin can set arbitrary `BookingStatus`.
- **Fix:** remove generic endpoint; central state machine.

### BUG-02 — Manual payment state forgery

- **Severity:** CRITICAL
- **Files:** `BookingController`, `BookingService`
- **Current:** supplied transaction ID can mark booking paid without Stripe verification.
- **Fix:** remove/deprecate; use verified Stripe reconciliation only.

### BUG-03 — No persistent payment ledger

- **Severity:** CRITICAL
- **Files:** domain-wide / new entity required
- **Current:** only one payment transaction ID stored on Booking.
- **Fix:** add `PaymentTransaction` domain and repository.

### BUG-04 — Split-payment transaction overwrite breaks full refund

- **Severity:** CRITICAL
- **Files:** `PaymentService`, `Booking`, `StripeRefundGatewayImpl`, `RefundService`
- **Current:** remaining payment overwrites advance Checkout Session ID.
- **Fix:** refund against actual transaction ledger and allocate across captures.

### BUG-05 — Core writes are insufficiently transactional

- **Severity:** CRITICAL
- **Files:** Booking/Payment/Refund/Invoice services
- **Fix:** DB transaction boundaries + after-commit external side effects.

### BUG-06 — Technician reassignment leaks BUSY state

- **Severity:** HIGH
- **File:** `BookingService.assignTechnician`
- **Fix:** release prior assignment transactionally + state guard.

### BUG-07 — Tracking bypasses acceptance

- **Severity:** HIGH
- **File:** `BookingService.updateTechnicianLocation`
- **Current:** location update can force `TECHNICIAN_ON_THE_WAY`.
- **Fix:** tracking must not change lifecycle.

### BUG-08 — Tracking regresses ARRIVED -> ON_THE_WAY

- **Severity:** HIGH
- **File:** `BookingService.updateTechnicianLocation`
- **Fix:** lifecycle immutable except through state machine.

### BUG-09 — Remote session start bypass

- **Severity:** HIGH
- **File:** `RemoteSessionService.startRemoteSession`
- **Current:** does not require `TECHNICIAN_ACCEPTED`.
- **Fix:** delegate to lifecycle service.

### BUG-10 — Remote session end bypasses completion rules

- **Severity:** HIGH
- **File:** `RemoteSessionService.endRemoteSession`
- **Current:** assigned technician can set `SERVICE_COMPLETED` without validating paid/remote/started/current state or completion side effects.
- **Fix:** remove alternate path; use central completion transition.

### BUG-11 — Booking can be closed before service completion

- **Severity:** HIGH
- **File:** `BookingService.closeBooking`
- **Current:** requires only PAID + invoice generated.
- **Fix:** require legal terminal service state.

### BUG-12 — Missing Service entity/catalog/Admin CRUD

- **Severity:** HIGH
- **File:** `TrustedPricingService` + missing domain
- **Fix:** DB-backed service catalog.

### BUG-13 — Duplicate reviews allowed

- **Severity:** HIGH
- **Files:** `RatingController`, `Rating`, `BookingService`
- **Fix:** unique booking rating + one authoritative service.

### BUG-14 — Duplicate invoice systems

- **Severity:** HIGH
- **Files:** `BookingService`, `InvoiceService`
- **Fix:** one InvoiceService implementation.

### BUG-15 — Technician can trigger invoice mutation before action authorization resolves

- **Severity:** HIGH/MEDIUM
- **File:** `InvoiceController.generateInvoiceFromBooking`
- **Current:** generic booking access permits technician, invoice mutation occurs, final invoice access check rejects technician.
- **Fix:** authorize role/action before mutation.

### BUG-16 — Booking creation not CUSTOMER-only

- **Severity:** HIGH
- **File:** `BookingController.createBooking`, `SecurityConfig`
- **Fix:** CUSTOMER-only; separate agent-on-behalf flow if needed.

### BUG-17 — Remaining payment terminal state differs between paths

- **Severity:** HIGH
- **Files:** `PaymentService`, `BookingService.remainingPaymentSuccess`
- **Current:** verified Stripe path and manual path create different end states.
- **Fix:** remove manual path; one transition model.

### BUG-18 — Business validation can become HTTP 500

- **Severity:** MEDIUM
- **Files:** controllers/services broadly
- **Fix:** domain exceptions + global advice.

### BUG-19 — DTO validation missing/incomplete

- **Severity:** HIGH/MEDIUM
- **Files:** Booking/Tracking/Rating/Login DTOs and controllers
- **Fix:** Bean Validation + `@Valid`.

### BUG-20 — Invalid country silently normalized to US

- **Severity:** MEDIUM
- **File:** `AuthController.register`
- **Fix:** reject unsupported country.

### BUG-21 — Email case uniqueness mismatch

- **Severity:** MEDIUM
- **Files:** `AuthController`, `UserRepository`, DB
- **Fix:** normalized email + case-insensitive unique index.

### BUG-22 — Money stored as Double

- **Severity:** MEDIUM
- **Files:** `Booking`, `Invoice`, pricing/payment services
- **Fix:** `BigDecimal` + numeric DB migration.

### BUG-23 — No production migration baseline

- **Severity:** HIGH
- **Files:** `pom.xml`, resources/config, DB
- **Fix:** Flyway/Liquibase; disable prod ddl-auto update.

### BUG-24 — JWT duplicate filter

- **Severity:** MEDIUM/LOW
- **File:** `jwt/JwtFilter.java`
- **Fix:** delete dead filter.

### BUG-25 — Public Swagger/root in production

- **Severity:** LOW/MEDIUM hardening
- **Files:** Security/config
- **Fix:** disable/restrict by prod profile.

### BUG-26 — Tracking coordinate/lifecycle validation missing

- **Severity:** MEDIUM
- **Files:** tracking DTO/service
- **Fix:** coordinate validation + allowed-state enforcement.

### BUG-27 — Notification ADMIN path can return wrong error behavior

- **Severity:** LOW
- **File:** `NotificationController`
- **Fix:** explicit supported/forbidden behavior.

### BUG-28 — CRM aggregation / N+1

- **Severity:** MEDIUM
- **File:** `AgentCrmService`
- **Fix:** paginated repository queries/projections.

### BUG-29 — Hardcoded invoice year

- **Severity:** LOW
- **File:** `BookingService.generateInvoice`
- **Fix:** remove duplicate generator; use centralized number service/clock.

### BUG-30 — Free-text technician verification/availability state

- **Severity:** MEDIUM
- **File:** Technician domain
- **Fix:** enums/constraints with migration.

---

## 22. Client Clarifications Required

Do not invent the following rules during implementation.

1. **Invoice specification**
   - final invoice vs payment receipt?
   - line items?
   - VAT/tax?
   - parts/hardware?
   - PDF/email delivery?

2. **Platform/service fee policy**
   - fixed, percentage, per service, per market?

3. **Protection plan pricing**
   - is it billable, what price/rule?

4. **Free quote workflow**
   - which services?
   - quote before booking/payment?
   - who approves quote?

5. **No-fix/no-fee**
   - when triggered?
   - full or partial refund?
   - who decides?

6. **Hardware/parts billing**
   - add after diagnosis?
   - separate invoice lines/payment?

7. **Emergency pricing**
   - eligibility and amount/multiplier.

8. **Cancellation policy**
   - allowed statuses?
   - customer/agent/admin permissions?
   - automatic vs reviewed refund?

9. **Technician verification**
   - exact required documents and approval criteria.

10. **Technician assignment/reassignment**
    - manual only today?
    - customer choice?
    - agent/admin permissions?

11. **Google Meet lifecycle**
    - central GeekOnSites account acceptable?
    - retention/expiry after completion?

12. **Location retention/privacy**
    - how long coordinates retained?
    - delete/anonymize after completion?

13. **Agent boundaries**
    - can Agent cancel/reassign/close?
    - should Agent ever directly change lifecycle status?

14. **Database/deployment architecture**
    - approve PostgreSQL/Render deviation from MySQL/Railway.

---

## 23. Recommended Target Architecture Changes

Only changes justified by discovered problems are recommended.

### 23.1 Central BookingStateMachine

One authoritative lifecycle service that:

- validates from-state -> transition -> to-state
- validates actor/role
- validates service mode
- validates payment prerequisites
- writes lifecycle timestamps
- emits domain events
- releases/updates technician availability
- prevents direct enum mutation outside lifecycle package

### 23.2 PaymentTransaction ledger

Introduce a persistent financial ledger before further payment/refund expansion.

### 23.3 Service catalog domain

Replace hardcoded catalog maps with DB-backed services/prices/add-ons while snapshotting final values into bookings.

### 23.4 Transaction + domain-event strategy

Database updates atomic; Stripe/Google/email/push side effects coordinated after commit or via a lightweight outbox/event approach where reliability matters.

### 23.5 One source per domain

- one rating path
- one invoice path
- one remote lifecycle path
- one Stripe payment confirmation path
- one booking state machine

### 23.6 Migration-controlled schema

Flyway/Liquibase + immutable migration history.

### 23.7 Consistent API contracts

DTO validation, domain exceptions, global error response, pagination.

---

## 24. Prioritized Remediation Plan

### P0 — Financial, lifecycle and data-integrity blockers

1. Add regression tests around illegal lifecycle/payment actions.
2. Introduce central booking state machine.
3. Remove arbitrary status mutation endpoint.
4. Remove manual payment-success endpoints.
5. Introduce PaymentTransaction ledger.
6. Migrate Stripe confirmation to record immutable transaction rows.
7. Fix split-payment refund allocation.
8. Fix tracking lifecycle mutation/regression.
9. Remove duplicate remote-session lifecycle mutations.
10. Fix closeBooking completion prerequisite.
11. Add DB transaction boundaries.

### P1 — Required client functionality / authorization

1. Service entity/catalog + public service APIs.
2. Admin service CRUD/pricing.
3. Payment admin/history APIs from ledger.
4. Booking creation CUSTOMER-only.
5. Invoice single source + authorization-before-mutation.
6. Rating single source + unique constraint.
7. Registration country validation.
8. Email normalization/unique constraint.
9. Global validation/error contract.

### P2 — Database and production hardening

1. Flyway/Liquibase baseline.
2. Disable production `ddl-auto=update`.
3. `Double -> BigDecimal` financial migration.
4. indexes/constraints.
5. enum/constrained technician statuses.
6. pagination and CRM query optimization.
7. restrict production Swagger/root.
8. remove duplicate JWT filter.

### P3 — Cleanup / maintainability / tests

1. remove dead controllers/services.
2. centralize Meet link validation.
3. normalize support email configuration.
4. notification idempotency improvements.
5. documentation/README.
6. expand integration tests for every lifecycle transition and payment/refund edge case.

---

## 25. Test Coverage Required Before Production

At minimum add/retain tests for:

### Lifecycle

- illegal transition rejected
- cancelled booking cannot restart
- completed booking cannot reassign
- tracking cannot alter lifecycle
- arrived cannot regress
- close requires service completion
- remote completion requires valid remote session

### Authorization

- customer cannot access another customer's booking
- technician cannot access another technician's booking
- technician cannot generate invoice
- non-customer cannot create customer booking
- agent/admin boundaries explicitly tested

### Payment

- Stripe amount mismatch rejected
- Stripe currency mismatch rejected
- wrong booking metadata rejected
- duplicate webhook idempotent
- webhook + confirmation race safe
- advance and remaining recorded as two transactions
- payment references never overwritten

### Refund

- refund advance only
- refund remaining only
- refund total across two captured transactions
- partial refund across multiple transactions
- duplicate refund request/idempotency
- already-refunded ceiling

### Rating

- only completed booking can be reviewed
- one review per booking
- technician average recomputed correctly

### Validation

- invalid country
- invalid date
- invalid latitude/longitude
- missing login email/password
- invalid service ID/mode

### Database migration

- clean database migrates from zero
- existing baseline upgrades cleanly

---

## 26. What Can Remain Largely Unchanged

The following parts are generally sound and should be preserved unless changes are required by the remediations above:

- Spring Boot monolith approach
- BCrypt password hashing
- JWT authentication concept
- DB-backed principal reload
- password-reset token design
- technician onboarding token design
- Stripe Checkout creation approach
- Stripe webhook signature verification
- amount/currency revalidation logic
- Google Calendar/Meet provisioning concept
- notification framework structure
- ownership checks that already exist
- current controller/service/repository layering as the general architecture
- existing test suite as a base for regression expansion

Do **not** rewrite working infrastructure simply for stylistic reasons.

---

## 27. Final Verdict

### Does the current backend support the GeekOnSites concept?

**Partially.**

The core product journey exists in code, but several backend guarantees promised by the client architecture are not currently reliable:

- the booking lifecycle is not centrally controlled;
- payment state can be forged through internal/admin API paths;
- financial history is not modeled as a real Payment domain;
- split-payment refunds are structurally unsafe because payment references are overwritten;
- Admin cannot manage the service catalog because there is no persistent Service domain;
- duplicated lifecycle/invoice/rating paths produce inconsistent behavior;
- schema/validation/transaction handling is not yet production-grade.

### Revised readiness

- **Feature surface:** ~65%
- **Production-safe backend:** ~55–60%

### Highest-priority implementation sequence

1. Booking lifecycle/state machine
2. PaymentTransaction ledger + split-payment/refund correctness
3. Remove unsafe payment/status bypasses
4. Transaction boundaries
5. Tracking/remote/close lifecycle fixes
6. Service catalog/Admin CRUD
7. Invoice/rating consolidation + authorization
8. Validation/error handling/auth data normalization
9. Flyway + BigDecimal + indexes/constraints
10. performance/cleanup/testing

### Rule for all future Codex implementation phases

Every phase must:

1. read this updated report first;
2. inspect the current code before editing;
3. preserve existing correct behavior;
4. add/modify tests for the targeted defect;
5. run the full Maven test suite;
6. never invent client business rules listed under **Client Clarifications Required**;
7. report files changed, migrations added, APIs changed, tests added, and remaining risks at the end of the phase.

---

**End of consolidated backend audit.**
