-- List snapshot filter (created_at) with keyset sort (ordered_at DESC, id DESC).
CREATE INDEX sales_order_tenant_created_ordered_id_idx
  ON sales_order (tenant_id, created_at, ordered_at DESC, id DESC);
