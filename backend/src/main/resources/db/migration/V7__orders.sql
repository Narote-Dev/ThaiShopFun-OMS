-- V7: order-side schema (plan task T10). Orders, recipients (encrypted PII), lines, status
-- history, shipments, returns, refunds, payment snapshots, sync cursors, shadow diffs, and
-- reconciliation issues. Columns follow docs/plan/03-data-model.md. Status values follow
-- docs/plan/01-process-map.md.
--
-- V5 is permanently unused (see V6). V8 is reserved for T08A.
--
-- Conventions, same as V4:
-- * uuid primary keys with no database default. The application generates UUIDv7.
-- * oms_migrator owns every table and trigger function. oms_app is DML only.
-- * ENABLE + FORCE RLS on every table, policy tenant_isolation, fail closed with no context.
-- * Tenant-consistent foreign keys: every entity table has UNIQUE (tenant_id, id) and children
--   reference (tenant_id, parent_id). ON DELETE RESTRICT, except order_recipient, which is
--   ON DELETE CASCADE so the PII row always goes with its order.
-- * Money is numeric(14,2) plus currency. currency is CHECK (currency = 'THB'): the plan is
--   THB-only, and a new currency should be a deliberate migration, not a silent insert.
-- * No PII outside order_recipient. name/phone/address are AES-256-GCM ciphertext written by
--   the application (com.thaishopfun.oms.pii.PiiCipher). The key never reaches SQL.
-- * No cross-tenant functions here. The redaction sweep and reconciliation jobs (later tasks)
--   will add V6-style SECURITY DEFINER id-returning functions.
--
-- source_event_id on refund and payment_status_snapshot is UNIQUE (tenant_id, source_event_id),
-- not global. Event ids are only unique per shop: the V1 inbox used a global UNIQUE
-- (source, event_id) and collided across shops, which V3 had to fix.
--
-- Trigger errors use SQLSTATE 23514 (check_violation), a stable message prefix, and a
-- constraint name, like V4:
-- * RETURN_QTY_EXCEEDED     (constraint return_line_qty_limit): the return qty of an order line,
--                           over requests that were never rejected, would exceed order_line.qty.
-- * READ_COMMITTED_REQUIRED (constraint return_line_isolation): a return qty check under a
--                           snapshot isolation level, where the SUM could miss a concurrent
--                           commit.
-- * RETURN_REJECTED_FINAL   (constraint return_request_rejected_sticky): clearing
--                           return_request.rejected once it is set.
-- * RETURN_REJECTED_FINAL   (constraint return_request_status_transition): a rejected request
--                           moving to any status other than REJECTED or CLOSED.
--
-- Lock order for the return checks: return_request (FOR SHARE) before order_line (FOR UPDATE,
-- in id order). Within one statement the checks cannot deadlock with each other, but two
-- transactions that insert return lines for the same order lines in opposite orders can.
-- Note for T13:
-- * insert the return lines of a request sorted by order_line_id;
-- * touch (lock or update) the return_request before any order_line in the same transaction;
-- * retry the whole transaction on 40P01 (deadlock_detected).

