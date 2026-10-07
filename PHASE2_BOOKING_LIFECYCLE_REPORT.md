# PHASE 2 — Central Booking State Machine & Lifecycle Enforcement

> Scope: GeekOnSites Java / Spring Boot backend. Booking lifecycle only.
> References: `GeekOnSites_Backend_Audit_Report_Updated.md`, `PHASE0_BASELINE_REPORT.md`, `PHASE1_PAYMENT_LEDGER_REPORT.md`, client PDF, live repository.
> No frontend changes. No unrelated domain redesign.

---

## 1. Previous lifecycle architecture

Booking status could be mutated from several independent places:

- `BookingService` set the status directly in ~13 methods (assignment, accept, reject,
  travel, arrival, service start, remote start, completion, invoice, close, status
  override).
- `RemoteSessionService` implemented a **second, weaker** remote lifecycle path that set
  `REMOTE_SESSION_STARTED` / `SERVICE_COMPLETED` without accepting/payment/current-state
  checks.
- `PaymentService` set booking status directly when a payment succeeded
  (`PAYMENT_COMPLETED` / `ASSIGNMENT_PENDING` / `SERVICE_COMPLETED`).
- `BookingController.updateStatus -> BookingService.updateStatus` let AGENT/ADMIN assign
  any `BookingStatus` value with no validation.
- Tracking (`updateTechnicianLocation`) forced `TECHNICIAN_ON_THE_WAY`, implicitly
  accepting jobs and regressing an arrived booking.

There was no single place that decided `current state + requested action + context =
allowed/rejected`.

## 2. Every previous BookingStatus mutation location

| FILE | METHOD | PREVIOUS STATE CHANGE | CALLER | VALIDATION | SIDE EFFECTS |
|---|---|---|---|---|---|
| BookingService | createBooking | null → PENDING | customer | none (init) | notification |
| BookingService | assignTechnician | * → TECHNICIAN_ASSIGNED | AGENT/ADMIN API | payment/tech/mode | tech BUSY, notifications, calendar |
| BookingService | technicianAcceptJob | ASSIGNED → ACCEPTED | technician API | exact state | timestamp, notification |
| BookingService | technicianRejectJob | ASSIGNED → REJECTED | technician API | exact state | clears tech, tech AVAILABLE, notification |
| BookingService | technicianOnTheWay | ACCEPTED → ON_THE_WAY | technician API | exact state | timestamp, notification |
| BookingService | technicianArrived | ON_THE_WAY → ARRIVED | technician API | exact state | flags, notification |
| BookingService | updateTechnicianLocation | * → ON_THE_WAY | technician API | **none (implicit)** | forced state, arrival notification |
| BookingService | startService | ARRIVED → SERVICE_STARTED | technician API | exact state + consent | timestamp, notification |
| BookingService | startRemoteSession | ACCEPTED → REMOTE_SESSION_STARTED | technician API | paid + exact state + link | timestamp, notification |
| BookingService | completeService | STARTED/REMOTE → COMPLETED / REMAINING_PAYMENT_PENDING | technician API | exact state | timestamp, tech AVAILABLE, notification |
| BookingService | generateInvoice | COMPLETED → INVOICE_GENERATED | owner/ops API | none | notification |
| BookingService | closeBooking | * → BOOKING_CLOSED | AGENT/ADMIN API | paid + invoice only | timestamp, notification |
| BookingService | updateStatus | * → ANY | AGENT/ADMIN API | **none** | none |
| RemoteSessionService | startRemoteSession | * → REMOTE_SESSION_STARTED | technician API | paid+remote+link only | none |
| RemoteSessionService | endRemoteSession | * → SERVICE_COMPLETED | technician API | assignment only | none |
| PaymentService | applyBookingPaymentAggregate | * → PAYMENT_COMPLETED / ASSIGNMENT_PENDING / SERVICE_COMPLETED | webhook/confirm | amount/currency | invoice, provisioning, notification |

## 3. Final BookingStatus list (actual enum — unchanged)

`PENDING, PAYMENT_COMPLETED, ASSIGNMENT_PENDING, TECHNICIAN_ASSIGNED, TECHNICIAN_ACCEPTED,
TECHNICIAN_REJECTED, TECHNICIAN_ON_THE_WAY, TECHNICIAN_ARRIVED, SERVICE_STARTED,
REMOTE_SESSION_STARTED, SERVICE_COMPLETED, INVOICE_GENERATED, REMAINING_PAYMENT_PENDING,
FULLY_PAID, BOOKING_CLOSED, CANCELLED`

