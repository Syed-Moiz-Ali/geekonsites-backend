# PHASE 1 — Payment Transaction Ledger, Split Payment & Refund Correctness

> Scope: GeekOnSites Java / Spring Boot backend. Payment domain only.
> References: `GeekOnSites_Backend_Audit_Report_Updated.md`, `PHASE0_BASELINE_REPORT.md`, client PDF (backend-relevant parts), and the live repository.
> No frontend changes. No `BookingStateMachine` / Phase 2 work.

---

## 1. Previous payment architecture

- A booking stored a single `Booking.paymentTransactionId` plus aggregate strings
  (`paymentStatus`, `paymentType`) and `Double` money fields (`paidAmount`, `advanceAmount`,
  `remainingAmount`, `totalAmount`).
- Checkout: `PaymentService.createCheckoutSession` computed the amount from the booking,
  created a Stripe Checkout Session, and stored nothing server-side except the returned
  session id in the Stripe metadata (`bookingId`, `paymentType`).
- Confirmation: `handleWebhook` (`checkout.session.completed`) and the authenticated
  `confirmCheckoutSession` fallback both called `applyCompletedCheckoutSession`, which:
  overwrote `Booking.paymentTransactionId`, set aggregate strings/amounts directly, then
  `finalizePaidBooking` generated an invoice and called remote-session provisioning.
- Refunds: `RefundService.approveAndExecute` called `StripeRefundGateway.refund(booking, amount, key)`,
  which resolved the *single* `Booking.paymentTransactionId` and refunded against that one
  PaymentIntent.
- Defects this caused: a REMAINING payment overwrote the ADVANCE session reference (audit
  C4), so a full split refund could attempt 100 against a PaymentIntent that captured only
  70; and every PAID booking (including on-site REMAINING settlements) was routed through
  remote-session provisioning, which throws for non-remote bookings (Phase 0 discovery).
- Unsafe admin endpoints `PUT /api/bookings/{id}/payment-success/{txn}` and
  `.../remaining-payment-success/{txn}` marked bookings paid from a caller-supplied id with
  no Stripe verification (audit C2 / BUG-02).

## 2. New payment architecture

```
Booking 1 ──< N PaymentTransaction (authoritative financial history)
                    │
                    └──< N PaymentRefund   (one per refund execution per transaction)
RefundRequest 1 ──< N PaymentRefund
```

- Every Stripe Checkout creation writes a `PaymentTransaction` row.
- The webhook and the authenticated confirmation fallback converge on **one** finalization
  method and are idempotent per Stripe session.
- Stripe amount/currency are validated against the **ledger's** expected values, which come
  from the server-computed booking (never the browser).
- `Booking.paidAmount` is derived from successful ledger rows (with a legacy fallback for
  bookings that predate the ledger).
- Remote-session provisioning runs **only** for REMOTE bookings.
- Refunds allocate the approved amount across the booking's successful transactions,
  most-recent-first, and persist one `PaymentRefund` row per allocation.

## 3. PaymentTransaction schema

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | internal id (also embedded in Stripe metadata) |
| `booking_id` | BIGINT NOT NULL | indexed |
| `customer_id` | BIGINT NULL | from the authenticated payer |
| `payment_type` | VARCHAR(20) NOT NULL | `FULL` / `ADVANCE` / `REMAINING` (`PaymentType`) |
| `provider` | VARCHAR(20) NOT NULL | `STRIPE` (`PaymentProvider`) |
| `amount_minor` | BIGINT NOT NULL | exact integer minor units (e.g. 3000 = $30.00) |
| `currency` | VARCHAR(3) NOT NULL | ISO code, upper-case |
| `checkout_session_id` | VARCHAR(255) | unique for non-null; set when Stripe session is created |
| `payment_intent_id` | VARCHAR(255) | set when the payment succeeds |
| `status` | VARCHAR(20) NOT NULL | `PaymentTransactionStatus` |
| `created_at` / `updated_at` | TIMESTAMP NOT NULL | audit |
| `completed_at` | TIMESTAMP NULL | set when SUCCEEDED |

