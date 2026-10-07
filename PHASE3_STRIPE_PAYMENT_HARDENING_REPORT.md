# PHASE 3 — Stripe & Payment Lifecycle Hardening

> Scope: GeekOnSites Java / Spring Boot backend — payment provider lifecycle only.
> References: `GeekOnSites_Backend_Audit_Report_Updated.md`, `PHASE0_BASELINE_REPORT.md`, `PHASE1_PAYMENT_LEDGER_REPORT.md`, `PHASE2_BOOKING_LIFECYCLE_REPORT.md`, client PDF, live repository.
> No frontend changes. No Phase 4 concurrency/locking work.

---

## 1. Payment architecture entering Phase 3

`Booking 1─<N PaymentTransaction` (authoritative financial history) and
`RefundRequest 1─<N PaymentRefund` (per-capture refund executions). Checkout wrote a
ledger row, webhook + authenticated confirmation shared one finalization path, split
payments/refunds worked, and `BookingStateMachine` owned the operational lifecycle.
Remaining Phase 3 gaps: no explicit provider-state machine, no handling of
`checkout.session.expired`, no duplicate-checkout prevention, no Stripe idempotency key,
no overpayment/stale-capture protection, no PaymentIntent immutability guard, and
scattered minor-unit conversions.

## 2. Stripe events supported before Phase 3

- `checkout.session.completed` (finalized only when `payment_status = paid`)
- `refund.updated`
- everything else: silently ignored (but the handler rethrew on any exception).

## 3. Stripe events supported after Phase 3

- `checkout.session.completed` → finalize **only if** `payment_status = paid`
- `checkout.session.expired` → mark the ledger transaction `EXPIRED` (no booking change)
- `refund.updated` → update `PaymentRefund` + recompute `RefundRequest`
- all other event types → acknowledged and ignored (no mutation, no error)

## 4. PaymentTransaction transition table

| From | Event/action | To |
|---|---|---|
| — (created) | checkout attempt created | INITIATED |
| INITIATED | Stripe session created | CHECKOUT_CREATED |
| INITIATED / CHECKOUT_CREATED | permanent creation failure | FAILED |
| INITIATED / CHECKOUT_CREATED | Stripe session expired | EXPIRED |
| INITIATED / CHECKOUT_CREATED / EXPIRED / FAILED | Stripe reports paid (provider truth) | SUCCEEDED |
| SUCCEEDED | any later/stale event | SUCCEEDED (terminal, no downgrade) |

Precedence: **SUCCEEDED > EXPIRED/FAILED**. `EXPIRED → SUCCEEDED` is allowed only when
Stripe itself reports the payment paid; `SUCCEEDED → EXPIRED/FAILED` is always refused.

## 5. PaymentRefund transition table

| From | Event | To |
|---|---|---|
| — (created) | allocation persisted before provider call | PENDING |
| PENDING | provider refund succeeded | SUCCEEDED |
| PENDING | provider refund failed/canceled | FAILED |
| SUCCEEDED | any later/stale update | SUCCEEDED (terminal) |

Provider refund id is immutable once set (a differing id is rejected). Execution
amount/currency are frozen when the allocation is created.

## 6. Checkout creation behavior

`PaymentService.createCheckoutSession`:
1. loads booking, verifies payer ownership;
2. normalizes the payment type and validates it against the current financial state,
   including a **zero/negative-outstanding-balance** rejection (ledger-derived);
3. records UK consent, derives the amount/currency **server-side** (never the caller);
4. duplicate prevention against an active `INITIATED/CHECKOUT_CREATED` attempt;
5. creates or reuses a `PaymentTransaction`, creates the Stripe session through
   `StripeCheckoutGateway` with a per-attempt idempotency key, and persists
   `checkoutSessionId`, `checkoutUrl`, `checkoutExpiresAt`.

## 7. Duplicate-checkout strategy

Before creating a session the service looks for the latest non-excess active attempt
(`INITIATED`/`CHECKOUT_CREATED`) for the same booking + payment type:
- **CHECKOUT_CREATED, usable URL, not expired** → reuse (return the existing URL/session;
  no new Stripe session, no new row);
