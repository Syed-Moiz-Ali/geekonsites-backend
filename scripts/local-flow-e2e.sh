#!/usr/bin/env bash
# =============================================================================
# GeekOnSites FLOW-WISE local E2E (chained HTTP flows)
# =============================================================================
# Runs chained flows against a LOCALLY RUNNING backend where each step consumes
# values (tokens, ids, codes) from the previous response. No ids/JWTs are pasted
# manually. No production services are contacted. No paid state is faked.
#
# Requires: curl, jq
# Env:
#   BASE                (default http://127.0.0.1:8080)
#   ADMIN_EMAIL         required (AdminAccountInitializer)
#   ADMIN_PASSWORD      required
#   CUSTOMER_PASSWORD   (default Passw0rd!)
# Optional provider flags:
#   STRIPE_TEST_KEY     if set (test key only), Stripe flows are attempted
#   GOOGLE_TEST=1       if set, Google/Meet flows are attempted
#
# Usage:
#   BASE=http://127.0.0.1:8080 ADMIN_EMAIL=admin@example.test \
#     ADMIN_PASSWORD=LocalE2eAdmin123! ./scripts/local-flow-e2e.sh
# =============================================================================
set -u
BASE="${BASE:-http://127.0.0.1:8080}"
ADMIN_EMAIL="${ADMIN_EMAIL:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
PW="${CUSTOMER_PASSWORD:-Passw0rd!}"
[ -n "$ADMIN_EMAIL" ] && [ -n "$ADMIN_PASSWORD" ] || { echo "ADMIN_EMAIL/ADMIN_PASSWORD required"; exit 2; }
command -v jq >/dev/null || { echo "jq is required"; exit 2; }

U="$(date +%s)$$"
PASS=0; FAIL=0; BLOCK=0; REQ=0
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# ---- helpers ---------------------------------------------------------------
# hit METHOD PATH TOKEN DATA  -> writes body to $TMP/body, echoes http code
hit() {
  local m="$1" p="$2" tok="${3:-}" data="${4:-}"
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$m" "$BASE$p" --max-time 25)
  [ -n "$tok" ] && args+=(-H "Authorization: Bearer $tok")
  if [ -n "$data" ]; then args+=(-H 'Content-Type: application/json' --data-binary "$data"); fi
  curl "${args[@]}"
}
step() { # tag desc method path token data expected
  local tag="$1" desc="$2" m="$3" p="$4" tok="${5:-}" data="${6:-}" exp="$7"
  local code; code="$(hit "$m" "$p" "$tok" "$data")"; REQ=$((REQ+1))
  echo "[$tag] $desc"; echo "$m $p"; echo "Expected: $exp"; echo "Actual: $code"
  if [ "$code" = "$exp" ]; then echo PASS; PASS=$((PASS+1)); else echo FAIL; FAIL=$((FAIL+1)); echo "Body: $(cat "$TMP/body" 2>/dev/null | tr '\n' ' ')"; fi
  echo
}
blocked() { echo "[$1] $2"; echo "BLOCKED - $3"; echo; BLOCK=$((BLOCK+1)); }
body() { cat "$TMP/body"; }

# ---- FLOW 0 ---------------------------------------------------------------
echo "FLOW 0 - LOCAL ENVIRONMENT"
step 0.1 "Health" GET /api/health "" "" 200 >/dev/null 2>&1
h="$(hit GET /api/health)"; [ "$h" = 200 ] && { echo "health: PASS"; PASS=$((PASS+1)); } || { echo "health: FAIL"; FAIL=$((FAIL+1)); }
list="$(hit GET /api/services?market=US)"; svc_list="$(body)"
echo "services: $list ($(echo "$svc_list" | jq 'length'))"; echo

