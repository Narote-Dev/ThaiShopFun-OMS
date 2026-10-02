-- V10: TSF channel_account at JIT, backfill, resolve_tenant via channel_account (T16).

-- Step 1: provision_tenant also creates the TSF channel_account (OBSERVE, CONNECTED).
CREATE OR REPLACE FUNCTION provision_tenant(
  p_tsf_shop_id text,
  p_name text,
  p_tier text,
  p_entitlement_status text,
  p_entitlement_expires_at timestamptz,
  p_ent_ver bigint
)
RETURNS uuid
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
  v_id uuid;
  v_account_tenant uuid;
BEGIN
  IF p_tsf_shop_id IS NULL OR btrim(p_tsf_shop_id) = '' THEN
    RAISE EXCEPTION 'tsf_shop_id is required';
  END IF;
  IF p_name IS NULL OR btrim(p_name) = '' THEN
    RAISE EXCEPTION 'tenant name is required';
  END IF;
  IF p_tier IS NULL OR btrim(p_tier) = '' THEN
    RAISE EXCEPTION 'membership tier is required';
  END IF;
  IF p_entitlement_status IS NULL
    OR p_entitlement_status NOT IN ('ACTIVE', 'GRACE', 'SUSPENDED') THEN
    RAISE EXCEPTION 'entitlement_status is invalid';
  END IF;
  IF p_ent_ver IS NULL OR p_ent_ver < 0 THEN
    RAISE EXCEPTION 'ent_ver is invalid';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_tsf_shop_id), 1);
  SELECT t.id
    INTO v_id
  FROM public.tenant AS t
  WHERE t.tsf_shop_id = p_tsf_shop_id;
  IF v_id IS NOT NULL THEN
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(v_id::text), 4);
  END IF;

  INSERT INTO public.tenant AS t (
    id,
    name,
    tsf_shop_id,
    membership_tier,
    entitlement_status,
    entitlement_expires_at,
    ent_ver
  )
  VALUES (
    pg_catalog.gen_random_uuid(),
    p_name,
    p_tsf_shop_id,
    p_tier,
    p_entitlement_status,
    p_entitlement_expires_at,
    p_ent_ver
  )
  ON CONFLICT (tsf_shop_id) DO UPDATE
    SET name = EXCLUDED.name,
        membership_tier = EXCLUDED.membership_tier,
        entitlement_status = EXCLUDED.entitlement_status,
        entitlement_expires_at = EXCLUDED.entitlement_expires_at,
        ent_ver = EXCLUDED.ent_ver
    WHERE t.ent_ver <= EXCLUDED.ent_ver
  RETURNING id INTO v_id;

  IF v_id IS NULL THEN
    SELECT existing.id
      INTO v_id
    FROM public.tenant AS existing
    WHERE existing.tsf_shop_id = p_tsf_shop_id;
  END IF;

  IF v_id IS NULL THEN
    RAISE EXCEPTION 'provision_tenant did not resolve an id';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(v_id::text), 4);

  INSERT INTO public.channel_account (
    id,
    tenant_id,
    channel,
    external_shop_id,
    mode,
    status
  )
  VALUES (
    pg_catalog.gen_random_uuid(),
    v_id,
    'TSF',
    p_tsf_shop_id,
    'OBSERVE',
    'CONNECTED'
  )
  ON CONFLICT (channel, external_shop_id) DO NOTHING;

  SELECT c.tenant_id
    INTO v_account_tenant
  FROM public.channel_account AS c
  WHERE c.channel = 'TSF'
    AND c.external_shop_id = p_tsf_shop_id;

  IF v_account_tenant IS DISTINCT FROM v_id THEN
    RAISE EXCEPTION 'tsf_shop_id % is already bound to another tenant', p_tsf_shop_id;
  END IF;

  RETURN v_id;
END
$fn$;

-- Step 2: Reject tenant.tsf_shop_id rows already owned by another tenant's channel_account.
DO $validate$
DECLARE
  r RECORD;
  v_owner uuid;
BEGIN
  FOR r IN
    SELECT t.id AS tenant_id, t.tsf_shop_id
    FROM public.tenant AS t
    WHERE t.tsf_shop_id IS NOT NULL AND btrim(t.tsf_shop_id) <> ''
  LOOP
    SELECT c.tenant_id
      INTO v_owner
    FROM public.channel_account AS c
    WHERE c.channel = 'TSF'
      AND c.external_shop_id = r.tsf_shop_id;

    IF v_owner IS NOT NULL AND v_owner <> r.tenant_id THEN
      RAISE EXCEPTION 'tsf_shop_id % is already bound to another tenant', r.tsf_shop_id;
    END IF;
  END LOOP;
END
$validate$;

-- Step 3: Backfill TSF channel_account for tenants that only exist in tenant.tsf_shop_id.
DO $backfill$
DECLARE
  r RECORD;
  v_owner uuid;
BEGIN
  FOR r IN
    SELECT t.id AS tenant_id, t.tsf_shop_id
    FROM public.tenant AS t
    WHERE NOT EXISTS (
      SELECT 1
      FROM public.channel_account AS c
      WHERE c.channel = 'TSF'
        AND c.external_shop_id = t.tsf_shop_id
    )
  LOOP
    SELECT c.tenant_id
      INTO v_owner
    FROM public.channel_account AS c
    WHERE c.channel = 'TSF'
      AND c.external_shop_id = r.tsf_shop_id;

    IF v_owner IS NOT NULL AND v_owner <> r.tenant_id THEN
      RAISE EXCEPTION 'tsf_shop_id % is already bound to another tenant', r.tsf_shop_id;
    END IF;

    PERFORM set_config('app.tenant_id', r.tenant_id::text, true);
    INSERT INTO public.channel_account (
      id,
      tenant_id,
      channel,
      external_shop_id,
      mode,
      status
    )
    VALUES (
      pg_catalog.gen_random_uuid(),
      r.tenant_id,
      'TSF',
      r.tsf_shop_id,
      'OBSERVE',
      'CONNECTED'
    )
    ON CONFLICT (channel, external_shop_id) DO NOTHING;
  END LOOP;
END
$backfill$;

-- Step 4: resolve_tenant via channel_account; TSF falls back to tenant.tsf_shop_id.
CREATE OR REPLACE FUNCTION resolve_tenant(channel text, external_shop_id text)
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
  SELECT COALESCE(
    (
      SELECT c.tenant_id
      FROM public.channel_account AS c
      WHERE c.channel = resolve_tenant.channel
        AND c.external_shop_id = resolve_tenant.external_shop_id
      LIMIT 1
    ),
    (
      SELECT t.id
      FROM public.tenant AS t
      WHERE resolve_tenant.channel = 'TSF'
        AND t.tsf_shop_id = resolve_tenant.external_shop_id
      LIMIT 1
    )
  )
$fn$;

ALTER FUNCTION provision_tenant(text, text, text, text, timestamptz, bigint) OWNER TO oms_maint;
ALTER FUNCTION resolve_tenant(text, text) OWNER TO oms_maint;

COMMENT ON FUNCTION provision_tenant(text, text, text, text, timestamptz, bigint) IS
  'JIT tenant upsert by tsf_shop_id. Ensures one TSF channel_account (OBSERVE, CONNECTED) without resetting existing rows. Returns id only.';
COMMENT ON FUNCTION resolve_tenant(text, text) IS
  'Cross-tenant. Resolves tenant id via channel_account; TSF falls back to tenant.tsf_shop_id when no account exists. Returns id only.';
