-- =============================================================================
-- PHASE 4 — excess-capture technical reversal state
-- =============================================================================
-- Additive only. ddl-auto also creates these columns; this documents/applies them
-- explicitly for production. No existing row/value is mutated or deleted.
-- =============================================================================

ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS reversal_status VARCHAR(20);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS reversal_refund_id VARCHAR(255);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS reversal_error VARCHAR(500);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS reversal_attempted_at TIMESTAMP NULL;
