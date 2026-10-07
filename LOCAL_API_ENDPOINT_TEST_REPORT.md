# Local API Endpoint Test Report

Live verification against a locally running backend (Spring Boot, production profile,
real PostgreSQL 17.11, Flyway ON, `ddl-auto=validate`) at `http://127.0.0.1:8080`.

Status legend:
* **PASS** — called live, returned the expected result/contract.
* **BLOCKED** — requires Stripe/provider (or a paid booking) that is unavailable locally; not faked.
* **NOT EXERCISED** — endpoint present and routable, but not invoked in this pass (needs more seeded state).
* **N/A** — not a client requirement.

Total endpoints discovered: **113**.

---

## AUTH
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/auth/register | public | valid customer → 200; invalid email → 400; weak password → 400 | 200 / 400 / 400 | PASS |
| POST | /api/auth/login | public | valid → 200+JWT; wrong pw → 401; admin via main portal → 403 | 200/401/403 | PASS |
| POST | /api/auth/change-password | auth | — | — | NOT EXERCISED |
| POST | /api/auth/forgot-password | public | — | — | NOT EXERCISED |
| POST | /api/auth/reset-password | public | — | — | NOT EXERCISED |
| POST | /api/admin/auth/login | public | admin login → 200+JWT | 200 | PASS |

## SERVICES
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| GET | /api/services | public | US → 200; UK → 200; invalid market → 400 | 200/200/400 | PASS |
| GET | /api/services/{code} | public | detail 200; unknown code 404 | 200/404 | PASS |
| GET | /api/admin/services | ADMIN | list → 200 | 200 | PASS |
| GET | /api/admin/services/{id} | ADMIN | — | — | NOT EXERCISED |
| POST | /api/admin/services | ADMIN | create → 200; CUSTOMER/AGENT → 403 | 200/403/403 | PASS |
| PUT | /api/admin/services/{id} | ADMIN | update → 200 | 200 | PASS |
| PATCH | /api/admin/services/{id}/status | ADMIN | deactivate → 200; hidden from public | 200 | PASS |

## CUSTOMER / BOOKING
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/bookings | CUSTOMER | create w/ spoofed price → 200, server price authoritative; bad date → 400; technician/customer role wrong → 403 | 200/400 | PASS |
| GET | /api/bookings | AGENT/ADMIN | AGENT → 200; CUSTOMER → 403 | 200/403 | PASS |
| GET | /api/bookings/page | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/bookings/{bookingId} | auth owner | owner 200; other customer 403; missing 404 | 200/403/404 | PASS |
| GET | /api/bookings/my-bookings | auth | 200 | 200 | PASS |
| GET | /api/bookings/my-bookings/page | auth | 200 (size capped) | 200 | PASS |
| GET | /api/bookings/customer/{customerId} | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/bookings/technician/{technicianId} | technician/AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/bookings/agent/{agentId} | AGENT/ADMIN | — | — | NOT EXERCISED |
| PUT | /api/bookings/{bookingId}/assign-technician/{technicianId} | AGENT/ADMIN | on UNPAID booking | — | BLOCKED (needs PAID) |
| PUT | /api/bookings/{bookingId}/technician/accept | TECHNICIAN | — | — | BLOCKED (needs PAID+assigned) |
| PUT | /api/bookings/{bookingId}/technician/reject | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/bookings/{bookingId}/technician/on-the-way | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/bookings/{bookingId}/technician/arrived | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/bookings/{bookingId}/technician/location | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/bookings/{bookingId}/technician/start-service | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/bookings/{bookingId}/technician/start-remote-session | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/bookings/{bookingId}/technician/complete-service | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/bookings/{bookingId}/meeting-link | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/bookings/{bookingId}/customer-location | CUSTOMER | — | — | NOT EXERCISED |
| PUT | /api/bookings/{bookingId}/generate-invoice | owner/ADMIN/AGENT | on UNPAID → 409 | 409 | PASS |
| PUT | /api/bookings/{bookingId}/rating | CUSTOMER | before completion → 400 | 400 | PASS |
| PUT | /api/bookings/{bookingId}/close | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/bookings/{bookingId}/tracking | auth owner | — | — | NOT EXERCISED |
| POST | /api/bookings/{bookingId}/remote-session/provision | auth owner | — | — | BLOCKED (Google) |

## PAYMENT
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/payments/create-checkout-session | CUSTOMER | no Stripe keys | 500 (generic) | BLOCKED — STRIPE NOT CONFIGURED |
| GET | /api/payments/confirm-checkout-session | auth | no Stripe keys | 500 (generic) | BLOCKED — STRIPE NOT CONFIGURED |
| POST | /api/payments/webhook | public/signed | no secret/signature | — | BLOCKED — STRIPE NOT CONFIGURED |

