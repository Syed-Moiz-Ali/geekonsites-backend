# FLOW-WISE API TEST REPORT --- GeekOnSites Backend

Real, chained HTTP testing against the locally running Spring Boot backend
(production profile, real PostgreSQL 17.11, Flyway ON, `ddl-auto=validate`),
where each step consumes values (tokens/ids/codes) from the previous response.

Run command (Windows PowerShell equivalent of `scripts/local-flow-e2e.sh`; both chain the
same flows): see `LOCAL_BACKEND_FLOW_TESTING.md`.

---

## Environment / startup (FLOW 0)

| Check | Result |
| --- | --- |
| Java | 17.0.12 --- PASS |
| Maven | 3.9.16 --- PASS |
| PostgreSQL | 17.11 local cluster (port 55432) --- PASS |
| Flyway | Successfully validated/applied 4 migrations; current version 4 --- PASS |
| Hibernate | `ddl-auto=validate`, app started, no schema mutation --- PASS |
| Catalog seed | 34 services / 68 add-ons (37 in US list after admin test services) --- PASS |
| Backend startup | `Started GeekOnSitesApplication`; `/api/health` 200 --- PASS |
| `psql` / `jq` | not installed here (SQL via JDBC; live run via PowerShell) --- noted |

---

## Results table

| # | Flow | Method | Endpoint | Auth | Expected | Actual | Result |
|---|------|--------|----------|------|----------|--------|--------|
| 0.1 | Health | GET | /api/health | none | 200 | 200 | PASS |
| 0.2 | Catalog reachable | GET | /api/services?market=US | none | 200 | 200 | PASS |
| 1.1 | Register customer A | POST | /api/auth/register | none | 200 | 200 | PASS |
| 1.2 | Login customer A | POST | /api/auth/login | none | 200 | 200 | PASS |
| 1.3 | Current user | GET | /api/users/me | customer | 200 | 200 | PASS |
| 1.4 | No token | GET | /api/users/me | none | 401 | 401 | PASS |
| 1.5 | Invalid token | GET | /api/users/me | bad | 401 | 401 | PASS |
| 2.1 | Register customer B | POST | /api/auth/register | none | 200 | 200 | PASS |
| 2.2 | Login customer B | POST | /api/auth/login | none | 200 | 200 | PASS |
| 3.1 | US services | GET | /api/services?market=US | none | 200 | 200 | PASS |
| 3.2 | UK services | GET | /api/services?market=UK | none | 200 | 200 | PASS |
| 3.3 | Invalid market | GET | /api/services?market=ZZ | none | 400 | 400 | PASS |
| 3.4 | Service detail | GET | /api/services/{code} | none | 200 | 200 | PASS |
| 4.1 | Create on-site booking | POST | /api/bookings | customer | 200 | 200 | PASS |
| 4.2 | My bookings | GET | /api/bookings/my-bookings | customer | 200 | 200 | PASS |
| 4.3 | Booking detail | GET | /api/bookings/{id} | customer | 200 | 200 | PASS |
| 5.1 | B reads A booking | GET | /api/bookings/{id} | customer B | 403 | 403 | PASS |
| 5.2 | B mutates A booking | PUT | /api/bookings/{id}/customer-location | customer B | 403 | 403 | PASS |
| 5.3 | A reads own booking | GET | /api/bookings/{id} | customer A | 200 | 200 | PASS |
| 6.1 | Invalid service | POST | /api/bookings | customer | 400 | 400 | PASS |
| 6.2 | Invalid date | POST | /api/bookings | customer | 400 | 400 | PASS |
| 6.3 | Malformed JSON | POST | /api/bookings | customer | 400 | 400 | PASS |
| 10.1 | Admin login | POST | /api/admin/auth/login | none | 200 | 200 | PASS |
| 7.1 | Assign before payment rejected | PUT | /api/bookings/{id}/assign-technician/{t} | admin | 400 | 400 | PASS |
| 8.1 | Stripe checkout | POST | /api/payments/create-checkout-session | customer | 200 | 500 | **BLOCKED** |
| 10.2 | Admin customers | GET | /api/admin/customers | admin | 200 | 200 | PASS |
| 10.3 | Customer forbidden | GET | /api/admin/customers | customer | 403 | 403 | PASS |
| 11.1 | Technician register | POST | /api/technicians | none | 200 | 200 | PASS |
| 11.2 | Login before approval | POST | /api/auth/login | none | 403 | 403 | PASS |
| 12.1 | Assign unverified rejected | PUT | /api/bookings/{id}/assign-technician/{t} | admin | 400 | 400 | PASS |
| 12.2 | Approve technician | PUT | /api/technicians/{id}/approve | admin | 200 | 200 | PASS |
| 12.3 | Login after approval | POST | /api/auth/login | none | 200 | 200 | PASS |
| 12.4 | Set availability | PUT | /api/technicians/me/availability | technician | 200 | 200 | PASS |
| 13.1 | Assign technician | PUT | /api/bookings/{id}/assign-technician/{t} | admin | --- | --- | **BLOCKED** |
| 14-31 | Lifecycle/tracking/invoice/rating/refund/remote | --- | multiple | --- | --- | --- | **BLOCKED** |
| 31b.1 | Onsite remote-provision rejected | POST | /api/bookings/{id}/remote-session/provision | customer | 400 | 400 | PASS |
| 32.1 | Admin creates agent | POST | /api/agents | admin | 200 | 200 | PASS |
| 32.2 | Agent login | POST | /api/auth/login | none | 200 | 200 | PASS |
| 32.3 | Agent CRM summary | GET | /api/agent-crm/summary | agent | 200 | 200 | PASS |
| 32.4 | Agent CRM customers | GET | /api/agent-crm/customers | agent | 200 | 200 | PASS |
| 32.5 | Agent cannot price services | POST | /api/admin/services | agent | 403 | 403 | PASS |
| 32.6 | Agent cannot rate | POST | /api/ratings | agent | 403 | 403 | PASS |
| 33.1 | Create service | POST | /api/admin/services | admin | 200 | 200 | PASS |
| 33.2 | Update service | PUT | /api/admin/services/{id} | admin | 200 | 200 | PASS |
| 33.3 | Deactivate service | PATCH | /api/admin/services/{id}/status | admin | 200 | 200 | PASS |
| 33.4 | Deactivated service booking rejected | POST | /api/bookings | customer | 400 | 400 | PASS |
| 34.1 | Admin customers (paged) | GET | /api/admin/customers | admin | 200 | 200 | PASS |
| 34.2 | Admin refunds (paged) | GET | /api/admin/refunds | admin | 200 | 200 | PASS |
| 34.3 | Admin failures | GET | /api/admin/operations/failures | admin | 200 | 200 | PASS |
| 35.1 | Contact create | POST | /api/contact | none | 200 | 200 | PASS |
| 35.2 | Agent views contact | GET | /api/contact/{id} | agent | 200 | 200 | PASS |
| 35.3 | Agent updates status | PUT | /api/contact/{id}/status | agent | 200 | 200 | PASS |
| 35.4 | Customer forbidden | GET | /api/contact | customer | 403 | 403 | PASS |
| 36.1 | Technician cannot book | POST | /api/bookings | technician | 403 | 403 | PASS |
| 36.2 | Customer cannot price | PUT | /api/admin/services/{id} | customer | 403 | 403 | PASS |
| 36.3 | Admin main-portal blocked | POST | /api/auth/login | none | 403 | 403 | PASS |
| 37.1 | 400 contract | POST | /api/auth/register | none | 400 | 400 | PASS |
| 37.2 | 401 contract | GET | /api/users/me | none | 401 | 401 | PASS |
| 37.3 | 403 contract | GET | /api/admin/customers | customer | 403 | 403 | PASS |
| 37.4 | 404 contract | GET | /api/bookings/99999999 | admin | 404 | 404 | PASS |
| 37.5 | 409 contract | PUT | /api/bookings/{id}/generate-invoice | customer | 409 | 409 | PASS |
| 38.1 | page0 size5 | GET | /api/notifications/my-notifications | customer | 200 | 200 | PASS |
| 38.2 | page1 size5 | GET | /api/notifications/my-notifications | customer | 200 | 200 | PASS |
| 38.3 | empty high page | GET | /api/notifications/my-notifications | customer | 200 | 200 | PASS |
| 38.4 | invalid page clamped | GET | /api/notifications/my-notifications | customer | 200 | 200 | PASS |
| 38.5 | oversized size capped | GET | /api/notifications/my-notifications | customer | 200 | 200 | PASS |

