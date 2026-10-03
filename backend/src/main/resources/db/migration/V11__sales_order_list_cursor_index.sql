-- Keyset list pagination: tenant-scoped ordered_at DESC, id DESC.
CREATE INDEX sales_order_tenant_ordered_id_desc_idx
  ON sales_order (tenant_id, ordered_at DESC, id DESC);
