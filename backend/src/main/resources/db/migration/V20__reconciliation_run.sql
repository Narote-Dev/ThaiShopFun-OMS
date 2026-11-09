-- V20: per-tenant reconciliation run history (T22). reconciliation_issue.run_id references this.

CREATE TABLE reconciliation_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  trigger text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  issues_found int NOT NULL DEFAULT 0,
  CONSTRAINT reconciliation_run_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT reconciliation_run_trigger_check CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
  CONSTRAINT reconciliation_run_issues_found_check CHECK (issues_found >= 0)
);

CREATE INDEX reconciliation_run_tenant_started_idx
  ON reconciliation_run (tenant_id, started_at DESC);

CREATE INDEX shipment_tenant_delivered_at_idx
  ON shipment (tenant_id, delivered_at)
  WHERE delivered_at IS NOT NULL;

DO $rls$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['reconciliation_run']
  LOOP
    EXECUTE format('ALTER TABLE public.%I OWNER TO oms_migrator', t);
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON public.%I '
        || 'USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) '
        || 'WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
      t
    );
    EXECUTE format('REVOKE ALL ON TABLE public.%I FROM PUBLIC', t);
  END LOOP;
END
$rls$;

GRANT SELECT, INSERT, UPDATE ON reconciliation_run TO oms_app, oms_maint;

COMMENT ON TABLE reconciliation_run IS
  'One row per nightly or manual reconciliation pass for a tenant (T22).';