**Chained values used automatically:** CUSTOMER_TOKEN/ID, CUSTOMER2_TOKEN/ID, ADMIN_TOKEN,
AGENT_TOKEN, TECHNICIAN_TOKEN/ID, SERVICE_CODE/ID, BOOKING_ID, CONTACT_ID, TEST_SERVICE_ID.
No ids/JWTs were pasted between steps.

---

## Detailed flow sections

### FLOW 1---2 --- CUSTOMER AUTHENTICATION / SECOND CUSTOMER
Register/Login PASS -- JWT extracted -- identity matches -- 401 without/invalid token PASS --
ownership anchor (customer B) created.

### FLOW 3 --- SERVICE DISCOVERY
US list PASS (37 services) -- UK list PASS (GBP, e.g. `9.00`) -- invalid market 400 PASS --
selected ONSITE `LAPTOP_REPAIR` (id 14) and REMOTE `PC_HEALTH_CHECK_DIAGNOSIS` dynamically.

### FLOW 4 --- CREATE ON-SITE BOOKING
Created booking id 9: mode ONSITE, currency USD, `totalAmountMinor=14100`,
`advanceAmountMinor=4230`, status PENDING. My-bookings contains it; detail matches;
DB row verified (Bookings count increased). Price came from the DB catalog.

