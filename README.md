# ThaiShopFun OMS

Multichannel order management for ThaiShopFun. This repository is the application monorepo (`backend/` and `frontend/`). API contracts live in the separate `tsf-oms-contracts` repo (T01C).

The approved plan is in [`docs/plan/`](docs/plan/plan.md).

## Run locally

Needs Java 17, Node.js 22, and Docker. From a fresh clone, in three commands (the last two keep running, so use two more terminals):

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run
cd frontend && npm ci && npm run dev
```

- Postgres 16 listens on `localhost:5432` (database `oms`). `oms` / `oms` is a dev-only bootstrap superuser. Flyway uses it. It bypasses row-level security.
- The API connects as `oms_app` / `oms_app` (dev-only, `NOBYPASSRLS`). Override with `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `OMS_APP_PASSWORD`. `docker/postgres/init` creates that login on a new volume. An existing volume that never ran the init script has `oms_app` as `NOLOGIN`; recreate the volume or `ALTER ROLE oms_app LOGIN PASSWORD 'oms_app'`.
- Flyway's login defaults to `FLYWAY_USER` / `FLYWAY_PASSWORD` (`oms` / `oms`). A `postgresql://` `DATABASE_URL` (Railway) keeps Flyway on that URL's user.
- API: <http://localhost:8080> — `GET /actuator/health` returns `{"status":"UP"}` with no token. `GET /api/v1/me` needs a user JWT (`aud=oms`). `GET /internal/v1/health` needs a service JWT (`aud=oms-internal`).
- Without `TSF_JWKS_URI` (or `OMS_JWKS_URI`), the process still starts and API calls return 401. T05 will provide the mock IdP.
- UI: <http://localhost:5173>

The backend waits up to 60 seconds for Postgres to accept connections. Flyway V1 and V2 run on startup.

## Layout

| Path | What it is |
|---|---|
| `backend/` | Spring Boot 4.1, Java 17, Maven wrapper. Actuator health. JWT resource server. Flyway V1 and V2. Spotless on `verify`. |
| `frontend/` | Vite, React, TypeScript. Vitest and ESLint. |
| `docker-compose.yml` | Postgres 16 for local development. |
| `docs/plan/` | Plan v2 (process map, scope, data model, API contract, task list, NFR). |
| `.github/workflows/ci.yml` | Backend `./mvnw verify` and frontend `npm ci && npm run lint && npm run build && npm test`. |
| `.railway/railway.ts` | Staging infrastructure. Dockerfiles are in each service directory. |

Flyway V1 is `backend/src/main/resources/db/migration/V1__foundation_rls.sql`. It creates `tenant`, `app_user`, `tenant_membership`, `audit_log`, `idempotency_key`, `inbox_event`, and `outbox_event`, plus `oms_migrator`, `oms_app` (`NOBYPASSRLS`), and `oms_maint`. Tenant tables use `ENABLE` and `FORCE ROW LEVEL SECURITY`. V2 is `V2__jit_provision.sql`: `SECURITY DEFINER` functions `upsert_app_user`, `provision_tenant`, `provision_membership`, and read-only `lookup_login`, owned by `oms_maint`, executable only by `oms_app`. Versions are taken in merge order as the next free number: one migration per PR, and a merged version is never edited. V1 is T02 and V2 is T03. T14 adds no migration.

The outbox publisher polls `outbox_event` with `claim_outbox_batch` (at-least-once, lease, backoff). It stays idle until both `TSF_OMS_EVENTS_URL` (absolute `http` or `https`) and `OMS_OUTBOX_WEBHOOK_SECRET` (at least 32 bytes) are set. OWNER and ADMIN retry a DEAD row with `GET /api/v1/outbox` (`limit` default 50, max 100, plus `offset`) and `POST /api/v1/outbox/{id}/retry`. The screen is `#/admin/outbox` (token stays in memory). This project is localhost-only: that page reaches the API through the Vite dev proxy (`/api` → `127.0.0.1:8080`). No nginx change is required.

The process refuses to start if the runtime role is superuser or has `BYPASSRLS`, unless `oms.security.allow-rls-bypass=true` is set explicitly. That flag is off by default and is not set for local Docker. If `DATABASE_USERNAME` / `DATABASE_PASSWORD` are set, they win over the user embedded in a `postgresql://` URL. Flyway still uses that URL user.

## Working rules

- Cursor opens feature PRs on `feat/Txx-*`.
- Codex does the second-pass review of every Cursor PR, and does isolated work (tests, adapters, migrations, contracts) on `codex/Txx-*`.
- Flyway migrations may be written by Cursor or Codex, one migration per PR, never edit a merged version. Codex reviews every migration PR.
- One task per PR. A change past about 600 lines, not counting tests, is split.
- Narote approves every merge to `main`. Branch protection is green CI, Codex review, and Narote's approval.
- Do not commit secrets, production passwords, or real PII.

## CI

GitHub Actions runs on pull requests and on `main`.

- Backend: Java 17, `./mvnw verify`. Spotless (`google-java-format` 1.28.0) is bound to `verify`, so a format violation fails the job.
- Frontend: `npm ci && npm run lint && npm run build && npm test`. `npm run lint` is ESLint with `--max-warnings 0`, so a lint violation fails the job. `npm test` is Vitest.

## Staging on Railway

Staging deploys from `main` after a one-time link. This repo cannot attach a Railway account. Nothing here is a secret.

Railway Config as Code (`railway.toml`) is deprecated and new services do not read it (hard stop 2026-12-01). `backend/railway.toml` and `frontend/railway.toml` record the same Dockerfile builder and health check for reference. The file Narote applies is [`.railway/railway.ts`](.railway/railway.ts). Do not set a service Config File path to a `railway.toml`, or Infrastructure as Code will refuse to manage that service.

`/actuator/health` is the backend health check. The frontend image serves the Vite build with nginx and answers `GET /`.

### One-time steps (Narote)

1. Install the Railway CLI and sign in: `railway login`.
2. Create a project in region **Southeast Asia (Singapore)** and an environment named `staging`.
3. In this clone, install the IaC SDK and link the project:

   ```bash
   npm ci
   railway link
   ```

4. Preview and apply (this creates Postgres, `backend`, and `frontend`, and wires `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`):

   ```bash
   railway config plan
   railway config apply
   ```

5. In each service, connect this GitHub repo if apply did not, set the auto-deploy branch to `main`, and turn automatic deploys on. Root directories are `backend/` and `frontend/`. Each directory has a `Dockerfile`; Railway builds that file.
6. Confirm the backend health check path is `/actuator/health` and the frontend path is `/`.
7. Open the backend URL and check `GET /actuator/health` is HTTP 200 with `"status":"UP"`.
8. Optional later: a project token in the `RAILWAY_TOKEN` repository secret, then the `railwayapp/config` action, so infra changes plan on pull requests and apply on merge. Code deploys do not need that token. Railway redeploys when `main` moves.

Railway's `DATABASE_URL` uses the `postgresql://` scheme. The backend rewrites it to a JDBC URL and takes the user and password from that URL. Do not commit those values.

Placeholders for later tasks (set in the Railway dashboard, not in git): `TSF_JWKS_URI`, `OMS_INTERNAL_HMAC_SECRET`. See `backend/.env.example` and `frontend/.env.example`.

Production deploy, PITR, and manual promotion are T26. Do not point this staging project at production.