`PaymentTransactionStatus`: `INITIATED`, `CHECKOUT_CREATED`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`.
Money is **never** a `Double` in the ledger; existing `Booking` `Double` fields are only
converted at the service boundary (documented legacy boundary).

## 4. PaymentRefund / refund-allocation schema

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `refund_request_id` | BIGINT NOT NULL | indexed → `RefundRequest` |
| `payment_transaction_id` | BIGINT NOT NULL | indexed → `PaymentTransaction` |
| `provider_refund_id` | VARCHAR(255) | unique for non-null (Stripe refund id) |
| `payment_intent_id` | VARCHAR(255) | captured PaymentIntent refunded |
| `amount_minor` | BIGINT NOT NULL | exact minor units for this allocation |
| `currency` | VARCHAR(3) NOT NULL | |
| `status` | VARCHAR(20) NOT NULL | `PaymentRefundStatus` = `PENDING`/`SUCCEEDED`/`FAILED`/`CANCELLED` |
| `idempotency_key` | VARCHAR(160) NOT NULL UNIQUE | `gos-refund-<requestId>-<transactionId>` |
| `created_at` / `updated_at` | TIMESTAMP NOT NULL | |
| `completed_at` | TIMESTAMP NULL | |

## 5. Checkout creation flow

`POST /api/payments/create-checkout-session` → `PaymentService.createCheckoutSession`:

1. Load booking; verify payer == booking customer.
2. Normalize/validate payment type and payment stage (unchanged business rules).
3. Record UK early-service consent (unchanged).
4. Compute `amountMinor` from the **booking** and persist a `PaymentTransaction`
   (`status = INITIATED`) with a `paymentTransactionId`.
5. Create the Stripe Checkout Session; pass `bookingId`, `paymentType`,
   `paymentTransactionId` as metadata.
6. On success set `checkoutSessionId` + `status = CHECKOUT_CREATED`; on failure set
   `status = FAILED` and rethrow.

Consumer-visible response (`url`, `sessionId`) is unchanged.

## 6. Webhook flow

`POST /api/payments/webhook` → `PaymentService.handleWebhook`:

1. Verify the Stripe signature (`Webhook.constructEvent`).
2. `checkout.session.completed` → `applyCompletedCheckoutSession(session, null)`.
3. `refund.updated` → find the `PaymentRefund` by `providerRefundId`, update its status, and
   recompute the parent `RefundRequest` status from succeeded-amount sums.

`applyCompletedCheckoutSession` (shared with the fallback):
resolve `PaymentTransaction` by `checkoutSessionId` (fallback: metadata id, then a
ledger-recovery row for legacy bookings) → verify booking association → verify payment type
→ if already `SUCCEEDED` return idempotently → `validatePaymentStage` → verify Stripe
amount/currency against the ledger → mark `SUCCEEDED`, store `paymentIntentId` → derive the
booking aggregate → notify → finalize (invoice; remote provisioning only if REMOTE).

## 7. Confirmation fallback flow

`GET /api/payments/confirm-checkout-session` retrieves the Stripe session server-side,
verifies `paymentStatus == paid` and customer ownership, then calls the **same**
`applyCompletedCheckoutSession`. Webhook and fallback therefore cannot diverge or
double-finalize: a session becomes `SUCCEEDED` at most once.

## 8. Booking payment aggregation logic

- On success the transaction is persisted, then
  `sumSuccessfulAmountMinorByBookingId(bookingId)` provides the booking's paid total.
- `FULL` → `paymentStatus = PAID`, `bookingStatus = PAYMENT_COMPLETED`.
- `ADVANCE` → `PARTIALLY_PAID`, `ASSIGNMENT_PENDING`.
- `REMAINING` → `PAID`, `remainingAmount = 0`, `SERVICE_COMPLETED`.
- `Booking.paidAmount` is set from the ledger sum when ledger data exists; for legacy
  bookings with no ledger rows it falls back to the server-computed expected amount.
  Replay-safe: an identical session is never applied twice, so `paidAmount` cannot double.

## 9. Advance + remaining payment example (30 + 70 = 100)

```
Booking total = 10000 minor
PaymentTransaction #1: type=ADVANCE   amount=3000  checkout=cs_advance   status=SUCCEEDED
PaymentTransaction #2: type=REMAINING amount=7000  checkout=cs_remaining status=SUCCEEDED
```
Both rows permanently exist; #2 does not overwrite #1 (verified by
`SplitPaymentRegressionTest` and `PaymentLedgerIntegrationTest`).

## 10. Split refund example

Approved refund 100 against captures A=30 (older) and B=70 (newer):
deterministic allocation **most-recent-first**:
- `PaymentRefund` → transaction B, 7000 minor
- `PaymentRefund` → transaction A, 3000 minor

Partial 80 → B 7000 + A 1000. No transaction is ever refunded above its remaining
captured amount; `StripeRefundGatewayImpl` re-validates each slice against the
PaymentIntent's `amountReceived - amountRefunded`.

## 11. Idempotency behavior

- **Payments:** ledger row keyed by `checkout_session_id`; a `SUCCEEDED` row short-circuits
  reprocessing. Legacy bookings already `PAID` for the same session are also short-circuited.
- **Refunds:** `PaymentRefund.idempotency_key` is unique; a retried approve skips already
  `SUCCEEDED` allocations and only processes the remainder. The Stripe call also carries the
  same idempotency key. Persisted rows (not in-memory state) are the safeguard.

## 12. On-site remaining-payment bug: root cause and fix

- **Root cause:** `finalizePaidBooking` called `RemoteSessionProvisioningService.provisionAfterPayment`
  for every `PAID` booking; for a non-remote booking that method throws
  "Remote session provisioning is available only for remote bookings" — after the booking
  had already been saved `PAID`. Stripe webhooks would then retry and keep failing. The
  mocked `PaymentServiceTest` hid this.
- **Fix:** `finalizePaidBooking` now only provisions when `isRemoteService(booking)`
  (`serviceMode == REMOTE` or `remoteSessionRequired == true`); otherwise it returns the
  booking. `OnsiteRemainingPaymentRegressionTest.settlingAnOnsiteBookingMustNotAttemptRemoteProvisioning`
  passes, and `remoteFullPaymentStillAttemptsRemoteProvisioning` proves provisioning is not
  disabled for remote bookings.

## 13. Legacy Booking payment-field treatment

- `Booking.paymentTransactionId`, `paymentType`, `paymentMethod`, `paymentStatus`,
  `paidAmount` are retained for API/backwards compatibility and are still written.
- They are **no longer the source of truth**: refunds and payment finalization read the
  ledger. The legacy id is used only as a fallback idempotency check for bookings finalized
  before the ledger existed.
- No legacy fields were deleted (per the phase brief).

## 14. Legacy persisted-data migration limitations

No automatic startup backfill was added (avoided by design). Assessment:
- **SAFE TO BACKFILL (manually, with Stripe access):** a booking with exactly one Stripe
  session whose captured amount equals `paidAmount` can be reconstructed as one
  `PaymentTransaction` by reading Stripe.
- **PARTIALLY RECOVERABLE (with Stripe access):** on-site split bookings where the ADVANCE
  session id was overwritten can have the earlier capture recovered from Stripe by querying
  sessions/PaymentIntents carrying `metadata.bookingId = <id>`.
- **NOT RECOVERABLE FROM THE DATABASE ALONE:** once `Booking.paymentTransactionId` was
  overwritten, the earlier capture cannot be reconstructed without Stripe/production data.
  No financial records were fabricated.

## 15. Files created

Production:
```
src/main/java/com/geekonsites/backend/enums/PaymentType.java
src/main/java/com/geekonsites/backend/enums/PaymentProvider.java
src/main/java/com/geekonsites/backend/enums/PaymentTransactionStatus.java
src/main/java/com/geekonsites/backend/enums/PaymentRefundStatus.java
src/main/java/com/geekonsites/backend/entity/PaymentTransaction.java
src/main/java/com/geekonsites/backend/entity/PaymentRefund.java
src/main/java/com/geekonsites/backend/repository/PaymentTransactionRepository.java
src/main/java/com/geekonsites/backend/repository/PaymentRefundRepository.java
database/migrations/20260825_payment_ledger.sql
```
Tests:
```
src/test/java/com/geekonsites/backend/service/PaymentLedgerIntegrationTest.java
src/test/java/com/geekonsites/backend/service/RefundAllocationTest.java
```

## 16. Files modified

Production:
```
src/main/java/com/geekonsites/backend/service/PaymentService.java        (ledger-backed)
src/main/java/com/geekonsites/backend/service/StripeRefundGateway.java   (per-transaction)
src/main/java/com/geekonsites/backend/service/StripeRefundGatewayImpl.java
src/main/java/com/geekonsites/backend/service/RefundService.java         (allocation + ledger)
src/main/java/com/geekonsites/backend/service/BookingService.java        (removed manual pay methods)
src/main/java/com/geekonsites/backend/controller/BookingController.java  (removed manual pay endpoints)
src/main/java/com/geekonsites/backend/config/SecurityConfig.java         (removed dead matchers)
```
Tests:
```
src/test/java/com/geekonsites/backend/support/Phase0IntegrationTestSupport.java  (ledger cleanup)
src/test/java/com/geekonsites/backend/service/PaymentServiceTest.java            (ctor only)
src/test/java/com/geekonsites/backend/service/PaymentNotificationTest.java       (ctor only)
src/test/java/com/geekonsites/backend/service/RefundServiceTest.java             (ctor + gateway)
src/test/java/com/geekonsites/backend/phase0/PaymentSecurityRegressionTest.java  (un-tagged)
src/test/java/com/geekonsites/backend/service/OnsiteRemainingPaymentRegressionTest.java (un-tagged + inverse)
src/test/java/com/geekonsites/backend/service/SplitPaymentRegressionTest.java    (rewritten for ledger)
```

## 17. Tests added

- `PaymentLedgerIntegrationTest` (9): FULL row; ADVANCE row; REMAINING second row; duplicate
  webhook idempotent; webhook+fallback finalize once; amount/currency/booking/type mismatches
  rejected against the ledger.
- `RefundAllocationTest` (5): single full refund; split full refund (70+30); split partial
  (70+10); refund retry idempotent; refund above captured total rejected.
- `OnsiteRemainingPaymentRegressionTest` gained a remote-provisioning inverse test.
- `SplitPaymentRegressionTest` rewritten to assert both ledger rows persist and the ledger
  sum is 10000.

## 18. Expected-failure tests converted to normal tests

- `phase0.PaymentSecurityRegressionTest.suppliedTransactionIdMustNotMarkAnUnpaidBookingPaid` → **passing**
- `phase0.PaymentSecurityRegressionTest.suppliedTransactionIdMustNotSettleRemainingBalanceWithoutStripe` → **passing**
- `service.SplitPaymentRegressionTest.advanceAndRemainingPaymentsMustBothRemainIndividuallyAddressable` → **passing**
- `service.SplitPaymentRegressionTest.fullSplitRefundMustBeReconcilableAcrossEveryCapturedPayment` → **passing**
- `service.OnsiteRemainingPaymentRegressionTest.settlingAnOnsiteBookingMustNotAttemptRemoteProvisioning` → **passing**

(`@Tag("expected-failure")` removed from each.)

## 19. Full test results

```
mvn -B clean test                                  → Tests run: 241, Failures: 34, Errors: 0, Skipped: 0
mvn -B test -DexcludedGroups=expected-failure       → Tests run: 207, Failures: 0, Errors: 0, Skipped: 0 (BUILD SUCCESS)
```
- 0 unexpected failures.
- The 34 failures are all `@Tag("expected-failure")` (Phase 2+ areas).
- Original 171-test baseline remains green; Phase 1 added 15 passing tests.

## 20. Known remaining payment risks

1. No payment/refund `@Transactional` boundary; DB writes + Stripe calls are sequenced, not
   atomic. Idempotency is persisted, but a crash mid-allocation leaves a `PROCESSING`/`FAILED`
   request recoverable by retry. Concurrency hardening is Phase 4.
2. `createCheckoutSession` / `applyCompletedCheckoutSession` do not pessimistically lock the
   booking, so two simultaneous checkouts for the same booking type could both create rows
   (Stripe session uniqueness is enforced at the provider; the second is harmless but not
   prevented). Phase 4.
3. `refund.updated` recomputation assumes `approvedRefundAmount` is set; an early provider
   event for an unapproved request is a no-op edge case.
4. H2 does not support partial indexes; ledger uniqueness for nullable columns is enforced in
   production by the PostgreSQL migration and at runtime by lookup logic. Tests rely on
   `ddl-auto`-generated schema.
5. Legacy bookings without ledger rows still fall back to `Booking.paidAmount` for the refund
   ceiling until backfilled.

## 21. Items intentionally deferred to later phases

- `BookingStateMachine` and removal of the arbitrary status endpoint (Phase 2).
- Tracking lifecycle regression, remote-session lifecycle unification, booking-close
  prerequisite, technician reassignment/BUSY release, invoice authorization-before-mutation,
  booking-creation role restriction, duplicate-rating prevention, country validation
  (Phase 2+, per the brief's Step 30).
- Service catalog, Admin CRUD, full Flyway baseline, global `BigDecimal` migration,
  global error contract (later phases).
- Admin payment-history API (Step 23 — not added; no concrete need in Phase 1).
