# ThaiShopFun OMS

Multichannel order management for ThaiShopFun. This repository is the application monorepo (`backend/` and `frontend/`). API contracts live temporarily in `contracts/` (stand-in for the `tsf-oms-contracts` repo, T01C).

The approved plan is in [`docs/plan/`](docs/plan/plan.md).

## Run locally

Needs Java 17, Node.js 22, and Docker. From a fresh clone, in three commands (the last two keep running, so use two more terminals):

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd frontend && npm ci && npm run dev
```

- Mock TSF listens on `localhost:8090` with Spring profile `local,e2e` (demo catalog/orders and REST debug controls). `docker compose` sets `SPRING_PROFILES_ACTIVE=local,e2e` on the `mock-tsf` service.
- Postgres 16 listens on `localhost:5432` (database `oms`). `oms` / `oms` is a dev-only bootstrap superuser. Flyway uses it. It bypasses row-level security.
- The API connects as `oms_app` / `oms_app` (dev-only, `NOBYPASSRLS`). Override with `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `OMS_APP_PASSWORD`. `docker/postgres/init` creates that login on a new volume. An existing volume that never ran the init script has `oms_app` as `NOLOGIN`; recreate the volume or `ALTER ROLE oms_app LOGIN PASSWORD 'oms_app'`.
- Flyway's login defaults to `FLYWAY_USER` / `FLYWAY_PASSWORD` (`oms` / `oms`). A `postgresql://` `DATABASE_URL` (Railway) keeps Flyway on that URL's user.
- API: <http://localhost:8080> — `GET /actuator/health` returns `{"status":"UP"}` with no token. `GET /api/v1/me` needs a user JWT (`aud=oms`). `GET /internal/v1/health` needs a service JWT (`aud=oms-internal`).
- Mock TSF: <http://localhost:8090> — IdP, section 4.7 REST, the OMS webhook receiver, and the control API. `GET /actuator/health` needs no token. The `local` profile points OMS at this process (`issuer`, JWKS, inbox HMAC, outbox URL and secret).
- UI: <http://localhost:5173> — sign in with ThaiShopFun (the mock picker). The public client is `oms-web` at `http://localhost:8090/tsf-idp` (`VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`, `VITE_OIDC_REDIRECT_URI` in `frontend/.env.example`). Tokens stay in memory. A reload sends the browser through `/authorize` again. The mock has no `revocation_endpoint` or `end_session_endpoint`, so log out clears memory and returns to the signed-out screen. Do not use the Vite `/tsf-idp` proxy as the OIDC authority.

Restarting `mock-tsf` generates a new RSA key. OMS caches that JWKS for about five minutes, so a token from the new process can 401 until OMS is restarted.

### Control API

Base `http://localhost:8090`. `/control` is a localhost harness. The events it carries are in `contracts/`; the harness itself is not.

Login hints: `owner-active` (ACTIVE), `owner-grace` (GRACE, GET allowed, POST `403 ENTITLEMENT_GRACE`), `owner-suspended` (SUSPENDED), `owner-expired` (ACTIVE with `expires_at` in the past, `403 ENTITLEMENT_INACTIVE`).

```bash
curl -s http://localhost:8090/tsf-idp/.well-known/openid-configuration
curl -s http://localhost:8090/tsf-idp/.well-known/jwks.json
curl -s -X POST http://localhost:8090/control/user-token \
  -H 'Content-Type: application/json' \
  -d '{"login_hint":"owner-active"}'
```

`GET /tsf-idp/authorize` with `login_hint` redirects with a PKCE code. Without `login_hint` it returns the user picker. The public client id is `oms-web` (no secret). `POST /tsf-idp/token` accepts `authorization_code`, `refresh_token`, and `client_credentials`. An authorization-code or refresh response includes a minimal `id_token` (`aud=oms-web`, `typ=JWT`) and an API access token (`aud=oms`, header `typ=at+jwt`). OMS accepts access-token `typ` `at+jwt` (preferred), `JWT`, or a missing `typ` (`oms.security.accepted-token-types`). The `local` and `test` profiles accept only `at+jwt`. An `id_token` used as a bearer is rejected because its audience is `oms-web`, not `oms`. `POST /control/user-token` accepts optional `ent_ver`, `status` (`ACTIVE`, `GRACE`, or `SUSPENDED`), and `expires_at` for that token only. A `membership.changed` event OMS accepts with 202 updates the seed's `ent_ver`, status, and `expires_at`. A 200 duplicate does not.

The web app calls the IdP at `http://localhost:8090/tsf-idp` with `client_id=oms-web`. The mock allows CORS preflight from `http://localhost:5173` and `http://127.0.0.1:5173` on `/tsf-idp/**` (`MOCK_CORS_ORIGINS` overrides that list). Vite also proxies `/tsf-idp` to the mock for same-origin debugging. Do not use that proxy as the OIDC authority: discovery `issuer` is `http://localhost:8090/tsf-idp`, and oidc-client-ts rejects a mismatch. Discovery does not advertise `revocation_endpoint` or `end_session_endpoint`.