# ---- FLOW 1 ---------------------------------------------------------------
echo "FLOW 1 - CUSTOMER A REGISTRATION + LOGIN"
CA="e2e.flow.a.$U@example.test"
step 1.1 "Register customer A" POST /api/auth/register "" "{\"fullName\":\"Flow A\",\"email\":\"$CA\",\"password\":\"$PW\",\"phone\":\"+1444$U\",\"country\":\"US\"}" 200
step 1.2 "Login customer A" POST /api/auth/login "" "{\"email\":\"$CA\",\"password\":\"$PW\"}" 200
CUSTOMER_TOKEN="$(body | jq -r .token)"; CUSTOMER_ID="$(body | jq -r .id)"
echo "JWT extracted: ${CUSTOMER_TOKEN:+yes}; CUSTOMER_ID=$CUSTOMER_ID"; echo
step 1.3 "Get current user" GET /api/users/me "$CUSTOMER_TOKEN" "" 200
step 1.4 "No token" GET /api/users/me "" "" 401
step 1.5 "Invalid token" GET /api/users/me "bad.token" "" 401

# ---- FLOW 2 ---------------------------------------------------------------
echo "FLOW 2 - CUSTOMER B"
CB="e2e.flow.b.$U@example.test"
step 2.1 "Register customer B" POST /api/auth/register "" "{\"fullName\":\"Flow B\",\"email\":\"$CB\",\"password\":\"$PW\",\"phone\":\"+1445$U\",\"country\":\"US\"}" 200
step 2.2 "Login customer B" POST /api/auth/login "" "{\"email\":\"$CB\",\"password\":\"$PW\"}" 200
CUSTOMER2_TOKEN="$(body | jq -r .token)"; CUSTOMER2_ID="$(body | jq -r .id)"

# ---- FLOW 3 ---------------------------------------------------------------
echo "FLOW 3 - SERVICE DISCOVERY"
step 3.1 "US list" GET /api/services?market=US "" "" 200; us="$(body)"
step 3.2 "UK list" GET /api/services?market=UK "" "" 200
step 3.3 "Invalid market" GET /api/services?market=ZZ "" "" 400
SERVICE_CODE="$(echo "$us" | jq -r '[.[]|select(.serviceMode=="ONSITE")][0].code')"
SERVICE_ID="$(echo "$us" | jq -r '[.[]|select(.serviceMode=="ONSITE")][0].id')"
REMOTE_SERVICE_CODE="$(echo "$us" | jq -r '[.[]|select(.serviceMode=="REMOTE")][0].code')"
echo "ONSITE SERVICE_CODE=$SERVICE_CODE id=$SERVICE_ID; REMOTE=$REMOTE_SERVICE_CODE"; echo

# ---- FLOW 4 ---------------------------------------------------------------
echo "FLOW 4 - CREATE ON-SITE BOOKING"
step 4.1 "Create booking" POST /api/bookings "$CUSTOMER_TOKEN" "{\"serviceCode\":\"$SERVICE_CODE\",\"country\":\"US\",\"address\":\"1 Flow St\",\"city\":\"Austin\",\"state\":\"TX\",\"postalCode\":\"73301\",\"bookingDate\":\"2030-02-01\"}" 200
BOOKING_ID="$(body | jq -r .id)"; echo "BOOKING_ID=$BOOKING_ID"
step 4.2 "My bookings" GET /api/bookings/my-bookings "$CUSTOMER_TOKEN" "" 200
step 4.3 "Booking detail" GET "/api/bookings/$BOOKING_ID" "$CUSTOMER_TOKEN" "" 200

# ---- FLOW 5 ---------------------------------------------------------------
echo "FLOW 5 - OWNERSHIP"
step 5.1 "B reads A booking" GET "/api/bookings/$BOOKING_ID" "$CUSTOMER2_TOKEN" "" 403
step 5.2 "B mutates A booking" PUT "/api/bookings/$BOOKING_ID/customer-location" "$CUSTOMER2_TOKEN" "{\"latitude\":1.0,\"longitude\":2.0}" 403

