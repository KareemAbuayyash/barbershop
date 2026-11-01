# Barbershop frontend

## Local development

1. Start PostgreSQL and the Spring Boot backend using the backend instructions.
2. Set `BARBER_ADMIN_USERNAME` and `BARBER_ADMIN_PASSWORD` in the backend process environment for the first owner account. Do not put credentials in frontend configuration.
3. From this directory, run `npm install` once and `npm run dev`. The Vite development server proxies `/api` to `http://localhost:8080`.
4. If the backend listens elsewhere, set `VITE_API_PROXY_TARGET` in the environment used to start Vite.

The browser uses same-origin `/api` requests, session cookies, and CSRF tokens kept only in memory. Customers can register at `/register` and sign in at `/login`; customer sessions cannot access management endpoints. The owner account remains private and is bootstrapped only from backend environment configuration.

## Production

Serve the frontend and reverse-proxy `/api` to the backend under the same origin. Configure the backend with `BARBER_SESSION_COOKIE_SECURE=true` and terminate HTTPS at the trusted production edge. Do not expose the backend over plain HTTP, add permissive CORS rules, or place credentials or session identifiers in frontend storage.

The login and guest-booking rate limits are in-memory and apply per backend instance. A multi-instance deployment needs a shared rate-limit store before relying on global limits.

## Validation

Run `npm test`, `npm run build`, and `npm run lint`.
