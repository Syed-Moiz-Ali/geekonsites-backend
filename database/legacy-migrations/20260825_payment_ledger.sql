-- =============================================================================
-- PHASE 1 — payment transaction ledger + refund execution ledger
-- =============================================================================
-- PostgreSQL migration. The application also defines these tables via JPA
-- (ddl-auto=update) so a fresh database works without running this file; this
-- migration exists to add the DB-level constraints/indexes that JPA cannot
-- express portably, and to document the intended production schema.
--
-- H2 (used by the test suite) does NOT support partial indexes (`... WHERE ...`).
-- The test schema is generated from the JPA entities, which is why the partial
-- unique indexes below are production-only. This difference is intentional and
-- documented in PHASE1_PAYMENT_LEDGER_REPORT.md.
--
-- SAFETY: this migration only CREATES new objects. It never mutates or deletes
-- existing booking/payment data, so it is safe to run on a live database.
-- =============================================================================

CREATE TABLE IF NOT EXISTS payment_transactions (
    id                  BIGSERIAL PRIMARY KEY,
    booking_id          BIGINT       NOT NULL REFERENCES bookings(id),
    customer_id         BIGINT       NULL REFERENCES users(id),
    payment_type        VARCHAR(20)  NOT NULL,
    provider            VARCHAR(20)  NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    checkout_session_id VARCHAR(255) NULL,
    payment_intent_id   VARCHAR(255) NULL,
    status              VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    completed_at        TIMESTAMP    NULL
);

CREATE INDEX IF NOT EXISTS idx_payment_tx_booking ON payment_transactions(booking_id);
CREATE INDEX IF NOT EXISTS idx_payment_tx_status  ON payment_transactions(status);

-- A Stripe session/intent may only ever map to one ledger row (multiple NULLs are
-- permitted, which is required while a row is still INITIATED/before capture).
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_tx_checkout_session
    ON payment_transactions(checkout_session_id) WHERE checkout_session_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_tx_payment_intent
    ON payment_transactions(payment_intent_id) WHERE payment_intent_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS payment_refunds (
    id                     BIGSERIAL PRIMARY KEY,
    refund_request_id      BIGINT       NOT NULL REFERENCES refund_requests(id),
    payment_transaction_id BIGINT       NOT NULL REFERENCES payment_transactions(id),
    provider_refund_id     VARCHAR(255) NULL,
    payment_intent_id      VARCHAR(255) NULL,
    amount_minor           BIGINT       NOT NULL,
    currency               VARCHAR(3)   NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    idempotency_key        VARCHAR(160) NOT NULL,
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP    NOT NULL,
    completed_at           TIMESTAMP    NULL
);

CREATE INDEX IF NOT EXISTS idx_payment_refund_request ON payment_refunds(refund_request_id);
CREATE INDEX IF NOT EXISTS idx_payment_refund_tx      ON payment_refunds(payment_transaction_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_refund_provider
    ON payment_refunds(provider_refund_id) WHERE provider_refund_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_refund_idempotency
    ON payment_refunds(idempotency_key);