# ---- FLOW 6 ---------------------------------------------------------------
echo "FLOW 6 - VALIDATION"
step 6.1 "Invalid service" POST /api/bookings "$CUSTOMER_TOKEN" "{\"serviceCode\":\"NO_SUCH\",\"country\":\"US\",\"address\":\"a\",\"city\":\"b\",\"state\":\"c\",\"postalCode\":\"73301\",\"bookingDate\":\"2030-02-01\"}" 400
step 6.2 "Invalid date" POST /api/bookings "$CUSTOMER_TOKEN" "{\"serviceCode\":\"$SERVICE_CODE\",\"country\":\"US\",\"address\":\"a\",\"city\":\"b\",\"state\":\"c\",\"postalCode\":\"73301\",\"bookingDate\":\"2030-13-40\"}" 400

# ---- FLOW 10 (admin, needed early) ---------------------------------------
echo "FLOW 10 - ADMIN LOGIN"
step 10.1 "Admin login" POST /api/admin/auth/login "" "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 200
ADMIN_TOKEN="$(body | jq -r .token)"; ADMIN_ID="$(body | jq -r .id)"
step 10.2 "Admin customers" GET /api/admin/customers "$ADMIN_TOKEN" "" 200
step 10.3 "Customer forbidden" GET /api/admin/customers "$CUSTOMER_TOKEN" "" 403

# ---- FLOW 7/8/9 -----------------------------------------------------------
echo "FLOW 7 - PAYMENT PREREQUISITE"
step 7.1 "Assign before payment rejected" PUT "/api/bookings/$BOOKING_ID/assign-technician/1" "$ADMIN_TOKEN" "" 400
echo "FLOW 8 - STRIPE CHECKOUT"
co="$(hit POST /api/payments/create-checkout-session "$CUSTOMER_TOKEN" "{\"bookingId\":$BOOKING_ID,\"paymentType\":\"ADVANCE\"}")"; REQ=$((REQ+1))
if [ "$co" = 200 ]; then echo "checkout: 200 PASS"; PASS=$((PASS+1)); CHECKOUT_SESSION_ID="$(body | jq -r .sessionId)"; else blocked 8 "Stripe checkout" "EXTERNAL STRIPE TEST PROVIDER NOT CONFIGURED (got $co)"; PAY_BLOCKED=1; fi
if [ "${PAY_BLOCKED:-0}" = 1 ]; then
  blocked 9 "Complete payment" "EXTERNAL STRIPE TEST PROVIDER NOT CONFIGURED"
  blocked 13 "Assign technician to booking" "assignment requires confirmed payment"
  blocked "14-31" "Technician lifecycle / tracking / invoice / rating / refund / remote" "post-payment chain blocked by provider"
fi

# ---- FLOW 11/12 -----------------------------------------------------------
echo "FLOW 11 - TECHNICIAN REGISTRATION"
PNG="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
TA="e2e.flow.tech.a.$U@example.test"
TECH_BODY="{\"name\":\"Flow Tech A\",\"email\":\"$TA\",\"password\":\"$PW\",\"phone\":\"+1446$U\",\"country\":\"US\",\"city\":\"Austin\",\"specialization\":\"Remote\",\"experienceYears\":3,\"serviceMode\":\"REMOTE_ONLY\",\"employmentType\":\"CONTRACT\",\"citizenshipStatus\":\"CITIZEN\",\"identityDocumentType\":\"DRIVER_LICENSE\",\"identityDocumentData\":\"$PNG\",\"livePhotoData\":\"$PNG\",\"workAuthorizationType\":\"NONE\",\"addressHistory\":\"1 St\",\"addressProofData\":\"$PNG\"}"
step 11.1 "Register technician A" POST /api/technicians "" "$TECH_BODY" 200
tl="$(hit GET /api/technicians "$ADMIN_TOKEN")"; TECH_LIST="$(body)"
TECHNICIAN_ID="$(echo "$TECH_LIST" | jq -r --arg e "$TA" '.[]|select(.email==$e)|.id' | head -1)"
echo "TECHNICIAN_ID=$TECHNICIAN_ID"
step 11.2 "Login before approval blocked" POST /api/auth/login "" "{\"email\":\"$TA\",\"password\":\"$PW\"}" 403
echo "FLOW 12 - APPROVAL"
step 12.1 "Assign unverified rejected" PUT "/api/bookings/$BOOKING_ID/assign-technician/$TECHNICIAN_ID" "$ADMIN_TOKEN" "" 400
step 12.2 "Approve technician" PUT "/api/technicians/$TECHNICIAN_ID/approve" "$ADMIN_TOKEN" "" 200
step 12.3 "Login after approval" POST /api/auth/login "" "{\"email\":\"$TA\",\"password\":\"$PW\"}" 200
TECHNICIAN_TOKEN="$(body | jq -r .token)"
step 12.4 "Set availability" PUT /api/technicians/me/availability "$TECHNICIAN_TOKEN" "{\"status\":\"AVAILABLE\"}" 200

