# Local E2E Backend Verification Report

Live verification of the GeekOnSites Spring Boot backend started locally against a
**real PostgreSQL** database, exercised over HTTP (curl), not via MockMvc/unit tests.

---

1. **Environment** — Windows (win32), PowerShell 5.1, non-elevated shell.
2. **Versions** — Java 17.0.12 (Oracle), Maven 3.9.16, **PostgreSQL 17.11** (ran a
   disposable local cluster on port 55432 using the zonky PostgreSQL binaries — no Docker,
   no admin install). curl 8.21.0. Docker **absent**; `psql`/`jq`/Stripe CLI **absent**.
   PostgreSQL (real server + JDBC driver `postgresql-42.7.4`) was used for all SQL.
3. **Database setup** — cluster `initdb` (trust auth, superuser `eos`-style local `e2e`),
   role `geekonsites_e2e`, database **`geekonsites_local_e2e`** (plus a second empty DB
   `geekonsites_local_e2e2` for the fresh-bootstrap test). No production/Railway/Render
   credentials used.
4. **Flyway result** — On the empty DB the app applied
   `V1 baseline schema`, `V2 integrity constraints and indexes`,
   `V3 exact money booking invoice`, `V4 external operation recovery` → all `success = t`.
5. **Hibernate validation result** — Startup with production profile
   (`ddl-auto=validate`) succeeded; no schema-validation error. No schema mutation by
   Hibernate.
6. **Catalog seed result** — `ServiceCatalogSeeder` seeded **34 services** and **68
   add-ons** (68 service prices across USD+GBP). (The prompt's "40/70" is stale; actual
   current counts are 34/68, used as authoritative.)
7. **Backend startup result** — `Started GeekOnSitesApplication`; Tomcat listening on 8080;
   0 ERROR / 0 Exception log lines.
8. **Endpoint inventory count** — **113** controller endpoints across 21 controllers.

9–23. **Domain results** (see `LOCAL_API_ENDPOINT_TEST_REPORT.md` for the full matrix):

* **Auth** — customer register/login (JWT), admin login via `/api/admin/auth/login`,
  agent created by admin + login, technician register → admin approve → login. All PASS.
  Missing token 401, invalid token 401, wrong role 403, admin on main portal 403.
* **Service catalog** — public US/UK listings 200, detail 200, unknown code 404, invalid
  market 400. Admin create/update/deactivate 200; deactivated service hidden from public;
  CUSTOMER/AGENT → 403.
* **Customer booking** — creation 200 with **server-authoritative price** (client sent
  `totalAmount=1.0`; booking snapshot `totalAmountMinor=4100`); snapshot code/mode/currency
  correct; invalid date 400; ownership enforced (other customer 403).
* **Payment** — **BLOCKED — STRIPE NOT CONFIGURED** (no test keys). `create-checkout-session`
  and `confirm-checkout-session` return a generic 500 (no provider detail leaked). No DB
  payment state was faked.
* **Technician** — registration 200 (`PENDING`), login blocked 403 before approval, admin
  approve 200, login 200, availability `AVAILABLE`, `my-bookings` 200.
* **Assignment / Tracking / Remote session / Completion** — **BLOCKED** (all require a PAID
  booking, which requires Stripe). Assignment on an unpaid booking was correctly rejected.
* **Invoice** — manual generation on an unpaid booking → 409; invoice uses exact minor
  snapshot and dynamic-year numbering (unit-tested); invoice lookup missing → 404.
* **Rating** — rating before completion → 400 (ownership/lifecycle enforced).
* **Notifications** — owned inbox 200, paginated, `size` capped at 100, `type` is the
  explicit `NotificationType` (e.g. `BOOKING_CREATED`).
* **Contact** — public create 200; AGENT list 200; CUSTOMER 403; missing 404.
* **Agent** — CRM summary/customers 200, dashboard-summary 200, booking-queue 200 (size cap
  100); CUSTOMER forbidden 403.
* **Admin** — customers paged 200, dashboard-stats 200, refunds paged 200, operations
  failures 200, services 200, retry 202/400; AGENT forbidden customers 403.
* **Refunds** — request/execute **BLOCKED — STRIPE** (requires a genuinely paid booking).

24. **Split-payment/refund results** — **BLOCKED — STRIPE** (cannot legitimately reach two
    captures without the provider). Covered by automated integration tests only.