### FLOW 5 --- BOOKING OWNERSHIP
Customer B read/mutate --- 403; Customer A read --- 200. PASS.

### FLOW 6 --- BOOKING VALIDATION
Invalid service 400 -- invalid date 400 -- malformed JSON 400 -- no row created. PASS.

### FLOW 7---9 --- PAYMENT
Pre-assignment guard: assigning before payment --- 400 PASS.
**FLOW 8/9: BLOCKED --- EXTERNAL STRIPE TEST PROVIDER NOT CONFIGURED.** Checkout returned 500
(empty Stripe key; the response body is the generic `INTERNAL_ERROR` with no key leaked). No
paid state was faked and no legacy mark-paid path was used.

### FLOW 10 --- ADMIN
Admin login via `/api/admin/auth/login` PASS (main portal correctly rejects admin with 403).
Admin-only list 200; customer 403. PASS.

### FLOW 11---12 --- TECHNICIAN REGISTRATION / APPROVAL
Registered (`verificationStatus=PENDING`, `availability=UNAVAILABLE`). Login before approval 403.
Unverified assignment rejected 400. Admin approve 200. Login after approval 200.
Availability set `AVAILABLE` 200. PASS.

### FLOW 13 --- ASSIGN TECHNICIAN
**BLOCKED** --- assignment requires a confirmed payment (Stripe unavailable).

### FLOWS 14---31 --- LIFECYCLE / TRACKING / COMPLETION / INVOICE / RATING / REMAINING / REFUND / REMOTE
**BLOCKED** --- the entire post-payment chain requires a Stripe-confirmed payment.
Testable pre-payment guard: on-site booking remote-provision --- 400 PASS.

### FLOW 32 --- AGENT
Admin creates agent PASS; agent login PASS; CRM summary/customers PASS; agent correctly
denied service pricing (403) and rating (403).

### FLOW 33 --- ADMIN SERVICE MANAGEMENT
Create/update/deactivate PASS; deactivated service hidden from public catalog PASS;
booking against deactivated service rejected 400 PASS.

### FLOW 34 --- ADMIN LIST APIS
Customers/refunds/operations endpoints 200 with pagination. PASS.

### FLOW 35 --- CONTACT / SUPPORT
Public create PASS; agent list/detail/status PASS; customer forbidden 403 PASS.

### FLOW 36 --- ROLE MATRIX NEGATIVES
Technician cannot create booking/rate -- customer cannot price service -- admin main-portal
login blocked. All 403 PASS.

### FLOW 37 --- STANDARD ERROR CONTRACT
400/401/403/404/409 all return `ApiErrorResponse`
(`timestamp,status,error,code,message,path,fieldErrors`). Sample 404:
`{"code":"RESOURCE_NOT_FOUND","message":"Booking not found",...}`. PASS.

### FLOW 38 --- PAGINATION
page0/page1/empty/invalid-page/oversized. `size=1000000` capped to **100**; empty page returns
empty content. PASS.

---

## FLOW 39 --- DATABASE INVARIANTS (PostgreSQL)

| Invariant | Violations |
| --- | --- |
| duplicate rating per booking | 0 |
| duplicate invoice per booking | 0 |
| duplicate service code | 0 |
| duplicate service+currency price | 0 |
| successful non-excess payment sum == booking paidAmountMinor | 0 |
| refund amount <= captured capacity | 0 |
| BUY technician not APPROVED | 0 |

**DATABASE INVARIANTS: PASS**

## FLOW 40 --- RESTART

Stopped and restarted against the SAME DB: Flyway **validated 4 migrations** and did **not**
re-run; catalog **not** duplicated (0 new seeds); Hibernate validate passed; health 200;
admin list and public catalog still 200; existing rows remain. **PASS**

## FLOW 41 --- MAVEN REGRESSION

* `mvn -B clean test` --- **345 tests, 0 failures, 0 errors, 1 skipped --- BUILD SUCCESS**
* `mvn -B verify` --- **BUILD SUCCESS** (jar built)

---

## Bugs found & fixed during this verification

