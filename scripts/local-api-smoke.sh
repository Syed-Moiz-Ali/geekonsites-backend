#!/usr/bin/env bash
# =============================================================================
# GeekOnSites local API smoke test (canonical, portable)
# =============================================================================
# Safe, read-mostly checks against a LOCALLY RUNNING backend. No production
# services are contacted. No secrets are hardcoded (override via env vars).
#
# Usage:
#   BASE=http://127.0.0.1:8080 ./scripts/local-api-smoke.sh
#
# Requires: curl.  Optional: jq (used if present for nicer output).
#
# It exercises: health, registration, login (JWT), protected-route auth (401),
# public service catalog, an authenticated identity call, a customer booking
# creation, and a validation (400) case. Exits non-zero on the first hard fail.
# =============================================================================
set -u

BASE="${BASE:-http://127.0.0.1:8080}"
CUSTOMER_PASSWORD="${CUSTOMER_PASSWORD:-Passw0rd!}"
UNIQ="$(date +%s)$$"
EMAIL="${CUSTOMER_EMAIL:-smoke.$UNIQ@example.test}"
FAIL=0

say()  { printf '%s\n' "$*"; }
pass() { printf 'PASS | %s\n' "$*"; }
fail() { printf 'FAIL | %s\n' "$*"; FAIL=1; }

# Extract the first "token":"..." value without requiring jq.
extract_token() { sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1; }

code_of() { # method path token bodyfile -> prints http code
  local method="$1" path="$2" token="${3:-}" bodyfile="${4:-}"
  local args=(-s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path" --max-time 15)
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  if [ -n "$bodyfile" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "@$bodyfile")
  fi
  curl "${args[@]}"
}

body_of() { # method path token bodyfile -> body
  local method="$1" path="$2" token="${3:-}" bodyfile="${4:-}"
  local args=(-s -X "$method" "$BASE$path" --max-time 15)
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  if [ -n "$bodyfile" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "@$bodyfile")
  fi
  curl "${args[@]}"
}

TMP="$(mktemp -d 2>/dev/null || mktemp -d -t gos-smoke)"; trap 'rm -rf "$TMP"' EXIT

# --- health ---------------------------------------------------------------
if [ "$(code_of GET /api/health)" = "200" ]; then pass "health 200"; else fail "health not 200"; fi

# --- register + login -----------------------------------------------------
printf '{"fullName":"Smoke User","email":"%s","password":"%s","phone":"+15550000100","country":"US"}' \
  "$EMAIL" "$CUSTOMER_PASSWORD" > "$TMP/reg.json"
c="$(code_of POST /api/auth/register '' "$TMP/reg.json")"
[ "$c" = "200" ] && pass "register customer 200" || fail "register customer got $c"

printf '{"email":"%s","password":"%s"}' "$EMAIL" "$CUSTOMER_PASSWORD" > "$TMP/login.json"
login_body="$(body_of POST /api/auth/login '' "$TMP/login.json")"
TOKEN="$(printf '%s' "$login_body" | extract_token)"
[ -n "$TOKEN" ] && pass "login returned JWT" || fail "login returned no token"

# --- auth negatives -------------------------------------------------------
[ "$(code_of GET /api/users/me)" = "401" ] && pass "no token -> 401" || fail "no token not 401"
[ "$(code_of GET /api/users/me 'not.a.jwt')" = "401" ] && pass "invalid token -> 401" || fail "invalid token not 401"
[ "$(code_of GET /api/users/me "$TOKEN")" = "200" ] && pass "identity with token 200" || fail "identity with token not 200"

# --- public catalog -------------------------------------------------------
[ "$(code_of GET '/api/services?market=US')" = "200" ] && pass "US catalog 200" || fail "US catalog not 200"
[ "$(code_of GET '/api/services?market=UK')" = "200" ] && pass "UK catalog 200" || fail "UK catalog not 200"
[ "$(code_of GET '/api/services?market=DE')" = "400" ] && pass "invalid market -> 400" || fail "invalid market not 400"

# --- booking --------------------------------------------------------------
svc_body="$(body_of GET '/api/services?market=US' '')"
svc_code="$(printf '%s' "$svc_body" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
if [ -n "$svc_code" ]; then
  printf '{"serviceCode":"%s","country":"US","address":"1 Smoke St","city":"Austin","state":"TX","postalCode":"73301","bookingDate":"2030-01-01","remoteSessionRequired":true,"totalAmount":1.0}' \
    "$svc_code" > "$TMP/book.json"
  c="$(code_of POST /api/bookings "$TOKEN" "$TMP/book.json")"
  [ "$c" = "200" ] && pass "create booking 200" || fail "create booking got $c"

  printf '{"serviceCode":"%s","country":"US","bookingDate":"2030-13-40","address":"a","city":"b","state":"c","postalCode":"73301"}' \
    "$svc_code" > "$TMP/badbook.json"
  c="$(code_of POST /api/bookings "$TOKEN" "$TMP/badbook.json")"
  [ "$c" = "400" ] && pass "invalid booking date -> 400" || fail "invalid booking date got $c"
else
  fail "could not discover a service code"
fi

say ""
if [ "$FAIL" = "0" ]; then say "SMOKE RESULT: PASS"; else say "SMOKE RESULT: FAIL"; fi
exit "$FAIL"
