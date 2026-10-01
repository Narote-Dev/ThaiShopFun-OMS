-- V9: cross-tenant lookup for checkout DELETE (plan task T12A).
--
-- DELETE carries only reservation_group_id. oms_app cannot see other tenants' rows under FORCE RLS.
-- Same pattern as V1/V6: SECURITY DEFINER, owned by oms_maint, returns tenant_id only.

CREATE INDEX stock_reservation_group_id_idx ON stock_reservation (reservation_group_id);

CREATE FUNCTION resolve_reservation_tenant(p_reservation_group_id uuid)
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
  SELECT tenant_id
  FROM public.stock_reservation
  WHERE reservation_group_id = p_reservation_group_id
  LIMIT 1
$fn$;

ALTER FUNCTION resolve_reservation_tenant(uuid) OWNER TO oms_maint;
REVOKE ALL ON FUNCTION resolve_reservation_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_reservation_tenant(uuid) TO oms_app;

COMMENT ON FUNCTION resolve_reservation_tenant(uuid) IS
  'Cross-tenant. Returns tenant_id only for one reservation_group_id, or NULL when unknown. Read-only.';
