$ErrorActionPreference = "Stop"

$email = (Read-Host "Admin email").Trim().ToLowerInvariant()
if ($email -notmatch "^[^@\s]+@[^@\s]+\.[^@\s]+$") {
    throw "Enter a valid admin email address."
}

$securePassword = Read-Host "Admin password (minimum 12 characters)" -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}

if ($password.Length -lt 12) {
    throw "Admin password must contain at least 12 characters."
}

$confirmSecurePassword = Read-Host "Confirm admin password" -AsSecureString
$confirmPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirmSecurePassword)
try {
    $confirmPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($confirmPointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($confirmPointer)
}

if ($password -cne $confirmPassword) {
    throw "Passwords do not match. Run setup-admin.ps1 again."
}

$secretDirectory = "C:\Users\Lenovo\firebase-secrets"
New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
Set-Content -LiteralPath (Join-Path $secretDirectory "admin-email.txt") -Value $email -NoNewline
Set-Content -LiteralPath (Join-Path $secretDirectory "admin-password.txt") -Value $password -NoNewline

Write-Host "Admin credentials saved outside the project. Restart the backend with .\run-local.ps1."