## 4. Status classification

| Group | Statuses |
|---|---|
| pre-payment | `PENDING` |
| ready-for-assignment | `PAYMENT_COMPLETED`, `ASSIGNMENT_PENDING`, `TECHNICIAN_REJECTED` |
| assigned | `TECHNICIAN_ASSIGNED` |
| accepted | `TECHNICIAN_ACCEPTED` |
| travel (on-site) | `TECHNICIAN_ON_THE_WAY`, `TECHNICIAN_ARRIVED` |
| service-active | `SERVICE_STARTED`, `REMOTE_SESSION_STARTED` |
| service-completed | `SERVICE_COMPLETED` |
| payment/settlement | `REMAINING_PAYMENT_PENDING`, `FULLY_PAID`, `INVOICE_GENERATED` |
| terminal | `BOOKING_CLOSED`, `CANCELLED` |

## 5. Legal transition table

| From | Action | To | Guards |
|---|---|---|---|
| PENDING | payment FULL confirmed | PAYMENT_COMPLETED | amount/currency/idempotency (PaymentService) |
| PENDING | payment ADVANCE confirmed | ASSIGNMENT_PENDING | on-site, amount/currency |
| PAYMENT_COMPLETED / ASSIGNMENT_PENDING / TECHNICIAN_REJECTED / TECHNICIAN_ASSIGNED | assignTechnician | TECHNICIAN_ASSIGNED | payment eligible, tech APPROVED+AVAILABLE+mode |
| TECHNICIAN_ASSIGNED | technicianAccept | TECHNICIAN_ACCEPTED | assigned tech |
| TECHNICIAN_ASSIGNED | technicianReject | TECHNICIAN_REJECTED | assigned tech; frees tech |
| TECHNICIAN_ACCEPTED | markOnTheWay | TECHNICIAN_ON_THE_WAY | on-site-like |
| TECHNICIAN_ON_THE_WAY | markArrived | TECHNICIAN_ARRIVED | on-site-like |
| TECHNICIAN_ARRIVED | startOnsiteService | SERVICE_STARTED | on-site-like + UK consent |
| TECHNICIAN_ACCEPTED | startRemoteSession | REMOTE_SESSION_STARTED | REMOTE + PAID + valid link |
| SERVICE_STARTED / REMOTE_SESSION_STARTED | completeService | SERVICE_COMPLETED | active service |
| SERVICE_COMPLETED | markRemainingPaymentPending | REMAINING_PAYMENT_PENDING | balance > 0 |
| REMAINING_PAYMENT_PENDING | payment REMAINING confirmed | SERVICE_COMPLETED | amount/currency |
| SERVICE_COMPLETED | markInvoiceGenerated | INVOICE_GENERATED | completed |
| SERVICE_COMPLETED / FULLY_PAID / INVOICE_GENERATED | closeBooking | BOOKING_CLOSED | PAID + invoice + completed |

## 6. Illegal transition rules

- Backward transitions rejected: `ARRIVED → ON_THE_WAY`, `SERVICE_STARTED → ARRIVED`,
  `SERVICE_COMPLETED → SERVICE_STARTED`, `BOOKING_CLOSED → FULLY_PAID`, `CANCELLED → active`.
- Terminal states (`CANCELLED`, `BOOKING_CLOSED`) reject every operational action.
- Mode guards: `ON-SITE → startRemoteSession` rejected; `REMOTE → markOnTheWay/markArrived/startOnsiteService` rejected.
- Payment guards: remote session start requires `PAID`; assignment requires `PAID` (or on-site `PARTIALLY_PAID`); close requires `PAID`.
- Close requires a genuinely completed/settled booking state (not merely PAID+invoice).
- Tracking cannot change lifecycle in any state, and is rejected in
  `SERVICE_COMPLETED`, `INVOICE_GENERATED`, `REMAINING_PAYMENT_PENDING`, `FULLY_PAID`,
  `BOOKING_CLOSED`, `CANCELLED`.

## 7. On-site lifecycle (text flow)

