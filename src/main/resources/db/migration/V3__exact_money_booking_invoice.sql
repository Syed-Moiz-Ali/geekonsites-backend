-- =============================================================================
-- PHASE 8 — V3 exact minor-unit money for bookings + invoices
-- =============================================================================
-- EXPAND step of the expand/backfill/switch migration. It is purely additive and
-- non-destructive:
--   * adds authoritative BIGINT `*_minor` columns next to the legacy Double
--     columns (the legacy columns are retained, deprecated, read-only mirrors);
--   * backfills every existing row from the legacy Double value using exact
--     round-half-up to the nearest minor unit;
--   * adds non-negative checks (NOT VALID, so legacy rows are never scanned).
--
-- The legacy Double columns are intentionally NOT dropped here (see the Phase 8
-- safety rule). Once the application has run on the new columns and a contract
-- migration is separately approved, a later migration may drop them.
--
-- Backfill semantics: USD/GBP are two-decimal currencies, so
--   round(major * 100)  ==  exact minor units for every representable booking value.
-- `round(numeric)` (not `round(double precision)`) is used to avoid binary
-- floating-point drift before rounding.
-- =============================================================================

-- ---------------------------------------------------------------- bookings ----
alter table bookings add column if not exists base_amount_minor bigint;
alter table bookings add column if not exists addons_amount_minor bigint;
alter table bookings add column if not exists protection_amount_minor bigint;
alter table bookings add column if not exists platform_fee_minor bigint;
alter table bookings add column if not exists total_amount_minor bigint;
alter table bookings add column if not exists advance_amount_minor bigint;
alter table bookings add column if not exists remaining_amount_minor bigint;
alter table bookings add column if not exists paid_amount_minor bigint;

update bookings set base_amount_minor       = round((base_amount::numeric) * 100)::bigint       where base_amount_minor is null       and base_amount is not null;
update bookings set addons_amount_minor     = round((addons_amount::numeric) * 100)::bigint      where addons_amount_minor is null     and addons_amount is not null;
update bookings set protection_amount_minor = round((protection_amount::numeric) * 100)::bigint  where protection_amount_minor is null and protection_amount is not null;
update bookings set platform_fee_minor      = round((platform_fee::numeric) * 100)::bigint       where platform_fee_minor is null      and platform_fee is not null;
update bookings set total_amount_minor      = round((total_amount::numeric) * 100)::bigint       where total_amount_minor is null      and total_amount is not null;
update bookings set advance_amount_minor    = round((advance_amount::numeric) * 100)::bigint     where advance_amount_minor is null    and advance_amount is not null;
update bookings set remaining_amount_minor  = round((remaining_amount::numeric) * 100)::bigint   where remaining_amount_minor is null  and remaining_amount is not null;
update bookings set paid_amount_minor       = round((paid_amount::numeric) * 100)::bigint        where paid_amount_minor is null       and paid_amount is not null;

-- ---------------------------------------------------------------- invoices ----
alter table invoices add column if not exists amount_minor bigint;
alter table invoices add column if not exists paid_amount_minor bigint;

update invoices set amount_minor      = round((amount::numeric) * 100)::bigint      where amount_minor is null      and amount is not null;
update invoices set paid_amount_minor = round((paid_amount::numeric) * 100)::bigint where paid_amount_minor is null and paid_amount is not null;

-- ------------------------------------------------- non-negative guard rails ----
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_bookings_money_nonneg') then
        alter table bookings add constraint ck_bookings_money_nonneg check (
            (base_amount_minor       is null or base_amount_minor       >= 0) and
            (addons_amount_minor     is null or addons_amount_minor     >= 0) and
            (protection_amount_minor is null or protection_amount_minor >= 0) and
            (platform_fee_minor      is null or platform_fee_minor      >= 0) and
            (total_amount_minor      is null or total_amount_minor      >= 0) and
            (advance_amount_minor    is null or advance_amount_minor    >= 0) and
            (remaining_amount_minor  is null or remaining_amount_minor  >= 0) and
            (paid_amount_minor       is null or paid_amount_minor       >= 0)
        ) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_invoices_money_nonneg') then
        alter table invoices add constraint ck_invoices_money_nonneg check (
            (amount_minor      is null or amount_minor      >= 0) and
            (paid_amount_minor is null or paid_amount_minor >= 0)
        ) not valid;
    end if;
end $$;