| Call | Body |
|---|---|
| `POST /control/events/send` | `{"event":{...envelope...}}` |
| `POST /control/events/repeat` | `{"times":5,"event":{...}}` — same bytes, same `event_id` |
| `POST /control/events/shuffle` | `{"events":[envelope, envelope]}` — sent in a different order |
| `POST /control/events/stale` | `{"skew_seconds":301,"event":{...}}` |
| `POST /control/events/bad-signature` | `{"event":{...}}` — signed with the wrong secret |
| `POST /control/demo/order-catalog` | Idempotent SKUs, stock, and listings for demo orders (`local`/`e2e` OMS profile); also switches `shop_active` TSF channel to `ACTIVE` so stock enforcement applies |
| `POST /control/demo/orders-seed` | Calls order-catalog then idempotent demo `order.*` events for `shop_active` (local/e2e only) |
| `POST /control/demo/invariants-check` | Runs one `InvariantJob` pass (`violations` count in JSON); use after demo seed to confirm `violations=0` |
| `POST /control/events/after-reservation-expiry` | `order.created` only. `{"reservation_expires_at":"...","event":{...}}`. Refuses a future `occurred_at` |
| `POST /control/checkout/reservations` | reservation request; OMS status and body are returned as-is |
| `DELETE /control/checkout/reservations/{id}` | release |
| `GET /control/received-events` | OMS webhooks that passed HMAC and schema. Keeps the last 1000 |
| `POST /control/faults` | `{"method":"GET","path":"/internal/v1/...","status":429,"times":1,"retry_after":30}` or `status` 503. Arms the next N authenticated 4.7 calls |

Section 4.7 (`/internal/v1/...` on the mock) needs a bearer token from `POST /tsf-idp/token` with `grant_type=client_credentials`, `client_id=oms-service`, and `client_secret=dev-oms-service-secret`. OMS calls the mock with the outbox HMAC on `POST /internal/v1/oms-events` and does not send that bearer token.

The backend waits up to 60 seconds for Postgres to accept connections. Flyway V1, V2, and V3 run on startup. Use the `local` profile so the dev inbox HMAC secret is loaded. Without that profile, and without `OMS_INBOX_HMAC_SECRETS`, the process refuses to start.

## Layout

| Path | What it is |
|---|---|
| `backend/` | Spring Boot 4.1, Java 17, Maven wrapper. Actuator health. JWT resource server. Flyway V1, V2, and V3. Spotless on `verify`. |
| `frontend/` | Vite, React, TypeScript. Vitest, Playwright, and ESLint. |
| `mock-tsf/` | Local ThaiShopFun. Spring Boot 4.1, Java 17. IdP, section 4.7, checkout client, event sender, OMS webhook receiver. |
| `contracts/` | OpenAPI 3.1 and JSON Schema for sections 4.3–4.7. Stand-in for `tsf-oms-contracts` until that repo exists. |
| `docker-compose.yml` | Postgres 16 and mock-tsf for local development. |
| `docs/plan/` | Plan v2 (process map, scope, data model, API contract, task list, NFR). |
| `.github/workflows/ci.yml` | Backend `./mvnw verify`, frontend `npm ci && npm run lint && npm run build && npm test`, Playwright SSO e2e, mock-tsf `./mvnw verify`, the delivery chaos suite, and contract example validation. |
| `.railway/railway.ts` | Staging infrastructure. Dockerfiles are in each service directory. |

Flyway V1 is `backend/src/main/resources/db/migration/V1__foundation_rls.sql`. It creates `tenant`, `app_user`, `tenant_membership`, `audit_log`, `idempotency_key`, `inbox_event`, and `outbox_event`, plus `oms_migrator`, `oms_app` (`NOBYPASSRLS`), and `oms_maint`. Tenant tables use `ENABLE` and `FORCE ROW LEVEL SECURITY`. V2 is `V2__jit_provision.sql`: `SECURITY DEFINER` functions `upsert_app_user`, `provision_tenant`, `provision_membership`, and read-only `lookup_login`, owned by `oms_maint`, executable only by `oms_app`. V3 is `V3__inbox_tenant_dedup.sql`: inbox dedup is `UNIQUE (tenant_id, source, event_id)`, with `aggregate_version` and `payload_sha256`. Versions are taken in merge order as the next free number. One migration per PR. Do not edit a version that has already been merged.

`POST /internal/v1/events` needs a service JWT and `X-Signature` (`t=<unix>,v1=<hex hmac-sha256>` over `t + "." + raw body`, skew at most 300 seconds). `OMS_INBOX_HMAC_SECRETS` is comma-separated, current key first, and has no default. The dev value is only in the `local` profile (`application-local.yml`). The poller runs unless `OMS_INBOX_WORKER_ENABLED=false`. An unknown shop returns `503` with `Retry-After: 60`. An active `membership.changed` for that shop creates the tenant.

