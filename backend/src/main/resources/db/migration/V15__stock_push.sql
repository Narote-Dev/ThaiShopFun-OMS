-- Durable dirty markers and retained listing version state. Clean rows are retained forever
-- (until listing deletion), so remapping cannot reuse an emitted version.
CREATE TABLE stock_push_pending (
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  channel_account_id uuid NOT NULL,
  listing_id uuid NOT NULL,
  dirty_since timestamptz,
  changed_at timestamptz,
  mapping_revision bigint NOT NULL DEFAULT 0 CHECK (mapping_revision >= 0),
  source_key text,
  observed_qty integer CHECK (observed_qty IS NULL OR observed_qty >= 0),
  stock_version bigint NOT NULL DEFAULT 0 CHECK (stock_version >= 0),
  PRIMARY KEY (tenant_id, listing_id),
  FOREIGN KEY (tenant_id, listing_id) REFERENCES channel_listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, channel_account_id) REFERENCES channel_account(tenant_id, id)
);
CREATE INDEX stock_push_pending_due_idx ON stock_push_pending(tenant_id, channel_account_id, dirty_since)
  WHERE dirty_since IS NOT NULL;
ALTER TABLE stock_push_pending OWNER TO oms_migrator;
ALTER TABLE stock_push_pending ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_push_pending FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_push_pending
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON stock_push_pending FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON stock_push_pending TO oms_app, oms_maint;

-- Capture every mapping writer, including manual mapping, auto-map, revive and demo seed.
-- No stock locks; invoker privileges and RLS apply. Updating pushed markers does not fire it.
CREATE FUNCTION mark_listing_stock_push() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF ROW(NEW.sku_id, NEW.removed_at, NEW.seller_sku, NEW.safety_buffer, NEW.mapped_at)
       IS NOT DISTINCT FROM
       ROW(OLD.sku_id, OLD.removed_at, OLD.seller_sku, OLD.safety_buffer, OLD.mapped_at) THEN
      RETURN NEW;
    END IF;
  END IF;
  INSERT INTO public.stock_push_pending
    (tenant_id, channel_account_id, listing_id, dirty_since, changed_at, mapping_revision)
  VALUES (NEW.tenant_id, NEW.channel_account_id, NEW.id, clock_timestamp(), clock_timestamp(), 1)
  ON CONFLICT (tenant_id, listing_id) DO UPDATE
    SET dirty_since = coalesce(stock_push_pending.dirty_since, EXCLUDED.dirty_since),
        changed_at = coalesce(stock_push_pending.changed_at, EXCLUDED.changed_at),
        mapping_revision = stock_push_pending.mapping_revision + 1;
  RETURN NEW;
END
$$;
ALTER FUNCTION mark_listing_stock_push() OWNER TO oms_migrator;
REVOKE ALL ON FUNCTION mark_listing_stock_push() FROM PUBLIC;
CREATE TRIGGER channel_listing_stock_push
  AFTER INSERT OR UPDATE OF sku_id, removed_at, seller_sku, safety_buffer, mapped_at
  ON channel_listing FOR EACH ROW EXECUTE FUNCTION mark_listing_stock_push();

ALTER TABLE shadow_diff DROP CONSTRAINT shadow_diff_kind_check;
ALTER TABLE shadow_diff ADD CONSTRAINT shadow_diff_kind_check
  CHECK (kind IN ('STOCK', 'ORDER', 'RESERVATION', 'STOCK_PUSH'));
ALTER TABLE outbox_event ADD COLUMN stock_changed_at timestamptz;

CREATE FUNCTION list_stock_push_accounts(p_full boolean)
RETURNS TABLE (tenant_id uuid, channel_account_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
  SELECT ca.tenant_id, ca.id FROM public.channel_account ca
  WHERE ca.channel = 'TSF' AND (p_full OR EXISTS (
    SELECT 1 FROM public.stock_push_pending p
    WHERE p.tenant_id = ca.tenant_id AND p.channel_account_id = ca.id
      AND p.dirty_since IS NOT NULL
  )) ORDER BY ca.tenant_id, ca.id
$$;
ALTER FUNCTION list_stock_push_accounts(boolean) OWNER TO oms_maint;
REVOKE ALL ON FUNCTION list_stock_push_accounts(boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_stock_push_accounts(boolean) TO oms_app;
COMMENT ON FUNCTION list_stock_push_accounts(boolean) IS
  'Cross-tenant discovery returns tenant and TSF account ids only. Full includes clean accounts.';
