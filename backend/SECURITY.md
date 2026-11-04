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

## Production database roles

Run production with the `prod` profile. It requires `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`, `DB_MIGRATOR_USERNAME`, and `DB_MIGRATOR_PASSWORD`. Use a
dedicated runtime database role for the application and a separate migration
role for Flyway. The runtime role should have only the table and sequence
privileges the application needs, including CRUD access to the Spring Session
tables; it must not be a PostgreSQL superuser or own the schema. The migration
role needs the DDL privileges required by Flyway.
Use a TLS-verified PostgreSQL connection in `DB_URL` when the database is
remote. Never reuse the local development `postgres` role in production.

## SQL injection review

All user-supplied values in JDBC queries must be passed as bind parameters
(`?`) or named parameters. Do not concatenate request values into SQL, table
names, column names, sort expressions, or predicates. Where a query needs a
different structure, choose between fixed SQL statements in code. The
notification recipient queries follow this rule and bind both the account type
and account ID. The sales report's generated `IN` clause only generates `?`
markers from the number of already-loaded booking IDs; the IDs themselves are
still passed separately as parameters.

The `prod` profile sends a restrictive Content Security Policy and baseline
Referrer/Permissions policies. Keep the policy synchronized with any newly
introduced external image, font, or script host; avoid adding third-party
scripts to the application.

HTTP sessions are stored in PostgreSQL by Spring Session. The optional
"remember me" login setting extends both the session inactivity limit and the
browser cookie to 30 days; ordinary sessions remain limited to 30 minutes.
Flyway migration `V25` creates the session tables.

Tests use the `test` profile, which disables automatic owner bootstrap. The
bootstrap integration tests create a separately named PostgreSQL schema and
drop it after execution.