- **CHECKOUT_CREATED but expired** → mark the old attempt `EXPIRED` and create a fresh
  attempt (the old row is preserved, never overwritten);
- **INITIATED** → reuse the row and retry Stripe creation with the same idempotency key;
- no active attempt → create a new attempt.

## 8. Stripe idempotency strategy

Each Stripe Checkout creation uses idempotency key `gos-checkout-<paymentTransactionId>`,
tied to **one internal payment attempt**. An HTTP client timeout/retry for the same
attempt cannot create a second session; a genuinely new attempt gets a new transaction id
(and therefore a new key). No permanent `bookingId+paymentType` key is used.

## 9. Checkout expiration handling

`checkout.session.expired` → `applyExpiredCheckoutSession` marks the matching ledger row
`EXPIRED` (idempotent, never downgrades `SUCCEEDED`). It does **not** change booking
payment aggregate, lifecycle, invoice, provisioning or notifications.

## 10. Failed payment handling

The configured Checkout is synchronous card-only (`Mode.PAYMENT`, default methods). Card
failures do not produce a `checkout.session.completed`; an abandoned session simply
expires (handled above). There is therefore no separate card failure webhook to process,
and this is **documented rather than fabricated**. A frontend cancel-page return is never
treated as a payment failure.

## 11. Async-payment decision and rationale

Asynchronous payment methods (which produce `checkout.session.async_payment_succeeded` /
`async_payment_failed`) are **not enabled**, so those events are not subscribed/handled.
This avoids inventing unsupported flows. If async methods are enabled later, they must be
added deliberately (tracked for a later phase).

## 12. Webhook validation flow

1. `Webhook.constructEvent` verifies the signature using `stripe.webhook.secret`
   (invalid signature → no mutation, error).
2. Dispatch by event type (see §3); unsupported types are ignored.
3. `checkout.session.completed` finalizes only for a paid session.

## 13. Authenticated confirmation flow

`confirmCheckoutSession` retrieves the Stripe session server-side, requires
`payment_status = paid`, then calls the same `applyCompletedCheckoutSession`. Provider
truth wins even if the local ledger row is `EXPIRED` (a genuinely paid session upgrades to
`SUCCEEDED`); an unpaid session is never resurrected. A browser cannot mark a booking paid.

## 14. Replay/idempotency handling

A `SUCCEEDED` transaction short-circuits finalization with a pure read (no repeated
aggregation, invoice, provisioning or notification). The legacy already-paid fallback is
retained only for pre-ledger bookings. Both webhook replay and repeated confirmation are
safe.

## 15. Out-of-order event behavior

Late/stale events cannot downgrade a provider outcome: `markExpired`/`markFailed` are
no-ops on `SUCCEEDED`. Provider state is monotonic toward `SUCCEEDED`.

## 16. Metadata validation

Checkout completion resolves the ledger row by `checkoutSessionId` (then metadata
`paymentTransactionId`), and validates Stripe metadata against database truth:
`bookingId` and `paymentType` must match the ledger row. **Database wins**; contradictory
metadata is rejected with no mutation.

## 17. PaymentIntent immutability

On success the PaymentIntent is persisted only if absent or equal to the existing value; a
later event claiming a different PaymentIntent for the same transaction is rejected
(`InvalidPaymentStateException`).

## 18. Overpayment prevention

Before applying a capture, the service computes
`alreadySuccessful(non-excess) + incoming` vs the booking obligation (`totalAmount`). A
capture that exceeds the obligation is marked `SUCCEEDED` **and** `excess = true`, preserved
as financial history, and excluded from the booking aggregate/lifecycle/invoice/
provisioning/notification. `Booking.paidAmount` therefore cannot become `200` for a `100`
obligation.

## 19. Stale successful Checkout handling

A stale/duplicate Stripe session that still captures money is **detected and quarantined**
(the `excess` row), not ignored and not counted. Automatic provider reversal is **not**
implemented, because a safe deterministic reversal requires the Phase 4 transaction/locking
work — this is explicitly recorded as a production blocker (§31).

