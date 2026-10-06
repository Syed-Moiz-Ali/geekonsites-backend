CREATE TABLE IF NOT EXISTS refund_requests (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id),
    customer_id BIGINT NOT NULL REFERENCES users(id),
    country VARCHAR(2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    original_payment_amount NUMERIC(12,2) NOT NULL,
    requested_refund_amount NUMERIC(12,2) NOT NULL,
    approved_refund_amount NUMERIC(12,2),
    refund_reason VARCHAR(120) NOT NULL,
    customer_message TEXT,
    refund_status VARCHAR(32) NOT NULL,
    stripe_payment_intent_id VARCHAR(255),
    stripe_refund_id VARCHAR(255),
    requested_at TIMESTAMP NOT NULL,
    reviewed_at TIMESTAMP,
    processed_at TIMESTAMP,
    reviewed_by_admin_id BIGINT REFERENCES users(id),
    admin_note TEXT,
    rule_context TEXT NOT NULL,
    suggested_maximum_refund_amount NUMERIC(12,2) NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refund_booking ON refund_requests(booking_id);
CREATE INDEX IF NOT EXISTS idx_refund_customer ON refund_requests(customer_id);
CREATE INDEX IF NOT EXISTS idx_refund_status ON refund_requests(refund_status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_active_booking
    ON refund_requests(booking_id)
    WHERE refund_status IN ('REQUESTED', 'UNDER_REVIEW', 'APPROVED', 'PROCESSING');
