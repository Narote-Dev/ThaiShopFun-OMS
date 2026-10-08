-- V14: Tenants eligible for scheduled order backfill (ACTIVE + unexpired GRACE, connected TSF).

CREATE FUNCTION list_tenants_for_order_backfill()
RETURNS TABLE (id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT t.id
  FROM tenant AS t
  WHERE (
      t.entitlement_status = 'ACTIVE'
      OR (
        t.entitlement_status = 'GRACE'
        AND (t.entitlement_expires_at IS NULL OR t.entitlement_expires_at > pg_catalog.now())
      )
    )
    AND NOT (
      t.entitlement_expires_at IS NOT NULL
      AND t.entitlement_expires_at <= pg_catalog.now()
      AND t.entitlement_status IN ('ACTIVE', 'GRACE')
    )
    AND EXISTS (
      SELECT 1
      FROM channel_account AS ca
      WHERE ca.tenant_id = t.id
        AND ca.channel = 'TSF'
        AND ca.status <> 'DISCONNECTED'
    );
$$;

ALTER FUNCTION list_tenants_for_order_backfill() OWNER TO oms_maint;
REVOKE ALL ON FUNCTION list_tenants_for_order_backfill() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_tenants_for_order_backfill() TO oms_app;

COMMENT ON FUNCTION list_tenants_for_order_backfill() IS
  'Cross-tenant. Returns id only for ACTIVE/unexpired GRACE tenants with a connected TSF account.';