-- Step 1: Orders. No PII here. channel_status is the channel's own status text.
-- version is the optimistic lock (03 principles). external_version is the channel's version.
CREATE TABLE sales_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  channel_account_id uuid NOT NULL,
  external_order_id text NOT NULL,
  order_status text NOT NULL DEFAULT 'ACTIVE',
  payment_status text NOT NULL,
  fulfillment_status text NOT NULL DEFAULT 'UNFULFILLED',
  hold_reason text NOT NULL DEFAULT 'NONE',
  hold_note text,
  channel_status text,
  payment_method text NOT NULL,
  currency text NOT NULL DEFAULT 'THB',
  subtotal numeric(14, 2) NOT NULL DEFAULT 0,
  shipping_fee numeric(14, 2) NOT NULL DEFAULT 0,
  discount numeric(14, 2) NOT NULL DEFAULT 0,
  grand_total numeric(14, 2) NOT NULL DEFAULT 0,
  ordered_at timestamptz NOT NULL,
  paid_at timestamptz,
  ship_by timestamptz,
  completed_at timestamptz,
  external_version bigint,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sales_order_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT sales_order_channel_account_external_order_key
    UNIQUE (channel_account_id, external_order_id),
  CONSTRAINT sales_order_channel_account_fkey FOREIGN KEY (tenant_id, channel_account_id)
    REFERENCES channel_account (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT sales_order_external_order_id_check CHECK (btrim(external_order_id) <> ''),
  CONSTRAINT sales_order_order_status_check CHECK (
    order_status IN ('ACTIVE', 'CANCELLED', 'COMPLETED')
  ),
  CONSTRAINT sales_order_payment_status_check CHECK (
    payment_status IN ('PENDING', 'PAID', 'COD_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED')
  ),
  CONSTRAINT sales_order_fulfillment_status_check CHECK (
    fulfillment_status IN (
      'UNFULFILLED', 'READY_TO_PICK', 'PICKING', 'PACKED', 'SHIPPED', 'DELIVERED'
    )
  ),
  CONSTRAINT sales_order_hold_reason_check CHECK (
    hold_reason IN (
      'NONE',
      'SKU_NOT_MAPPED',
      'OUT_OF_STOCK',
      'ADDRESS_PROBLEM',
      'PAYMENT_MISMATCH',
      'CHANNEL_CANCEL_PENDING',
      'MANUAL'
    )
  ),
  CONSTRAINT sales_order_payment_method_check CHECK (payment_method IN ('PREPAID', 'COD')),
  CONSTRAINT sales_order_currency_check CHECK (currency = 'THB'),
  CONSTRAINT sales_order_amounts_check CHECK (
    subtotal >= 0 AND shipping_fee >= 0 AND discount >= 0 AND grand_total >= 0
  ),
  CONSTRAINT sales_order_version_check CHECK (version >= 0),
  CONSTRAINT sales_order_external_version_check CHECK (
    external_version IS NULL OR external_version >= 0
  )
);

-- Step 2: Recipient PII, one row per order. *_enc columns are
-- version(1) || kid length(1) || kid || nonce(12) || ciphertext+tag. The AAD is that header
-- (version || kid length || kid) plus tenant, order, and column.
-- phone_hash is HMAC-SHA256 (32 bytes) of the normalized phone, for search. phone_last4 is clear
-- text. Redaction (PDPA, 03 "PII lifecycle") leaves only province and postcode: the three *_enc
-- columns, phone_hash, and phone_last4 are all nulled, so no searchable identifier remains.
CREATE TABLE order_recipient (
  order_id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  name_enc bytea,
  phone_enc bytea,
  phone_hash bytea,
  phone_last4 text,
  address_enc bytea,
  province text,
  postcode text,
  pii_status text NOT NULL DEFAULT 'ACTIVE',
  redact_after timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT order_recipient_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT order_recipient_pii_status_check CHECK (pii_status IN ('ACTIVE', 'REDACTED')),
  CONSTRAINT order_recipient_redacted_check CHECK (
    pii_status <> 'REDACTED' OR (name_enc IS NULL AND phone_enc IS NULL AND address_enc IS NULL)
  ),
  CONSTRAINT order_recipient_active_check CHECK (
    pii_status <> 'ACTIVE' OR (name_enc IS NOT NULL AND address_enc IS NOT NULL)
  ),
  CONSTRAINT order_recipient_phone_check CHECK (
    CASE pii_status
      WHEN 'REDACTED' THEN phone_hash IS NULL AND phone_last4 IS NULL
      ELSE (phone_enc IS NULL) = (phone_hash IS NULL)
    END
  ),
  CONSTRAINT order_recipient_phone_hash_check CHECK (
    phone_hash IS NULL OR octet_length(phone_hash) = 32
  ),
  CONSTRAINT order_recipient_phone_last4_check CHECK (
    phone_last4 IS NULL OR phone_last4 ~ '^[0-9]{4}$'
  )
);