```
PENDING
 --(advance payment)--> ASSIGNMENT_PENDING
 --(assign)-----------> TECHNICIAN_ASSIGNED
 --(accept)-----------> TECHNICIAN_ACCEPTED
 --(on-the-way)-------> TECHNICIAN_ON_THE_WAY
 --(location updates)--> (status unchanged)
 --(arrived)----------> TECHNICIAN_ARRIVED
 --(start-service)----> SERVICE_STARTED
 --(complete-service)--> SERVICE_COMPLETED (no balance) | REMAINING_PAYMENT_PENDING (balance)
 --(remaining payment)--> SERVICE_COMPLETED
 --(invoice)----------> INVOICE_GENERATED
 --(close)------------> BOOKING_CLOSED
```

## 8. Remote lifecycle (text flow)

```
PENDING
 --(full payment)-----> PAYMENT_COMPLETED
 --(assign)-----------> TECHNICIAN_ASSIGNED
 --(accept)-----------> TECHNICIAN_ACCEPTED
 --(save meeting link)--> (status unchanged)
 --(start remote)-----> REMOTE_SESSION_STARTED
 --(end remote)-------> SERVICE_COMPLETED
 --(close)------------> BOOKING_CLOSED
```

## 9. BookingStateMachine architecture

`com.geekonsites.backend.service.BookingStateMachine` is a small, dependency-free
`@Service` and the **only** production component that calls
`booking.setBookingStatus(...)`. It exposes business actions (not a generic
`transition(status)` API):

`assignTechnician`, `technicianAccept`, `technicianReject`, `markOnTheWay`,
`markArrived`, `startOnsiteService`, `startRemoteSession`, `completeService`,
`markRemainingPaymentPending`, `onPaymentConfirmed`, `markInvoiceGenerated`,
`closeBooking`, `assertTrackingAllowed`.

Each action validates current state / mode / terminal / payment prerequisites, applies
the status change and lifecycle timestamps, and returns whether the state changed
(idempotency signal). Illegal requests throw `InvalidBookingTransitionException`
(a `ResponseStatusException` subclass → 400/409). Application services own persistence,
notifications and technician availability.

## 10. PaymentService → lifecycle integration

`PaymentService.applyBookingPaymentAggregate` still owns the payment aggregate
(`paymentStatus`, `paidAmount`, ledger sums) but no longer sets booking status; it calls
`bookingStateMachine.onPaymentConfirmed(booking, PaymentType.X)`. Financial truth stays
with `PaymentService` / `PaymentTransaction`; the operational booking state is decided by
the state machine (Step 21/30 boundary preserved).

## 11. Technician assignment / reassignment logic

`BookingService.assignTechnician` keeps the existing payment + technician eligibility +
mode checks, then calls `stateMachine.assignTechnician` (rejects
completed/cancelled/closed/active bookings, resets acceptance/rejection evidence). If a
different technician was previously assigned, the previous technician is released to
`AVAILABLE`; the new technician is set `BUSY`. Reassigning the same technician is a
no-op for availability.

## 12. Technician accept / reject behavior

- Accept: only from `TECHNICIAN_ASSIGNED`; assigned-technician check in the caller;
  idempotent when already `TECHNICIAN_ACCEPTED`.
- Reject: only from `TECHNICIAN_ASSIGNED`; releases the technician to `AVAILABLE`,
  clears the assignment, and moves to `TECHNICIAN_REJECTED` (reassignment-eligible).

## 13. Tracking behavior before vs after

| Before | After |
|---|---|
| Location update forced `TECHNICIAN_ON_THE_WAY` | Location update never changes status |
| Implicitly accepted an assigned job | Cannot bypass acceptance |
| Regressed `TECHNICIAN_ARRIVED` → `ON_THE_WAY` | Arrival preserved |
| Mutated completed/closed/cancelled bookings | Rejected (409) in terminal/completed states |
| Unbounded coordinates accepted | Latitude/longitude range guard (400) |
| Distance-based "arrived" side effect | Removed (pure tracking) |

Tracking now only writes coordinates/tracking metadata after ownership + allowed-state
checks.

## 14. Remote-session behavior before vs after