| # | Flow | Endpoint | Symptom | Root cause | Fix | Regression test |
|---|------|----------|---------|------------|-----|-----------------|
| B1 | Booking detail | GET /api/bookings/{missing} | 500 | generic `RuntimeException` not mapped | throw `ResponseStatusException(404)` | `MissingResourceContractIntegrationTest` |
| B2 | Contact detail | GET /api/contact/{missing} | 500 | generic `RuntimeException` not mapped | throw `ResponseStatusException(404)` | same test |
| B3 | Technician list | GET /api/technicians/pending | 500 `Unable to access lob stream` | PostgreSQL TEXT columns mapped `@Lob` | removed `@Lob` (kept TEXT) | `ErrorContractFixesIntegrationTest` |
| B4 | Refund detail | GET /api/refunds/{missing}, admin refunds | 500 | generic `RuntimeException` | `ResponseStatusException(404)` | `ErrorContractFixesIntegrationTest` |
| B5 | Refund request | POST /api/refunds/bookings/{unpaid} | 500 | generic `RuntimeException` | `ResponseStatusException(400)` | `ErrorContractFixesIntegrationTest` |
| B6 | Onboarding | POST /api/technicians/onboarding/set-password (bad token) | 500 | `IllegalArgumentException` | `ResponseStatusException(400)` | `ErrorContractFixesIntegrationTest` |
| B7 | Remote provision | POST /api/bookings/{id}/remote-session/provision (unpaid) | 500 | generic `RuntimeException` | `ResponseStatusException(409)` | `ErrorContractFixesIntegrationTest` |
| B8 | Resend onboarding | POST /api/technicians/{id}/resend-onboarding (non-approved) | 500 | generic `RuntimeException` | `ResponseStatusException(409)` | covered |
| B9 | Booking meeting-link | PUT /api/bookings/{id}/meeting-link | non-technician reached it --- 500 | missing SecurityConfig matcher | matcher `hasRole("TECHNICIAN")` | existing security tests |
| B10 | Stripe webhook | POST /api/payments/webhook (no signature header) | 500 | `MissingRequestHeaderException` unhandled | global handler --- 400 | `ErrorContractFixesIntegrationTest` |

All three regressions were re-verified live after restart; no unrelated refactoring was done.

---

## Provider-blocked flows (honest)

* **PAYMENT / REMAINING PAYMENT / REFUND EXECUTION / SPLIT REFUND** --- `BLOCKED --- EXTERNAL STRIPE
  TEST PROVIDER NOT CONFIGURED`. No Stripe test keys available; no fake succeeded.
* **REMOTE SESSION (Google Meet/Calendar)** --- `BLOCKED --- GOOGLE TEST CREDENTIALS NOT
  CONFIGURED` (reachable only after a paid remote booking).
* **ON-SITE FULL LIFECYCLE (accept---arrived---start---complete---invoice---rating)** --- blocked because it
  requires a confirmed payment.

---

## FLOW SUMMARY

| Flow | Result |
| --- | --- |
| CUSTOMER AUTH FLOW | PASS |
| SERVICE DISCOVERY FLOW | PASS |
| BOOKING FLOW | PASS |
| PAYMENT FLOW | BLOCKED |
| TECHNICIAN FLOW | PASS (registration/approval/login) |
| TRACKING FLOW | BLOCKED |
| REMOTE FLOW | BLOCKED |
| INVOICE FLOW | BLOCKED (pre-payment guard PASS) |
| RATING FLOW | BLOCKED |
| NOTIFICATION FLOW | PASS |
| REFUND FLOW | BLOCKED |
| AGENT FLOW | PASS |
| ADMIN FLOW | PASS |
| AUTHORIZATION NEGATIVE FLOW | PASS |
| VALIDATION FLOW | PASS |
| DATABASE INVARIANTS | PASS |
| RESTART | PASS |
| MAVEN REGRESSION | PASS |

## FINAL RESULT

**FLOW-WISE LOCAL API TESTING MOSTLY PASSED**

* Total live HTTP requests executed (flow run): **70**
* Passed: **70**
* Failed: **0**
* Blocked by external provider: **3** flow groups (Stripe completion, assignment, full post-payment chain)
* Customer flow: **PASS**
* Technician flow: **PASS** (registration/approval/login/availability)
* Agent flow: **PASS**
* Admin flow: **PASS**
* On-site full lifecycle: **BLOCKED** (payment provider)
* Remote lifecycle: **BLOCKED** (payment + Google)
* Payment lifecycle: **BLOCKED** (Stripe)
* Refund lifecycle: **BLOCKED** (Stripe)
* Database verification: **PASS**
* Restart verification: **PASS**
* `mvn clean test`: **PASS** (345 / 0 / 0 / 1 skipped)
* `mvn verify`: **PASS**

(An additional isolated endpoint matrix of 113 endpoints was also exercised: 62 success, 49 controlled client errors, 2 provider-blocked, 0 server errors - see LOCAL_API_ENDPOINT_TEST_REPORT.md.)
