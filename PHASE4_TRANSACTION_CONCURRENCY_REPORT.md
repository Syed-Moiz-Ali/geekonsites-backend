# PHASE 4 — Transactions, Concurrency, Locking & Atomicity

> Scope: GeekOnSites Java / Spring Boot backend — concurrency + transactional integrity.
> References: audit + Phase 0/1/2/3 reports, client PDF, live repository.
> No frontend changes. No later-phase features.

---

## 1. Transaction architecture before Phase 4

Only a few isolated methods were transactional (`TechnicianService`, `PasswordResetService`,
`AgentCrmService`, `RemoteSessionProvisioningService`, `UkEarlyServiceConsentService`,
`InvoiceService.synchronized`). The core write flows — booking assignment/lifecycle,
payment finalization, refund execution — performed multiple repository writes with **no
transaction boundaries and no row locking**. Concurrent webhook+confirm, duplicate
webhooks, concurrent checkout creation, concurrent refund workers, and concurrent
assignment/completion could interleave. External calls (Google Calendar, Stripe) were made
inside write methods.

## 2. Transaction architecture after Phase 4

- Core multi-write flows are `@Transactional` with explicit ownership.
- Critical rows are locked with `PESSIMISTIC_WRITE` in a documented global order.
- External side effects run AFTER COMMIT via application events (`@TransactionalEventListener`
  + `REQUIRES_NEW`), so provider failures cannot roll back business state and no external
  call happens before commit.
- Checkout creation is two-phase, so no DB lock is held during the Stripe call.
- Excess captures get a persisted, idempotent technical reversal.

## 3. Critical write-flow ownership table

| Flow | Owning method | Writes | External | Transaction | Lock | Race/partial risk |
|---|---|---|---|---|---|---|
| createBooking | BookingService.createBooking | booking, notification | push/email | yes | none | low |
| assignTechnician | BookingService.assignTechnician | booking, 1–2 technicians | calendar/notify (after commit) | yes | Booking + Technician(asc id) | fixed |
| accept/reject/on-way/arrived/start | BookingService.* | booking (+tech availability) | notify | yes | Booking + Technician | fixed |
| completeService | BookingService.completeService | booking, technician | notify | yes | Booking + Technician | fixed |
| closeBooking | BookingService.closeBooking | booking | notify | yes | Booking | fixed |
| generateInvoice | BookingService.generateInvoice | booking | notify | yes | Booking | low |
| applyCompletedCheckoutSession | PaymentService | payment tx, booking, invoice | provisioning (after commit) | yes | Booking | fixed |
| createCheckoutSession | PaymentService (+stripe) | payment tx | Stripe (outside tx) | two-phase | Booking | fixed |
| applyExpiredCheckoutSession | PaymentService | payment tx | none | yes | none | idempotent |
| applyRefundUpdate | PaymentService | payment refund, refund request | none | yes | none | idempotent |
| approveAndExecute | RefundService | refund req, payment refunds, payment tx | Stripe (see §33) | yes | RefundRequest + Booking + PaymentTransaction | fixed |
| requestRefund/review/reject | RefundService | refund request | email | yes | RefundRequest (review/reject) | low |
| provisioning | RemoteSessionProvisioningService | booking | Google | none (short writes) | none during Google | fixed |
| excess reversal | ExcessReversalService | payment tx | Stripe (outside tx) | two short tx | PaymentTransaction | fixed |

## 4. @Transactional methods added

- `BookingService`: class-level `@Transactional`; `@Transactional(readOnly = true)` on
  `getAllBookings`, `getBookingById`, `getBookingForCurrentUser`, `getBookingsBy*`, `getTracking`.
- `PaymentService`: `applyCompletedCheckoutSession`, `applyExpiredCheckoutSession`,
  `applyRefundUpdate` (`@Transactional`); `createCheckoutSession` uses a `TransactionTemplate`
  for its two short phases.
- `RefundService`: class-level `@Transactional`; `@Transactional(readOnly = true)` on reads.
- `InvoiceService.generateInvoiceFromBooking`: `@Transactional`.
- `ExcessReversalService`: `TransactionTemplate` (REQUIRES_NEW).

## 5. Read-only transaction decisions

Read list/lookup methods in `BookingService` and `RefundService` are `readOnly = true` so
Hibernate skips dirty-checking and the DB can route to replicas later. No global read-only
regime was imposed.

## 6. Locking strategy