| Before | After |
|---|---|
| `RemoteSessionService` set status directly (parallel path) | Delegates to `BookingService`/state machine |
| `start` skipped `TECHNICIAN_ACCEPTED` (only paid+link) | Requires `REMOTE` + `PAID` + accepted + valid link |
| `end` could complete on-site/unpaid/assigned bookings | Requires REMOTE; delegates to completion (valid active state only) |
| No completion side effects | Completion frees technician + notifies (shared path) |

## 15. Booking close prerequisites

Close now requires: `paymentStatus == PAID` **and** `invoiceGenerated == true` (existing
business flow) **and** the lifecycle state ∈ {`SERVICE_COMPLETED`, `FULLY_PAID`,
`INVOICE_GENERATED`}. `PAYMENT_COMPLETED`, `TECHNICIAN_ASSIGNED`, `TECHNICIAN_ACCEPTED`,
`SERVICE_STARTED`, `CANCELLED` are rejected with 409.

## 16. Terminal-state behavior

`CANCELLED` and `BOOKING_CLOSED` reject assignment, acceptance, rejection, travel,
arrival, service start/completion, remote start/end, and tracking. A cancelled booking
cannot be closed. No rollback action was invented.

## 17. Timestamp behavior

`technicianAcceptedAt`, `technicianRejectedAt`, `technicianOnTheWayAt`,
`serviceStartedAt`, `serviceCompletedAt`, `remoteSessionStartedAt`,
`remoteSessionEndedAt`, `bookingClosedAt` are set by the state machine only on the
corresponding valid transition, and only if not already set. Repeated/idempotent actions
do not rewrite them.

## 18. Idempotency decisions

- `technicianAccept`, `markOnTheWay`, `markArrived`, `startOnsiteService`,
  `startRemoteSession`, `completeService`, `closeBooking`, `markRemainingPaymentPending`
  return `false` and perform no side effects when already in the target state.
- Application services gate saves/notifications on the changed flag, so HTTP retries do
  not duplicate notifications.
- `onPaymentConfirmed` is only reached after `PaymentService`'s ledger idempotency check.

## 19. Direct status mutations remaining after refactor

| FILE | METHOD | DIRECT STATUS MUTATION? | WHY IT REMAINS |
|---|---|---|---|
| BookingStateMachine | (all actions) | yes | The single authoritative lifecycle authority |
| Booking | `@PrePersist onCreate` | sets default `PENDING` only if null | Entity initialization, not a business transition |
| BookingService | createBooking | no (relies on `@PrePersist`) | Initialization |
| PaymentService | applyBookingPaymentAggregate | no (calls `onPaymentConfirmed`) | Lifecycle delegated |
| RemoteSessionService | all | no (delegates) | Lifecycle delegated |

Repository-wide search for `setBookingStatus(` now returns matches **only** inside
`BookingStateMachine` (plus two Javadoc mentions). Goal of one lifecycle authority met.

## 20. API endpoints removed / changed

- **Removed:** `PUT /api/bookings/{bookingId}/status/{status}` (arbitrary status override)
  and the matching `SecurityConfig` rule. Callers now receive 404 (a client error).
- **Unchanged paths/contracts:** assign-technician, technician accept/reject/on-the-way/
  arrived/location/start-service/start-remote-session/complete-service, meeting-link,
  remote-sessions create/get/start/end, close, tracking. Only internal validation changed.
- `RemoteSessionController` gained a local `@ExceptionHandler(ResponseStatusException)` so
  lifecycle conflicts return 4xx rather than 500 (no path change).

## 21. Files created

Production:
```
src/main/java/com/geekonsites/backend/service/BookingStateMachine.java
src/main/java/com/geekonsites/backend/service/InvalidBookingTransitionException.java
```
Tests:
```
src/test/java/com/geekonsites/backend/service/BookingStateMachineTest.java
src/test/java/com/geekonsites/backend/service/OnsiteLifecycleIntegrationTest.java
src/test/java/com/geekonsites/backend/service/RemoteLifecycleIntegrationTest.java
```

## 22. Files modified

