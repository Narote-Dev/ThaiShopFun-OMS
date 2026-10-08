-- T12B-FU: per-order hold resolver backoff (sweeper rotation)

CREATE TABLE order_hold_retry (
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  order_id uuid NOT NULL,
  attempts int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  last_error text NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT order_hold_retry_pkey PRIMARY KEY (tenant_id, order_id),
  CONSTRAINT order_hold_retry_sales_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX order_hold_retry_tenant_next_attempt_idx
  ON order_hold_retry (tenant_id, next_attempt_at);

DO $rls$
BEGIN
  EXECUTE 'ALTER TABLE public.order_hold_retry OWNER TO oms_migrator';
  EXECUTE 'ALTER TABLE public.order_hold_retry ENABLE ROW LEVEL SECURITY';
  EXECUTE 'ALTER TABLE public.order_hold_retry FORCE ROW LEVEL SECURITY';
  EXECUTE $policy$
    CREATE POLICY tenant_isolation ON public.order_hold_retry
      USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
      WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  $policy$;
  EXECUTE 'REVOKE ALL ON TABLE public.order_hold_retry FROM PUBLIC';
END
$rls$;

GRANT SELECT, INSERT, UPDATE, DELETE ON order_hold_retry TO oms_app, oms_maint;
