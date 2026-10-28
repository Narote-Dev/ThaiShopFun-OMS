-- V17: pick lists and per-line pack scan progress (T18a). Label/ship stays in T18b.
--
-- pick_list groups READY_TO_PICK orders into PICKING. order_pack_progress tracks barcode
-- scans until fulfillment moves to PACKED. No label PDF storage.

CREATE TABLE pick_list (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  warehouse_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid,
  CONSTRAINT pick_list_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT pick_list_warehouse_fkey FOREIGN KEY (tenant_id, warehouse_id)
    REFERENCES warehouse (tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE pick_list_order (
  tenant_id uuid NOT NULL,
  pick_list_id uuid NOT NULL,
  order_id uuid NOT NULL,
  sequence int NOT NULL,
  released_at timestamptz,
  PRIMARY KEY (tenant_id, pick_list_id, order_id),
  CONSTRAINT pick_list_order_pick_fkey FOREIGN KEY (tenant_id, pick_list_id)
    REFERENCES pick_list (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT pick_list_order_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT pick_list_order_sequence_check CHECK (sequence >= 0)
);

CREATE UNIQUE INDEX pick_list_order_active_order_uq ON pick_list_order (tenant_id, order_id)
  WHERE released_at IS NULL;

CREATE TABLE order_pack_progress (
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  packed_qty int NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, order_id, order_line_id),
  CONSTRAINT order_pack_progress_line_fkey FOREIGN KEY (tenant_id, order_line_id)
    REFERENCES order_line (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT order_pack_progress_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT order_pack_progress_qty_check CHECK (packed_qty >= 0)
);

CREATE INDEX pick_list_tenant_created_idx ON pick_list (tenant_id, created_at DESC);

DO $rls$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['pick_list', 'pick_list_order', 'order_pack_progress']
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

GRANT SELECT, INSERT, UPDATE, DELETE ON pick_list, pick_list_order, order_pack_progress
  TO oms_app, oms_maint;
