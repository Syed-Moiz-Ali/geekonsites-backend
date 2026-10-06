CREATE TABLE IF NOT EXISTS crm_notes (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES users(id),
    agent_id BIGINT NULL REFERENCES agents(id),
    author_name VARCHAR(255) NOT NULL,
    author_role VARCHAR(32) NOT NULL,
    note_text TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_crm_notes_customer ON crm_notes(customer_id);

CREATE TABLE IF NOT EXISTS crm_follow_ups (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES users(id),
    agent_id BIGINT NULL REFERENCES agents(id),
    owner_name VARCHAR(255) NOT NULL,
    owner_role VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    internal_note TEXT NULL,
    status VARCHAR(32) NOT NULL,
    follow_up_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_crm_followup_customer ON crm_follow_ups(customer_id);
CREATE INDEX IF NOT EXISTS idx_crm_followup_due ON crm_follow_ups(status, follow_up_at);
