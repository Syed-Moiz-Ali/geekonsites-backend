-- =============================================================================
-- PHASE 3 — Stripe payment lifecycle hardening columns
-- =============================================================================
-- Small, additive migration. The application also creates these columns via JPA
-- (ddl-auto=update); this file documents and applies them explicitly for production.
--
-- SAFETY: only ADDs columns to payment_transactions. No existing row or value is
-- mutated or deleted.
-- =============================================================================

-- Hosted Stripe Checkout URL for an active attempt (duplicate-checkout reuse).
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS checkout_url VARCHAR(1024);
-- Authoritative Stripe Checkout expiry (stale-attempt detection).
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS checkout_expires_at TIMESTAMP NULL;
-- True when a genuine capture exceeds the booking obligation (quarantined excess).
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS excess BOOLEAN NOT NULL DEFAULT FALSE;

-- Speeds up duplicate-checkout lookup of the latest active attempt.
CREATE INDEX IF NOT EXISTS idx_payment_tx_active_lookup
    ON payment_transactions (booking_id, payment_type, status, created_at)
    WHERE excess = FALSE;
