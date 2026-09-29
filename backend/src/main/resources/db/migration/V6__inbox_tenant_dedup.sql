-- V6: inbox dedup is per tenant, and aggregate_version is a real column.
--
-- V1 used UNIQUE (source, event_id). That key is global. Two shops delivering the
-- same event_id collide, and under RLS the second insert fails instead of
-- deduping inside the caller's tenant (the existing row is invisible).
-- Delivery is at-least-once per shop, so the key is (tenant_id, source, event_id).
--
-- claim_inbox_batch is unchanged on purpose. It does not filter entitlement.
-- The worker decides ACTIVE / GRACE / SUSPENDED after the claim, so a suspended
-- shop's rows stay queued instead of disappearing from the claim.
--
-- V3 (T06), V4 (T10), and V5 (T50M) are reserved and are not in this version.
-- V7 belongs to the outbox work. spring.flyway.out-of-order lets those land later.

ALTER TABLE inbox_event
  ADD COLUMN aggregate_version bigint NOT NULL DEFAULT 0;

ALTER TABLE inbox_event
  ADD CONSTRAINT inbox_event_aggregate_version_check CHECK (aggregate_version >= 0);

ALTER TABLE inbox_event
  DROP CONSTRAINT inbox_event_source_event_key;

ALTER TABLE inbox_event
  ADD CONSTRAINT inbox_event_tenant_source_event_key
  UNIQUE (tenant_id, source, event_id);

-- Step 1: The version lookup is per tenant, source, and aggregate, and only
-- reads rows the worker has already finished.
CREATE INDEX inbox_event_aggregate_processed_idx
  ON inbox_event (tenant_id, source, aggregate_id, aggregate_version DESC)
  WHERE status = 'PROCESSED';

-- Step 2: Flyway runs as the bootstrap superuser. Hand the new index to the
-- table owner so a later migrator can drop it. The unique index follows.
ALTER INDEX inbox_event_aggregate_processed_idx OWNER TO oms_migrator;
ALTER INDEX inbox_event_tenant_source_event_key OWNER TO oms_migrator;

COMMENT ON COLUMN inbox_event.aggregate_version IS
  'Envelope aggregate_version. 0 means the sender omitted it. A version at or below the latest PROCESSED version for the same aggregate is stored and not handled again.';

COMMENT ON CONSTRAINT inbox_event_tenant_source_event_key ON inbox_event IS
  'At-least-once dedup per tenant. Replaces V1 UNIQUE (source, event_id).';
