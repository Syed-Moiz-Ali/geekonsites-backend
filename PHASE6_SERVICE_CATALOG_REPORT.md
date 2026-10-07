# PHASE 6 — Service Catalog, Pricing & Admin Service Management

> Scope: GeekOnSites Java / Spring Boot backend — replace hardcoded catalog/pricing with a
> database-backed Service Catalog. No frontend changes. No quote/parts/emergency/VAT scope.
> References: audit + Phase 0–5 reports, client PDF, live repository.

---

## 1. Previous hardcoded catalog architecture

Service discovery and pricing were entirely in Java: `TrustedPricingService` held
`static final Map<String, ServicePrice> SERVICES` (40 services: name → US/UK price + mode) and
`static final Map<String, AddonPrice> ADDONS` (70 add-ons). A dead `PricingService` also
existed. Admin could not manage services; every price change required a redeploy; no DB entity.

## 2. Extracted existing catalog

All 40 services and 70 add-ons with exact US/UK amounts and modes were transcribed verbatim
into `ServiceCatalogSeeder` (see §17). Example: `PC Health Check & Diagnosis` = US 29.00 /
UK 25.00 (REMOTE); `Laptop Repair` = 129.00 / 109.00 (ONSITE); `Remote IT Support` = 99.99 /
79.99. Add-ons preserve the exact values, including quirks (`gos-secure` = 29 / 24).

## 3. New Service domain model

`entity/Service` (table `services`): `id`, `code` (unique, non-null, stable), `name`,
`description`, `serviceMode` (`ServiceMode` enum, non-null), `active`, `sortOrder`,
`createdAt`, `updatedAt`.

## 4. ServicePrice model

`entity/ServicePrice` (table `service_prices`): `id`, `serviceId`, `currency` (`Currency`
enum), `amountMinor` (exact integer), timestamps. Unique `(service_id, currency)`. Chosen as a
separate price entity (not two columns on `Service`) for the client's stated future
scalability, while keeping one current price per currency.

## 5. Addon decision/model

Add-ons **are genuinely used** by booking pricing (the old `ADDONS` map fed `addonTotal`), so
they were migrated to a minimal persistent model: `entity/ServiceAddon` (table
`service_addons`): `id`, `code` (unique), `name`, `usdAmountMinor`, `gbpAmountMinor`, `active`,
`sortOrder`, timestamps. Two exact minor-unit columns are sufficient for the US/UK-only scope
(documented; a per-currency price table would be unnecessary complexity).

## 6. Service code design

Codes are stable, machine-readable, uppercase, unique, non-null: generated from the historical
name (e.g., `PC_HEALTH_CHECK_DIAGNOSIS`). `Service.code` is the business identity; `name` and
prices are Admin-editable. Admins supply a code on create (normalized) and it is immutable on
update.

## 7. Service mode authority

`Service.serviceMode` is authoritative. `TrustedPricingService` derives the mode from the
catalog; a client-supplied `BookingRequest.serviceMode` that contradicts the service is
rejected (400, "Selected service is not available for the chosen support method."). A booking
can never be created as ONSITE for a REMOTE service (and vice versa).

## 8. Country/currency mapping

Reuses Phase 5 `CountrySupport`: US → `USD`, UK → `GBP`, no fallback; unsupported market → 400.
No silently-defaulted currency.

## 9. Public service APIs

- `GET /api/services?market=US|UK` → active services with the market price.
- `GET /api/services/{code}?market=US|UK` → active service detail (404 if unknown/inactive or
  no price for that market).
Responses use `ServiceResponse` (DTO): `id, code, name, description, serviceMode, price
(decimal), currency`. No JPA entity/relationship exposure.

## 10. Admin service APIs

- `GET /api/admin/services` (all, incl. inactive), `GET /api/admin/services/{id}`
- `POST /api/admin/services` (create)
- `PUT /api/admin/services/{id}` (update name/description/mode/sortOrder/active/prices)
- `PATCH /api/admin/services/{id}/status` (activate/deactivate)
Responses use `AdminServiceResponse` with both USD and GBP prices.

## 11. Admin role rules

`/api/admin/**` requires `ROLE_ADMIN`. CUSTOMER/TECHNICIAN/AGENT are rejected (403) — verified.
The public `GET /api/services` is `permitAll`.

