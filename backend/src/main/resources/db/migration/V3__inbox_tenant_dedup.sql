-- V3: inbox dedup is per tenant. aggregate_version and payload_sha256 are real columns.
--
-- V1 used UNIQUE (source, event_id). That key is global. Two shops delivering the
-- same event_id collide, and under RLS the second insert fails instead of
-- deduping inside the caller's tenant (the existing row is invisible).
-- Delivery is at-least-once per shop, so the key is (tenant_id, source, event_id).
--
-- claim_inbox_batch now also returns next_attempt_at. That timestamp is the lease
-- token the worker must still hold when it updates the row. Entitlement is not
-- filtered here; the worker decides after the claim.

ALTER TABLE inbox_event
  ADD COLUMN aggregate_version bigint NOT NULL DEFAULT 0;

ALTER TABLE inbox_event
  ADD CONSTRAINT inbox_event_aggregate_version_check CHECK (aggregate_version >= 0);

ALTER TABLE inbox_event
  ADD COLUMN payload_sha256 bytea NOT NULL DEFAULT '\x'::bytea;

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

-- Step 2: Replace the claim function so the lease timestamp comes back with the ids.
-- CREATE OR REPLACE cannot change the return type.
DROP FUNCTION claim_inbox_batch(integer, interval);

CREATE FUNCTION claim_inbox_batch(
  n integer,
  p_lease interval DEFAULT interval '5 minutes'
)
RETURNS TABLE (id uuid, tenant_id uuid, next_attempt_at timestamptz)
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  -- Step 1: Reject a limit or lease that would scan an unbounded batch or hide it too long.
  IF n IS NULL OR n < 1 OR n > 1000 THEN
    RAISE EXCEPTION 'claim_inbox_batch limit must be between 1 and 1000';
  END IF;
  IF p_lease IS NULL OR p_lease <= interval '0' OR p_lease > interval '1 hour' THEN
    RAISE EXCEPTION 'claim_inbox_batch lease must be greater than 0 and at most 1 hour';
  END IF;

  -- Step 2: Lease due RECEIVED/FAILED rows. The lease is next_attempt_at.
  -- SKIP LOCKED keeps parallel workers off the same row.
  RETURN QUERY
  WITH picked AS (
    SELECT i.id
    FROM public.inbox_event AS i
    WHERE i.status IN ('RECEIVED', 'FAILED')
      AND (i.next_attempt_at IS NULL OR i.next_attempt_at <= now())
    ORDER BY i.next_attempt_at NULLS FIRST, i.received_at, i.id
    LIMIT n
    FOR UPDATE SKIP LOCKED
  )
  UPDATE public.inbox_event AS e
  SET attempts = e.attempts + 1,
      next_attempt_at = pg_catalog.now() + p_lease
  FROM picked
  WHERE e.id = picked.id
  RETURNING e.id, e.tenant_id, e.next_attempt_at;
END
$fn$;

ALTER FUNCTION claim_inbox_batch(integer, interval) OWNER TO oms_maint;
REVOKE ALL ON FUNCTION claim_inbox_batch(integer, interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_inbox_batch(integer, interval) TO oms_app;

COMMENT ON COLUMN inbox_event.aggregate_version IS
  'Envelope aggregate_version. 0 means the sender omitted it. A version at or below the latest PROCESSED version for the same aggregate is stored and not handled again.';

COMMENT ON COLUMN inbox_event.payload_sha256 IS
  'SHA-256 of the raw body stored on the first insert. A later delivery with the same event id and a different hash is still a duplicate.';

COMMENT ON CONSTRAINT inbox_event_tenant_source_event_key ON inbox_event IS
  'At-least-once dedup per tenant. Replaces V1 UNIQUE (source, event_id).';

COMMENT ON FUNCTION claim_inbox_batch(integer, interval) IS
  'Cross-tenant lease of inbox rows. FOR UPDATE SKIP LOCKED. Returns id, tenant_id, and the lease timestamp next_attempt_at.';