Pessimistic row locks (`@Lock(PESSIMISTIC_WRITE)` + `findByIdForUpdate`) are used for short
critical sections where conflicting writes are expected:
`BookingRepository.findByIdForUpdate` (existing),
`PaymentTransactionRepository.findByIdForUpdate`,
`PaymentRefundRepository.findByIdForUpdate`,
`RefundRequestRepository.findByIdForUpdate`,
`TechnicianRepository.findByIdForUpdate`.

## 7. Global lock ordering

**Booking → PaymentTransaction → PaymentRefund → RefundRequest → Technician (ascending id).**

- Payment finalization locks `Booking` (then reads/updates the transaction within the same tx).
- Refund locks `Booking` (read) → `RefundRequest` → candidate `PaymentTransaction`s.
- Assignment locks `Booking` → technicians sorted by id (prevents A-locks-1-then-2 vs B-2-then-1 deadlock).

All multi-row lock paths follow this order.

## 8. Payment finalization concurrency design

`applyCompletedCheckoutSession` (shared by webhook + confirm) is `@Transactional` and locks
the **Booking** row first. Two simultaneous finalizations for the same booking serialize on
that lock; the loser re-reads the ledger row, sees `SUCCEEDED`, and returns idempotently. No
Stripe retrieval occurs while locked (the `Session` object is already available). All
financial aggregation and the lifecycle action happen inside the short locked section.

## 9. Booking financial aggregate locking

Because the booking row is locked before recomputing `paidAmount` / `paymentStatus`, two
different transactions for the same booking (e.g. advance vs remaining) cannot produce a
lost update. `paidAmount` is derived from the successful non-excess ledger sum.

## 10. Checkout creation concurrency design

Two-phase:
1. `reserveCheckoutAttempt` (short tx, locks Booking): validate, find the active attempt,
   reuse/expire/create the `INITIATED` row.
2. Stripe `Session.create` (no lock held).
3. `markAttemptCreated` (short tx, locks the transaction).

Two concurrent requests serialize on the Booking lock, so at most one active attempt/session
for the same booking+type is created; the other reuses it.

## 11. Checkout crash-window handling

- **A. tx persisted, crash before Stripe**: an `INITIATED` row remains; a later request
  reuses that row (same id) and retries Stripe with the same idempotency key `gos-checkout-<txId>`.
- **B. Stripe session created, crash before persisting session id**: retry reuses the same
  `INITIATED` row and the same Stripe idempotency key → Stripe returns the same session.
- **C. Stripe timeout but session created**: same as B — the idempotency key prevents a
  second session.
- **D. DB save fails after Stripe creation**: the row stays `INITIATED`; retry (same key)
  recovers the session id.

## 12. Refund concurrency design

`approveAndExecute` is `@Transactional` and locks the `RefundRequest`. Only one **active**
refund request per booking is allowed (PostgreSQL partial unique index
`uq_refund_active_booking`), so concurrent workers target the same request and serialize on
its lock; a second concurrent worker sees a terminal/non-decidable state and is rejected.

## 13. Refund allocation reservation logic

`PaymentRefund` allocations are persisted as `PENDING` **before** the provider call, and
capacity is computed with `sumActiveAmountMinorByPaymentTransactionId` (status `PENDING` +
`SUCCEEDED`). A `PENDING` reservation therefore consumes refundable capacity, so concurrent
workers cannot over-allocate the same capture.

## 14. Technician assignment locking

`assignTechnician` locks the `Booking`, then locks the previous and new technicians in
ascending-id order. The previous technician is released to `AVAILABLE` and the new one set
`BUSY` inside the same transaction. The booking and technician rows cannot end in a
half-updated state.

## 15. Accept/reject concurrency

Both lock the booking (via `validateTechnicianBooking` → `findByIdForUpdate`). Exactly one
legal transition wins; the loser observes the new state and gets a conflict/no-op. The
Phase 2 state machine keeps the outcome deterministic; technician availability matches the
winner (verified by `concurrentAcceptVersusRejectResultsInOneCoherentState`).

## 16. Completion/close concurrency

`completeService` and `closeBooking` lock the booking; the second concurrent call sees the
already-completed/closed state and returns idempotently (`changed == false`), so there is one
completion timestamp, one technician release, and no duplicate downstream effect.

## 17. Invoice concurrency changes

`InvoiceService.generateInvoiceFromBooking` is `@Transactional` (still `synchronized` for
in-JVM ordering), and `createInvoice` uses `saveAndFlush` inside a try/catch on
`DataIntegrityViolationException` to return the winning invoice when two threads race the
unique `invoices.booking_id` index — preventing duplicate invoices/numbers. Invoice format
and numbering are unchanged.

## 18. @Version decisions