-- Step 3: Order lines. sku_id is null while the channel SKU is unmapped (hold SKU_NOT_MAPPED).
-- external_line_id is the channel line id (line_id in 04 section 4.5), which return events use.
-- UNIQUE (tenant_id, order_id, id) is the target of return_line's same-order foreign key.
CREATE TABLE order_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  order_id uuid NOT NULL,
  sku_id uuid,
  external_line_id text,
  external_sku_id text,
  name text NOT NULL,
  qty integer NOT NULL,
  unit_price numeric(14, 2) NOT NULL DEFAULT 0,
  discount numeric(14, 2) NOT NULL DEFAULT 0,
  line_total numeric(14, 2) NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT order_line_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT order_line_tenant_order_id_key UNIQUE (tenant_id, order_id, id),
  CONSTRAINT order_line_order_external_line_key UNIQUE (order_id, external_line_id),
  CONSTRAINT order_line_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT order_line_sku_fkey FOREIGN KEY (tenant_id, sku_id)
    REFERENCES sku (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT order_line_qty_check CHECK (qty > 0),
  CONSTRAINT order_line_amounts_check CHECK (
    unit_price >= 0 AND discount >= 0 AND line_total >= 0
  )
);

-- Step 4: Status history, append-only (trigger, Step 10). from_value is null for the first
-- entry of a dimension. Both values must belong to the dimension's status list.
CREATE TABLE order_status_history (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  order_id uuid NOT NULL,
  dimension text NOT NULL,
  from_value text,
  to_value text NOT NULL,
  reason text,
  actor text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT order_status_history_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT order_status_history_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT order_status_history_dimension_check CHECK (
    dimension IN ('ORDER', 'PAYMENT', 'FULFILLMENT', 'HOLD')
  ),
  CONSTRAINT order_status_history_value_check CHECK (
    CASE dimension
      WHEN 'ORDER' THEN
        to_value IN ('ACTIVE', 'CANCELLED', 'COMPLETED')
        AND (from_value IS NULL OR from_value IN ('ACTIVE', 'CANCELLED', 'COMPLETED'))
      WHEN 'PAYMENT' THEN
        to_value IN ('PENDING', 'PAID', 'COD_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED')
        AND (
          from_value IS NULL
          OR from_value IN ('PENDING', 'PAID', 'COD_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED')
        )
      WHEN 'FULFILLMENT' THEN
        to_value IN (
          'UNFULFILLED', 'READY_TO_PICK', 'PICKING', 'PACKED', 'SHIPPED', 'DELIVERED'
        )
        AND (
          from_value IS NULL
          OR from_value IN (
            'UNFULFILLED', 'READY_TO_PICK', 'PICKING', 'PACKED', 'SHIPPED', 'DELIVERED'
          )
        )
      WHEN 'HOLD' THEN
        to_value IN (
          'NONE',
          'SKU_NOT_MAPPED',
          'OUT_OF_STOCK',
          'ADDRESS_PROBLEM',
          'PAYMENT_MISMATCH',
          'CHANNEL_CANCEL_PENDING',
          'MANUAL'
        )
        AND (
          from_value IS NULL
          OR from_value IN (
            'NONE',
            'SKU_NOT_MAPPED',
            'OUT_OF_STOCK',
            'ADDRESS_PROBLEM',
            'PAYMENT_MISMATCH',
            'CHANNEL_CANCEL_PENDING',
            'MANUAL'
          )
        )
      ELSE false
    END
  )
);

-- Step 5: Shipments, at most one per order. No label PDF is stored, only its cache expiry.
CREATE TABLE shipment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  order_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  carrier text,
  tracking_no text,
  external_shipment_id text,
  label_cached_until timestamptz,
  status text NOT NULL DEFAULT 'PENDING',
  shipped_at timestamptz,
  delivered_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT shipment_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT shipment_order_id_key UNIQUE (order_id),
  CONSTRAINT shipment_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT shipment_warehouse_fkey FOREIGN KEY (tenant_id, warehouse_id)
    REFERENCES warehouse (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT shipment_status_check CHECK (
    status IN (
      'PENDING',
      'LABEL_READY',
      'SHIPPED',
      'IN_TRANSIT',
      'DELIVERED',
      'FAILED',
      'RETURNED_TO_SENDER'
    )
  )
);