## 20. Legacy Booking payment-field usage

| Usage | Classification |
|---|---|
| `PaymentService.applyBookingPaymentAggregate` writes `Booking.paymentTransactionId` | COMPATIBILITY WRITE |
| `PaymentService.isLegacyAlreadyPaid` reads it as an idempotency fallback | LEGACY READ FALLBACK |
| `Booking.paymentTransactionId`, `BookingRequest.paymentTransactionId`, `Invoice.paymentTransactionId` fields | DEAD/COMPATIBILITY |
| Any refund/finalization authority | **none** — ledger is authoritative |

## 21. Legacy fallback isolation

The pre-ledger fallback is isolated in `PaymentService.recoverLegacyTransaction(...)` with
an explicit warning log and a clear name. It runs only when no ledger row exists for the
session/attempt, and the recovered row is persisted only after Stripe reports the payment
paid AND the amount/currency are verified against the server-computed booking. It cannot
overwrite or compete with ledger truth; no historical transaction is fabricated.

## 22. Payment aggregate invariants

- `Booking.paidAmount` = sum of successful **non-excess** ledger rows (legacy fallback when
  no ledger data).
- `paidAmount` never increases on failed/expired/cancelled/replayed transactions.
- `paymentStatus` is derived consistently: `PARTIALLY_PAID` (advance), `PAID`
  (full/remaining), `BALANCE_PENDING` (completion with balance).
- `BookingStatus` changes only through `BookingStateMachine`; the payment layer never
  mutates `PaymentTransaction` statuses indirectly and vice versa.

## 23. Refund webhook hardening

`refund.updated` resolves the `PaymentRefund` by `providerRefundId`, updates its status
through `PaymentRefundStateMachine` (no downgrade from `SUCCEEDED`, immutable provider id),
then recomputes the `RefundRequest` aggregate from succeeded-sum sums (idempotent, cannot
double-count).

## 24. Side-effect idempotency

Finalization side effects (lifecycle action, invoice, remote provisioning, notifications)
run only on the first genuine success transition. A `SUCCEEDED` replay is a pure read.
Phase 1 mode gating is preserved (on-site never provisions remote; remaining settles the
balance rather than creating a new obligation).

## 25. Direct financial mutations remaining

| File | Mutation | Why it remains |
|---|---|---|
| `PaymentTransactionStateMachine` | status / paymentIntentId / checkoutSessionId | The one provider-state authority |
| `PaymentService.createCheckoutSession` | `new tx` status INITIATED | Row creation (initialization) |
| `PaymentService.recoverLegacyTransaction` | `new tx` status INITIATED | Legacy recovery row creation |
| `PaymentService.applyBookingPaymentAggregate` | `Booking.paymentStatus` / `paidAmount` | Compatibility/read-model aggregate, ledger-derived |
| `BookingService` | `paidAmount=0`, `paymentStatus=PENDING` | Booking creation initialization |
| `BookingService.completeService` | `paymentStatus=BALANCE_PENDING` | Balance aggregate at completion |
| `InvoiceService` | copies `paymentStatus`/`paidAmount` onto the invoice | Document snapshot |
| `RefundService` | `PaymentRefund` status PENDING on allocation creation | Allocation row creation |
| `PaymentRefundStateMachine` | refund status | The one refund-state authority |

No code uses `Booking.paymentTransactionId` as financial authority.

## 26. Files created

Production:
```
src/main/java/com/geekonsites/backend/service/PaymentMoney.java
src/main/java/com/geekonsites/backend/service/InvalidPaymentStateException.java
src/main/java/com/geekonsites/backend/service/PaymentTransactionStateMachine.java
src/main/java/com/geekonsites/backend/service/PaymentRefundStateMachine.java
src/main/java/com/geekonsites/backend/service/StripeCheckoutGateway.java
src/main/java/com/geekonsites/backend/service/StripeCheckoutGatewayImpl.java
database/migrations/20260826_payment_hardening.sql
```
Tests:
```
src/test/java/com/geekonsites/backend/service/StripePaymentHardeningTest.java
```