No `@Version` columns were added. The critical paths are short and serialized with
pessimistic row locks, which is simpler and sufficient here; optimistic retries would add
complexity without benefit at this scale. Reassessed explicitly for `Booking`,
`PaymentTransaction`, `PaymentRefund`, `Technician`, `RefundRequest` → pessimistic chosen
for all. (Documented for a future revisit if contention patterns change.)

## 19. External side-effect before/after behavior

| Side effect | Before | After |
|---|---|---|
| Remote provisioning (Google) | inline inside the payment tx | AFTER COMMIT (`RemoteSessionProvisionRequestedEvent`) |
| Excess reversal (Stripe) | n/a | AFTER COMMIT (`ExcessPaymentReversalRequestedEvent`) |
| Assignment notifications + calendar attendee sync | inline inside assignment tx | AFTER COMMIT (`BookingAssignedEvent`) |
| Other lifecycle notifications (accept/arrived/complete/…), payment-success notification | inline | inline (best-effort, non-critical; see §24/§33) |

## 20. After-commit event design

`ApplicationEventPublisher` publishes `RemoteSessionProvisionRequestedEvent`,
`ExcessPaymentReversalRequestedEvent`, `BookingAssignedEvent`.
`ExternalSideEffectListener` handles them with
`@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` and
`@Transactional(propagation = REQUIRES_NEW)` (a committed transaction's resources are still
bound during AFTER_COMMIT, so the side effect needs its own transaction). Listener failures
are caught and logged — they can never roll back committed state. `fallbackExecution = true`
keeps behavior when an event is published outside a transaction.

## 21. Remote provisioning retry behavior

`RemoteSessionProvisioningService.provisionAfterPayment` is invoked after commit and is no
longer `@Transactional` and no longer uses `findByIdForUpdate`, so no lock is held during the
Google call. It persists `PROVISIONING`/`READY`/`FAILED` plus `remoteSessionProvisioningError`
and is safe to retry (reuses an existing event/link). A failed Google call leaves the booking
paid with a retryable `FAILED` provisioning state.

## 22. Excess-capture reversal architecture

On detecting an excess capture, `applyCompletedCheckoutSession` marks the transaction
`SUCCEEDED` + `excess = true` + `reversalStatus = PENDING` (inside the locked tx) and
publishes `ExcessPaymentReversalRequestedEvent`. After commit, `ExcessReversalService`:
reserves (locks the row, guards `SUCCEEDED`), calls Stripe to refund **exactly**
`transaction.amountMinor` against **that** transaction's PaymentIntent, then records
`SUCCEEDED`/`reversalRefundId` or `FAILED`/`reversalError`. It never touches the booking
aggregate (excess stays excluded from `paidAmount`) and never uses `RefundRuleEngine`.

## 23. Excess reversal retry/idempotency

Deterministic provider idempotency key `gos-excess-reversal-<paymentTransactionId>`; the row
is locked during reserve/record; `SUCCEEDED` is terminal and short-circuits; `FAILED` is
retryable. A repeated worker/webhook cannot issue a second provider refund.

## 24. H2 / PostgreSQL concurrency differences

Tests run on H2 (`MODE=PostgreSQL`). H2 supports `SELECT … FOR UPDATE` and demonstrated the
expected serialization, but PostgreSQL lock acquisition/timeout, deadlock detection, and
partial-index behaviour differ. In particular the refund active-request guard relies on a
**partial unique index** (`WHERE refund_status IN (…)`) that exists only in the PostgreSQL
migration, not in H2's JPA-generated schema.

## 25. PostgreSQL verification still required

Before production, re-verify on PostgreSQL: pessimistic lock timeouts, deadlock order under
concurrent assignment, the partial unique index for active refunds, and concurrent
`saveAndFlush` behaviour on the invoice unique index. Testcontainers was **not** added (Docker
infra not guaranteed here); documented as a follow-up.

## 26. Multi-write methods still not transactional and justification

| Method | Multi-write? | Transactional? | Why |
|---|---|---|---|
| `RemoteSessionProvisioningService.provisionAfterPayment` | yes | no | intentionally lock-free across the Google call; each state save is an independent short write; FAILED is persisted on error |
| `ExcessReversalService.reverse` | yes | via TransactionTemplate | two short REQUIRES_NEW transactions around the Stripe call |
| `RemoteSessionService.startRemoteSession/endRemoteSession` | delegates | no | lifecycle owned by `BookingService` (transactional) |
| `NotificationService.create*` | single write | no | single-row insert (auto-commit) |

## 27. Files created

