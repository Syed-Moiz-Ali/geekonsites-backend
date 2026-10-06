$ErrorActionPreference = "Stop"

$existingListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($existingListener) {
    try {
        $health = (Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/api/health" -TimeoutSec 5).Content
        if ($health -like "GeekOnSites Backend API*") {
            Write-Host "GeekOnSites backend is already running on http://localhost:8080 (PID $($existingListener.OwningProcess))."
            exit 0
        }
    } catch {
        # The port belongs to another process; report it clearly below.
    }
    throw "Port 8080 is already used by PID $($existingListener.OwningProcess). Stop that process or choose another backend port."
}

$env:SPRING_PROFILES_ACTIVE = "local"
$localTrustStore = "C:\Users\Lenovo\geek-on-sites-frontend\.tools\gradle-cacerts-msjdk21"
if (Test-Path -LiteralPath $localTrustStore) {
    $env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=$localTrustStore -Djavax.net.ssl.trustStorePassword=changeit"
} else {
    $env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
}
if (-not $env:JWT_SECRET) {
    $env:JWT_SECRET = "gos-local-development-signing-key-change-before-production"
}

$adminEmailFile = "C:\Users\Lenovo\firebase-secrets\admin-email.txt"
$adminPasswordFile = "C:\Users\Lenovo\firebase-secrets\admin-password.txt"
if ((Test-Path -LiteralPath $adminEmailFile) -and (Test-Path -LiteralPath $adminPasswordFile)) {
    $env:ADMIN_EMAIL = (Get-Content -LiteralPath $adminEmailFile -Raw).Trim()
    $env:ADMIN_PASSWORD = (Get-Content -LiteralPath $adminPasswordFile -Raw).Trim()
}

$firebaseCredential = "C:\Users\Lenovo\firebase-secrets\geekonsites-firebase-admin.json"
if (Test-Path -LiteralPath $firebaseCredential) {
    $env:FIREBASE_ENABLED = "true"
    $env:GOOGLE_APPLICATION_CREDENTIALS = $firebaseCredential
}

$googleOAuthClient = "C:\Users\Lenovo\firebase-secrets\geekonsites-google-oauth-client.json"
$googleRefreshToken = "C:\Users\Lenovo\firebase-secrets\google-calendar-refresh-token.txt"
if ((Test-Path -LiteralPath $googleOAuthClient) -and (Test-Path -LiteralPath $googleRefreshToken)) {
    $env:GOOGLE_CALENDAR_ENABLED = "true"
    $env:GOOGLE_CALENDAR_OAUTH_CLIENT_JSON = Get-Content -LiteralPath $googleOAuthClient -Raw
    $env:GOOGLE_CALENDAR_REFRESH_TOKEN = (Get-Content -LiteralPath $googleRefreshToken -Raw).Trim()
    $env:GOOGLE_CALENDAR_ID = "primary"
    $env:GOOGLE_CALENDAR_TIME_ZONE = "UTC"
}

$stripeTestKey = "C:\Users\Lenovo\firebase-secrets\stripe-test-secret-key.txt"
if (Test-Path -LiteralPath $stripeTestKey) {
    $env:STRIPE_SECRET_KEY = (Get-Content -LiteralPath $stripeTestKey -Raw).Trim()
}

$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $maven) {
    $bundledMaven = "C:\Users\Lenovo\Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd"
    if (-not (Test-Path -LiteralPath $bundledMaven)) {
        throw "Maven was not found. Install Maven or update the bundled Maven path in run-local.ps1."
    }
    $maven = $bundledMaven
} else {
    $maven = $maven.Source
}

& $maven spring-boot:run
