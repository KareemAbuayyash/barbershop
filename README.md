# BARBER HOUSE

**Barbershop bookings and business management in one place.**

BARBER HOUSE is a responsive web application for managing a barbershop and its customer experience. Customers can browse services and manage bookings from their phone or computer. Shop owners can organize appointments and daily operations through a single management area.

## Features

### For customers

- Browse active services, prices, durations, and service images.
- Request a booking as a guest or with a customer account.
- View booking updates and request a different time or cancellation.
- Receive in-app notifications and, where configured, browser push notifications.

### For shop owners

- Review bookings, confirm or update appointments, and manage the daily queue.
- Set working hours, closed days, and booking availability.
- Manage services, products, inventory, and stock movements.
- Track sales, expenses, customer balances, and partial debt payments.
- Review customer activity, notifications, and shop performance reports.

### Platform

- Arabic and English interface with responsive desktop and mobile layouts.
- Role-based access for owners and customers.
- Server-side input validation, CSRF protection, and rate-limited authentication and booking endpoints.
- Persistent sessions and production secure-cookie configuration.
- Database schema updates managed with Flyway migrations.

## Technology

| Component | Technology |
| --- | --- |
| Frontend | React, TypeScript, Vite |
| Backend | Java 21, Spring Boot, Spring Security, Spring Data JPA |
| Database | PostgreSQL, Flyway |
| Browser notifications | Web Push API, service worker, VAPID |

## Run locally

### Prerequisites

- Java 21 or later
- Node.js and npm
- PostgreSQL

### 1. Create the database

Create a PostgreSQL database named `barbershop_db`. The default development configuration connects to `localhost:5432` as `postgres`. Set the database password and, when initializing a new local database, the first owner account in the backend environment:

```powershell
$env:DB_PASSWORD = "your-local-database-password"
$env:BARBER_ADMIN_USERNAME = "your-owner-username"
$env:BARBER_ADMIN_PASSWORD = "use-a-unique-password"
```

Flyway applies database migrations when the backend starts. Owner bootstrap credentials are only for initializing an owner account; do not commit real credentials or secret values.

### 2. Start the backend

From the project root:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The API listens on port `8080` by default.

### 3. Start the frontend

In a second terminal, from the project root:

```powershell
cd frontend
npm install
npm run dev
```

Vite serves the application and proxies `/api` requests to `http://localhost:8080`. Set `VITE_API_PROXY_TARGET` if the backend uses a different address.

## Optional Web Push setup

Generate a VAPID key pair for the environment and configure these backend variables:

- `BARBERSHOP_PUSH_VAPID_PUBLIC_KEY`
- `BARBERSHOP_PUSH_VAPID_PRIVATE_KEY`
- `BARBERSHOP_PUSH_SUBJECT` (for example, a `mailto:` contact)

Keep the private key on the server and outside source control. Deployed push notifications require HTTPS.

## Production configuration

- Set `SPRING_PROFILES_ACTIVE=prod` and `BARBER_SESSION_COOKIE_SECURE=true`.
- Serve the frontend and backend on the same HTTPS origin, proxying `/api` to Spring Boot.
- Configure `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` for the application database account.
- Configure `DB_MIGRATOR_USERNAME` and `DB_MIGRATOR_PASSWORD` separately for Flyway migrations.
- Supply owner bootstrap credentials through the deployment's secret manager. After the initial owner is created, set `BARBER_ADMIN_BOOTSTRAP_ENABLED=false`.
- The current rate limiter stores state in memory per backend instance. A multi-instance deployment needs a shared rate-limit store.
- Set up automated database backups and verify that restoration works before relying on the production system.

## Repository structure

```text
backend/    Spring Boot API, domain logic, security, and Flyway migrations
frontend/   React application, UI, and Vite configuration
scripts/    Project scripts
```

## Development checks

Run frontend checks from `frontend/`:

```powershell
npm test
npm run lint
npm run build
```

Run backend tests from `backend/`:

```powershell
.\mvnw.cmd test
```

## License

No open-source license is specified in this repository. All rights are reserved unless a license is added.