## 12. Active/inactive behavior

Inactive services are excluded from public discovery and cannot be selected for a new booking
(400). They remain resolvable via Admin and via `serviceId` for historical bookings. No hard
delete.

## 13. Price snapshot architecture

On booking creation the server loads the active Service + current `ServicePrice`, validates the
mode, computes fees, and **snapshots** the amounts onto `Booking` (`baseAmount`, `addonsAmount`,
`platformFee`, `totalAmount`, `advanceAmount`, `remainingAmount`, `currency`). Booking remains
authoritative for the historical payable amount; later Service price edits never change it.

## 14. Booking → Service relationship

`Booking` gained `serviceId`, `serviceCodeSnapshot`, `serviceNameSnapshot`,
`serviceModeSnapshot` (plus existing `serviceType`/`serviceMode`). Historical display does not
depend on the current Service row.

## 15. Historical booking compatibility

Legacy `Booking` fields (`serviceType`, `serviceMode`, `baseAmount`, ...) remain. `serviceType`
is set to the resolved service name for compatibility; `serviceId`/snapshots are additive and
nullable so pre-Phase-6 bookings keep working.

## 16. Legacy booking backfill strategy

Deterministic by service name (the old `serviceType` values are the seeded service names):
**SAFE TO BACKFILL** for bookings whose `serviceType` exactly matches a seeded service name.
Ambiguous/free-text values are **UNMAPPABLE**. No automatic backfill/fabrication was performed
in Phase 6 (documented; can be folded into the future migration baseline).

## 17. Initial catalog seed strategy

`ServiceCatalogSeeder` (an `ApplicationRunner`) is **idempotent**: create-if-absent by stable
code; existing rows and their Admin-edited prices are never overwritten; repeated startups do
not duplicate data. Seeding is controlled by `app.catalog.seed` (default `true`). Amounts,
modes and names are preserved exactly from the former maps.

## 18. TrustedPricingService before/after

Before: static `SERVICES`/`ADDONS` maps were the pricing authority. After: no static maps; it
orchestrates pricing over `ServiceCatalogService` (DB) + `ServiceAddonRepository` (DB) + a
platform-fee constant, returning an internal `PricingQuote` (exact minor units). The dead
`PricingService` was deleted.

## 19. Platform-fee treatment

The flat fee (US 12.00 / UK 12.00 → 1200 minor) is preserved exactly as `PLATFORM_FEE_MINOR`,
kept conceptually separate from Service base price. **CLIENT CLARIFICATION REQUIRED**: the PDF
says fees apply "according to defined pricing policy" but does not define it; no fee engine was
invented.

## 20. Protection-plan treatment

`protectionPlan` is still accepted but priced at zero (unchanged). **NEEDS CLIENT
CLARIFICATION** (billable? price/rule?) — no invented charge.

## 21. Payment compatibility

`PaymentService` prices from the **Booking snapshot** (`totalAmount`/`advanceAmount`/
`remainingAmount`), never from live `ServicePrice`. Verified: after a price change, paying an
existing booking uses its snapshotted amount.

## 22. Refund compatibility

Refunds use captured `PaymentTransaction`s + the booking aggregate (unchanged). No Service
price dependency.

## 23. Invoice compatibility

`InvoiceService` copies the booking's recorded amount/paid amount; it does not re-price from
the live Service.

## 24. Exact-money representation

Catalog prices/add-ons use exact integer minor units; the boundary to the legacy `Double`
booking fields uses `PaymentMoney` (single canonical helper). No new floating-point money.

## 25. API compatibility treatment

Existing `POST /api/bookings` clients may still send `serviceType` (resolved to a catalog
code/name). A new `serviceCode` field is the preferred identifier. Client-supplied
price/currency/mode are ignored/rejected (server authoritative).

## 26. Static pricing code removed/remaining

Repository-wide search for `static final Map` / `SERVICES =` / `ADDONS =`: **none remain in
production**. The former catalog now exists only as `ServiceCatalogSeeder` seed data
(non-authoritative, idempotent) and in the seeded database.

## 27. Database constraints

`services.code` unique; `service_prices (service_id, currency)` unique; `service_addons.code`
unique. `bookings` gained nullable `service_id`/snapshot columns. Migration
`database/migrations/20260829_service_catalog.sql` (PostgreSQL) is additive and to be folded
into the future Flyway baseline; `ddl-auto` also creates the schema for tests.