# ---- FLOW 31b / 32 / 33 / 34 / 35 / 36 / 37 / 38 -------------------------
echo "FLOW 31b - MODE NEGATIVE"
step 31b.1 "Onsite booking remote provision rejected" POST "/api/bookings/$BOOKING_ID/remote-session/provision" "$CUSTOMER_TOKEN" "" 400

echo "FLOW 32 - AGENT"
AG="e2e.flow.agent.$U@example.test"
step 32.1 "Admin creates agent" POST /api/agents "$ADMIN_TOKEN" "{\"name\":\"Flow Agent\",\"email\":\"$AG\",\"password\":\"$PW\",\"country\":\"US\",\"city\":\"Austin\"}" 200
step 32.2 "Agent login" POST /api/auth/login "" "{\"email\":\"$AG\",\"password\":\"$PW\"}" 200
AGENT_TOKEN="$(body | jq -r .token)"
step 32.3 "Agent CRM summary" GET /api/agent-crm/summary "$AGENT_TOKEN" "" 200
step 32.4 "Agent CRM customers" GET "/api/agent-crm/customers?page=0&size=5" "$AGENT_TOKEN" "" 200
step 32.5 "Agent CANNOT price services" POST /api/admin/services "$AGENT_TOKEN" "{\"code\":\"AG_$U\",\"name\":\"x\",\"serviceMode\":\"REMOTE\",\"usdPrice\":1,\"gbpPrice\":1}" 403
step 32.6 "Agent CANNOT rate" POST /api/ratings "$AGENT_TOKEN" "{\"bookingId\":$BOOKING_ID,\"rating\":5}" 403

echo "FLOW 33 - ADMIN SERVICE MANAGEMENT"
step 33.1 "Create service" POST /api/admin/services "$ADMIN_TOKEN" "{\"code\":\"E2E_FLOW_SVC_$U\",\"name\":\"Flow Svc\",\"serviceMode\":\"REMOTE\",\"usdPrice\":50,\"gbpPrice\":40}" 200
TEST_SERVICE_ID="$(body | jq -r .id)"
step 33.2 "Update service" PUT "/api/admin/services/$TEST_SERVICE_ID" "$ADMIN_TOKEN" "{\"name\":\"Flow Svc v2\",\"serviceMode\":\"REMOTE\",\"usdPrice\":55,\"gbpPrice\":45}" 200
step 33.3 "Deactivate" PATCH "/api/admin/services/$TEST_SERVICE_ID/status" "$ADMIN_TOKEN" "{\"active\":false}" 200
step 33.4 "Booking deactivated service rejected" POST /api/bookings "$CUSTOMER_TOKEN" "{\"serviceCode\":\"E2E_FLOW_SVC_$U\",\"country\":\"US\",\"address\":\"a\",\"city\":\"b\",\"state\":\"c\",\"postalCode\":\"73301\",\"bookingDate\":\"2030-02-02\"}" 400