-- Step 6: Returns. A return does not touch the order's own status (01-process-map.md).
-- UNIQUE (tenant_id, order_id, id) is the target of return_line's and refund's same-order FKs.
-- rejected records that the request was ever REJECTED. It is set by trigger (Step 12) and never
-- cleared, so a rejected request stays out of the return qty count after REJECTED -> CLOSED.
-- The CHECK keeps it consistent with status: REJECTED implies rejected, and a rejected request
-- can only be REJECTED or CLOSED.
CREATE TABLE return_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  order_id uuid NOT NULL,
  external_return_id text,
  type text NOT NULL,
  status text NOT NULL DEFAULT 'REQUESTED',
  rejected boolean NOT NULL DEFAULT false,
  reason text,
  requested_at timestamptz NOT NULL DEFAULT now(),
  received_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT return_request_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT return_request_tenant_order_id_key UNIQUE (tenant_id, order_id, id),
  CONSTRAINT return_request_order_external_return_key UNIQUE (order_id, external_return_id),
  CONSTRAINT return_request_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT return_request_type_check CHECK (type IN ('RETURN', 'RTS')),
  CONSTRAINT return_request_status_check CHECK (
    status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'RECEIVED', 'CLOSED')
  ),
  CONSTRAINT return_request_rejected_check CHECK (
    (status <> 'REJECTED' OR rejected) AND (NOT rejected OR status IN ('REJECTED', 'CLOSED'))
  )
);

-- order_id is carried on the line so both foreign keys include it: the request and the order
-- line must belong to the same order, enforced by the keys rather than by a trigger. The same
-- keys also block moving a request or a line to another order while it has return lines.
-- condition stays null until the goods are received.
CREATE TABLE return_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  order_id uuid NOT NULL,
  return_id uuid NOT NULL,
  order_line_id uuid NOT NULL,
  qty integer NOT NULL,
  condition text,
  restocked_qty integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT return_line_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT return_line_return_order_line_key UNIQUE (return_id, order_line_id),
  CONSTRAINT return_line_return_fkey FOREIGN KEY (tenant_id, order_id, return_id)
    REFERENCES return_request (tenant_id, order_id, id) ON DELETE RESTRICT,
  CONSTRAINT return_line_order_line_fkey FOREIGN KEY (tenant_id, order_id, order_line_id)
    REFERENCES order_line (tenant_id, order_id, id) ON DELETE RESTRICT,
  CONSTRAINT return_line_qty_check CHECK (qty > 0),
  CONSTRAINT return_line_condition_check CHECK (
    condition IS NULL OR condition IN ('RESELLABLE', 'DAMAGED')
  ),
  CONSTRAINT return_line_restocked_qty_check CHECK (restocked_qty BETWEEN 0 AND qty)
);

-- Step 7: Payment mirrors from TSF Pay. Read-only copies: no card or bank data columns.
-- refund.return_id is optional. When set, (tenant_id, order_id, return_id) must be a return of
-- the same order (MATCH SIMPLE skips the key when return_id is null).
CREATE TABLE refund (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  order_id uuid NOT NULL,
  return_id uuid,
  provider_ref text,
  amount numeric(14, 2) NOT NULL,
  currency text NOT NULL DEFAULT 'THB',
  status text NOT NULL,
  observed_at timestamptz NOT NULL,
  source_event_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT refund_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT refund_tenant_source_event_key UNIQUE (tenant_id, source_event_id),
  CONSTRAINT refund_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT refund_return_fkey FOREIGN KEY (tenant_id, order_id, return_id)
    REFERENCES return_request (tenant_id, order_id, id) ON DELETE RESTRICT,
  CONSTRAINT refund_status_check CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
  CONSTRAINT refund_amount_check CHECK (amount >= 0),
  CONSTRAINT refund_currency_check CHECK (currency = 'THB'),
  CONSTRAINT refund_source_event_id_check CHECK (btrim(source_event_id) <> '')
);

-- Append-only (trigger, Step 10). status is the provider status as TSF Pay reports it. 03 does
-- not enumerate it, and sales_order.payment_status (checked) is what OMS derives from it.
CREATE TABLE payment_status_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  order_id uuid NOT NULL,
  provider text NOT NULL,
  provider_ref text,
  status text NOT NULL,
  amount numeric(14, 2) NOT NULL,
  refunded_amount numeric(14, 2) NOT NULL DEFAULT 0,
  currency text NOT NULL DEFAULT 'THB',
  paid_at timestamptz,
  observed_at timestamptz NOT NULL,
  source_event_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT payment_status_snapshot_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT payment_status_snapshot_tenant_source_event_key UNIQUE (tenant_id, source_event_id),
  CONSTRAINT payment_status_snapshot_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT payment_status_snapshot_provider_check CHECK (provider IN ('XENDIT', 'OPN')),
  CONSTRAINT payment_status_snapshot_status_check CHECK (btrim(status) <> ''),
  CONSTRAINT payment_status_snapshot_amounts_check CHECK (amount >= 0 AND refunded_amount >= 0),
  CONSTRAINT payment_status_snapshot_currency_check CHECK (currency = 'THB'),
  CONSTRAINT payment_status_snapshot_source_event_id_check CHECK (btrim(source_event_id) <> '')
);

