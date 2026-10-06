# GeekOnSites PostgreSQL Runbook

GeekOnSites uses one PostgreSQL database behind the Spring Boot API. The website and Android app never receive database credentials and never connect to PostgreSQL directly. Both clients call the same authenticated HTTPS API.

## Production architecture

1. Website and Android call `https://<backend-domain>/api/...`.
2. Spring Boot validates JWT roles and request ownership.
3. The Render backend uses the database's private hostname.
4. PostgreSQL stores users, technicians, agents, bookings, payments, invoices, notifications, ratings, support messages, reset tokens, and push-device registrations.

Never put a PostgreSQL URL, username, or password in Vite variables, Capacitor files, the APK, or Git.

## Local configuration

Create a PostgreSQL database and application user, then set these environment variables before starting the backend:

```powershell
$env:DB_HOST="localhost"
$env:DB_PORT="5432"
$env:DB_NAME="geekonsites"
$env:DB_USERNAME="geekonsites"
$env:DB_PASSWORD="your-local-password"
$env:JWT_SECRET="a-random-secret-of-at-least-32-bytes"
.\run-local.ps1
```

Hibernate creates the current entity schema in a new empty database. Schema migrations will be versioned before production data is introduced.

## Render

`render.yaml` provisions a managed Render PostgreSQL database and injects its private host, port, database, username, and password into the backend service. Public database access is disabled by default.

### Launch checklist

1. Create the Blueprint from this repository in the Render dashboard.
2. Confirm `geekonsites-v2-backend` and `geekonsites-v2-postgres` are in the same Render region.
3. Enter every environment variable marked `sync: false`; never commit the values.
4. Use the database's internal connection details for the backend.
5. Deploy once with an empty database and confirm `/api/health` succeeds.
6. Register one test customer, create one technician and one agent, complete one US test booking and one UK test booking, and confirm all records persist after a backend restart.
7. Before public launch, upgrade PostgreSQL from the temporary free plan to a paid plan with backups. The free database expires and has no managed backups.
8. Create an exported logical backup before every production schema release.

### Viewing production data

Preferred: open the PostgreSQL resource in Render and use its Admin Apps/connection tools. For desktop inspection, use pgAdmin or DBeaver with the full external connection URL from Render's **Connect** menu and require SSL. Temporarily allow only your current public IP, then disable external access again after inspection.

Useful read-only queries:

```sql
SELECT current_database(), current_user, version();

SELECT schemaname, tablename
FROM pg_catalog.pg_tables
WHERE schemaname = 'public'
ORDER BY tablename;

SELECT id, email, role, country
FROM users
ORDER BY id DESC
LIMIT 50;

SELECT id, customer_email, country, currency, booking_status, payment_status,
       total_amount, paid_amount, created_at
FROM bookings
ORDER BY id DESC
LIMIT 50;

SELECT country, currency, COUNT(*) AS bookings,
       COALESCE(SUM(paid_amount), 0) AS revenue
FROM bookings
GROUP BY country, currency
ORDER BY country;
```

Do not edit payment, invoice, password, role, or booking-status rows manually. Use the admin/API workflow so audit behavior and related records remain consistent.

### Backups

Paid Render PostgreSQL provides managed recovery features. Also keep periodic encrypted logical exports with `pg_dump`. Test restoration into a separate database before launch and after major schema changes.
