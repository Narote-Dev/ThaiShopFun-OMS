-- V1 foundation: identity, audit, idempotency, inbox, and outbox.
--
-- Flyway connects as a superuser. Locally that is the docker-compose user `oms`,
-- which is a dev-only bootstrap account and bypasses row-level security.
-- This script creates the roles, then hands table ownership to oms_migrator.
-- oms_app is the future runtime role (T03): DML only, NOBYPASSRLS, not an owner.
-- oms_maint is break-glass: BYPASSRLS and NOLOGIN until someone enables it.
--
-- Policies read current_setting('app.tenant_id', true) and are meant to be set
-- with SET LOCAL or set_config(..., true) inside the same transaction.
-- NULLIF treats both "never set" (NULL) and "cleared" ('', what Postgres leaves
-- behind after the local setting ends) as no tenant. A non-empty invalid value
-- still fails the uuid cast (fail closed).
--
-- Primary keys are uuid with no database default. The application generates UUIDv7.
--
-- Deviations, also called out in the T02 PR:
-- * app_user has no tenant_id, so it is not a tenant table and has no RLS policy.
--   oms_app is granted nothing on it.
-- * resolve_tenant(channel, external_shop_id) can only see tenant.tsf_shop_id for
--   channel 'TSF' until channel_account exists (T06).
-- * list_active_tenant_ids returns entitlement_status = 'ACTIVE' only.
-- * inbox has no IN_FLIGHT status. claim_inbox_batch leases a row by moving
--   next_attempt_at forward and incrementing attempts. Outbox claim sets
--   IN_FLIGHT and lease_until. Both return (id, tenant_id) and take an optional
--   lease, default 5 minutes, capped at 1 hour.
-- * tenant_membership.status is constrained to ACTIVE/REVOKED.

-- Step 1: Create roles. Superuser only, because BYPASSRLS cannot be granted otherwise.
-- LOGIN and passwords are left alone when the role already exists (docker init).
DO $roles$
DECLARE
  actor_is_super boolean;
BEGIN
  SELECT r.rolsuper
    INTO actor_is_super
  FROM pg_catalog.pg_roles AS r
  WHERE r.rolname = current_user;

  IF NOT coalesce(actor_is_super, false) THEN
    RAISE EXCEPTION
      'Flyway V1 must run as a superuser so it can create oms_maint BYPASSRLS and reassign ownership';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'oms_migrator') THEN
    CREATE ROLE oms_migrator NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
  ALTER ROLE oms_migrator NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'oms_app') THEN
    CREATE ROLE oms_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
  ALTER ROLE oms_app NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'oms_maint') THEN
    CREATE ROLE oms_maint NOLOGIN NOSUPERUSER BYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
  ALTER ROLE oms_maint NOLOGIN NOSUPERUSER BYPASSRLS NOCREATEDB NOCREATEROLE;
END
$roles$;

COMMENT ON ROLE oms_migrator IS
  'Owns Flyway tables. DDL. NOBYPASSRLS, so FORCE RLS applies to the owner too.';
COMMENT ON ROLE oms_app IS
  'Runtime DML for the app and jobs. NOBYPASSRLS. Not a table owner.';
COMMENT ON ROLE oms_maint IS
  'Break-glass maintenance. BYPASSRLS and NOLOGIN until explicitly enabled.';

-- Step 2: Tables. Columns follow docs/plan/03-data-model.md.
CREATE TABLE tenant (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  tsf_shop_id text NOT NULL,
  membership_tier text NOT NULL,
  entitlement_status text NOT NULL,
  entitlement_expires_at timestamptz,
  ent_ver bigint NOT NULL,
  CONSTRAINT tenant_tsf_shop_id_key UNIQUE (tsf_shop_id),
  CONSTRAINT tenant_entitlement_status_check CHECK (
    entitlement_status IN ('ACTIVE', 'GRACE', 'SUSPENDED')
  )
);

CREATE TABLE app_user (
  id uuid PRIMARY KEY,
  tsf_user_id text NOT NULL,
  email text,
  display_name text,
  last_login_at timestamptz,
  CONSTRAINT app_user_tsf_user_id_key UNIQUE (tsf_user_id)
);

CREATE TABLE tenant_membership (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  user_id uuid NOT NULL REFERENCES app_user (id),
  role text NOT NULL,
  status text NOT NULL,
  CONSTRAINT tenant_membership_tenant_user_key UNIQUE (tenant_id, user_id),
  CONSTRAINT tenant_membership_role_check CHECK (role IN ('OWNER', 'ADMIN', 'STAFF')),
  CONSTRAINT tenant_membership_status_check CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE audit_log (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  actor_type text NOT NULL,
  actor_id text,
  action text NOT NULL,
  entity_type text NOT NULL,
  entity_id text,
  "before" jsonb,
  "after" jsonb,
  ip inet,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT audit_log_actor_type_check CHECK (
    actor_type IN ('USER', 'SYSTEM', 'TSF', 'PLATFORM_ADMIN')
  )
);

CREATE TABLE idempotency_key (
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  scope text NOT NULL,
  "key" text NOT NULL,
  request_hash text NOT NULL,
  response_status integer,
  response_body jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, scope, "key")
);

