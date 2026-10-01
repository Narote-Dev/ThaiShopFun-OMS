-- V8: commit-consistent ledger order (plan task T08A).
--
-- Flyway must run this migration as the database superuser (the Flyway URL user). That role
-- sees every row despite FORCE RLS. oms_migrator would not, so the backfill would assign no
-- sequences and SET NOT NULL would fail on existing databases.
--
-- created_at is the transaction start time, so concurrent writers on the same inventory row can
-- appear out of commit order in history. Each inventory row keeps ledger_seq, the count of
-- ledger rows already committed for that (tenant, sku, warehouse). The app increments it while
-- holding the inventory row lock and stores the value on each new inventory_ledger row.

-- Step 1: Per-inventory-row sequence counter.
ALTER TABLE inventory
  ADD COLUMN ledger_seq bigint NOT NULL DEFAULT 0;

ALTER TABLE inventory
  ADD CONSTRAINT inventory_ledger_seq_check CHECK (ledger_seq >= 0);

-- Step 2: Backfill existing ledger rows in commit-time order, then require the column.
ALTER TABLE inventory_ledger
  ADD COLUMN ledger_seq bigint;

-- Change: V4 append-only trigger rejects UPDATE; disable only for the backfill.
ALTER TABLE inventory_ledger DISABLE TRIGGER inventory_ledger_append_only;

WITH numbered AS (
  SELECT
    l.id,
    row_number() OVER (
      PARTITION BY l.tenant_id, l.sku_id, l.warehouse_id
      ORDER BY l.created_at, l.id
    ) AS seq
  FROM inventory_ledger AS l
)
UPDATE inventory_ledger AS l
SET ledger_seq = n.seq
FROM numbered AS n
WHERE l.id = n.id;

ALTER TABLE inventory_ledger ENABLE TRIGGER inventory_ledger_append_only;

ALTER TABLE inventory_ledger
  ALTER COLUMN ledger_seq SET NOT NULL;

UPDATE inventory AS i
SET ledger_seq = s.max_seq
FROM (
  SELECT tenant_id, sku_id, warehouse_id, max(ledger_seq) AS max_seq
  FROM inventory_ledger
  GROUP BY tenant_id, sku_id, warehouse_id
) AS s
WHERE i.tenant_id = s.tenant_id
  AND i.sku_id = s.sku_id
  AND i.warehouse_id = s.warehouse_id;

CREATE UNIQUE INDEX inventory_ledger_tenant_sku_wh_seq_key
  ON inventory_ledger (tenant_id, sku_id, warehouse_id, ledger_seq);

CREATE INDEX inventory_ledger_tenant_sku_seq_idx
  ON inventory_ledger (tenant_id, sku_id, warehouse_id, ledger_seq DESC);

COMMENT ON COLUMN inventory.ledger_seq IS
  'Count of ledger rows committed for this stock row. Incremented under row lock on write.';
COMMENT ON COLUMN inventory_ledger.ledger_seq IS
  'Monotonic per (tenant, sku, warehouse); history and running totals order by this, not created_at.';
