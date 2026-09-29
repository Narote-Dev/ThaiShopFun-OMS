-- V2: JIT provisioning for the first login.
--
-- oms_app has no privileges on app_user (V1) and cannot INSERT a new tenant
-- while app.tenant_id is unset, because the tenant policy checks id = that setting.
-- These SECURITY DEFINER functions run as oms_maint (BYPASSRLS) and return ids only.
--
-- No passwords here. oms_app LOGIN is created by docker init or the test harness.
-- Do not edit V1. T06's catalog migration uses the next free version (V3).

-- Step 1: Upsert the person. Concurrent first logins share one row via tsf_user_id.
CREATE FUNCTION upsert_app_user(
  p_tsf_user_id text,
  p_email text,
  p_display_name text
)
RETURNS uuid
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
  v_id uuid;
BEGIN
  IF p_tsf_user_id IS NULL OR btrim(p_tsf_user_id) = '' THEN
    RAISE EXCEPTION 'tsf_user_id is required';
  END IF;
  IF p_email IS NOT NULL AND btrim(p_email) = '' THEN
    p_email := NULL;
  END IF;
  IF p_display_name IS NOT NULL AND btrim(p_display_name) = '' THEN
    p_display_name := NULL;
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_tsf_user_id), 2);

  INSERT INTO public.app_user AS u (id, tsf_user_id, email, display_name, last_login_at)
  VALUES (
    pg_catalog.gen_random_uuid(),
    p_tsf_user_id,
    p_email,
    p_display_name,
    pg_catalog.now()
  )
  ON CONFLICT (tsf_user_id) DO UPDATE
    SET email = COALESCE(EXCLUDED.email, u.email),
        display_name = COALESCE(EXCLUDED.display_name, u.display_name),
        last_login_at = pg_catalog.now()
  RETURNING id INTO v_id;

  IF v_id IS NULL THEN
    RAISE EXCEPTION 'upsert_app_user did not resolve an id';
  END IF;
  RETURN v_id;
END
$fn$;

-- Step 2: Upsert the shop. A new tenant cannot be inserted by oms_app without context.
-- Extra arguments beyond (tsf_shop_id, name, tier) fill NOT NULL columns.
-- A token with a lower ent_ver does not overwrite a newer row.
CREATE FUNCTION provision_tenant(
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

  INSERT INTO public.tenant AS t (
    id, name, tsf_shop_id, membership_tier, entitlement_status, entitlement_expires_at, ent_ver
  )
  VALUES (
    pg_catalog.gen_random_uuid(), p_name, p_tsf_shop_id, p_tier, p_entitlement_status,
    p_entitlement_expires_at, p_ent_ver
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
    SELECT existing.id INTO v_id
    FROM public.tenant AS existing
    WHERE existing.tsf_shop_id = p_tsf_shop_id;
  END IF;

  IF v_id IS NULL THEN
    RAISE EXCEPTION 'provision_tenant did not resolve an id';
  END IF;
  RETURN v_id;
END
$fn$;

-- Step 3: Link the user to the shop. A REVOKED membership is never reactivated.
CREATE FUNCTION provision_membership(
  p_tenant_id uuid,
  p_user_id uuid,
  p_role text
)
RETURNS uuid
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
  v_id uuid;
BEGIN
  IF p_tenant_id IS NULL OR p_user_id IS NULL THEN
    RAISE EXCEPTION 'tenant_id and user_id are required';
  END IF;
  IF p_role IS NULL OR p_role NOT IN ('OWNER', 'ADMIN', 'STAFF') THEN
    RAISE EXCEPTION 'shop_role is invalid';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtext(p_tenant_id::text || ':' || p_user_id::text), 3);

  INSERT INTO public.tenant_membership AS m (id, tenant_id, user_id, role, status)
  VALUES (pg_catalog.gen_random_uuid(), p_tenant_id, p_user_id, p_role, 'ACTIVE')
  ON CONFLICT (tenant_id, user_id) DO UPDATE
    SET role = EXCLUDED.role
    WHERE m.status <> 'REVOKED'
  RETURNING id INTO v_id;

  IF v_id IS NULL THEN
    SELECT existing.id INTO v_id
    FROM public.tenant_membership AS existing
    WHERE existing.tenant_id = p_tenant_id
      AND existing.user_id = p_user_id;
  END IF;

  IF v_id IS NULL THEN
    RAISE EXCEPTION 'provision_membership did not resolve an id';
  END IF;
  RETURN v_id;
END
$fn$;

-- Step 4: Read-only lookup. Ids, ent_ver, and membership status only.
CREATE FUNCTION lookup_login(p_tsf_shop_id text, p_tsf_user_id text)
RETURNS TABLE (
  tenant_id uuid,
  user_id uuid,
  membership_id uuid,
  ent_ver bigint,
  membership_status text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
  SELECT t.id, u.id, m.id, t.ent_ver, m.status
  FROM public.tenant AS t
  LEFT JOIN public.app_user AS u
    ON u.tsf_user_id = p_tsf_user_id
  LEFT JOIN public.tenant_membership AS m
    ON m.tenant_id = t.id
   AND m.user_id = u.id
  WHERE t.tsf_shop_id = p_tsf_shop_id
$fn$;

ALTER FUNCTION upsert_app_user(text, text, text) OWNER TO oms_maint;
ALTER FUNCTION provision_tenant(text, text, text, text, timestamptz, bigint) OWNER TO oms_maint;
ALTER FUNCTION provision_membership(uuid, uuid, text) OWNER TO oms_maint;
ALTER FUNCTION lookup_login(text, text) OWNER TO oms_maint;

REVOKE ALL ON FUNCTION upsert_app_user(text, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION provision_tenant(text, text, text, text, timestamptz, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION provision_membership(uuid, uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION lookup_login(text, text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION upsert_app_user(text, text, text) TO oms_app;
GRANT EXECUTE ON FUNCTION provision_tenant(text, text, text, text, timestamptz, bigint) TO oms_app;
GRANT EXECUTE ON FUNCTION provision_membership(uuid, uuid, text) TO oms_app;
GRANT EXECUTE ON FUNCTION lookup_login(text, text) TO oms_app;

COMMENT ON FUNCTION upsert_app_user(text, text, text) IS
  'JIT user upsert. Returns id only. Owned by oms_maint.';
COMMENT ON FUNCTION provision_tenant(text, text, text, text, timestamptz, bigint) IS
  'JIT tenant upsert by tsf_shop_id. Does not downgrade ent_ver. Returns id only.';
COMMENT ON FUNCTION provision_membership(uuid, uuid, text) IS
  'JIT membership upsert. Does not reactivate REVOKED. Returns id only.';
COMMENT ON FUNCTION lookup_login(text, text) IS
  'Read-only. Returns ids, ent_ver, and membership status. No other columns.';