CREATE TABLE inbox_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  source text NOT NULL,
  event_id text NOT NULL,
  event_type text NOT NULL,
  aggregate_id text NOT NULL,
  payload jsonb NOT NULL,
  status text NOT NULL,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  last_error text,
  received_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz,
  CONSTRAINT inbox_event_source_event_key UNIQUE (source, event_id),
  CONSTRAINT inbox_event_status_check CHECK (
    status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'DEAD')
  ),
  CONSTRAINT inbox_event_attempts_check CHECK (attempts >= 0)
);

CREATE TABLE outbox_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  aggregate_type text NOT NULL,
  aggregate_id text NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  status text NOT NULL,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  lease_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  CONSTRAINT outbox_event_status_check CHECK (
    status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'DEAD')
  ),
  CONSTRAINT outbox_event_attempts_check CHECK (attempts >= 0)
);

-- Indexes required from the start for the tables this version creates.
CREATE INDEX inbox_event_status_next_attempt_at_idx
  ON inbox_event (status, next_attempt_at);

CREATE INDEX outbox_event_status_next_attempt_at_idx
  ON outbox_event (status, next_attempt_at);

-- Step 3: oms_migrator owns every application table. oms_app never does.
ALTER TABLE tenant OWNER TO oms_migrator;
ALTER TABLE app_user OWNER TO oms_migrator;
ALTER TABLE tenant_membership OWNER TO oms_migrator;
ALTER TABLE audit_log OWNER TO oms_migrator;
ALTER TABLE idempotency_key OWNER TO oms_migrator;
ALTER TABLE inbox_event OWNER TO oms_migrator;
ALTER TABLE outbox_event OWNER TO oms_migrator;

