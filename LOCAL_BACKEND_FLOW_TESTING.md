# Local Backend Flow Testing Guide

How to run the backend locally and execute the **flow-wise** (chained) API tests, and how to
reset/rerun them. Companion to `scripts/local-flow-e2e.sh` and `FLOW_WISE_API_TEST_REPORT.md`.

---

## 1. PostgreSQL startup

Use a disposable local PostgreSQL (never production). With the zonky PostgreSQL binaries (no
Docker/admin needed):

```powershell
& <pg>\bin\initdb -D C:\temp\gos-pg\data -U gos -A trust -E UTF8 --no-locale
& <pg>\bin\postgres -D C:\temp\gos-pg\data -p 55432
```

Then create the role + database (any SQL client; here JDBC was used since the server-only
distribution has no `psql`):

```sql
CREATE ROLE geekonsites_e2e LOGIN PASSWORD 'e2e_local_pw';
CREATE DATABASE geekonsites_local_e2e OWNER geekonsites_e2e;
```

## 2. Environment configuration

```powershell
$env:SPRING_PROFILES_ACTIVE="production"     # Flyway ON + ddl-auto=validate
$env:DB_HOST="127.0.0.1"; $env:DB_PORT="55432"
$env:DB_NAME="geekonsites_local_e2e"
$env:DB_USERNAME="geekonsites_e2e"; $env:DB_PASSWORD="e2e_local_pw"
$env:JWT_SECRET="local-e2e-jwt-secret-0123456789abcdef"
$env:ADMIN_EMAIL="admin@example.test"; $env:ADMIN_PASSWORD="LocalE2eAdmin123!"
$env:FIREBASE_ENABLED="false"; $env:GOOGLE_CALENDAR_ENABLED="false"
$env:MAIL_HOST="localhost"; $env:MAIL_PORT="2525"; $env:PORT="8080"
$env:STRIPE_SECRET_KEY=""      # leave empty unless you have TEST keys
```

## 3. Backend startup

```powershell
mvn -B -o -DskipTests package
java -jar target/geekonsites-backend-0.0.1-SNAPSHOT.jar
```

Confirm: Flyway validates/migrates, Hibernate `validate` passes, `Started GeekOnSitesApplication`,
`curl http://127.0.0.1:8080/api/health` → 200.

## 4. How to create Admin / Agent local accounts

* **Admin** — created once at startup by `AdminAccountInitializer` from `ADMIN_EMAIL` +
  `ADMIN_PASSWORD` (≥ 12 chars). Login via `POST /api/admin/auth/login` (the normal
  `/api/auth/login` rejects ADMIN with 403).
* **Agent** — no public registration. Create with the admin token:
  `POST /api/agents {"name":..,"email":..,"password":..,"country":..,"city":..}`, then login
  via `/api/auth/login`.
* **Technician** — public `POST /api/technicians` with evidence data-URLs
  (`identityDocumentData`, `livePhotoData`, and for on-site `drivingLicenseData`,
  `vehicleInsuranceData`, `publicLiabilityData`). Login is blocked (403) until an admin calls
  `PUT /api/technicians/{id}/approve`.
* **Customer** — public `POST /api/auth/register`.

## 5. How to run the flow script

```bash
BASE=http://127.0.0.1:8080 \
ADMIN_EMAIL=admin@example.test ADMIN_PASSWORD=LocalE2eAdmin123! \
./scripts/local-flow-e2e.sh
```

The script auto-extracts tokens/ids/codes between steps (no manual copying) and prints
`[tag] desc / METHOD path / Expected / Actual / PASS|FAIL`. It exits non-zero on any FAIL.

## 6. Stripe TEST configuration

Set `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` to **test-mode** keys only and (optionally)
forward events with the Stripe CLI. If absent, Flows 8–9 (checkout/completion) and everything
downstream (assignment, lifecycle, invoice, rating, remaining payment, refunds, remote) are
reported `BLOCKED — EXTERNAL STRIPE TEST PROVIDER NOT CONFIGURED`. The script never fakes a
payment. Never use `sk_live_`/`rk_live_` keys.

## 7. Google test configuration (if applicable)

Set `GOOGLE_CALENDAR_ENABLED=true` with a valid refresh token to exercise remote provisioning.
Otherwise remote session flows are `BLOCKED — GOOGLE TEST CREDENTIALS NOT CONFIGURED`; the
persisted `FAILED/PENDING` provisioning state and the recovery worker remain verifiable.

## 8. How flow variables are chained

The script keeps variables such as `CUSTOMER_TOKEN`, `CUSTOMER_ID`, `CUSTOMER2_TOKEN`,
`ADMIN_TOKEN`, `AGENT_TOKEN`, `TECHNICIAN_TOKEN`, `SERVICE_CODE`, `SERVICE_ID`, `BOOKING_ID`,
`CONTACT_ID`, `TEST_SERVICE_ID`, `CHECKOUT_SESSION_ID`. Each is assigned from the JSON body of
the previous response (`jq -r` in bash; `ConvertFrom-Json` in PowerShell). Later requests send
`Authorization: Bearer $CUSTOMER_TOKEN` etc. automatically.

## 9. How to reset the disposable local DB

```sql
DROP DATABASE IF EXISTS geekonsites_local_e2e;
CREATE DATABASE geekonsites_local_e2e OWNER geekonsites_e2e;
```

Restart the backend — Flyway rebuilds from V1 and the seeder repopulates the catalog.

## 10. How to rerun a single failed flow

Run the whole script (it is idempotent — it registers fresh synthetic users each run using a
timestamp/random suffix, so no cleanup is required). To focus on one flow, copy the relevant
`FLOW n` block from `scripts/local-flow-e2e.sh` into a scratch script and run it with the same
`BASE`/`ADMIN_*` env. Because each run creates new synthetic accounts, reruns never collide.

---

### Notes
* Synthetic identities only (`*@example.test`); no real user data, no production credentials.
* Live verification here used a PowerShell equivalent because `bash`/`jq` are not installed on the
  Windows host; the canonical deliverable is the `.sh` script.
* SQL checks (invariants, `flyway_schema_history`, object inventory) were run over JDBC because the
  local PostgreSQL distribution ships no `psql`.