## 27. Files modified

Production:
```
src/main/java/com/geekonsites/backend/entity/PaymentTransaction.java
src/main/java/com/geekonsites/backend/repository/PaymentTransactionRepository.java
src/main/java/com/geekonsites/backend/service/PaymentService.java
src/main/java/com/geekonsites/backend/service/RefundService.java
```
Tests:
```
src/test/java/com/geekonsites/backend/service/PaymentServiceTest.java        (ctor)
src/test/java/com/geekonsites/backend/service/PaymentNotificationTest.java   (ctor)
src/test/java/com/geekonsites/backend/service/RefundServiceTest.java         (ctor)
src/test/java/com/geekonsites/backend/service/RefundAllocationTest.java      (ctor)
```

## 28. Tests created/modified

- Created `StripePaymentHardeningTest` (26 tests): paid completion; unpaid no-op; wrong
  booking/tx/type metadata; amount/currency mismatch; changed PaymentIntent; lowercase
  currency; webhook replay; webhook↔confirm overlap; overpayment quarantine; expired
  handling + duplicate + stale-after-success; zero-balance/type checkout rejection;
  duplicate checkout reuse; expired checkout retirement + new attempt; new checkout creates
  one ledger row; invalid signature; unknown event; refund no-downgrade; duplicate refund
  update.
- Modified 4 test constructors only (new dependencies).

## 29. Maven results

```
mvn -B clean test                                  → Tests run: 281, Failures: 10, Errors: 0, Skipped: 0
mvn -B test -DexcludedGroups=expected-failure       → Tests run: 271, Failures: 0, Errors: 0, Skipped: 0 (BUILD SUCCESS)
```
0 unexpected failures. Original 171 baseline, Phase 1 ledger/refund, and Phase 2 lifecycle
suites remain green (`PaymentLedgerIntegrationTest`, `RefundAllocationTest`,
`SplitPaymentRegressionTest`, `OnsiteRemainingPaymentRegressionTest`,
`BookingStateMachineTest`, `OnsiteLifecycleIntegrationTest`, `RemoteLifecycleIntegrationTest`).

## 30. Expected failures remaining (10)

Intentionally deferred later-phase tests, untouched:
`InvoiceAuthorizationIntegrationTest` (2), `BookingCreationRoleRegressionTest` (3),
`RatingRegressionTest` (1), `RegistrationCountryRegressionTest` (4).

## 31. Unresolved concurrency risks for Phase 4

- **Stale/duplicate capture reversal**: an `excess` capture is quarantined but not
  automatically reversed. A safe deterministic refund of the excess requires Phase 4
  transaction/allocation work — recorded as a **production blocker**.
- True simultaneous `webhook` + `confirm`, two webhook threads, and concurrent
  checkout-creation requests are handled only sequentially; no pessimistic/optimistic
  locking was added (Phase 4). The per-attempt Stripe idempotency key mitigates duplicate
  provider sessions but not concurrent DB races.
- The `find active attempt` → `create` sequence is not atomic; Phase 4 will add locking.

## 32. New defects discovered

- None. Confirmed the existing architecture behaved correctly for the newly tested paths
  once the guards were added. Noted that Phase 1's `applyCompletedCheckoutSession` would
  have thrown "already paid" for a stale capture (rather than quarantining it); Phase 3
  fixed the ordering so genuine excess captures are preserved and quarantined.

## 33. Phase 4 readiness

**READY.** Provider-state transitions are centralized and monotonic; only paid sessions
finalize; expiry/unknown events are safe; duplicate checkout and provider idempotency are in
place; expected values are frozen and cross-validated; overpayment is quarantined; refunds
are hardened; and the two-authority model (`PaymentTransactionStateMachine` /
`PaymentRefundStateMachine` for provider state, `BookingStateMachine` for booking lifecycle)
is intact. Remaining work is concurrency/locking and provider reversal of quarantined
excess captures.
