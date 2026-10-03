-- T12B: channel_listing mapping metadata + durable inbox orphan marker

ALTER TABLE channel_listing
  ADD COLUMN seller_sku text NULL,
  ADD COLUMN name text NULL,
  ADD COLUMN mapping_source text NULL,
  ADD COLUMN mapped_at timestamptz NULL,
  ADD COLUMN removed_at timestamptz NULL;

ALTER TABLE channel_listing
  ADD CONSTRAINT channel_listing_mapping_source_check
    CHECK (mapping_source IS NULL OR mapping_source IN ('AUTO', 'MANUAL'));

ALTER TABLE channel_listing
  ADD CONSTRAINT channel_listing_mapping_source_sku_check
    CHECK (
      (sku_id IS NULL AND mapping_source IS NULL AND mapped_at IS NULL)
      OR (sku_id IS NOT NULL AND mapping_source IS NOT NULL AND mapped_at IS NOT NULL)
    );

UPDATE channel_listing
SET mapping_source = 'MANUAL',
    mapped_at = pg_catalog.now()
WHERE sku_id IS NOT NULL;

CREATE INDEX channel_listing_unmapped_account_idx
  ON channel_listing (tenant_id, channel_account_id)
  WHERE sku_id IS NULL;

ALTER TABLE inbox_event
  ADD COLUMN orphan_recorded_at timestamptz NULL;

UPDATE inbox_event
SET orphan_recorded_at = pg_catalog.now()
WHERE last_error = 'ORDER_EVENT_WITHOUT_ORDER';