## 28. Transaction/concurrency behavior

Admin create/update are `@Transactional`; a Service update atomically upserts its USD/GBP price
in one command. Duplicate code is protected by the DB unique index and mapped to 409. Price
updates are last-write-wins (documented) — acceptable for Admin edit; the unique constraint
prevents duplicate price rows.

## 29. Files created

```
src/main/java/com/geekonsites/backend/enums/Currency.java
src/main/java/com/geekonsites/backend/entity/Service.java
src/main/java/com/geekonsites/backend/entity/ServicePrice.java
src/main/java/com/geekonsites/backend/entity/ServiceAddon.java
src/main/java/com/geekonsites/backend/repository/ServiceRepository.java
src/main/java/com/geekonsites/backend/repository/ServicePriceRepository.java
src/main/java/com/geekonsites/backend/repository/ServiceAddonRepository.java
src/main/java/com/geekonsites/backend/service/ServiceCatalogService.java
src/main/java/com/geekonsites/backend/service/ServiceCatalogSeeder.java
src/main/java/com/geekonsites/backend/service/PricingQuote.java
src/main/java/com/geekonsites/backend/controller/ServiceController.java
src/main/java/com/geekonsites/backend/controller/AdminServiceController.java
src/main/java/com/geekonsites/backend/dto/ServiceResponse.java
src/main/java/com/geekonsites/backend/dto/AdminServiceResponse.java
src/main/java/com/geekonsites/backend/dto/AdminServiceCreateRequest.java
src/main/java/com/geekonsites/backend/dto/AdminServiceUpdateRequest.java
src/main/java/com/geekonsites/backend/dto/ServiceStatusRequest.java
database/migrations/20260829_service_catalog.sql
src/test/java/com/geekonsites/backend/phase6/Phase6ServiceCatalogIntegrationTest.java
```

## 30. Files modified

```
src/main/java/com/geekonsites/backend/service/TrustedPricingService.java   (DB-backed; maps removed)
src/main/java/com/geekonsites/backend/service/BookingService.java          (PricingQuote → snapshots)
src/main/java/com/geekonsites/backend/entity/Booking.java                  (serviceId + snapshots)
src/main/java/com/geekonsites/backend/dto/BookingRequest.java              (serviceCode)
src/main/java/com/geekonsites/backend/config/SecurityConfig.java           (public /api/services)
src/main/resources/application.properties                                  (app.catalog.seed)
src/test/java/com/geekonsites/backend/service/TrustedPricingServiceTest.java (DB-catalog integration)
```
Deleted: `src/main/java/com/geekonsites/backend/service/PricingService.java` (dead).

## 31. Tests added

`Phase6ServiceCatalogIntegrationTest` (11): US-market USD catalog, UK-market GBP catalog,
invalid market rejected, inactive service not public, admin create/update, non-admin forbidden,
duplicate code (409) + invalid price (400), booking resolves DB service/snapshots server price,
booking cannot override service mode, price-history invariant (+ payment uses snapshot),
inactive service cannot be booked while existing booking survives. `TrustedPricingServiceTest`
rewritten against the DB catalog (same values asserted).

## 32. Complete Maven results

```
mvn -B clean test → Tests run: 305, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
mvn -B verify     → Tests run: 305, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```
0 expected-failure tags remain.

## 33. New defects discovered

None. `Service` collides with the Spring `@Service` annotation, handled by using the annotation
fully-qualified where the entity is imported (documented for maintainers).

## 34. Client clarifications still required

- Platform/service fee policy (flat 12.00 currently; PDF undefined).
- Protection-plan pricing (currently zero).
- Free quote / no-fix-no-fee / parts / emergency pricing (later phases).
- Whether agent-assisted (on-behalf) booking is needed.
- Legacy `Booking.serviceId` backfill approval (names map deterministically).

## 35. Phase 7 readiness

**READY.** The service catalog is a real DB entity with stable codes, modes, exact US/GBP
prices and Admin management; booking resolves and snapshots server-authoritative pricing;
payment/refund/invoice use the booking snapshot; historical bookings are unaffected by price
changes. `BookingStateMachine` and `PaymentTransaction` remain the sole lifecycle/financial
authorities, and the full suite is green with 0 expected failures.
