ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS uk_early_service_consent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS uk_early_service_consent_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS uk_early_service_consent_text_version VARCHAR(255) NULL;