-- Step 4: ENABLE + FORCE RLS. The tenant table matches on id, not tenant_id.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
  USING (id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE tenant_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_membership FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_membership
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_key
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE inbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inbox_event
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON outbox_event
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Step 5: Privileges. Audit is append-only at the grant layer and via trigger.
GRANT USAGE ON SCHEMA public TO oms_app, oms_migrator, oms_maint;
GRANT CREATE ON SCHEMA public TO oms_migrator;

DO $connect$
BEGIN
  EXECUTE format(
    'GRANT CONNECT ON DATABASE %I TO oms_app, oms_migrator, oms_maint',
    current_database()
  );
END
$connect$;

-- oms_app must not delete a tenant. Phase 0 has no caller for that.
GRANT SELECT, INSERT, UPDATE ON tenant TO oms_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON
  tenant_membership,
  idempotency_key,
  inbox_event,
  outbox_event
TO oms_app;

GRANT SELECT, INSERT ON audit_log TO oms_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON
  tenant,
  app_user,
  tenant_membership,
  idempotency_key,
  inbox_event,
  outbox_event
TO oms_maint;

GRANT SELECT, INSERT ON audit_log TO oms_maint;

REVOKE ALL ON TABLE app_user FROM oms_app;

-- Step 6: Append-only audit. Fires for the owner and for superusers too.
CREATE FUNCTION audit_log_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only';
END
$fn$;

ALTER FUNCTION audit_log_reject_mutation() OWNER TO oms_migrator;
REVOKE ALL ON FUNCTION audit_log_reject_mutation() FROM PUBLIC;

CREATE TRIGGER audit_log_append_only
  BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW
  EXECUTE FUNCTION audit_log_reject_mutation();

CREATE TRIGGER audit_log_append_only_truncate
  BEFORE TRUNCATE ON audit_log
  FOR EACH STATEMENT
  EXECUTE FUNCTION audit_log_reject_mutation();

-- Step 7: Cross-tenant functions. Owned by oms_maint (BYPASSRLS) so FORCE RLS
-- does not hide other tenants. Each one returns ids only.
CREATE FUNCTION list_active_tenant_ids()
RETURNS TABLE (id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
  SELECT t.id
  FROM public.tenant AS t
  WHERE t.entitlement_status = 'ACTIVE'
$fn$;

CREATE FUNCTION resolve_tenant(channel text, external_shop_id text)
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
  SELECT t.id
  FROM public.tenant AS t
  WHERE channel = 'TSF'
    AND t.tsf_shop_id = external_shop_id
$fn$;

CREATE FUNCTION claim_inbox_batch(
  n integer,
  p_lease interval DEFAULT interval '5 minutes'
)
RETURNS TABLE (id uuid, tenant_id uuid)
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  -- Step 1: Reject a limit or lease that would scan an unbounded batch or hide it too long.
  IF n IS NULL OR n < 1 OR n > 1000 THEN
    RAISE EXCEPTION 'claim_inbox_batch limit must be between 1 and 1000';
  END IF;
  IF p_lease IS NULL OR p_lease <= interval '0' OR p_lease > interval '1 hour' THEN
    RAISE EXCEPTION 'claim_inbox_batch lease must be greater than 0 and at most 1 hour';
  END IF;

  -- Step 2: Lease due RECEIVED/FAILED rows. Inbox has no IN_FLIGHT status, so the
  -- lease is next_attempt_at. SKIP LOCKED keeps parallel workers off the same row.
  -- Return tenant_id so the worker can open a per-tenant transaction.
  RETURN QUERY
  WITH picked AS (
    SELECT i.id
    FROM public.inbox_event AS i
    WHERE i.status IN ('RECEIVED', 'FAILED')
      AND (i.next_attempt_at IS NULL OR i.next_attempt_at <= now())
    ORDER BY i.next_attempt_at NULLS FIRST, i.received_at, i.id
    LIMIT n
    FOR UPDATE SKIP LOCKED
  )
  UPDATE public.inbox_event AS e
  SET attempts = e.attempts + 1,
      next_attempt_at = pg_catalog.now() + p_lease
  FROM picked
  WHERE e.id = picked.id
  RETURNING e.id, e.tenant_id;
END
$fn$;

CREATE FUNCTION claim_outbox_batch(
  n integer,
  p_lease interval DEFAULT interval '5 minutes'
)
RETURNS TABLE (id uuid, tenant_id uuid)
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  -- Step 1: Reject a limit or lease that would scan an unbounded batch or hide it too long.
  IF n IS NULL OR n < 1 OR n > 1000 THEN
    RAISE EXCEPTION 'claim_outbox_batch limit must be between 1 and 1000';
  END IF;
  IF p_lease IS NULL OR p_lease <= interval '0' OR p_lease > interval '1 hour' THEN
    RAISE EXCEPTION 'claim_outbox_batch lease must be greater than 0 and at most 1 hour';
  END IF;

  -- Step 2: Claim due PENDING rows, and IN_FLIGHT rows whose lease expired.
  RETURN QUERY
  WITH picked AS (
    SELECT o.id
    FROM public.outbox_event AS o
    WHERE (
        o.status = 'PENDING'
        AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
      )
      OR (
        o.status = 'IN_FLIGHT'
        AND o.lease_until IS NOT NULL
        AND o.lease_until <= now()
      )
    ORDER BY o.next_attempt_at NULLS FIRST, o.created_at, o.id
    LIMIT n
    FOR UPDATE SKIP LOCKED
  )
  UPDATE public.outbox_event AS e
  SET status = 'IN_FLIGHT',
      attempts = e.attempts + 1,
      lease_until = pg_catalog.now() + p_lease
  FROM picked
  WHERE e.id = picked.id
  RETURNING e.id, e.tenant_id;
END
$fn$;

ALTER FUNCTION list_active_tenant_ids() OWNER TO oms_maint;
ALTER FUNCTION resolve_tenant(text, text) OWNER TO oms_maint;
ALTER FUNCTION claim_inbox_batch(integer, interval) OWNER TO oms_maint;
ALTER FUNCTION claim_outbox_batch(integer, interval) OWNER TO oms_maint;

REVOKE ALL ON FUNCTION list_active_tenant_ids() FROM PUBLIC;
REVOKE ALL ON FUNCTION resolve_tenant(text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_inbox_batch(integer, interval) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_outbox_batch(integer, interval) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION list_active_tenant_ids() TO oms_app;
GRANT EXECUTE ON FUNCTION resolve_tenant(text, text) TO oms_app;
GRANT EXECUTE ON FUNCTION claim_inbox_batch(integer, interval) TO oms_app;
GRANT EXECUTE ON FUNCTION claim_outbox_batch(integer, interval) TO oms_app;

COMMENT ON FUNCTION list_active_tenant_ids() IS
  'Cross-tenant. Returns id only, for entitlement_status ACTIVE.';
COMMENT ON FUNCTION resolve_tenant(text, text) IS
  'Cross-tenant. Returns the tenant id for channel TSF and tsf_shop_id. No other columns.';
COMMENT ON FUNCTION claim_inbox_batch(integer, interval) IS
  'Cross-tenant lease of inbox rows. FOR UPDATE SKIP LOCKED. Returns id and tenant_id only.';
COMMENT ON FUNCTION claim_outbox_batch(integer, interval) IS
  'Cross-tenant lease of outbox rows. Sets IN_FLIGHT. FOR UPDATE SKIP LOCKED. Returns id and tenant_id only.';
