-- T27: retention markers and ids-only work discovery across ALL entitlement states.
ALTER TABLE inbox_event ADD COLUMN payload_scrubbed_at timestamptz;
ALTER TABLE outbox_event ADD COLUMN payload_scrubbed_at timestamptz;
CREATE INDEX inbox_pii_due_idx ON inbox_event (coalesce(processed_at, received_at), tenant_id)
  WHERE payload_scrubbed_at IS NULL AND status IN ('PROCESSED', 'DEAD');
CREATE INDEX outbox_pii_due_idx ON outbox_event (coalesce(sent_at, created_at), tenant_id)
  WHERE payload_scrubbed_at IS NULL AND status IN ('SENT', 'DEAD');

-- V6 SECURITY DEFINER pattern: pinned path, qualified tables, maintenance owner,
-- no public execute. FORCE RLS and existing policies are unchanged.
CREATE FUNCTION list_tenants_with_pii_due(p_now timestamptz)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
  SELECT r.tenant_id FROM public.order_recipient r
  WHERE r.pii_status = 'ACTIVE' AND r.redact_after <= p_now
  UNION
  SELECT i.tenant_id FROM public.inbox_event i
  WHERE i.payload_scrubbed_at IS NULL AND i.status IN ('PROCESSED', 'DEAD')
    AND coalesce(i.processed_at, i.received_at) <= p_now - interval '7 days'
  UNION
  SELECT o.tenant_id FROM public.outbox_event o
  WHERE o.payload_scrubbed_at IS NULL AND o.status IN ('SENT', 'DEAD')
    AND coalesce(o.sent_at, o.created_at) <= p_now - interval '7 days'
$fn$;
ALTER FUNCTION list_tenants_with_pii_due(timestamptz) OWNER TO oms_maint;
REVOKE ALL ON FUNCTION list_tenants_with_pii_due(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_tenants_with_pii_due(timestamptz) TO oms_app;
COMMENT ON FUNCTION list_tenants_with_pii_due(timestamptz) IS
  'Read-only tenant ids for recipient redaction and terminal event payload scrubbing. All entitlement states.';