Production:
```
src/main/java/com/geekonsites/backend/service/BookingService.java
src/main/java/com/geekonsites/backend/service/RemoteSessionService.java
src/main/java/com/geekonsites/backend/service/PaymentService.java
src/main/java/com/geekonsites/backend/controller/BookingController.java
src/main/java/com/geekonsites/backend/controller/RemoteSessionController.java
src/main/java/com/geekonsites/backend/config/SecurityConfig.java
```
Tests:
```
src/test/java/com/geekonsites/backend/service/RemoteSessionPaymentGateTest.java  (ctor)
src/test/java/com/geekonsites/backend/service/PaymentServiceTest.java            (ctor)
src/test/java/com/geekonsites/backend/service/PaymentNotificationTest.java       (ctor)
src/test/java/com/geekonsites/backend/phase0/ArbitraryStatusEndpointRegressionTest.java  (untagged)
src/test/java/com/geekonsites/backend/phase0/BookingLifecycleIntegrationTest.java         (untagged)
src/test/java/com/geekonsites/backend/phase0/BookingCloseRegressionTest.java              (untagged)
src/test/java/com/geekonsites/backend/phase0/TechnicianAssignmentIntegrationTest.java     (untagged)
src/test/java/com/geekonsites/backend/phase0/TrackingLifecycleRegressionTest.java         (untagged)
src/test/java/com/geekonsites/backend/phase0/RemoteSessionLifecycleRegressionTest.java    (untagged)
```

## 23. Tests added

- `BookingStateMachineTest` (12): full on-site chain + timestamps, remote chain, illegal/
  backward transitions, mode guards, terminal states, payment-confirmed states,
  idempotency/timestamp stability, close prerequisites, remaining-payment guard, tracking
  guard, assignment guard.
- `OnsiteLifecycleIntegrationTest` (1): end-to-end on-site flow including split payment and
  ledger verification (Steps 34 + 36).
- `RemoteLifecycleIntegrationTest` (1): end-to-end remote flow (Step 35).

## 24. Expected-failure tests fixed

24 removed tags:
- `ArbitraryStatusEndpointRegressionTest` (2)
- `BookingLifecycleIntegrationTest` (5)
- `BookingCloseRegressionTest` (4)
- `TechnicianAssignmentIntegrationTest` (3)
- `TrackingLifecycleRegressionTest` (6)
- `RemoteSessionLifecycleRegressionTest` (4)

## 25. Expected-failure tests remaining (10)

- `InvoiceAuthorizationIntegrationTest` (2)
- `BookingCreationRoleRegressionTest` (3)
- `RatingRegressionTest` (1)
- `RegistrationCountryRegressionTest` (4)

These are intentionally deferred (invoice authorization, booking-creation roles, rating
deduplication, registration-country validation).

## 26. Full Maven test result

```
mvn -B clean test                                  → Tests run: 255, Failures: 10, Errors: 0, Skipped: 0
mvn -B test -DexcludedGroups=expected-failure       → Tests run: 245, Failures: 0, Errors: 0, Skipped: 0 (BUILD SUCCESS)
```
- 0 unexpected failures.
- The 10 failures are all `@Tag("expected-failure")` later-phase tests.
- Original 171 baseline remains green; Phase 1 payment/split tests remain green.

## 27. New defects discovered

- No new production defects were discovered in Phase 2 beyond the audited lifecycle issues
  (H2/H3/H4/H5/H6/BUG-06/07/08/09/10/11), all now fixed.
- Note: the Phase 0 report mapped the technician-reassignment test to Phase 1, but the
  Phase 2 brief explicitly scoped it here; it is now fixed and untagged.
- Phase 1 `applyBookingPaymentAggregate` was the last remaining direct status mutation
  outside `BookingService`; it is now routed through the state machine.

## 28. Technical debt intentionally deferred

- Full transaction/after-commit architecture (C5) — Phase 4.
- Global error contract (all business failures still surface via
  `ResponseStatusException` / `InvalidBookingTransitionException`).
- Invoice authorization-before-mutation, booking-creation role restriction, duplicate
  rating prevention, registration-country validation.
- Service catalog, BigDecimal migration, Flyway baseline, Swagger restriction.
- `ukEarlyServiceConsentService.validateBeforeServiceStart` still called inside
  `BookingService` (a precondition, not a lifecycle transition).
- Booking `Double` money fields and non-FK flat IDs.

## 29. Phase 3 readiness

**READY.** One authoritative lifecycle authority exists; the arbitrary status endpoint is
gone; every lifecycle regression test is green; Phase 1 payment-ledger behavior is intact;
only the 10 intentionally-deferred later-phase tests remain red. No Phase 3 work was
started.
