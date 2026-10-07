ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_idempotency_key
    ON notifications(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
