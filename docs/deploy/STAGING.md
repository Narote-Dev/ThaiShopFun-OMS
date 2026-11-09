# Staging deployment (Railway + Vercel)

Trial hosting for **ThaiShopFun OMS** with **mock TSF only** (no real TSF, no real customer data). Narote’s current target is **low cost**: one small Railway replica per app service, Vercel **Hobby** for the SPA.

Official references:

- Railway config as code: [Config as code](https://docs.railway.com/reference/config-as-code) (`$schema`: `https://railway.com/railway.schema.json`)
- Railway health checks: `deploy.healthcheckPath` / `deploy.healthcheckTimeout` in the same doc
- Vercel SPA rewrites: [Rewrites](https://vercel.com/docs/project-configuration#rewrites)
- Vercel Password Protection is **Pro/Enterprise only** ([Password protection](https://vercel.com/docs/security/deployment-protection#password-protection)) — **not** available on Hobby. **Do not rely on it.** The OMS **staging demo login** is the access gate.

## Architecture

| Layer | Host | Notes |
|--------|------|--------|
| Frontend | Vercel (static) | `VITE_OMS_API_BASE_URL` points at Railway backend |
| OMS API | Railway `backend/` | `SPRING_PROFILES_ACTIVE=staging` |
| Mock TSF | Railway (repo root + `mock-tsf/Dockerfile`) | Webhooks + TSF REST only |
| Postgres | Railway Postgres plugin | Flyway on startup; runtime **`oms_app`** |

## Keep Railway usage small

- **One replica** per service (default in Railway service settings).
- In the Railway dashboard, pick the **smallest CPU/RAM** plan that still runs Java 17 comfortably (start with the minimum offered for each service).
- **Postgres**: smallest tier for trials; enable [PITR](https://docs.railway.com/databases/postgresql#point-in-time-recovery) when you move beyond throwaway demos (see `docs/plan/NFR.md`).
- Do not add extra workers or autoscaling for staging trials.

## Access control (no Vercel gate)

1. **Frontend**: every screen sits behind `RequireSession`; staging uses the **demo login form** (`VITE_OMS_STAGING_LOGIN=true`), not TSF OIDC redirect.
2. **Backend**: `/api/**` and `/internal/**` require a valid JWT. Public endpoints:
   - `GET /actuator/health` (and other exposed actuator routes in `application.yml`)
   - `GET|POST` under **`/staging-idp/**`** (JWKS, demo account list, login only — **no registration**)
3. **Login protection**: rate limit + IP lockout (`StagingLoginThrottler`) on `POST …/login`.
4. **Noindex**: `X-Robots-Tag` on API responses (staging profile) + meta `robots` + Vercel `headers` in `frontend/vercel.json`.
5. **Optional later**: Vercel **Password Protection** on Pro adds a second gate; still keep demo login + JWT.

## One-time setup

### 1. Railway project

Use `.railway/railway.ts` **or** per-service config files (legacy [config as code](https://docs.railway.com/guides/config-as-code), supported until **2026-12-01**):

| Service | Config file path in repo | Root directory |
|---------|---------------------------|----------------|
| `backend` | `/backend/railway.json` | `backend` |
| `mock-tsf` | `/mock-tsf/railway.json` | **repository root** (Dockerfile needs `contracts/`) |
| `frontend` (optional on Railway) | `/frontend/railway.toml` | `frontend` — **prefer Vercel** for the SPA |

`railway config apply` (see `.railway/README.md`) after `railway login` / `railway link`.

### 2. Postgres bootstrap (`oms_app`)

After the **first** successful Flyway migrate, enable the runtime role (Railway SQL console), using a strong password stored in `DATABASE_PASSWORD`:

```sql
ALTER ROLE oms_app LOGIN PASSWORD '<generate-strong-password>';
```

Flyway should keep using the **superuser** from `DATABASE_URL` / `FLYWAY_USER` + `FLYWAY_PASSWORD`; runtime must use `DATABASE_USERNAME=oms_app`.

### 3. Vercel project

- Root directory: `frontend`
- Build: `npm ci && npm run build`
- Output: `dist`
- Commit `frontend/vercel.json` (SPA rewrites + `X-Robots-Tag`)

## Environment variables

### Backend (`SPRING_PROFILES_ACTIVE=staging`)

| Variable | Purpose |
|----------|---------|
| `PORT` | Railway injects; default 8080 |
| `DATABASE_URL` | Railway Postgres URL (`postgresql://…`) |
| `DATABASE_USERNAME` | **`oms_app`** |
| `DATABASE_PASSWORD` | Password set via `ALTER ROLE` above |
| `FLYWAY_USER` / `FLYWAY_PASSWORD` | Bootstrap login (often superuser from plugin) |
| `OMS_JWT_ISSUER` | Public issuer URL, e.g. `https://<backend>/staging-idp` |
| `OMS_JWKS_URI` | e.g. `https://<backend>/staging-idp/.well-known/jwks.json` |
| `OMS_JWT_AUDIENCE` | `oms` |
| `OMS_JWT_INTERNAL_AUDIENCE` | `oms-internal` |
| `OMS_SECURITY_INTERNAL_CLIENT_IDS` | e.g. `tsf` (comma-separated) |
| `OMS_INBOX_HMAC_SECRETS` | Same value as mock `MOCK_INBOX_HMAC_SECRET` |
| `OMS_PII_KEYS` | `kid:base64-32-byte-key` |
| `OMS_PII_ACTIVE_KEY_ID` | kid |
| `OMS_PII_HASH_KEY` | base64 ≥ 32 bytes |
| `OMS_OUTBOX_WEBHOOK_SECRET` | Same as mock outbox secret |
| `TSF_OMS_EVENTS_URL` | `https://<mock-tsf>/internal/v1/oms-events` |
| `OMS_TSF_BASE_URL` | `https://<mock-tsf>` |
| `OMS_TSF_TOKEN_URI` | mock token endpoint |
| `OMS_TSF_CLIENT_SECRET` | mock service client secret |
| `OMS_STAGING_JWT_RSA_PRIVATE_KEY` | PKCS#8 PEM or base64 DER (staging signing key) |
| `OMS_STAGING_JWT_KEY_ID` | JWKS `kid` (default `staging-oms-1`) |
| `OMS_STAGING_DEMO_PASSWORD_HASH` | BCrypt hash of shared demo password (generate off-repo) |
| `OMS_STAGING_CORS_ALLOWED_ORIGINS` | Comma-separated Vercel preview + production origins |
| `OMS_STAGING_DEMO_SEED_ON_STARTUP` | `true` to run demo catalog seed on boot |
| `OMS_STAGING_DEMO_ACCOUNTS` | Optional JSON array overriding `staging/demo-accounts.json` |
| `OMS_STAGING_ADMIN_SEED_DEMO` | `true` = seed once and **exit** (one-off job) |

Generate demo password hash (example):

```bash
python3 -c "import bcrypt; print(bcrypt.hashpw(b'your-demo-password', bcrypt.gensalt()).decode())"
```

Generate RSA key (example):

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | openssl pkcs8 -topk8 -nocrypt
```

### Mock TSF

| Variable | Purpose |
|----------|---------|
| `PORT` | `8090` |
| `MOCK_TSF_ISSUER` | Public mock IdP URL |
| `MOCK_OMS_BASE_URL` | Backend public URL |
| `MOCK_INBOX_HMAC_SECRET` | Matches `OMS_INBOX_HMAC_SECRETS` |
| `MOCK_OUTBOX_WEBHOOK_SECRET` | Matches `OMS_OUTBOX_WEBHOOK_SECRET` |
| `MOCK_CORS_ALLOWED_ORIGINS` | Vercel origins (if using mock UI helpers) |

### Vercel (frontend)

| Variable | Purpose |
|----------|---------|
| `VITE_OMS_API_BASE_URL` | `https://<backend>` (no trailing slash) |
| `VITE_OMS_STAGING_LOGIN` | `true` |
| `VITE_OMS_STAGING_IDP_PATH` | `/staging-idp` (default) |

## Demo users (no self-signup)

Accounts are defined in `backend/src/main/resources/staging/demo-accounts.json` (no passwords in git). To **add/remove** users:

1. Edit the JSON **or** set `OMS_STAGING_DEMO_ACCOUNTS` to a full JSON array.
2. Redeploy backend (or restart).
3. Share the same **shared demo password** (hash in `OMS_STAGING_DEMO_PASSWORD_HASH`) out-of-band with trial users.

There is **no** registration API.

## Reset demo data

1. **Catalog/orders demo rows**: run admin seed  
   `OMS_STAGING_ADMIN_SEED_DEMO=true` with `SPRING_PROFILES_ACTIVE=staging` (Railway one-off shell) — idempotent via `OrderDemoCatalogService`.
2. **Full reset**: create a new Postgres volume or restore from PITR / snapshot per Railway docs, then re-run Flyway from scratch and `ALTER ROLE oms_app …` again.

## Health checks

- Backend: `GET /actuator/health` — wired in `backend/railway.json` per [Railway `healthcheckPath`](https://docs.railway.com/reference/config-as-code#healthcheck-path).
- Mock TSF: `GET /actuator/health` — `mock-tsf/railway.json`.
- Docker `HEALTHCHECK` uses the same path inside each image.

## Smoke test

1. `curl -fsS https://<backend>/actuator/health`
2. Login from Vercel UI with a demo account + shared password.
3. `GET https://<backend>/api/v1/me` with the returned Bearer token.

## CI

This repo does **not** auto-deploy staging from this PR. Merge and Narote apply Railway/Vercel when ready.

GitHub Actions validates the branch on each PR update; no staging CD is wired in this workflow.
