-- V18 (T18b): transient label PDF bytes keyed by shipment. Purged via shipment.label_cached_until.
-- Does not replace shipment row (V7); only holds printable bytes until expiry after SHIPPED.

CREATE TABLE shipment_label_cache (
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  shipment_id uuid NOT NULL,
  kind text NOT NULL DEFAULT 'LABEL',
  pdf bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, shipment_id),
  CONSTRAINT shipment_label_cache_shipment_fkey FOREIGN KEY (tenant_id, shipment_id)
    REFERENCES shipment (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT shipment_label_cache_kind_check CHECK (kind IN ('LABEL', 'PACKING_SLIP'))
);

CREATE INDEX shipment_label_cache_tenant_created_idx
  ON shipment_label_cache (tenant_id, created_at);

-- Step 1: Cross-tenant discovery for purge job (all entitlement states).
CREATE FUNCTION list_tenants_with_label_cache_due(p_now timestamptz)
RETURNS TABLE(tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT DISTINCT s.tenant_id
  FROM shipment s
  JOIN shipment_label_cache slc ON slc.tenant_id = s.tenant_id AND slc.shipment_id = s.id
  WHERE s.label_cached_until IS NOT NULL AND s.label_cached_until <= p_now;
$$;

ALTER FUNCTION list_tenants_with_label_cache_due(timestamptz) OWNER TO oms_maint;
REVOKE ALL ON FUNCTION list_tenants_with_label_cache_due(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_tenants_with_label_cache_due(timestamptz) TO oms_app;

COMMENT ON FUNCTION list_tenants_with_label_cache_due(timestamptz) IS
  'Returns tenant ids with label PDF cache past label_cached_until (T18 purge).';

DO $rls$
BEGIN
  EXECUTE 'ALTER TABLE public.shipment_label_cache OWNER TO oms_migrator';
  EXECUTE 'ALTER TABLE public.shipment_label_cache ENABLE ROW LEVEL SECURITY';
  EXECUTE 'ALTER TABLE public.shipment_label_cache FORCE ROW LEVEL SECURITY';
  EXECUTE
    'CREATE POLICY tenant_isolation ON public.shipment_label_cache '
      || 'USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) '
      || 'WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)';
  EXECUTE 'REVOKE ALL ON TABLE public.shipment_label_cache FROM PUBLIC';
END
$rls$;

GRANT SELECT, INSERT, UPDATE, DELETE ON shipment_label_cache TO oms_app, oms_maint;
