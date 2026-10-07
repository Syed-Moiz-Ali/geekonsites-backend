# PHASE 7 — API Validation, Error Contract & Request Hardening

> Scope: GeekOnSites Java / Spring Boot backend — the API boundary (validation + errors).
> No frontend changes. No domain redesign (money/Flyway/pricing policy unchanged).

---

## 1. API validation architecture before Phase 7

Validation was inconsistent: some DTOs had Bean Validation, others none; controllers did not
`@Valid` uniformly; a few controllers kept local `@ExceptionHandler`s with different body
shapes (`{status,code,message}` vs `{message}`); `PaymentController.createCheckoutSession`
wrapped everything in `catch (Exception)` → 500; malformed dates/enums/JSON could surface as
500; 401/403 used Spring defaults (403 for unauthenticated, ad-hoc JWT JSON).

## 2. API validation architecture after Phase 7

- One standard error body: `ApiErrorResponse`.
- One `@RestControllerAdvice` (`GlobalExceptionHandler`) maps all known exceptions with stable
  error codes and correct HTTP statuses.
- DTOs carry structural Bean Validation; controllers use `@Valid` consistently.
- 401/403 from Spring Security use the same body (`RestAuthenticationEntryPoint` /
  `RestAccessDeniedHandler`); the JWT filter's 401 also uses it.
- Controller-wide catch-alls removed; unexpected defects remain 500 (generic, logged).

## 3. Request DTO audit table (summary)

| DTO | Endpoint | Validation | Risk before |
|---|---|---|---|
| LoginRequest | /api/auth/login, admin login | **added** @NotBlank email/password | null email → NPE/500 |
| RegisterRequest | /api/auth/register | **added** @NotBlank fullName/email/password + @Email (kept @Pattern) | blank fields accepted |
| Forgot/ResetPasswordRequest | auth | already @NotBlank/@Email/@Pattern | ok |
| ChangePasswordRequest | auth | already @NotBlank/@Size | ok |
| BookingRequest | POST /api/bookings | **added** @Pattern bookingDate, @Size timeSlot | malformed date → 500 |
| TechnicianLocationRequest | …/technician/location | **added** @DecimalMin/@DecimalMax lat/lon | out-of-range coords reached service |
| CustomerLocationRequest | …/customer-location | **added** lat/lon range | same |
| RatingRequest | POST /api/ratings | **added** @NotNull bookingId, @Min/@Max rating, @Size review | invalid rating reached service |
| StripeCheckoutRequest | /api/payments/create-checkout-session | **added** @NotNull bookingId | null bookingId |
| AdminServiceCreate/Update | /api/admin/services | already validated (@NotBlank/@NotNull/@DecimalMin) | ok |
| ContactRequest | /api/contact | already @NotBlank/@Email/@Size | ok |
| RemoteChatMessageRequest | /api/remote-session-chat | already @NotBlank/@Size(2000) | ok |
| PushDeviceTokenRequest | /api/notifications/devices | already @NotBlank/@Pattern/@Size | ok |
| RefundRequestCreateDto | /api/refunds | already @NotBlank/@Size | ok |

## 4. DTO validations added

LoginRequest, RegisterRequest, BookingRequest (date/size), TechnicianLocationRequest,
CustomerLocationRequest, RatingRequest, StripeCheckoutRequest.

## 5. Business validations intentionally retained in services

Service active/price/mode resolution, customer ownership, booking lifecycle, technician
eligibility, duplicate rating, refund eligibility, payment amount/currency, one-review-per-
booking. These stay in the domain (not annotations).

## 6. Standard ApiErrorResponse

`dto/ApiErrorResponse`: `timestamp, status, error, code, message, path, fieldErrors[]`
(`fieldErrors` = `{field, message}`). Never contains stack traces, exception class names, SQL,
or provider secrets.

## 7. Error-code catalog

