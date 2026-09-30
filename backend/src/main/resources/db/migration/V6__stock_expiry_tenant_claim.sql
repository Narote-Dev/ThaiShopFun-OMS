-- V6: cross-tenant lookup for the stock reservation expiry job (plan task T08).
--
-- V5 is unused: it was reserved for T07, which shipped without a migration. Never add a V5
-- later. Flyway outOfOrder is off, so a V5 added after V6 is applied would fail validation.
-- This migration only adds one function. No table changes.
--
-- oms_app cannot see other tenants' reservations (FORCE RLS), and list_active_tenant_ids
-- returns ACTIVE tenants only, so GRACE and SUSPENDED shops' holds would never expire.
-- This follows the V1/V3 claim pattern: SECURITY DEFINER, owned by oms_maint (BYPASSRLS),
-- pinned search_path, fully qualified names, and it returns tenant ids only. It does not
-- modify rows. The job then opens one transaction per tenant with that tenant's context,
-- locks inventory in id order, and re-checks status and expires_at under the lock.
--
-- p_now comes from the application clock so tests are deterministic.
-- p_limit must be 1..1000. A sql function cannot RAISE, so an out-of-range limit fails the
-- integer cast of a message that names the bound (SQLSTATE 22P02). The message depends on
-- p_limit, so the planner cannot fold it into an error when the limit is valid.

-- Step 1: Distinct tenants that have at least one ACTIVE reservation past p_now.
-- Uses stock_reservation_active_expiry_idx (status, expires_at) WHERE status = 'ACTIVE'.
CREATE FUNCTION list_tenants_with_expired_reservations(p_now timestamptz, p_limit integer)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
  SELECT DISTINCT r.tenant_id
  FROM public.stock_reservation AS r
  WHERE r.status = 'ACTIVE'
    AND r.expires_at <= p_now
  LIMIT CASE
    WHEN p_limit BETWEEN 1 AND 1000 THEN p_limit
    ELSE (
      'list_tenants_with_expired_reservations limit must be between 1 and 1000, got '
        || coalesce(p_limit::text, 'null')
    )::integer
  END
$fn$;

-- Step 2: Owned by oms_maint so FORCE RLS does not hide other tenants. Only oms_app runs it.
ALTER FUNCTION list_tenants_with_expired_reservations(timestamptz, integer) OWNER TO oms_maint;
REVOKE ALL ON FUNCTION list_tenants_with_expired_reservations(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_tenants_with_expired_reservations(timestamptz, integer) TO oms_app;

COMMENT ON FUNCTION list_tenants_with_expired_reservations(timestamptz, integer) IS
  'Cross-tenant. Returns tenant_id only, for tenants with ACTIVE reservations where expires_at <= p_now. Any entitlement status. Read-only. p_limit 1..1000.';
