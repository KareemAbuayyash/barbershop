# Backend security operations

## Initial owner account

The backend creates the first owner account only when the management account
table is empty. Set both environment variables for the first startup:

- `BARBER_ADMIN_USERNAME`
- `BARBER_ADMIN_PASSWORD`

The username is trimmed and normalized to lowercase. The password must be
12-72 UTF-8 bytes for BCrypt. Credentials are not stored in application
properties or source control. Once an owner exists, later startups do not reset
or replace the account. Supplying only one bootstrap variable is an error.

For local development in PowerShell, from the `backend` directory:

```powershell
$env:BARBER_ADMIN_USERNAME = "owner"
$securePassword = Read-Host "Owner password" -AsSecureString
$passwordBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:BARBER_ADMIN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordBstr)
    .\mvnw.cmd spring-boot:run
} finally {
    Remove-Item Env:BARBER_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordBstr)
}
```

## Browser session and CSRF

Authentication uses an HTTP session, not HTTP Basic. The API endpoints are
`POST /api/auth/login`, `GET /api/auth/me`, and `POST /api/auth/logout`.
State-changing requests, including login, logout, and guest booking creation,
require a CSRF token from `GET /api/csrf`. Fetch a fresh token after login and
logout. Keep the session cookie in the browser; do not persist passwords or
session IDs in browser storage.

The session cookie is HttpOnly and SameSite=Lax, and sessions expire after 30
minutes of inactivity. Local HTTP uses a non-Secure cookie by default. Activate
the `prod` Spring profile to enable Secure cookies, or configure
`BARBER_SESSION_COOKIE_SECURE`. Production deployments must use HTTPS.

## Rate limiting

The initial limits are five failed login attempts per IP per 15 minutes and ten
new guest-booking attempts per IP per 10 minutes. Configuration is available
under `barbershop.security.rate-limit` in `application.properties`. An
idempotent booking replay does not consume another booking attempt. Client IPs
come from the servlet remote address; forwarded headers are not trusted.

The limiter is bounded and in-memory, so its counts are per backend process.
It is intended for the initial single-instance deployment and is not a global
limit across multiple replicas.

Tests use the `test` profile, which disables automatic owner bootstrap. The
bootstrap integration tests create a separately named PostgreSQL schema and
drop it after execution.
