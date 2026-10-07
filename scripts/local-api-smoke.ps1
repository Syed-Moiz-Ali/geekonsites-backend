# =============================================================================
# GeekOnSites local API smoke test (Windows PowerShell)
# =============================================================================
# Safe checks against a LOCALLY RUNNING backend. No production services, no
# hardcoded secrets. Mirrors scripts/local-api-smoke.sh.
#
#   $env:BASE = "http://127.0.0.1:8080"; ./scripts/local-api-smoke.ps1
# =============================================================================
$ErrorActionPreference = "Continue"
$BASE = if ($env:BASE) { $env:BASE } else { "http://127.0.0.1:8080" }
$PW   = if ($env:CUSTOMER_PASSWORD) { $env:CUSTOMER_PASSWORD } else { "Passw0rd!" }
$uniq = Get-Random
$email = if ($env:CUSTOMER_EMAIL) { $env:CUSTOMER_EMAIL } else { "smoke.$uniq@example.test" }
$fail = 0

function Say($m) { Write-Output $m }
function Ok($m)  { Write-Output "PASS | $m" }
function No($m)  { Write-Output "FAIL | $m"; $script:fail = 1 }

function Req($method, $path, $token, $body) {
    $tmp = Join-Path $env:TEMP "gos-resp-$uniq.json"
    $a = @('-s','-o',$tmp,'-w','%{http_code}','-X',$method,"$BASE$path",'--max-time','15')
    if ($token) { $a += @('-H',"Authorization: Bearer $token") }
    if ($body) {
        $bf = Join-Path $env:TEMP "gos-req-$uniq.json"
        [IO.File]::WriteAllText($bf, $body, (New-Object Text.UTF8Encoding($false)))
        $a += @('-H','Content-Type: application/json','--data-binary',"@$bf")
    }
    $code = (& curl.exe @a) 2>$null
    $resp = if (Test-Path $tmp) { Get-Content $tmp -Raw } else { "" }
    return [pscustomobject]@{ code = "$code".Trim(); body = $resp }
}

$r = Req GET "/api/health" $null $null
if ($r.code -eq "200") { Ok "health 200" } else { No "health $($r.code)" }

$reg = Req POST "/api/auth/register" $null (ConvertTo-Json @{fullName="Smoke User";email=$email;password=$PW;phone="+15550000100";country="US"})
if ($reg.code -eq "200") { Ok "register customer" } else { No "register customer $($reg.code)" }

$login = Req POST "/api/auth/login" $null (ConvertTo-Json @{email=$email;password=$PW})
$token = ($login.body | ConvertFrom-Json).token
if ($token) { Ok "login JWT" } else { No "login no token" }

if ((Req GET "/api/users/me" $null $null).code -eq "401") { Ok "no token -> 401" } else { No "no token" }
if ((Req GET "/api/users/me" "bad.token" $null).code -eq "401") { Ok "invalid token -> 401" } else { No "invalid token" }
if ((Req GET "/api/users/me" $token $null).code -eq "200") { Ok "identity 200" } else { No "identity" }

if ((Req GET "/api/services?market=US" $null $null).code -eq "200") { Ok "US catalog" } else { No "US catalog" }
if ((Req GET "/api/services?market=UK" $null $null).code -eq "200") { Ok "UK catalog" } else { No "UK catalog" }
if ((Req GET "/api/services?market=DE" $null $null).code -eq "400") { Ok "invalid market -> 400" } else { No "invalid market" }

$svc = (Req GET "/api/services?market=US" $null $null).body | ConvertFrom-Json
$code = ($svc | Where-Object { $_.serviceMode -eq "REMOTE" } | Select-Object -First 1).code
if ($code) {
    $b = ConvertTo-Json @{serviceCode=$code;country="US";address="1 Smoke St";city="Austin";state="TX";postalCode="73301";bookingDate="2030-01-01";remoteSessionRequired=$true;totalAmount=1.0}
    if ((Req POST "/api/bookings" $token $b).code -eq "200") { Ok "create booking" } else { No "create booking" }
    $bb = ConvertTo-Json @{serviceCode=$code;country="US";bookingDate="2030-13-40";address="a";city="b";state="c";postalCode="73301"}
    if ((Req POST "/api/bookings" $token $bb).code -eq "400") { Ok "invalid date -> 400" } else { No "invalid date" }
} else { No "no service code discovered" }

Say ""
if ($fail -eq 0) { Say "SMOKE RESULT: PASS" } else { Say "SMOKE RESULT: FAIL" }
exit $fail