`VALIDATION_ERROR`, `INVALID_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `RESOURCE_NOT_FOUND`,
`CONFLICT`, `INVALID_BOOKING_TRANSITION`, `PAYMENT_VALIDATION_FAILED`, `INTERNAL_ERROR`.

## 8. GlobalExceptionHandler mappings

`MethodArgumentNotValidException`, `ConstraintViolationException` → 400 VALIDATION_ERROR (+
all field errors); `HttpMessageNotReadableException` / `MissingServletRequestParameterException`
/ `MethodArgumentTypeMismatchException` / `NumberFormatException` → 400 INVALID_REQUEST;
`NoResourceFoundException` → 404; `ResourceNotFoundException`/`EntityNotFoundException` → 404;
`InvalidBookingTransitionException` → its status, code INVALID_BOOKING_TRANSITION;
`InvalidPaymentStateException` → 409 PAYMENT_VALIDATION_FAILED; `ResponseStatusException` →
its status; `DataIntegrityViolationException` → 409 CONFLICT; `AccessDeniedException` → 403;
`Exception` → 500 INTERNAL_ERROR (logged; generic message).

## 9. Security 401/403 handling

`RestAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler` (403) emit the standard
body; wired via `.exceptionHandling(...)`. The JWT filter's expired/invalid-token 401 also
emits the standard body with no parser internals. Authenticated wrong-role requests remain 403.

## 10. Malformed JSON behavior

`HttpMessageNotReadableException` → 400 INVALID_REQUEST with the standard body (no Jackson
internals). Invalid enum values in JSON follow the same path (400).

## 11. Enum conversion behavior

Invalid enum path/query/body values produce 400 (type mismatch → 400; unreadable body → 400).
No `IllegalArgumentException → 500`.

## 12. Not-found handling

`ResourceNotFoundException` (new) + `EntityNotFoundException` + `NoResourceFoundException` →
404 RESOURCE_NOT_FOUND. Removed endpoints (e.g. the Phase-2 status override) now return 404,
not 500.

## 13. Conflict handling

Duplicate rating, duplicate email, duplicate service code, already-paid, remaining-not-due,
no-outstanding-balance → 409 CONFLICT (application checks + DB constraints; no raw DB text).

## 14. Booking transition error handling

`InvalidBookingTransitionException` (carries 400/409) is mapped to code
INVALID_BOOKING_TRANSITION with its status and reason. Existing Phase-0/2 tests that expect the
specific reason are preserved (now via `$.message`).

## 15. Payment error handling

Checkout validation (`no outstanding balance`, `advance already processed`, `remaining not
due`, `already paid`, `invalid amount`) → 400/409 with clean messages; booking not found → 404;
cross-customer pay → 403. Raw Stripe messages are not returned; gateway failures remain 500
(generic). Webhook signature → 400.

## 16. Stripe webhook special handling

Invalid signature → 400 (no mutation); unsupported valid events → acknowledged (200);
transient processing failure → 5xx so Stripe retries. The webhook controller no longer wraps
errors in a catch-all.

## 17. Database constraint translation

`DataIntegrityViolationException` → 409 CONFLICT with a generic message (no SQL). Application
pre-checks + unique constraints are the primary guard.

## 18. Sensitive-data protections

Validation responses return field names + safe messages only; password/token/JWT/push-token
values are never echoed; Stripe/SQL details are never exposed; unexpected errors are generic.

## 19. Controller catch-all cleanup

Removed: `PaymentController` catch-all; local `@ExceptionHandler(ResponseStatusException)` in
`BookingController`, `RatingController`, `RemoteSessionController`; and the three local handlers
in `AdminServiceController`. All now use the central advice.

## 20. Generic RuntimeException cleanup

`PaymentService` checkout-path errors → `ResponseStatusException`/`ResourceNotFoundException`;
new `ResourceNotFoundException`. Message-checked `applyCompletedCheckoutSession` internals were
deliberately left as RuntimeExceptions (asserted by Phase-1/3 tests; webhook path). Remaining
`RuntimeException` business conditions are documented for the later error-contract phase.

## 21. Remaining broad catch(Exception) usages + justification

- `GoogleCalendarService` (2): wraps Google API failures → FAILED provisioning state (non-critical, retryable).
- `PaymentService` (confirm fallback, webhook): converts provider/parse failures to safe messages; webhook rethrows for retry.
- `PushNotificationService` (2): best-effort push; must never break business flow.
- `StripeCheckoutGatewayImpl` / `StripeRefundGatewayImpl`: wrap provider SDK errors.
- `TrustedPricingService.addonTotal`: distinguishes expected business errors (rethrow) from parse errors (400).
All guard non-critical external side effects; none convert business success into 500.

## 22. Date/time parsing strategy

`BookingRequest.bookingDate` remains a String for compatibility but is `@Pattern`-validated
(`YYYY-MM-DD`) and parsed safely in the service; a format-valid but impossible date yields 400,
never 500. Other timestamps use `LocalDateTime` types.

## 23. Coordinate validation

`TechnicianLocationRequest`/`CustomerLocationRequest` enforce latitude ∈ [-90,90] and longitude
∈ [-180,180] via `@DecimalMin/@DecimalMax`; the Phase-2 service guard remains as defense in
depth. Invalid coordinates → 400 with field errors and no persistence.

## 24. Rating validation

`RatingRequest`: `@NotNull bookingId`, rating `@Min(1) @Max(5)`, review `@Size(2000)`; `@Valid`
on the controller. `RatingService` still owns ownership/duplicate/lifecycle/technician-derivation.

## 25. Service-catalog validation

Admin DTOs already validated (`@NotBlank` code/name, `@NotNull` mode, `@DecimalMin` prices);
validation/missing fields → 400 VALIDATION_ERROR; duplicate code → 409 CONFLICT; invalid market
→ 400; inactive service booking → 400. All use the standard body.

## 26. Auth/registration validation

Login/register email+password validated; `CountrySupport` business normalization retained (not
moved into `@Pattern`); public registration still forces CUSTOMER (Phase 5).

## 27. File/Base64 validation

`TechnicianService` still enforces per-document 5 MB decoded-size and JPG/PNG/PDF prefix
checks, returning 400 (`RuntimeException` → mapped by advice). No media-storage redesign.

## 28. No-mutation-on-invalid-request verification

`ApiErrorContractIntegrationTest` asserts user/booking/rating counts and the location field are
unchanged after invalid requests (`validationErrorUsesStandardContract`,
`unknownServiceRejectedWithNoBookingCreated`, `outOfRangeCoordinates…`,
`outOfRangeRatingIsRejectedWithNoReviewCreated`).

## 29. Success API compatibility impact

Success responses are unchanged. The only externally visible change is the **error body shape**
(now the standard contract) and unauthenticated **401** (previously 403). These are the intended
Phase 7 outcomes.

## 30. Files created

```
src/main/java/com/geekonsites/backend/dto/ApiErrorResponse.java
src/main/java/com/geekonsites/backend/exception/ResourceNotFoundException.java
src/main/java/com/geekonsites/backend/exception/GlobalExceptionHandler.java
src/main/java/com/geekonsites/backend/config/RestAuthenticationEntryPoint.java
src/main/java/com/geekonsites/backend/config/RestAccessDeniedHandler.java
src/test/java/com/geekonsites/backend/phase7/ApiErrorContractIntegrationTest.java
```

## 31. Files modified

```
src/main/java/com/geekonsites/backend/config/SecurityConfig.java
src/main/java/com/geekonsites/backend/jwt/JwtAuthenticationFilter.java
src/main/java/com/geekonsites/backend/controller/PaymentController.java
src/main/java/com/geekonsites/backend/controller/BookingController.java
src/main/java/com/geekonsites/backend/controller/RatingController.java
src/main/java/com/geekonsites/backend/controller/RemoteSessionController.java
src/main/java/com/geekonsites/backend/controller/AdminServiceController.java
src/main/java/com/geekonsites/backend/service/PaymentService.java
src/main/java/com/geekonsites/backend/service/BookingService.java
src/main/java/com/geekonsites/backend/dto/LoginRequest.java
src/main/java/com/geekonsites/backend/dto/RegisterRequest.java
src/main/java/com/geekonsites/backend/dto/BookingRequest.java
src/main/java/com/geekonsites/backend/dto/TechnicianLocationRequest.java
src/main/java/com/geekonsites/backend/dto/CustomerLocationRequest.java
src/main/java/com/geekonsites/backend/dto/RatingRequest.java
src/main/java/com/geekonsites/backend/dto/StripeCheckoutRequest.java
```

## 32. Tests created/modified

- Created `ApiErrorContractIntegrationTest` (12): validation contract + field errors, malformed
  JSON, invalid enum, unknown service (no booking), 404, 401, 403, coordinate field errors +
  no mutation, rating field errors + no review, duplicate service code 409, invalid price 400,
  invalid booking transition code.
- Aligned 4 pre-existing suites to the new contract (semantics preserved): JWT-expired/reason
  assertions now check `$.message`; unauthenticated expectations corrected from 403 → 401; one
  latent `@Async` email race hardened with a bounded `timeout()` verify.

## 33. Final test counts

```
before Phase 7: 305
added:          12
final total:    317
failures:       0
errors:         0
skipped:        0
@Tag("expected-failure"): 0
```

## 34. New defects discovered

None functional. Noted that a global `@ExceptionHandler(Exception.class)` would swallow Spring's
`NoResourceFoundException` (fixed with a 404 mapping), and that an older test raced `@Async`
email delivery (hardened).

## 35. Deferred items

- Normalizing every remaining business `RuntimeException` in `PaymentService`/`BookingService`
  to ResponseStatusException (Phase-8 candidate).
- Request correlation IDs (optional; not built).
- Swagger/OpenAPI error-schema exposure and Swagger production lockdown (later).
- Full money migration, validation on every query param, pagination overhaul (later phases).

## 36. Phase 8 readiness

**READY.** One standard error contract, consistent statuses (400/401/403/404/409/500), field-
level validation, no-mutation-on-invalid guarantees, controller catch-alls removed, and 401/403
standardized. `BookingStateMachine`, `PaymentTransaction`, the DB service catalog and the
booking price snapshot remain the sole authorities; the full suite (`mvn clean test` and
`mvn verify`) is green with 0 expected failures.
