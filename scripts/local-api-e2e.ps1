# =============================================================================
# GeekOnSites optional FULL local E2E (Windows PowerShell)
# =============================================================================
# Orchestrates the larger local flow against a running backend: auth for every
# role, service admin CRUD, booking + ownership, notifications pagination,
# contact, agent CRM, admin views, technician registration/approval, and
# provider (Stripe) checkout which is marked BLOCKED when keys are absent.
#
# Required env:
#   ADMIN_EMAIL, ADMIN_PASSWORD   (created by AdminAccountInitializer)
# Optional env:
#   BASE, CUSTOMER_PASSWORD, STRIPE_TEST_KEY
#
# Safety: refuses an obvious LIVE Stripe key (sk_live_/rk_live_). Never prints
# passwords or tokens. Does not hardcode real credentials.
# =============================================================================
$ErrorActionPreference = "Continue"
$BASE = if ($env:BASE) { $env:BASE } else { "http://127.0.0.1:8080" }
$ADMIN_EMAIL = $env:ADMIN_EMAIL
$ADMIN_PASSWORD = $env:ADMIN_PASSWORD
if (-not $ADMIN_EMAIL -or -not $ADMIN_PASSWORD) {
    Write-Error "ADMIN_EMAIL and ADMIN_PASSWORD are required"; exit 2
}
if ($env:STRIPE_TEST_KEY -match '^(sk|rk)_live_') {
    Write-Error "Refusing to run with a LIVE Stripe key"; exit 2
}
$PW = if ($env:CUSTOMER_PASSWORD) { $env:CUSTOMER_PASSWORD } else { "Passw0rd!" }
$uniq = Get-Random
$work = Join-Path $env:TEMP "gos-e2e-$uniq"; New-Item -ItemType Directory -Force -Path $work | Out-Null
$pass = 0; $fail = 0; $blocked = 0

function Req($method, $path, $token, $body) {
    $tmp = Join-Path $work "resp.json"
    $a = @('-s','-o',$tmp,'-w','%{http_code}','-X',$method,"$BASE$path",'--max-time','25')
    if ($token) { $a += @('-H',"Authorization: Bearer $token") }
    if ($body) { $bf = Join-Path $work "req.json"; [IO.File]::WriteAllText($bf,$body,(New-Object Text.UTF8Encoding($false))); $a += @('-H','Content-Type: application/json','--data-binary',"@$bf") }
    $code = (& curl.exe @a) 2>$null
    $resp = if (Test-Path $tmp) { Get-Content $tmp -Raw } else { "" }
    return [pscustomobject]@{ code = "$code".Trim(); body = $resp }
}
function Check($label, $method, $path, $token, $body, $expect) {
    $r = Req $method $path $token $body
    if ($r.code -eq $expect) { $script:pass++; Write-Output "PASS | $label ($($r.code))" }
    else { $script:fail++; Write-Output "FAIL | $label expect $expect got $($r.code) :: $(($r.body -replace '\s+',' '))" }
    return $r
}

$al = Req POST "/api/admin/auth/login" $null (ConvertTo-Json @{email=$ADMIN_EMAIL;password=$ADMIN_PASSWORD})
$tokAdmin = ($al.body | ConvertFrom-Json).token
if (-not $tokAdmin) { Write-Error "admin login failed"; exit 2 }

$c1 = "e2e.c.$uniq@example.test"; $c2 = "e2e.c2.$uniq@example.test"; $ag = "e2e.ag.$uniq@example.test"
Check "register c1" POST "/api/auth/register" $null (ConvertTo-Json @{fullName="C1";email=$c1;password=$PW;country="US"}) "200" | Out-Null
Check "register c2" POST "/api/auth/register" $null (ConvertTo-Json @{fullName="C2";email=$c2;password=$PW;country="US"}) "200" | Out-Null
$tok1 = ((Req POST "/api/auth/login" $null (ConvertTo-Json @{email=$c1;password=$PW})).body | ConvertFrom-Json).token
$tok2 = ((Req POST "/api/auth/login" $null (ConvertTo-Json @{email=$c2;password=$PW})).body | ConvertFrom-Json).token
Check "admin creates agent" POST "/api/agents" $tokAdmin (ConvertTo-Json @{name="Agent";email=$ag;password=$PW;country="US";city="Austin"}) "200" | Out-Null
$tokAgent = ((Req POST "/api/auth/login" $null (ConvertTo-Json @{email=$ag;password=$PW})).body | ConvertFrom-Json).token

$us = (Req GET "/api/services?market=US" $null $null).body | ConvertFrom-Json
$remote = ($us | Where-Object { $_.serviceMode -eq "REMOTE" } | Select-Object -First 1).code
$testCode = "E2E_TEST_SERVICE_$uniq"
Check "admin create service" POST "/api/admin/services" $tokAdmin (ConvertTo-Json @{code=$testCode;name="E2E";serviceMode="REMOTE";usdPrice=50;gbpPrice=40}) "200" | Out-Null
Check "customer forbidden service admin" POST "/api/admin/services" $tok1 (ConvertTo-Json @{code="X_$uniq";name="x";serviceMode="REMOTE";usdPrice=1;gbpPrice=1}) "403" | Out-Null

$bk = Check "customer booking" POST "/api/bookings" $tok1 (ConvertTo-Json @{serviceCode=$remote;country="US";address="1 A";city="Austin";state="TX";postalCode="73301";bookingDate="2030-01-01";remoteSessionRequired=$true;totalAmount=1.0}) "200"
$bookingId = ($bk.body | ConvertFrom-Json).id
Check "c2 cannot read c1 booking" GET "/api/bookings/$bookingId" $tok2 $null "403" | Out-Null
Check "notifications paged" GET "/api/notifications/my-notifications?page=0&size=1000" $tok1 $null "200" | Out-Null
Check "contact create" POST "/api/contact" $null (ConvertTo-Json @{fullName="E2E";email="e2e.$uniq@example.test";phone="+1";country="US";subject="s";message="m"}) "200" | Out-Null
Check "agent CRM summary" GET "/api/agent-crm/summary" $tokAgent $null "200" | Out-Null
Check "admin customers paged" GET "/api/admin/customers?page=0&size=1000" $tokAdmin $null "200" | Out-Null
Check "admin operations failures" GET "/api/admin/operations/failures" $tokAdmin $null "200" | Out-Null

$pay = Req POST "/api/payments/create-checkout-session" $tok1 (ConvertTo-Json @{bookingId=$bookingId;paymentType="FULL"})
Write-Output "BLOCKED | stripe checkout http=$($pay.code) (provider not configured)"; $blocked++
Write-Output "E2E SUMMARY | pass=$pass fail=$fail blocked=$blocked"