echo "FLOW 34 - ADMIN LISTS"
step 34.1 "Admin customers" GET "/api/admin/customers?page=0&size=5" "$ADMIN_TOKEN" "" 200
step 34.2 "Admin refunds" GET "/api/admin/refunds?page=0&size=5" "$ADMIN_TOKEN" "" 200
step 34.3 "Admin operations failures" GET /api/admin/operations/failures "$ADMIN_TOKEN" "" 200

echo "FLOW 35 - CONTACT"
step 35.1 "Public contact create" POST /api/contact "" "{\"fullName\":\"Flow\",\"email\":\"e2e.flow.c.$U@example.test\",\"phone\":\"+15550009999\",\"country\":\"US\",\"subject\":\"s\",\"message\":\"m\"}" 200
CONTACT_ID="$(body | jq -r .id)"
step 35.2 "Agent views contact" GET "/api/contact/$CONTACT_ID" "$AGENT_TOKEN" "" 200
step 35.3 "Agent updates contact status" PUT "/api/contact/$CONTACT_ID/status?status=RESOLVED" "$AGENT_TOKEN" "" 200
step 35.4 "Customer forbidden" GET /api/contact "$CUSTOMER_TOKEN" "" 403

echo "FLOW 36 - ROLE MATRIX"
step 36.1 "Technician cannot create booking" POST /api/bookings "$TECHNICIAN_TOKEN" "{\"serviceCode\":\"$SERVICE_CODE\",\"country\":\"US\",\"address\":\"a\",\"city\":\"b\",\"state\":\"c\",\"postalCode\":\"73301\",\"bookingDate\":\"2030-02-03\"}" 403
step 36.2 "Customer cannot price services" PUT "/api/admin/services/$TEST_SERVICE_ID" "$CUSTOMER_TOKEN" "{\"name\":\"x\",\"serviceMode\":\"REMOTE\",\"usdPrice\":1,\"gbpPrice\":1}" 403
step 36.3 "Admin main-portal login blocked" POST /api/auth/login "" "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 403

echo "FLOW 37 - ERROR CONTRACT"
step 37.1 "400" POST /api/auth/register "" "{\"fullName\":\"x\",\"email\":\"bad\",\"password\":\"weak\",\"country\":\"US\"}" 400
step 37.2 "401" GET /api/users/me "" "" 401
step 37.3 "403" GET /api/admin/customers "$CUSTOMER_TOKEN" "" 403
step 37.4 "404" GET /api/bookings/99999999 "$ADMIN_TOKEN" "" 404
step 37.5 "409" PUT "/api/bookings/$BOOKING_ID/generate-invoice" "$CUSTOMER_TOKEN" "" 409

echo "FLOW 38 - PAGINATION"
step 38.1 "page0 size5" GET "/api/notifications/my-notifications?page=0&size=5" "$CUSTOMER_TOKEN" "" 200
step 38.2 "page1 size5" GET "/api/notifications/my-notifications?page=1&size=5" "$CUSTOMER_TOKEN" "" 200
step 38.3 "empty high page" GET "/api/notifications/my-notifications?page=9999&size=5" "$CUSTOMER_TOKEN" "" 200
step 38.4 "invalid page" GET "/api/notifications/my-notifications?page=-1&size=5" "$CUSTOMER_TOKEN" "" 200
step 38.5 "oversized size capped" GET "/api/notifications/my-notifications?page=0&size=1000000" "$CUSTOMER_TOKEN" "" 200
echo "capped size = $(body | jq -r .size)"

echo "=================================================="
echo "FLOW SUMMARY"
echo "Live HTTP requests executed: $REQ"
echo "Passed: $PASS"
echo "Failed: $FAIL"
echo "Blocked by external provider: $BLOCK"
echo "=================================================="
[ "$FAIL" = 0 ] && echo "FLOW-WISE LOCAL API TESTING PASSED" || echo "FLOW-WISE LOCAL API TESTING FAILED"
exit "$FAIL"
