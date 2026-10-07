-- =============================================================================
-- PHASE 6 — service catalog, pricing and add-ons
-- =============================================================================
-- Additive only. ddl-auto also creates these tables; this documents/applies them
-- explicitly for production. Data seeding is handled idempotently by
-- ServiceCatalogSeeder (to be folded into the future versioned Flyway baseline,
-- together with the seed data).
-- =============================================================================

CREATE TABLE IF NOT EXISTS services (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(120) NOT NULL,
    name         VARCHAR(200) NOT NULL,
    description  VARCHAR(1000) NULL,
    service_mode VARCHAR(20)  NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INTEGER      NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uq_services_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS service_prices (
    id            BIGSERIAL PRIMARY KEY,
    service_id    BIGINT      NOT NULL REFERENCES services(id),
    currency      VARCHAR(3)  NOT NULL,
    amount_minor  BIGINT      NOT NULL,
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    CONSTRAINT uq_service_price_currency UNIQUE (service_id, currency)
);
CREATE INDEX IF NOT EXISTS idx_service_price_service ON service_prices(service_id);

CREATE TABLE IF NOT EXISTS service_addons (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(120) NOT NULL,
    name              VARCHAR(200) NOT NULL,
    usd_amount_minor  BIGINT       NOT NULL,
    gbp_amount_minor  BIGINT       NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INTEGER      NULL,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uq_service_addons_code UNIQUE (code)
);

-- Link bookings to the catalog; historical bookings without service_id keep their snapshots.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS service_id BIGINT NULL REFERENCES services(id);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS service_code_snapshot VARCHAR(120) NULL;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS service_name_snapshot VARCHAR(200) NULL;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS service_mode_snapshot VARCHAR(20) NULL;
