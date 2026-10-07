-- =============================================================================
-- PHASE 9 — durable retry state for critical external side effects
-- =============================================================================
-- Adds bounded, crash-safe retry bookkeeping to the two operations that already
-- persist an operational status:
--   * payment_transactions.reversal_*  (excess-capture Stripe reversal)
--   * bookings.remote_provisioning_*   (Google Calendar / Meet provisioning)
--
-- Purely additive and idempotent. No existing row/value is mutated or deleted.
-- Backoff/attempt caps are applied by the scheduled recovery worker, not here.
-- =============================================================================

alter table payment_transactions add column if not exists reversal_attempts integer not null default 0;
alter table payment_transactions add column if not exists reversal_next_attempt_at timestamp(6);

alter table bookings add column if not exists remote_provisioning_attempts integer not null default 0;
alter table bookings add column if not exists remote_provisioning_next_attempt_at timestamp(6);

-- Speeds up the bounded recovery scans.
create index if not exists idx_payment_tx_reversal_recovery
    on payment_transactions (reversal_status, reversal_next_attempt_at)
    where excess = true;

create index if not exists idx_bookings_remote_recovery
    on bookings (remote_session_status, remote_provisioning_next_attempt_at)
    where remote_session_required = true;