25. **Validation / error-contract results** — 400 (`VALIDATION_ERROR`, `INVALID_REQUEST`,
    field errors), 401, 403, 404, 409 all return the standard `ApiErrorResponse`
    (`timestamp,status,error,code,message,path,fieldErrors`).
26. **Pagination results** — caps enforced live (see §25 of endpoint report); filters run
    in DB.
27. **Authorization negative tests** — no-token 401, invalid-token 401, wrong-role 403 all PASS.
28. **Database invariant checks** — all 0: duplicate rating per booking, duplicate invoice per
    booking, duplicate service code, duplicate service-price currency, paid-sum vs
    `paid_amount_minor` mismatch, refund over capture, BUSY-but-unapproved technician.
29. **Restart result** — restarted against the same DB: Flyway **validated 4 migrations**
    (`Current version … 4`), no re-execution; seeder produced **0** new rows; health 200;
    existing rows intact.
30. **Fresh DB recreation result** — second empty DB: Flyway migrated from zero
    (V1→V4, `Successfully applied 4 migrations`), seeded 34/68, health 200.
31. **Log review** — 0 ERROR / 0 Exception. WARNs (benign): Flyway notes PG 17.11 is newer
    than its tested max (16); explicit `PostgreSQLDialect` deprecation; SecurityConfig
    `AuthenticationProvider` notice. None are defects.
32. **Bugs discovered** (during this verification):
    * `GET /api/bookings/{missing}` → **500** (`INTERNAL_ERROR`) instead of 404.
    * `GET /api/contact/{missing}` → **500** instead of 404.
33. **Bugs fixed during verification** — both above: `BookingService.getBookingById` and
    `ContactService.getMessageById` now throw `ResponseStatusException(404)` so a missing
    resource produces the standard 404 `RESOURCE_NOT_FOUND` body. Regression test added
    (`MissingResourceContractIntegrationTest`, 2 tests). Verified live after restart: both
    now return 404. No unrelated code changed.
34. **External-provider tests blocked** —
    * **BLOCKED — STRIPE TEST CREDENTIALS NOT AVAILABLE** (all payment/refund execute/webhook).
    * **PROVIDER NOT CONFIGURED — GOOGLE CALENDAR** (remote provisioning persisted as
      `FAILED`/`PENDING`; recovery worker present).
    * **PROVIDER NOT CONFIGURED — FIREBASE** push (in-app notifications still written).
    * **EMAIL** — no SMTP; async sends do not block business flows.
35. **Remaining blockers** — the complete paid booking → assignment → tracking → completion →
    invoice → rating → refund loop cannot run locally without Stripe test mode; it is
    unverified end-to-end here (backend logic is covered by the automated suite).
36. **Final local-backend verdict** — **LOCAL E2E MOSTLY READY** (everything upstream of the
    payment provider is verified live; provider-dependent flows are honestly blocked).

---

## Final verdict

**LOCAL E2E MOSTLY READY**

* Total endpoints discovered: **113**
* Endpoints tested live: **~62** (endpoint calls + contract/negative checks)
* Passed: **62**
* Failed (remaining): **0** (the 3 initial failures were test-data issues, corrected and
  re-run; the 2 genuine 500→404 defects were fixed and re-verified)
* Blocked by external provider/environment: **~25** (Stripe / Google / Firebase / paid-state)
* Not applicable: **0**
* HTTP happy-path flows passed: **all non-provider flows** (auth, catalog, service admin,
  booking, ownership, notifications, contact, agent CRM, admin, technician approval)
* Authorization negative tests passed: **4/4**
* Validation negative tests passed: **5/5**
* Database invariant checks passed: **7/7**
* Flyway local PostgreSQL: **PASS**
* Spring Boot live startup: **PASS**
* Restart against migrated DB: **PASS**
* Fresh database bootstrap: **PASS**
* Maven test: **PASS** — 339 tests, 0 failures, 0 errors, 1 skipped
* Maven verify: **PASS** — BUILD SUCCESS

### Provider honesty
No Stripe/Google/Firebase/email success is claimed. Those are explicitly marked
**BLOCKED / NOT CONFIGURED**. No DB payment state was faked and no legacy "mark paid"
path was used.

### Cleanup
The live app instance and the disposable PostgreSQL cluster remain running locally for
review; stop them with `Stop-Process -Name java` and `pg_ctl -D <data> stop`. No source
files were left in a broken state.
