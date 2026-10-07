-- =============================================================================
-- PHASE 8 — V2 integrity constraints + query indexes
-- =============================================================================
-- Adds the database-level guarantees that JPA/Hibernate cannot express portably:
--   * functional / partial unique indexes (case-insensitive email, active refund,
--     one-open-attempt, provider id uniqueness);
--   * foreign keys for the important relationships (safe, non-destructive);
--   * indexes for the columns the repositories filter/sort on most often;
--   * non-negative CHECK constraints on financial columns.
--
-- SAFETY / IDEMPOTENCY
--   * Indexes use `CREATE ... IF NOT EXISTS`.
--   * Constraints are guarded by `pg_constraint` catalog lookups and named with
--     PostgreSQL's own default FK names, so re-running on a pre-Flyway database
--     (where earlier ad-hoc SQL already created some of these) is a no-op.
--   * Foreign keys are added `NOT VALID`: they are enforced for all NEW rows
--     immediately, but existing rows are not scanned, so migration never fails on
--     legacy orphan data. A follow-up `VALIDATE CONSTRAINT` is documented in
--     DATABASE_MIGRATION_RUNBOOK.md once data is reconciled.
--   * This migration NEVER mutates or deletes existing data.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Functional / partial unique indexes
-- -----------------------------------------------------------------------------
create unique index if not exists uq_users_email_lower
    on users (lower(email));
create unique index if not exists uq_technicians_company_email_lower
    on technicians (lower(company_email)) where company_email is not null;
create unique index if not exists uq_notification_idempotency_key
    on notifications (idempotency_key) where idempotency_key is not null;
create unique index if not exists uq_refund_active_booking
    on refund_requests (booking_id)
    where refund_status in ('REQUESTED','UNDER_REVIEW','APPROVED','PROCESSING');
create unique index if not exists uq_payment_tx_checkout_session
    on payment_transactions (checkout_session_id) where checkout_session_id is not null;
create unique index if not exists uq_payment_tx_payment_intent
    on payment_transactions (payment_intent_id) where payment_intent_id is not null;
create unique index if not exists uq_payment_refund_provider
    on payment_refunds (provider_refund_id) where provider_refund_id is not null;

-- -----------------------------------------------------------------------------
-- Query indexes for repository filters / sorts
-- -----------------------------------------------------------------------------
create index if not exists idx_bookings_customer on bookings (customer_id);
create index if not exists idx_bookings_technician on bookings (technician_id);
create index if not exists idx_bookings_agent on bookings (agent_id);
create index if not exists idx_bookings_service on bookings (service_id);
create index if not exists idx_bookings_status on bookings (booking_status);
create index if not exists idx_bookings_payment_status on bookings (payment_status);
create index if not exists idx_bookings_created_at on bookings (created_at);
create index if not exists idx_invoices_customer on invoices (customer_id);
create index if not exists idx_invoices_technician on invoices (technician_id);
create index if not exists idx_ratings_customer on ratings (customer_id);
create index if not exists idx_ratings_technician on ratings (technician_id);
create index if not exists idx_notifications_customer on notifications (customer_id);
create index if not exists idx_notifications_technician on notifications (technician_id);
create index if not exists idx_notifications_booking on notifications (booking_id);
create index if not exists idx_technicians_availability on technicians (availability_status);
create index if not exists idx_technicians_verification on technicians (verification_status);
create index if not exists idx_technicians_personal_email on technicians (personal_email);
create index if not exists idx_payment_refund_status on payment_refunds (status);
create index if not exists idx_remote_chat_sender on remote_chat_messages (sender_user_id);

-- -----------------------------------------------------------------------------
-- Foreign keys (NOT VALID; enforced for new rows, legacy rows not scanned)
-- -----------------------------------------------------------------------------
do $$
declare
    fk record;
begin
    for fk in
        select * from (values
            ('bookings', 'customer_id', 'users', 'bookings_customer_id_fkey'),
            ('bookings', 'technician_id', 'technicians', 'bookings_technician_id_fkey'),
            ('bookings', 'agent_id', 'agents', 'bookings_agent_id_fkey'),
            ('bookings', 'service_id', 'services', 'bookings_service_id_fkey'),
            ('invoices', 'booking_id', 'bookings', 'invoices_booking_id_fkey'),
            ('ratings', 'booking_id', 'bookings', 'ratings_booking_id_fkey'),
            ('payment_transactions', 'booking_id', 'bookings', 'payment_transactions_booking_id_fkey'),
            ('payment_transactions', 'customer_id', 'users', 'payment_transactions_customer_id_fkey'),
            ('payment_refunds', 'refund_request_id', 'refund_requests', 'payment_refunds_refund_request_id_fkey'),
            ('payment_refunds', 'payment_transaction_id', 'payment_transactions', 'payment_refunds_payment_transaction_id_fkey'),
            ('refund_requests', 'booking_id', 'bookings', 'refund_requests_booking_id_fkey'),
            ('refund_requests', 'customer_id', 'users', 'refund_requests_customer_id_fkey'),
            ('refund_requests', 'reviewed_by_admin_id', 'users', 'refund_requests_reviewed_by_admin_id_fkey'),
            ('remote_chat_messages', 'booking_id', 'bookings', 'remote_chat_messages_booking_id_fkey'),
            ('remote_chat_messages', 'sender_user_id', 'users', 'remote_chat_messages_sender_user_id_fkey'),
            ('notifications', 'booking_id', 'bookings', 'notifications_booking_id_fkey')
        ) as t(child_table, child_column, parent_table, constraint_name)
    loop
        if not exists (select 1 from pg_constraint where conname = fk.constraint_name) then
            execute format(
                'alter table if exists %I add constraint %I foreign key (%I) references %I (id) not valid',
                fk.child_table, fk.constraint_name, fk.child_column, fk.parent_table
            );
        end if;
    end loop;
end $$;

-- -----------------------------------------------------------------------------
-- Financial non-negativity checks (guarded; added NOT VALID so legacy rows are
-- never scanned and migration cannot fail on pre-existing data).
-- -----------------------------------------------------------------------------
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_payment_tx_amount_nonneg') then
        alter table payment_transactions
            add constraint ck_payment_tx_amount_nonneg check (amount_minor >= 0) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_payment_refund_amount_nonneg') then
        alter table payment_refunds
            add constraint ck_payment_refund_amount_nonneg check (amount_minor >= 0) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_service_price_amount_nonneg') then
        alter table service_prices
            add constraint ck_service_price_amount_nonneg check (amount_minor >= 0) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_service_addons_amount_nonneg') then
        alter table service_addons
            add constraint ck_service_addons_amount_nonneg
                check (usd_amount_minor >= 0 and gbp_amount_minor >= 0) not valid;
    end if;
end $$;