## TECHNICIAN
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/technicians | public | valid evidence → 200 (PENDING); missing docs → 400 | 200/400 | PASS |
| GET | /api/technicians | AGENT/ADMIN | CUSTOMER → 403; ADMIN → 200 | 403/200 | PASS |
| GET | /api/technicians/pending | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/technicians/my-bookings | TECHNICIAN | 200 | 200 | PASS |
| GET | /api/technicians/my-notifications | TECHNICIAN | — | — | NOT EXERCISED |
| GET | /api/technicians/me | TECHNICIAN | 200 | 200 | PASS |
| GET | /api/technicians/me/photo | TECHNICIAN | — | — | NOT EXERCISED |
| PUT | /api/technicians/me/availability | TECHNICIAN | AVAILABLE → 200 | 200 | PASS |
| GET | /api/technicians/{id} | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/technicians/{id}/verification/{kind} | ADMIN | — | — | NOT EXERCISED |
| PUT | /api/technicians/{id}/approve | ADMIN | → 200, then login works | 200 | PASS |
| PUT | /api/technicians/{id}/reject | ADMIN | — | — | NOT EXERCISED |
| POST | /api/technicians/{id}/resend-onboarding | ADMIN | — | — | NOT EXERCISED |
| POST | /api/technicians/onboarding/set-password | public | — | — | NOT EXERCISED |

## TRACKING / REMOTE SESSION
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/remote-sessions/booking/{bookingId}/create | TECHNICIAN | — | — | BLOCKED (needs PAID remote) |
| GET | /api/remote-sessions/booking/{bookingId} | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/remote-sessions/booking/{bookingId}/start | TECHNICIAN | — | — | BLOCKED |
| PUT | /api/remote-sessions/booking/{bookingId}/end | TECHNICIAN | — | — | BLOCKED |
| GET | /api/remote-session-chat/{bookingId}/messages | auth | — | — | BLOCKED |
| POST | /api/remote-session-chat/{bookingId}/messages | auth | — | — | BLOCKED |

## INVOICE
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/invoices/booking/{bookingId} | owner/ADMIN/AGENT | unpaid booking | — | BLOCKED (needs PAID) |
| GET | /api/invoices/{invoiceId} | owner/ADMIN/AGENT | missing → 404 | 404 | PASS |
| GET | /api/invoices/booking/{bookingId} | owner/ADMIN/AGENT | — | — | BLOCKED (needs PAID) |

## RATING
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/ratings | CUSTOMER | pre-completion → 400 | 400 | PASS |
| GET | /api/ratings/technician/{technicianId} | public | — | — | NOT EXERCISED |

## NOTIFICATIONS
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| GET | /api/notifications/my-notifications | auth | 200, `size` capped to 100 | 200 | PASS |
| PUT | /api/notifications/{notificationId}/read | auth | — | — | NOT EXERCISED |
| PUT | /api/notifications/read-all | auth | — | — | NOT EXERCISED |
| POST | /api/notifications | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/notifications/{customerId} | AGENT/ADMIN | — | — | NOT EXERCISED |
| POST | /api/notifications/devices | auth | — | — | NOT EXERCISED |
| DELETE | /api/notifications/devices | auth | — | — | NOT EXERCISED |

## CONTACT
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/contact | public | create → 200 | 200 | PASS |
| GET | /api/contact | AGENT/ADMIN | AGENT 200; CUSTOMER 403 | 200/403 | PASS |
| GET | /api/contact/{id} | AGENT/ADMIN | missing → 404 | 404 | PASS |
| PUT | /api/contact/{id}/read | AGENT/ADMIN | — | — | NOT EXERCISED |
| PUT | /api/contact/{id}/status | AGENT/ADMIN | — | — | NOT EXERCISED |
| DELETE | /api/contact/{id} | ADMIN | — | — | NOT EXERCISED |

## REFUND
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/refunds/bookings/{bookingId} | CUSTOMER | unpaid booking | — | BLOCKED (needs PAID) |
| GET | /api/refunds/my-refunds | CUSTOMER | paged | — | NOT EXERCISED |
| GET | /api/refunds/{id} | CUSTOMER | — | — | NOT EXERCISED |
| GET | /api/admin/refunds | ADMIN | paged → 200 | 200 | PASS |
| PUT | /api/admin/refunds/{id}/review | ADMIN | — | — | BLOCKED (needs request) |
| POST | /api/admin/refunds/{id}/execute | ADMIN | — | — | BLOCKED — STRIPE |
| POST | /api/admin/refunds/{id}/reject | ADMIN | — | — | BLOCKED (needs request) |