```
src/main/java/com/geekonsites/backend/enums/PaymentReversalStatus.java
src/main/java/com/geekonsites/backend/service/RemoteSessionProvisionRequestedEvent.java
src/main/java/com/geekonsites/backend/service/ExcessPaymentReversalRequestedEvent.java
src/main/java/com/geekonsites/backend/service/BookingAssignedEvent.java
src/main/java/com/geekonsites/backend/service/ExternalSideEffectListener.java
src/main/java/com/geekonsites/backend/service/ExcessReversalService.java
database/migrations/20260827_payment_reversal.sql
src/test/java/com/geekonsites/backend/service/Phase4ConcurrencyIntegrationTest.java
src/test/java/com/geekonsites/backend/service/ExcessReversalServiceTest.java
```

## 28. Files modified

```
src/main/java/com/geekonsites/backend/entity/PaymentTransaction.java
src/main/java/com/geekonsites/backend/repository/PaymentTransactionRepository.java
src/main/java/com/geekonsites/backend/repository/PaymentRefundRepository.java
src/main/java/com/geekonsites/backend/repository/RefundRequestRepository.java
src/main/java/com/geekonsites/backend/repository/TechnicianRepository.java
src/main/java/com/geekonsites/backend/service/PaymentService.java
src/main/java/com/geekonsites/backend/service/BookingService.java
src/main/java/com/geekonsites/backend/service/RefundService.java
src/main/java/com/geekonsites/backend/service/InvoiceService.java
src/main/java/com/geekonsites/backend/service/RemoteSessionProvisioningService.java
src/test/java/com/geekonsites/backend/service/RemoteSessionProvisioningServiceTest.java
src/test/java/com/geekonsites/backend/service/RemoteSessionPaymentGateTest.java
src/test/java/com/geekonsites/backend/service/PaymentServiceTest.java
src/test/java/com/geekonsites/backend/service/PaymentNotificationTest.java
src/test/java/com/geekonsites/backend/service/StripePaymentHardeningTest.java
src/test/java/com/geekonsites/backend/service/RefundServiceTest.java
src/test/java/com/geekonsites/backend/service/RefundAllocationTest.java
```

## 29. Tests added

- `Phase4ConcurrencyIntegrationTest` (5): real-thread concurrency for payment finalization,
  assignment, accept-vs-reject, completion; plus a rollback/no-partial-state test.
- `ExcessReversalServiceTest` (3): deterministic reversal, second-reversal idempotency,
  provider-failure retryable state.

## 30. Full test counts

```
mvn -B clean test                                  → Tests run: 289, Failures: 10, Errors: 0, Skipped: 0
mvn -B test -DexcludedGroups=expected-failure       → Tests run: 279, Failures: 0, Errors: 0, Skipped: 0 (BUILD SUCCESS)
```

## 31. Expected failures remaining (10)

Untouched later-phase tests: `InvoiceAuthorizationIntegrationTest` (2),
`BookingCreationRoleRegressionTest` (3), `RatingRegressionTest` (1),
`RegistrationCountryRegressionTest` (4).

## 32. New defects discovered

1. **After-commit writes were being lost under `AFTER_COMMIT`** (the committed transaction's
   resources are still bound); fixed by running side-effect listeners in `REQUIRES_NEW`.
2. **Excess-reversal reservation semantics** initially treated `PENDING` (set at detection)
   as "already reserved", so no worker would reverse; fixed so only `SUCCEEDED` short-circuits
   and concurrency is handled by the row lock + deterministic provider key.

## 33. Remaining transactional risks

- The Stripe refund call in `RefundService.approveAndExecute` still executes within the
  `@Transactional` method (admin-triggered, low frequency). A Phase 5 refactor should split
  reservation (tx) from provider execution (non-tx) as done for excess reversal.
- Lifecycle/payment-success notifications are still inline (best-effort, non-critical; DB
  rows are transactional and push/email failures never roll back). Assignment + provisioning
  + excess side effects are after-commit.
- Crashing between DB commit and an AFTER_COMMIT side effect can drop that side effect
  (no persistent outbox). For CRITICAL eventual work (excess reversal) the `reversalStatus`
  row is persisted in the same transaction, so a retry/recovery path exists; provisioning
  persists `PROVISIONING/FAILED` for retry. A minimal outbox is deferred.
- H2 vs PostgreSQL verification pending (§25).

## 34. Phase 5 readiness

**READY.** Critical multi-write flows have explicit transaction boundaries and pessimistic
locking with a documented global order; payment finalization, checkout creation, assignment,
accept/reject, completion/close, invoice, and refund allocation are concurrency-safe;
provider calls are not held inside DB locks except the documented low-frequency refund case;
excess captures now get a persisted, idempotent after-commit technical reversal; and
Phase 1/2/3 suites remain green with 0 unexpected failures.