-- Step 8: Sync and platform. sync_cursor has no id: one row per (channel account, resource).
CREATE TABLE sync_cursor (
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  channel_account_id uuid NOT NULL,
  resource text NOT NULL,
  cursor text,
  last_success_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (channel_account_id, resource),
  CONSTRAINT sync_cursor_channel_account_fkey FOREIGN KEY (tenant_id, channel_account_id)
    REFERENCES channel_account (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT sync_cursor_resource_check CHECK (resource IN ('ORDERS', 'LISTINGS'))
);

-- ref names what was compared (a listing, an order, a reservation group). Values are jsonb so
-- a stock number and an order status fit the same column.
CREATE TABLE shadow_diff (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  channel_account_id uuid NOT NULL,
  kind text NOT NULL,
  ref text NOT NULL,
  oms_value jsonb,
  channel_value jsonb,
  observed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT shadow_diff_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT shadow_diff_channel_account_fkey FOREIGN KEY (tenant_id, channel_account_id)
    REFERENCES channel_account (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT shadow_diff_kind_check CHECK (kind IN ('STOCK', 'ORDER', 'RESERVATION')),
  CONSTRAINT shadow_diff_ref_check CHECK (btrim(ref) <> '')
);

-- order_id is null for a shop-level issue. run_id identifies the reconciliation run; there is
-- no run table yet, so it has no foreign key. details must not carry PII.
CREATE TABLE reconciliation_issue (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  run_id uuid NOT NULL,
  rule text NOT NULL,
  order_id uuid,
  details jsonb,
  status text NOT NULL DEFAULT 'OPEN',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reconciliation_issue_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT reconciliation_issue_order_fkey FOREIGN KEY (tenant_id, order_id)
    REFERENCES sales_order (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT reconciliation_issue_status_check CHECK (status IN ('OPEN', 'ACK', 'RESOLVED')),
  CONSTRAINT reconciliation_issue_rule_check CHECK (btrim(rule) <> '')
);

-- Step 9: Indexes. The ones listed in 03-data-model.md, plus the foreign key columns later
-- queries filter by (and that RESTRICT checks scan on parent delete).
CREATE INDEX sales_order_tenant_fulfillment_ordered_idx
  ON sales_order (tenant_id, fulfillment_status, ordered_at DESC);

CREATE INDEX sales_order_tenant_hold_idx
  ON sales_order (tenant_id, hold_reason)
  WHERE hold_reason <> 'NONE';

CREATE INDEX sales_order_tenant_ship_by_idx
  ON sales_order (tenant_id, ship_by)
  WHERE fulfillment_status IN ('READY_TO_PICK', 'PICKING', 'PACKED');

CREATE INDEX order_recipient_tenant_phone_hash_idx
  ON order_recipient (tenant_id, phone_hash);

CREATE INDEX order_recipient_pii_status_redact_after_idx
  ON order_recipient (pii_status, redact_after);

CREATE INDEX order_line_tenant_sku_idx
  ON order_line (tenant_id, sku_id)
  WHERE sku_id IS NOT NULL;

CREATE INDEX order_status_history_tenant_order_created_idx
  ON order_status_history (tenant_id, order_id, created_at);

CREATE INDEX shipment_tenant_warehouse_idx ON shipment (tenant_id, warehouse_id);

CREATE INDEX shipment_tenant_tracking_no_idx
  ON shipment (tenant_id, tracking_no)
  WHERE tracking_no IS NOT NULL;

-- The return qty SUM filters by order_line_id. It also serves the order_line RESTRICT check.
CREATE INDEX return_line_order_line_idx ON return_line (order_line_id);

CREATE INDEX return_line_tenant_order_return_idx
  ON return_line (tenant_id, order_id, return_id);

CREATE INDEX refund_tenant_order_idx ON refund (tenant_id, order_id);

CREATE INDEX refund_tenant_order_return_idx
  ON refund (tenant_id, order_id, return_id)
  WHERE return_id IS NOT NULL;

CREATE INDEX payment_status_snapshot_tenant_order_observed_idx
  ON payment_status_snapshot (tenant_id, order_id, observed_at DESC);

CREATE INDEX sync_cursor_tenant_channel_account_idx
  ON sync_cursor (tenant_id, channel_account_id);

CREATE INDEX shadow_diff_tenant_account_kind_observed_idx
  ON shadow_diff (tenant_id, channel_account_id, kind, observed_at DESC);

-- One open (OPEN or ACK) issue per (tenant, rule, order). NULLS NOT DISTINCT (Postgres 15+)
-- makes shop-level issues (order_id null) unique per rule too.
CREATE UNIQUE INDEX reconciliation_issue_open_key
  ON reconciliation_issue (tenant_id, rule, order_id) NULLS NOT DISTINCT
  WHERE status <> 'RESOLVED';

CREATE INDEX reconciliation_issue_tenant_status_idx
  ON reconciliation_issue (tenant_id, status, created_at DESC);

CREATE INDEX reconciliation_issue_tenant_order_idx
  ON reconciliation_issue (tenant_id, order_id)
  WHERE order_id IS NOT NULL;

-- Step 10: Ownership, RLS, and grants. Same expression as V1. No context = no rows.
DO $rls$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'sales_order',
    'order_recipient',
    'order_line',
    'order_status_history',
    'shipment',
    'return_request',
    'return_line',
    'refund',
    'payment_status_snapshot',
    'sync_cursor',
    'shadow_diff',
    'reconciliation_issue'
  ]
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

GRANT SELECT, INSERT, UPDATE, DELETE ON
  sales_order,
  order_recipient,
  order_line,
  shipment,
  return_request,
  return_line,
  refund,
  sync_cursor,
  shadow_diff,
  reconciliation_issue
TO oms_app, oms_maint;

-- History and payment snapshots are append-only at the grant layer too, like inventory_ledger.
GRANT SELECT, INSERT ON order_status_history, payment_status_snapshot TO oms_app, oms_maint;

-- Step 11: Append-only history and payment snapshots. Fires for the owner and superusers too.
CREATE FUNCTION order_append_only_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END
$fn$;

CREATE TRIGGER order_status_history_append_only
  BEFORE UPDATE OR DELETE ON order_status_history
  FOR EACH ROW
  EXECUTE FUNCTION order_append_only_reject_mutation();

CREATE TRIGGER order_status_history_append_only_truncate
  BEFORE TRUNCATE ON order_status_history
  FOR EACH STATEMENT
  EXECUTE FUNCTION order_append_only_reject_mutation();

CREATE TRIGGER payment_status_snapshot_append_only
  BEFORE UPDATE OR DELETE ON payment_status_snapshot
  FOR EACH ROW
  EXECUTE FUNCTION order_append_only_reject_mutation();

CREATE TRIGGER payment_status_snapshot_append_only_truncate
  BEFORE TRUNCATE ON payment_status_snapshot
  FOR EACH STATEMENT
  EXECUTE FUNCTION order_append_only_reject_mutation();

-- Step 12: Return quantity limit. For every order line, the qty of its return lines whose
-- request was never rejected (NOT return_request.rejected) must stay <= order_line.qty.
-- rejected is sticky (return_request_rejected_guard below): it is set when status becomes
-- REJECTED and can never be cleared, and a rejected request may only move on to CLOSED
-- (01-process-map.md: REJECTED -> CLOSED). So a request never starts counting again, and only
-- two writes can break the limit, each with a trigger:
-- * return_line INSERT, or UPDATE of qty / order_line_id / return_id / order_id.
-- * order_line UPDATE that lowers qty.
-- Every check locks the order line FOR UPDATE and then runs the SUM as a new statement. Under
-- READ COMMITTED that statement sees every return line committed by a writer that held the
-- lock before us. Under REPEATABLE READ or SERIALIZABLE it would not, so those levels are
-- refused outright (READ_COMMITTED_REQUIRED), like the V4 bundle flip.
-- A row that is not visible (another tenant under RLS) is skipped on purpose. Such a statement
-- is rejected by RLS WITH CHECK or by the composite foreign key instead.
CREATE FUNCTION return_line_qty_check()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
DECLARE
  v_isolation text := pg_catalog.current_setting('transaction_isolation');
  v_line_ids uuid[];
  v_rejected boolean;
  v_line_qty integer;
  v_returned bigint;
BEGIN
  -- Step 0: Only READ COMMITTED sees concurrent commits in the SUM below.
  IF v_isolation <> 'read committed' THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'return_line_isolation',
      MESSAGE = 'READ_COMMITTED_REQUIRED: return lines can only change under READ COMMITTED '
        || '(current: ' || v_isolation || ')';
  END IF;

  -- Step 1: Lock the request first (lock order: request, then order lines). rejected only ever
  -- goes false -> true, and a concurrent rejection can only lower the count.
  SELECT r.rejected
    INTO v_rejected
  FROM public.return_request AS r
  WHERE r.id = NEW.return_id
  FOR SHARE;

  -- Step 2: Lock the order lines in id order. A moved line locks its old and its new line.
  IF TG_OP = 'UPDATE' THEN
    v_line_ids := ARRAY[OLD.order_line_id, NEW.order_line_id];
  ELSE
    v_line_ids := ARRAY[NEW.order_line_id];
  END IF;
  PERFORM 1
  FROM public.order_line AS l
  WHERE l.id = ANY (v_line_ids)
  ORDER BY l.id
  FOR UPDATE;

  -- Step 3: A line of a rejected request never counts, so there is nothing to check.
  IF v_rejected IS NULL OR v_rejected THEN
    RETURN NEW;
  END IF;

  -- Step 4: Sum the other counting lines of this order line (new snapshot) and add this one.
  SELECT l.qty INTO v_line_qty FROM public.order_line AS l WHERE l.id = NEW.order_line_id;
  IF v_line_qty IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT coalesce(sum(rl.qty), 0)
    INTO v_returned
  FROM public.return_line AS rl
  JOIN public.return_request AS rr ON rr.id = rl.return_id
  WHERE rl.order_line_id = NEW.order_line_id
    AND rl.id <> NEW.id
    AND NOT rr.rejected;

  IF v_returned + NEW.qty > v_line_qty THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'return_line_qty_limit',
      MESSAGE = 'RETURN_QTY_EXCEEDED: order line ' || NEW.order_line_id || ' has qty '
        || v_line_qty || ', returned would be ' || (v_returned + NEW.qty);
  END IF;

  RETURN NEW;
END
$fn$;

-- The UPDATE already holds the order_line row lock when this runs, so return line writers wait.
CREATE FUNCTION order_line_return_qty_check()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
DECLARE
  v_isolation text := pg_catalog.current_setting('transaction_isolation');
  v_returned bigint;
BEGIN
  -- Step 0: Only READ COMMITTED sees concurrent commits in the SUM below.
  IF v_isolation <> 'read committed' THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'return_line_isolation',
      MESSAGE = 'READ_COMMITTED_REQUIRED: order_line.qty can only be lowered under READ '
        || 'COMMITTED (current: ' || v_isolation || ')';
  END IF;

  -- Step 1: The new qty must still cover every counting return line.
  SELECT coalesce(sum(rl.qty), 0)
    INTO v_returned
  FROM public.return_line AS rl
  JOIN public.return_request AS rr ON rr.id = rl.return_id
  WHERE rl.order_line_id = NEW.id
    AND NOT rr.rejected;

  IF v_returned > NEW.qty THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'return_line_qty_limit',
      MESSAGE = 'RETURN_QTY_EXCEEDED: order line ' || NEW.id || ' qty ' || NEW.qty
        || ' is below the returned qty ' || v_returned;
  END IF;

  RETURN NEW;
END
$fn$;

-- rejected is sticky. Becoming REJECTED sets it; nothing clears it; a rejected request may only
-- stay REJECTED or move to CLOSED. Rejection only lowers the count, so no re-sum is needed.
-- Also runs on INSERT, so a request created as REJECTED is marked too.
CREATE FUNCTION return_request_rejected_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.rejected THEN
    -- Step 1: Once rejected, always rejected. Checked before Step 3 so an explicit clear is
    -- refused, not silently re-set.
    IF NOT NEW.rejected THEN
      RAISE EXCEPTION USING
        ERRCODE = 'check_violation',
        CONSTRAINT = 'return_request_rejected_sticky',
        MESSAGE = 'RETURN_REJECTED_FINAL: return ' || NEW.id || ' was rejected; rejected cannot '
          || 'be cleared';
    END IF;

    -- Step 2: A rejected request can only move on to CLOSED (or stay REJECTED).
    IF NEW.status NOT IN ('REJECTED', 'CLOSED') THEN
      RAISE EXCEPTION USING
        ERRCODE = 'check_violation',
        CONSTRAINT = 'return_request_status_transition',
        MESSAGE = 'RETURN_REJECTED_FINAL: return ' || NEW.id || ' was rejected; '
          || OLD.status || ' -> ' || NEW.status || ' is not allowed (only CLOSED)';
    END IF;
  END IF;

  -- Step 3: REJECTED always means rejected.
  IF NEW.status = 'REJECTED' THEN
    NEW.rejected := true;
  END IF;

  RETURN NEW;
END
$fn$;

CREATE TRIGGER return_line_qty_check
  BEFORE INSERT OR UPDATE OF qty, order_line_id, return_id, order_id ON return_line
  FOR EACH ROW
  EXECUTE FUNCTION return_line_qty_check();

CREATE TRIGGER order_line_return_qty_check
  BEFORE UPDATE OF qty ON order_line
  FOR EACH ROW
  WHEN (NEW.qty < OLD.qty)
  EXECUTE FUNCTION order_line_return_qty_check();

CREATE TRIGGER return_request_rejected_guard
  BEFORE INSERT OR UPDATE OF status, rejected ON return_request
  FOR EACH ROW
  EXECUTE FUNCTION return_request_rejected_guard();

-- Step 13: Trigger functions are owned by oms_migrator and not callable by PUBLIC.
-- Postgres does not check EXECUTE when a trigger fires, so oms_app needs no grant.
ALTER FUNCTION order_append_only_reject_mutation() OWNER TO oms_migrator;
ALTER FUNCTION return_line_qty_check() OWNER TO oms_migrator;
ALTER FUNCTION order_line_return_qty_check() OWNER TO oms_migrator;
ALTER FUNCTION return_request_rejected_guard() OWNER TO oms_migrator;

REVOKE ALL ON FUNCTION order_append_only_reject_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION return_line_qty_check() FROM PUBLIC;
REVOKE ALL ON FUNCTION order_line_return_qty_check() FROM PUBLIC;
REVOKE ALL ON FUNCTION return_request_rejected_guard() FROM PUBLIC;

COMMENT ON TABLE sales_order IS
  'No PII. UNIQUE (channel_account_id, external_order_id). version is the optimistic lock.';
COMMENT ON TABLE order_recipient IS
  'The only PII table. *_enc = app-level AES-256-GCM (version || kid_len || kid || nonce || ct+tag, AAD header||tenant||order||column). REDACTED keeps province and postcode only (enc columns, phone_hash, phone_last4 are NULL). ON DELETE CASCADE with its order.';
COMMENT ON TABLE order_status_history IS
  'Append-only. UPDATE, DELETE, and TRUNCATE are rejected by trigger.';
COMMENT ON TABLE payment_status_snapshot IS
  'Append-only mirror of TSF Pay. No card or bank data. UNIQUE (tenant_id, source_event_id).';
COMMENT ON TABLE refund IS
  'Read-only mirror of TSF Pay refunds. UNIQUE (tenant_id, source_event_id).';
COMMENT ON TABLE return_line IS
  'Same order as its request and order line (composite FKs). Qty per order line over never-rejected requests <= order_line.qty (triggers, READ COMMITTED only).';
COMMENT ON COLUMN return_request.rejected IS
  'Sticky: set when status becomes REJECTED, never cleared. Rejected requests only move to CLOSED and never count toward the return qty limit.';
COMMENT ON INDEX reconciliation_issue_open_key IS
  'One open issue per (tenant, rule, order). NULLS NOT DISTINCT: one open shop-level issue per rule.';