Recipient PII (T10, Flyway V7) is encrypted in the application with AES-256-GCM before it reaches `order_recipient`. `OMS_PII_KEYS` (`kid:base64-32-byte-key`, comma-separated), `OMS_PII_ACTIVE_KEY_ID`, and `OMS_PII_HASH_KEY` (HMAC key for `phone_hash`, base64, at least 32 bytes) have no default. They are required in every profile; the local profile ships dev-only keys (`application-local.yml`), and the test config has its own. Without all three the process refuses to start. Rotate by adding the new key to the ring and switching the active id; old values still decrypt.

The outbox publisher polls `outbox_event` with `claim_outbox_batch` (at-least-once, lease, backoff). It stays idle until both `TSF_OMS_EVENTS_URL` (absolute `http` or `https`) and `OMS_OUTBOX_WEBHOOK_SECRET` (at least 32 bytes) are set. OWNER and ADMIN retry a DEAD row with `GET /api/v1/outbox` (`limit` default 50, max 100, plus `offset`) and `POST /api/v1/outbox/{id}/retry`. The screen is `#/admin/outbox`, behind the SSO guard. The access token stays in memory and is sent by the shared API client. This project is localhost-only: that page reaches the API through the Vite dev proxy (`/api` → `127.0.0.1:8080`). No nginx change is required.

Catalog and warehouses (T07): products, SKUs, bundle components, warehouses, and CSV import under `/api/v1`, with the pages `#/catalog/skus`, `#/catalog/products`, `#/catalog/import`, and `#/warehouses`. Endpoints, error codes, the CSV format, and the audit actions are in [`docs/api/catalog.md`](docs/api/catalog.md). No migration: T07 runs on the V4 tables.

Stock documents and per-SKU history (T08A): drafts and posting for opening balance, receive, adjustment, count, and write-off under `/api/v1/stock-documents`, plus `GET /api/v1/skus/{id}/stock-history`. UI routes `#/stock/documents`, `#/stock/documents/:id`, and `#/catalog/skus/:id/history` (T04 shell nav links from the SKU list). Details are in [`docs/api/stock-documents.md`](docs/api/stock-documents.md). Flyway **V8** adds commit-consistent `inventory_ledger.ledger_seq` (history running totals order by sequence, not transaction start time). Voiding an OPENING still leaves ledger history on the row, so a new OPENING is refused (`422 OPENING_ALREADY_SET`); use an ADJUSTMENT instead.

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

- Backend: Java 17. The job first runs `./mvnw -DskipTests package` with no `mock-tsf` artifact installed, then installs `mock-tsf` from the same commit (`-Dspotless.skip=true`; Spotless for that module is the mock-tsf job), then `./mvnw -Pmock-acceptance verify`. Spotless (`google-java-format` 1.28.0) is bound to backend `verify`. The acceptance tests live in `src/mock-acceptance` and are not on the default classpath, so the image build and `spring-boot:run` do not need the mock jar.
- Frontend: `npm ci && npm run lint && npm run build && npm test`. `npm run lint` is ESLint with `--max-warnings 0`, so a lint violation fails the job. `npm test` is Vitest.
- Frontend e2e: `npm run test:e2e` in `frontend/`. Playwright starts `docker compose up` (Postgres and mock-tsf), the backend `local` profile, and Vite, then signs in through the mock IdP. Browsers are cached in CI. Run it locally with Java 17, Node 22, and Docker:

  ```bash
  cd frontend && npm ci && npx playwright install --with-deps chromium && npm run test:e2e
  ```
- Delivery chaos locally (Java 17, Docker): `cd mock-tsf && ./mvnw -DskipTests -Dspotless.skip=true install`, then `cd backend && ./mvnw -Pmock-acceptance,chaos test`. `DeliveryChaosTest` sends 1,000 `order.created` events through the mock into `POST /internal/v1/events` (duplicates, reordering, bad and stale signatures, handler rollbacks, killed and restarted workers), then publishes 1,000 `stock.updated` events through a localhost fault proxy to the mock receiver (killed publishers before ack and after ack, 500, 503, 429 and 503 with `Retry-After`, resets, timeouts). Faults come from a fixed seed, printed at the start; rerun a failure with `-Dchaos.seed=<seed>`. It takes well under a minute. In `backend/target/chaos-report.json`, each direction has `events_sent`, `unique_delivered`, `total_deliveries`, `duplicate_rate` (`total / unique − 1`), `retries_by_cause`, and `wall_time_ms`. The test passes on correctness (nothing lost, one effect per event, all `SENT`); the duplicate rate is reported, not asserted.
- Mock TSF: `./mvnw verify` in `mock-tsf/`.
- Delivery chaos (T14B): its own job, so the backend job does not run it (JUnit tag `chaos` is excluded by default). It installs `mock-tsf`, runs `./mvnw -Pmock-acceptance,chaos test` in `backend/`, and uploads `backend/target/chaos-report.json` as the `chaos-report` artifact.
- Contracts: Spectral lints `contracts/openapi/*.yaml` (resolving `$ref`s). `ContractExamplesTest` validates every file in `contracts/examples/` against its schema and checks that each event example filename matches `event_type`.

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
