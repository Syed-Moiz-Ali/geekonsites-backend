# Local Backend Setup & E2E Guide

How to run the GeekOnSites Spring Boot backend locally against **real PostgreSQL**
with Flyway as the schema authority, and verify it end-to-end over HTTP.

> Do not use H2 for the primary live run. H2 is only used by the automated test suite.

---

## 1. Prerequisites

| Tool | Version used for verification | Notes |
| --- | --- | --- |
| JDK | 17 | `java -version` |
| Maven | 3.9.x | `mvn -v` |
| PostgreSQL | 16 or 17 | server only is fine (no `psql` needed) |
| curl | any | for the smoke scripts |

The repo ships **no Docker requirement**. If you have no PostgreSQL, you can obtain a
real, disposable PostgreSQL without admin rights using the zonky binaries
(`io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64` or the Linux/mac
equivalent), extract `bin/`, then run `initdb` + `postgres` directly.

---

## 2. Create a disposable local database

Never point this at Render/Railway production. Example (adjust path/port):

```powershell
# 1) init a throwaway cluster (trust auth, local only)
& <pg>/bin/initdb -D C:\temp\gos-pg\data -U gos -A trust -E UTF8 --no-locale
# 2) start it on a non-default port
& <pg>/bin/postgres -D C:\temp\gos-pg\data -p 55432
```

Create the app role + database (via any SQL client, or the JDBC helper pattern):

```sql
CREATE ROLE geekonsites_e2e LOGIN PASSWORD 'e2e_local_pw';
CREATE DATABASE geekonsites_local_e2e OWNER geekonsites_e2e;
```

---

## 3. Environment variables

```powershell
$env:SPRING_PROFILES_ACTIVE = "production"   # Flyway ON + ddl-auto=validate
$env:DB_HOST="127.0.0.1"; $env:DB_PORT="55432"
$env:DB_NAME="geekonsites_local_e2e"
$env:DB_USERNAME="geekonsites_e2e"; $env:DB_PASSWORD="e2e_local_pw"
$env:JWT_SECRET="local-e2e-jwt-secret-0123456789abcdef"   # >= 32 bytes
$env:ADMIN_EMAIL="admin@example.test"                      # one-time admin bootstrap
$env:ADMIN_PASSWORD="LocalE2eAdmin123!"                    # >= 12 chars
$env:FIREBASE_ENABLED="false"
$env:GOOGLE_CALENDAR_ENABLED="false"
$env:MAIL_HOST="localhost"; $env:MAIL_PORT="2525"
$env:PORT="8080"
$env:RECOVERY_ENABLED="true"
$env:STRIPE_SECRET_KEY=""    # leave empty unless you have TEST keys
```

`ADMIN_EMAIL`/`ADMIN_PASSWORD` are used **once** at startup by
`AdminAccountInitializer` to create the admin account (main login rejects ADMIN;
use `POST /api/admin/auth/login`).

---

## 4. Spring profile & Flyway behaviour

* `production` profile → `spring.flyway.enabled=true`,
  `spring.jpa.hibernate.ddl-auto=validate`.
* On a **fresh** DB, Flyway applies `V1..V4` from
  `src/main/resources/db/migration/` and the app starts.
* On an **already-migrated** DB, Flyway validates and does **not** re-run
  (`baseline-on-migrate` + history table). Hibernate only validates.
* The catalog seeder (`ServiceCatalogSeeder`) is idempotent — it seeds 34 services
  and 68 add-ons and does not duplicate on restart.

---

## 5. Start the backend

```powershell
mvn -B -o -DskipTests package       # or: mvn -B package
java -jar target/geekonsites-backend-0.0.1-SNAPSHOT.jar
```

Wait for `Started GeekOnSitesApplication`. Startup must show Flyway validating/
migrating and no Hibernate schema mutation.

---

## 6. Verify health

```powershell
curl.exe http://127.0.0.1:8080/api/health
# GeekOnSites Backend API is running successfully  (HTTP 200)
```

---

## 7. Run the smoke script

```bash
BASE=http://127.0.0.1:8080 ./scripts/local-api-smoke.sh
```

Windows:

```powershell
$env:BASE="http://127.0.0.1:8080"; ./scripts/local-api-smoke.ps1
```

Optional larger flow (needs admin env):

```powershell
$env:ADMIN_EMAIL="admin@example.test"; $env:ADMIN_PASSWORD="LocalE2eAdmin123!"
./scripts/local-api-e2e.ps1
```

---

## 8. Run the full test suite

```powershell
mvn -B clean test
mvn -B verify
```

The PostgreSQL migration-validation test (`PostgresMigrationValidationTest`) is skipped
unless `GOS_POSTGRES_TEST_URL` is set. To run it against the local cluster:

```powershell
$env:GOS_POSTGRES_TEST_URL="jdbc:postgresql://127.0.0.1:55432/postgres"
$env:GOS_POSTGRES_TEST_USER="gos"
$env:GOS_POSTGRES_TEST_PASSWORD="x"
mvn -B -Dtest=PostgresMigrationValidationTest test
```

---

## 9. Reset the disposable local DB

```sql
DROP DATABASE IF EXISTS geekonsites_local_e2e;
CREATE DATABASE geekonsites_local_e2e OWNER geekonsites_e2e;
```

Then restart the app — Flyway rebuilds the schema from V1.

---

## 10. External providers

| Provider | Local behaviour |
| --- | --- |
| Stripe | Without test keys, `POST /api/payments/create-checkout-session` fails (500). Payment-dependent flows (assignment, tracking, completion, invoice, rating, refund execution) are **BLOCKED** locally. Use Stripe TEST keys + webhook forwarding to exercise them. |
| Google Calendar | `GOOGLE_CALENDAR_ENABLED=false`; remote provisioning persists `FAILED`/`PENDING` state and the recovery worker retries. No fake success. |
| Firebase push | `FIREBASE_ENABLED=false`; in-app notification records are still written; push is best-effort. |
| Email | No SMTP in local; sends are async and do not block business flows. |

Never introduce a permanent mock provider into production code to make locals pass.