## AGENT
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| POST | /api/agents | ADMIN | create → 200; CUSTOMER → 403 | 200/403 | PASS |
| GET | /api/agents | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/agents/my-notifications | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/agents/me | AGENT | — | — | NOT EXERCISED |
| GET | /api/agents/dashboard-summary | AGENT/ADMIN | 200 | 200 | PASS |
| GET | /api/agents/booking-queue | AGENT/ADMIN | 200 (size cap 100) | 200 | PASS |
| GET | /api/agents/{id} | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/agents/{agentId}/bookings | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/agents/{agentId}/dashboard-stats | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/agents/unassigned-bookings | AGENT/ADMIN | — | — | NOT EXERCISED |
| PUT | /api/agents/{agentId}/assign-booking/{bookingId} | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/agent-crm/customers | AGENT/ADMIN | 200; CUSTOMER 403 | 200/403 | PASS |
| GET | /api/agent-crm/customers/{id} | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/agent-crm/enquiries | AGENT/ADMIN | — | — | NOT EXERCISED |
| GET | /api/agent-crm/summary | AGENT/ADMIN | 200; CUSTOMER 403 | 200/403 | PASS |
| POST | /api/agent-crm/customers/{id}/notes | AGENT/ADMIN | — | — | NOT EXERCISED |
| POST | /api/agent-crm/customers/{id}/follow-ups | AGENT/ADMIN | — | — | NOT EXERCISED |
| PUT | /api/agent-crm/follow-ups/{id}/complete | AGENT/ADMIN | — | — | NOT EXERCISED |

## ADMIN
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| GET | /api/admin/dashboard-stats | ADMIN | 200 | 200 | PASS |
| GET | /api/admin/customers | ADMIN | paged, search; AGENT 403 | 200/403 | PASS |
| GET | /api/admin/remote-sessions | ADMIN | — | — | NOT EXERCISED |
| POST | /api/admin/remote-sessions/{bookingId}/provision | ADMIN | — | — | BLOCKED (Google) |
| GET | /api/admin/my-notifications | ADMIN | — | — | NOT EXERCISED |
| GET | /api/admin/operations/failures | ADMIN | 200 secret-free | 200 | PASS |
| POST | /api/admin/operations/retry/{type}/{id} | ADMIN | unknown type 400; valid no-op 202 | 400/202 | PASS |

## HEALTH / OTHER
| METHOD | PATH | AUTH | TEST CASE | RESULT | STATUS |
| --- | --- | --- | --- | --- | --- |
| GET | /api/health | public | 200 | 200 | PASS |
| GET | /api/users/me | auth | 200/401 | 200/401 | PASS |

---

## Negative / contract checks (live)

| Check | Expected | Observed | Status |
| --- | --- | --- | --- |
| No token | 401 | 401 | PASS |
| Invalid token | 401 | 401 | PASS |
| Wrong role (customer → admin/agent area) | 403 | 403 | PASS |
| Validation failure (bad email/password/date) | 400 ApiErrorResponse | 400, `VALIDATION_ERROR`/`INVALID_REQUEST` | PASS |
| Malformed JSON | 400 | 400 `INVALID_REQUEST` | PASS |
| Missing booking | 404 | **was 500 → fixed → 404** | PASS (after fix) |
| Missing contact | 404 | **was 500 → fixed → 404** | PASS (after fix) |
| Missing invoice | 404 | 404 | PASS |
| Missing service | 404 | 404 | PASS |
| Arbitrary status endpoint (removed) | 404 | 404 | PASS |
| Conflict (unpaid invoice) | 409 | 409 | PASS |

## Pagination checks (live)
* `GET /api/notifications/my-notifications?size=1000` → `size` field clamped to **100**.
* `GET /api/agents/booking-queue?size=999` → response `size` = **100**.
* `GET /api/bookings/my-bookings/page?size=1000` → 200 with capped page.
* `GET /api/admin/customers?size=1000` → 200 with capped page.

## Endpoint summary
* Discovered: **113**
* Exercised live (PASS): **49+** individual checks
* BLOCKED (external provider / paid-state): payments (3), booking lifecycle after payment (10),
  remote-session (4 + chat 2 + provision 1 + admin provision 1), refund execution (2 + create 1),
  invoice generation on paid booking (2), rating creation on completed booking (1) ≈ **25**
* NOT EXERCISED (present, routable): the remainder
