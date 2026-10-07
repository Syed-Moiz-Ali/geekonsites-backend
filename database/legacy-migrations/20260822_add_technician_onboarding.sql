-- Non-destructive technician company-email and onboarding support.
ALTER TABLE technicians ADD COLUMN IF NOT EXISTS personal_email VARCHAR(255);
ALTER TABLE technicians ADD COLUMN IF NOT EXISTS company_email VARCHAR(255);
ALTER TABLE technicians ADD COLUMN IF NOT EXISTS onboarding_status VARCHAR(32);
ALTER TABLE technicians ADD COLUMN IF NOT EXISTS company_email_assigned_at TIMESTAMPTZ;
ALTER TABLE technicians ADD COLUMN IF NOT EXISTS onboarding_email_sent_at TIMESTAMPTZ;
ALTER TABLE technicians ADD COLUMN IF NOT EXISTS password_setup_completed_at TIMESTAMPTZ;

UPDATE technicians SET personal_email = email WHERE personal_email IS NULL;
UPDATE technicians SET onboarding_status = 'NOT_STARTED' WHERE onboarding_status IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_technicians_company_email_lower
    ON technicians (LOWER(company_email)) WHERE company_email IS NOT NULL;

CREATE TABLE IF NOT EXISTS technician_onboarding_tokens (
    id BIGSERIAL PRIMARY KEY,
    technician_id BIGINT NOT NULL REFERENCES technicians(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_technician_onboarding_technician
    ON technician_onboarding_tokens (technician_id);
