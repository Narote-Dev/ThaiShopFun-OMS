-- V4: channel accounts, catalog, warehouses, and stock (Phase 1 schema, plan task T06).
--
-- docs/plan calls this "Flyway V3". V3 is the inbox dedup migration, so this is V4.
-- Columns follow docs/plan/03-data-model.md. Phase 5 allocation tables are not here.
--
-- Conventions, same as V1:
-- * uuid primary keys with no database default. The application generates UUIDv7.
-- * oms_migrator owns every table and trigger function. oms_app is DML only.
-- * ENABLE + FORCE RLS on every table, policy tenant_isolation, fail closed with no context.
--
-- Tenant-consistent foreign keys: every entity table has UNIQUE (tenant_id, id) and
-- children reference (tenant_id, parent_id). A row can never point at another tenant's
-- parent, even for a role that bypasses RLS. Foreign keys are ON DELETE RESTRICT.
--
-- Not in this migration (no behaviour change to V1-V3 functions):
-- * resolve_tenant still reads tenant.tsf_shop_id. Creating the TSF channel_account at
--   JIT and switching resolve_tenant to channel_account is a later migration (T16).
-- * No default warehouse is created. T07 does that.
--
-- Trigger errors use SQLSTATE 23514 (check_violation), a stable message prefix, and a
-- constraint name, so the API layer can map them without parsing free text:
-- * NESTED_BUNDLE        (constraint sku_bundle_nesting): a bundle used as a component,
--                        or is_bundle set on a SKU that is a component.
-- * BUNDLE_REQUIRED      (constraint sku_bundle_parent): components on a non-bundle SKU,
--                        or is_bundle cleared on a SKU that has components.
-- * BUNDLE_NOT_STOCKABLE (constraint sku_bundle_stock): inventory, reservation, or stock
--                        document line for a bundle SKU, or is_bundle set on a SKU that
--                        already has any of those rows.
-- * STOCK_DOCUMENT_IMMUTABLE (constraint stock_document_immutable): edits after POST.
-- * READ_COMMITTED_REQUIRED (constraints sku_bundle_isolation, stock_document_isolation):
--                        an is_bundle flip or a document post under a snapshot isolation
--                        level, where the trigger could miss a concurrent commit.
--
-- Lock order: the sku FOR SHARE locks below are taken on catalog and stock document
-- writes only, never on the reservation path. T07 and T08 should still lock SKUs in id
-- order and retry a transaction that fails with 40P01 (deadlock_detected).

-- Step 1: Channel accounts. UNIQUE (channel, external_shop_id) is global on purpose:
-- one external shop maps to exactly one tenant. credentials_ref names a secret held
-- elsewhere. Tokens and secrets are never stored in this table.
CREATE TABLE channel_account (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  channel text NOT NULL,
  external_shop_id text NOT NULL,
  mode text NOT NULL DEFAULT 'OBSERVE',
  stock_sync_paused boolean NOT NULL DEFAULT false,
  status text NOT NULL,
  credentials_ref text,
  token_expires_at timestamptz,
  last_synced_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT channel_account_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT channel_account_channel_external_shop_key UNIQUE (channel, external_shop_id),
  CONSTRAINT channel_account_channel_check CHECK (
    channel IN ('TSF', 'SHOPEE', 'LAZADA', 'TIKTOK')
  ),
  CONSTRAINT channel_account_mode_check CHECK (
    mode IN ('OBSERVE', 'SHADOW', 'CONTROL', 'ACTIVE')
  ),
  CONSTRAINT channel_account_status_check CHECK (status IN ('CONNECTED', 'DISCONNECTED')),
  CONSTRAINT channel_account_external_shop_id_check CHECK (btrim(external_shop_id) <> ''),
  CONSTRAINT channel_account_credentials_ref_check CHECK (
    credentials_ref IS NULL OR btrim(credentials_ref) <> ''
  )
);

-- Step 2: Catalog. product status is not enumerated in 03-data-model.md, so the set
-- here is ACTIVE/INACTIVE. A bundle SKU's stock is derived from its components.
CREATE TABLE product (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  name text NOT NULL,
  status text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT product_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT product_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE sku (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  product_id uuid NOT NULL,
  sku_code text NOT NULL,
  name text NOT NULL,
  barcode text,
  weight_g integer,
  is_bundle boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sku_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT sku_tenant_sku_code_key UNIQUE (tenant_id, sku_code),
  CONSTRAINT sku_product_fkey FOREIGN KEY (tenant_id, product_id)
    REFERENCES product (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT sku_sku_code_check CHECK (btrim(sku_code) <> ''),
  CONSTRAINT sku_weight_g_check CHECK (weight_g IS NULL OR weight_g >= 0)
);

-- One level only: the bundle has is_bundle = true, every component has is_bundle = false.
-- That rules out nested bundles, and with it circular ones. Enforced by trigger (Step 8).
CREATE TABLE sku_bundle_component (
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  bundle_sku_id uuid NOT NULL,
  component_sku_id uuid NOT NULL,
  qty integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (bundle_sku_id, component_sku_id),
  CONSTRAINT sku_bundle_component_bundle_fkey FOREIGN KEY (tenant_id, bundle_sku_id)
    REFERENCES sku (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT sku_bundle_component_component_fkey FOREIGN KEY (tenant_id, component_sku_id)
    REFERENCES sku (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT sku_bundle_component_qty_check CHECK (qty > 0),
  CONSTRAINT sku_bundle_component_not_self_check CHECK (bundle_sku_id <> component_sku_id)
);

-- sku_id is nullable: a listing pulled from the channel may not be mapped yet.
CREATE TABLE channel_listing (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  channel_account_id uuid NOT NULL,
  sku_id uuid,
  external_item_id text,
  external_sku_id text NOT NULL,
  stock_control boolean NOT NULL DEFAULT false,
  safety_buffer integer NOT NULL DEFAULT 0,
  last_exposed_qty integer,
  last_pushed_version bigint,
  last_seen_channel_qty integer,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT channel_listing_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT channel_listing_account_external_sku_key UNIQUE (channel_account_id, external_sku_id),
  CONSTRAINT channel_listing_channel_account_fkey FOREIGN KEY (tenant_id, channel_account_id)
    REFERENCES channel_account (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT channel_listing_sku_fkey FOREIGN KEY (tenant_id, sku_id)
    REFERENCES sku (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT channel_listing_safety_buffer_check CHECK (safety_buffer >= 0),
  CONSTRAINT channel_listing_last_exposed_qty_check CHECK (
    last_exposed_qty IS NULL OR last_exposed_qty >= 0
  ),
  CONSTRAINT channel_listing_last_pushed_version_check CHECK (
    last_pushed_version IS NULL OR last_pushed_version >= 0
  )
);

-- Step 3: Warehouses. At most one default per tenant.
CREATE TABLE warehouse (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  code text NOT NULL,
  name text NOT NULL,
  address jsonb,
  is_default boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT warehouse_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT warehouse_tenant_code_key UNIQUE (tenant_id, code),
  CONSTRAINT warehouse_code_check CHECK (btrim(code) <> '')
);

-- Step 4: Stock. Non-bundle SKUs only (trigger, Step 8). The app changes on_hand and
-- reserved with a conditional UPDATE. The CHECK is the last line of defence.
-- UNIQUE (tenant_id, sku_id, warehouse_id) is the target of the ledger and reservation
-- foreign keys. sku ids are globally unique, so it also means one row per (sku, warehouse).
CREATE TABLE inventory (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  on_hand integer NOT NULL DEFAULT 0,
  reserved integer NOT NULL DEFAULT 0,
  stock_version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT inventory_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT inventory_tenant_sku_warehouse_key UNIQUE (tenant_id, sku_id, warehouse_id),
  CONSTRAINT inventory_sku_fkey FOREIGN KEY (tenant_id, sku_id)
    REFERENCES sku (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT inventory_warehouse_fkey FOREIGN KEY (tenant_id, warehouse_id)
    REFERENCES warehouse (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT inventory_quantity_check CHECK (
    on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand
  ),
  CONSTRAINT inventory_stock_version_check CHECK (stock_version >= 0)
);

-- Append-only (trigger, Step 9). The foreign key to inventory ties every entry to a
-- stock row, so a ledger entry can never exist for a bundle SKU or outlive its row.
-- ref_type/ref_id point at the source (document line, reservation, order). ref_type
-- values are not enumerated in 03-data-model.md, so they are free text here.
CREATE TABLE inventory_ledger (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  delta_on_hand integer NOT NULL,
  delta_reserved integer NOT NULL,
  reason text NOT NULL,
  ref_type text,
  ref_id uuid,
  actor text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT inventory_ledger_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT inventory_ledger_inventory_fkey FOREIGN KEY (tenant_id, sku_id, warehouse_id)
    REFERENCES inventory (tenant_id, sku_id, warehouse_id) ON DELETE RESTRICT,
  CONSTRAINT inventory_ledger_reason_check CHECK (
    reason IN (
      'OPENING_BALANCE',
      'RECEIVE',
      'ADJUST_IN',
      'ADJUST_OUT',
      'COUNT_CORRECTION',
      'DAMAGE_WRITE_OFF',
      'RETURN_RESTOCK',
      'SHIP',
      'RESERVE',
      'RELEASE',
      'UNPACK'
    )
  ),
  CONSTRAINT inventory_ledger_ref_check CHECK ((ref_type IS NULL) = (ref_id IS NULL))
);

-- One row per component SKU. Bundle quantities are exploded and summed by the app.
-- owner_ref is text because a CHECKOUT owner is the TSF checkout id.
-- reservation_group_id is the reservation_id returned to TSF. expires_at null = no expiry.
-- The foreign key to inventory requires a stock row, so a bundle SKU (which never has
-- one) cannot be reserved. This is the hot path, so there is no trigger and no sku row
-- lock here: the FK takes FOR KEY SHARE on the inventory row, which the reserving
-- UPDATE of inventory.reserved already holds more strongly, and never touches sku.
CREATE TABLE stock_reservation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  owner_type text NOT NULL,
  owner_ref text NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  qty integer NOT NULL,
  status text NOT NULL,
  expires_at timestamptz,
  reservation_group_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT stock_reservation_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT stock_reservation_inventory_fkey FOREIGN KEY (tenant_id, sku_id, warehouse_id)
    REFERENCES inventory (tenant_id, sku_id, warehouse_id) ON DELETE RESTRICT,
  CONSTRAINT stock_reservation_owner_type_check CHECK (owner_type IN ('CHECKOUT', 'ORDER')),
  CONSTRAINT stock_reservation_owner_ref_check CHECK (btrim(owner_ref) <> ''),
  CONSTRAINT stock_reservation_status_check CHECK (
    status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED')
  ),
  CONSTRAINT stock_reservation_qty_check CHECK (qty > 0)
);

-- Step 5: Stock documents. DRAFT -> POSTED -> VOID only (trigger, Step 10).
-- The ledger write on post stays in the application (T08A).
-- posted_by is an app_user id. app_user is not tenant-scoped, so there is no foreign key.
CREATE TABLE stock_document (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  type text NOT NULL,
  status text NOT NULL,
  reference_no text,
  note text,
  count_started_at timestamptz,
  posted_at timestamptz,
  posted_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT stock_document_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT stock_document_type_check CHECK (
    type IN ('OPENING', 'RECEIVE', 'ADJUSTMENT', 'COUNT', 'WRITE_OFF')
  ),
  CONSTRAINT stock_document_status_check CHECK (status IN ('DRAFT', 'POSTED', 'VOID')),
  CONSTRAINT stock_document_posted_at_check CHECK ((status = 'DRAFT') = (posted_at IS NULL)),
  CONSTRAINT stock_document_count_started_at_check CHECK (
    count_started_at IS NULL OR type = 'COUNT'
  )
);

-- reason_code is required for ADJUSTMENT lines. The app enforces it at post time,
-- because a DRAFT line may still be incomplete.
CREATE TABLE stock_document_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  document_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  qty integer NOT NULL,
  system_qty_at_start integer,
  counted_qty integer,
  reason_code text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT stock_document_line_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT stock_document_line_document_fkey FOREIGN KEY (tenant_id, document_id)
    REFERENCES stock_document (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT stock_document_line_sku_fkey FOREIGN KEY (tenant_id, sku_id)
    REFERENCES sku (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT stock_document_line_warehouse_fkey FOREIGN KEY (tenant_id, warehouse_id)
    REFERENCES warehouse (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT stock_document_line_system_qty_check CHECK (
    system_qty_at_start IS NULL OR system_qty_at_start >= 0
  ),
  CONSTRAINT stock_document_line_counted_qty_check CHECK (
    counted_qty IS NULL OR counted_qty >= 0
  )
);

-- Step 6: Indexes. The ones listed in 03-data-model.md, plus the foreign key columns
-- later queries filter by (and that RESTRICT checks scan on parent delete).
CREATE UNIQUE INDEX warehouse_one_default_per_tenant_idx
  ON warehouse (tenant_id)
  WHERE is_default;

CREATE INDEX sku_tenant_product_idx ON sku (tenant_id, product_id);

CREATE INDEX sku_bundle_component_component_sku_idx
  ON sku_bundle_component (component_sku_id);

CREATE INDEX channel_listing_tenant_sku_idx
  ON channel_listing (tenant_id, sku_id)
  WHERE sku_id IS NOT NULL;

CREATE INDEX inventory_tenant_warehouse_idx ON inventory (tenant_id, warehouse_id);

CREATE INDEX inventory_ledger_tenant_sku_created_idx
  ON inventory_ledger (tenant_id, sku_id, warehouse_id, created_at DESC);

CREATE INDEX inventory_ledger_tenant_ref_idx
  ON inventory_ledger (tenant_id, ref_type, ref_id)
  WHERE ref_id IS NOT NULL;

CREATE UNIQUE INDEX stock_reservation_active_owner_sku_key
  ON stock_reservation (tenant_id, owner_type, owner_ref, sku_id)
  WHERE status = 'ACTIVE';

CREATE INDEX stock_reservation_active_expiry_idx
  ON stock_reservation (status, expires_at)
  WHERE status = 'ACTIVE';

CREATE INDEX stock_reservation_owner_idx
  ON stock_reservation (tenant_id, owner_type, owner_ref);

CREATE INDEX stock_reservation_tenant_sku_idx
  ON stock_reservation (tenant_id, sku_id, warehouse_id);

CREATE INDEX stock_reservation_group_idx
  ON stock_reservation (tenant_id, reservation_group_id);

CREATE INDEX stock_document_tenant_status_created_idx
  ON stock_document (tenant_id, status, created_at DESC);

CREATE INDEX stock_document_line_document_idx
  ON stock_document_line (tenant_id, document_id);

CREATE INDEX stock_document_line_sku_idx
  ON stock_document_line (tenant_id, sku_id);

-- Step 7: Ownership, RLS, and grants. Same expression as V1. No context = no rows.
DO $rls$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'channel_account',
    'product',
    'sku',
    'sku_bundle_component',
    'channel_listing',
    'warehouse',
    'inventory',
    'inventory_ledger',
    'stock_reservation',
    'stock_document',
    'stock_document_line'
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
  channel_account,
  product,
  sku,
  sku_bundle_component,
  channel_listing,
  warehouse,
  inventory,
  stock_reservation,
  stock_document,
  stock_document_line
TO oms_app, oms_maint;

-- The ledger is append-only at the grant layer too, like audit_log in V1.
GRANT SELECT, INSERT ON inventory_ledger TO oms_app, oms_maint;

-- Step 8: Bundle rules. The component, inventory, and document line checks lock the
-- referenced sku rows FOR SHARE. A foreign key check only takes FOR KEY SHARE, which does
-- not block an UPDATE of is_bundle. FOR SHARE does, so an is_bundle flip and a new
-- reference cannot both pass their checks concurrently: whoever runs second waits, then
-- sees the committed row (READ COMMITTED) or fails with 40001 (snapshot isolation).
-- stock_reservation has no sku check: its inventory foreign key covers it (Step 4).
-- A sku that is not visible (another tenant under RLS) is skipped here on purpose. Such
-- a statement is rejected by RLS WITH CHECK or by the composite foreign key instead.
CREATE FUNCTION sku_bundle_component_check()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
DECLARE
  v_bundle_is_bundle boolean;
  v_component_is_bundle boolean;
BEGIN
  -- Step 1: Lock both sku rows in id order so two writers cannot deadlock.
  PERFORM 1
  FROM public.sku AS s
  WHERE s.id IN (NEW.bundle_sku_id, NEW.component_sku_id)
  ORDER BY s.id
  FOR SHARE;

  SELECT s.is_bundle INTO v_bundle_is_bundle FROM public.sku AS s WHERE s.id = NEW.bundle_sku_id;
  SELECT s.is_bundle INTO v_component_is_bundle
  FROM public.sku AS s
  WHERE s.id = NEW.component_sku_id;

  -- Step 2: A bundle (or the bundle itself) cannot be a component.
  IF v_component_is_bundle THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'sku_bundle_nesting',
      MESSAGE = 'NESTED_BUNDLE: component sku ' || NEW.component_sku_id || ' is a bundle';
  END IF;

  -- Step 3: Components can only hang off a SKU flagged as a bundle.
  IF v_bundle_is_bundle IS NOT NULL AND NOT v_bundle_is_bundle THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'sku_bundle_parent',
      MESSAGE = 'BUNDLE_REQUIRED: sku ' || NEW.bundle_sku_id || ' is not a bundle';
  END IF;

  RETURN NEW;
END
$fn$;

-- Shared by inventory and stock_document_line.
CREATE FUNCTION sku_require_stockable()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
DECLARE
  v_is_bundle boolean;
BEGIN
  -- Step 1: Lock the sku so is_bundle cannot flip before this row commits.
  SELECT s.is_bundle
    INTO v_is_bundle
  FROM public.sku AS s
  WHERE s.id = NEW.sku_id
  FOR SHARE;

  -- Step 2: Stock is held per component. A bundle SKU never has its own stock rows.
  IF v_is_bundle THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'sku_bundle_stock',
      MESSAGE = 'BUNDLE_NOT_STOCKABLE: sku ' || NEW.sku_id || ' is a bundle ('
        || TG_TABLE_NAME || ')';
  END IF;

  RETURN NEW;
END
$fn$;

-- The other direction: is_bundle changes on a sku that is already referenced.
-- The UPDATE holds the row lock on the sku, so writers of new references wait on it.
-- The EXISTS checks must see rows committed after this transaction started, which only
-- READ COMMITTED gives (a fresh snapshot per statement). Under REPEATABLE READ or
-- SERIALIZABLE a component insert that commits after our snapshot would be invisible
-- here and both would pass, so the flip is refused outright at those levels.
CREATE FUNCTION sku_is_bundle_change_check()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
DECLARE
  v_isolation text := pg_catalog.current_setting('transaction_isolation');
BEGIN
  -- Step 0: Only READ COMMITTED sees concurrent commits in the checks below.
  IF v_isolation <> 'read committed' THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'sku_bundle_isolation',
      MESSAGE = 'READ_COMMITTED_REQUIRED: is_bundle can only change under READ COMMITTED '
        || '(current: ' || v_isolation || ')';
  END IF;

  IF NEW.is_bundle THEN
    -- Step 1: Becoming a bundle. It must not be a component anywhere.
    IF EXISTS (
      SELECT 1 FROM public.sku_bundle_component AS c WHERE c.component_sku_id = NEW.id
    ) THEN
      RAISE EXCEPTION USING
        ERRCODE = 'check_violation',
        CONSTRAINT = 'sku_bundle_nesting',
        MESSAGE = 'NESTED_BUNDLE: sku ' || NEW.id || ' is a component of another bundle';
    END IF;

    -- Step 2: It must not hold stock of its own. A reservation or ledger row always has
    -- an inventory row (foreign key), so checking inventory covers both.
    IF EXISTS (
        SELECT 1 FROM public.inventory AS i
        WHERE i.tenant_id = NEW.tenant_id AND i.sku_id = NEW.id
      )
      OR EXISTS (
        SELECT 1 FROM public.stock_document_line AS l
        WHERE l.tenant_id = NEW.tenant_id AND l.sku_id = NEW.id
      )
    THEN
      RAISE EXCEPTION USING
        ERRCODE = 'check_violation',
        CONSTRAINT = 'sku_bundle_stock',
        MESSAGE = 'BUNDLE_NOT_STOCKABLE: sku ' || NEW.id || ' has stock rows';
    END IF;
  ELSE
    -- Step 3: No longer a bundle. It must not have components left.
    IF EXISTS (
      SELECT 1 FROM public.sku_bundle_component AS c WHERE c.bundle_sku_id = NEW.id
    ) THEN
      RAISE EXCEPTION USING
        ERRCODE = 'check_violation',
        CONSTRAINT = 'sku_bundle_parent',
        MESSAGE = 'BUNDLE_REQUIRED: sku ' || NEW.id || ' still has components';
    END IF;
  END IF;

  RETURN NEW;
END
$fn$;

CREATE TRIGGER sku_bundle_component_check
  BEFORE INSERT OR UPDATE ON sku_bundle_component
  FOR EACH ROW
  EXECUTE FUNCTION sku_bundle_component_check();

CREATE TRIGGER sku_is_bundle_change_check
  BEFORE UPDATE OF is_bundle ON sku
  FOR EACH ROW
  WHEN (OLD.is_bundle IS DISTINCT FROM NEW.is_bundle)
  EXECUTE FUNCTION sku_is_bundle_change_check();

-- UPDATE OF sku_id keeps the hot on_hand/reserved updates free of the extra lock.
CREATE TRIGGER inventory_sku_stockable
  BEFORE INSERT OR UPDATE OF sku_id ON inventory
  FOR EACH ROW
  EXECUTE FUNCTION sku_require_stockable();

CREATE TRIGGER stock_document_line_sku_stockable
  BEFORE INSERT OR UPDATE OF sku_id ON stock_document_line
  FOR EACH ROW
  EXECUTE FUNCTION sku_require_stockable();

-- Step 9: Append-only ledger. Fires for the owner and for superusers too.
CREATE FUNCTION inventory_ledger_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
BEGIN
  RAISE EXCEPTION 'inventory_ledger is append-only';
END
$fn$;

CREATE TRIGGER inventory_ledger_append_only
  BEFORE UPDATE OR DELETE ON inventory_ledger
  FOR EACH ROW
  EXECUTE FUNCTION inventory_ledger_reject_mutation();

CREATE TRIGGER inventory_ledger_append_only_truncate
  BEFORE TRUNCATE ON inventory_ledger
  FOR EACH STATEMENT
  EXECUTE FUNCTION inventory_ledger_reject_mutation();

-- Step 10: Stock document immutability. Allowed status moves: DRAFT -> POSTED and
-- POSTED -> VOID. A POSTED header may only change status (and updated_at). A VOID
-- header is frozen. Only DRAFT documents may be deleted.
-- Posting is READ COMMITTED only. A line writer holds the document FOR SHARE but does not
-- modify it, so under a snapshot level the post would succeed without seeing a line that
-- committed after its snapshot. T08A should lock the document FOR UPDATE, then read lines.
CREATE FUNCTION stock_document_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
BEGIN
  -- Step 1: Delete is for drafts only.
  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION USING
        ERRCODE = 'check_violation',
        CONSTRAINT = 'stock_document_immutable',
        MESSAGE = 'STOCK_DOCUMENT_IMMUTABLE: cannot delete ' || OLD.status || ' document';
    END IF;
    RETURN OLD;
  END IF;

  -- Step 2: Status transitions.
  IF NOT (
    (OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'POSTED'))
    OR (OLD.status = 'POSTED' AND NEW.status = 'VOID')
  ) THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'stock_document_immutable',
      MESSAGE = 'STOCK_DOCUMENT_IMMUTABLE: ' || OLD.status || ' -> ' || NEW.status
        || ' is not allowed';
  END IF;

  -- Step 3: A post must see every committed line, which needs a fresh snapshot.
  IF OLD.status = 'DRAFT' AND NEW.status = 'POSTED'
    AND pg_catalog.current_setting('transaction_isolation') <> 'read committed'
  THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'stock_document_isolation',
      MESSAGE = 'READ_COMMITTED_REQUIRED: a stock document can only be posted under READ '
        || 'COMMITTED (current: ' || pg_catalog.current_setting('transaction_isolation') || ')';
  END IF;

  -- Step 4: Past DRAFT, nothing but status and updated_at may change. Comparing the
  -- whole row as jsonb also covers columns added by later migrations.
  IF OLD.status <> 'DRAFT'
    AND (pg_catalog.to_jsonb(NEW) - 'status' - 'updated_at')
      IS DISTINCT FROM (pg_catalog.to_jsonb(OLD) - 'status' - 'updated_at')
  THEN
    RAISE EXCEPTION USING
      ERRCODE = 'check_violation',
      CONSTRAINT = 'stock_document_immutable',
      MESSAGE = 'STOCK_DOCUMENT_IMMUTABLE: ' || OLD.status || ' document fields are read-only';
  END IF;

  RETURN NEW;
END
$fn$;

-- Lines are writable only while their document is DRAFT. The document row is locked
-- FOR SHARE so a concurrent post waits for this line write, or this write sees POSTED.
CREATE FUNCTION stock_document_line_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $fn$
DECLARE
  v_document_ids uuid[];
  v_status text;
BEGIN
  -- Step 1: Collect the documents this change touches (a moved line touches two).
  IF TG_OP = 'INSERT' THEN
    v_document_ids := ARRAY[NEW.document_id];
  ELSIF TG_OP = 'UPDATE' THEN
    v_document_ids := ARRAY[OLD.document_id, NEW.document_id];
  ELSE
    v_document_ids := ARRAY[OLD.document_id];
  END IF;

  -- Step 2: Lock in id order and reject if any of them is past DRAFT. A document that
  -- is not visible is left to the composite foreign key and RLS.
  FOR v_status IN
    SELECT d.status
    FROM public.stock_document AS d
    WHERE d.id = ANY (v_document_ids)
    ORDER BY d.id
    FOR SHARE
  LOOP
    IF v_status <> 'DRAFT' THEN
      RAISE EXCEPTION USING
        ERRCODE = 'check_violation',
        CONSTRAINT = 'stock_document_immutable',
        MESSAGE = 'STOCK_DOCUMENT_IMMUTABLE: lines of a ' || v_status
          || ' document are read-only';
    END IF;
  END LOOP;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END
$fn$;

CREATE TRIGGER stock_document_guard
  BEFORE UPDATE OR DELETE ON stock_document
  FOR EACH ROW
  EXECUTE FUNCTION stock_document_guard();

CREATE TRIGGER stock_document_line_guard
  BEFORE INSERT OR UPDATE OR DELETE ON stock_document_line
  FOR EACH ROW
  EXECUTE FUNCTION stock_document_line_guard();

-- Step 11: Trigger functions are owned by oms_migrator and not callable by PUBLIC.
-- Postgres does not check EXECUTE when a trigger fires, so oms_app needs no grant.
ALTER FUNCTION sku_bundle_component_check() OWNER TO oms_migrator;
ALTER FUNCTION sku_require_stockable() OWNER TO oms_migrator;
ALTER FUNCTION sku_is_bundle_change_check() OWNER TO oms_migrator;
ALTER FUNCTION inventory_ledger_reject_mutation() OWNER TO oms_migrator;
ALTER FUNCTION stock_document_guard() OWNER TO oms_migrator;
ALTER FUNCTION stock_document_line_guard() OWNER TO oms_migrator;

REVOKE ALL ON FUNCTION sku_bundle_component_check() FROM PUBLIC;
REVOKE ALL ON FUNCTION sku_require_stockable() FROM PUBLIC;
REVOKE ALL ON FUNCTION sku_is_bundle_change_check() FROM PUBLIC;
REVOKE ALL ON FUNCTION inventory_ledger_reject_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION stock_document_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION stock_document_line_guard() FROM PUBLIC;

COMMENT ON COLUMN channel_account.credentials_ref IS
  'Name of a secret in the secret manager. Never a token or secret value.';
COMMENT ON CONSTRAINT channel_account_channel_external_shop_key ON channel_account IS
  'Global on purpose: one external shop maps to exactly one tenant.';
COMMENT ON TABLE inventory IS
  'Non-bundle SKUs only (trigger inventory_sku_stockable).';
COMMENT ON TABLE inventory_ledger IS
  'Append-only. UPDATE, DELETE, and TRUNCATE are rejected by trigger.';
COMMENT ON TABLE stock_reservation IS
  'One row per component SKU. At most one ACTIVE row per (tenant, owner_type, owner_ref, sku). Requires an inventory row (FK), so bundles cannot be reserved.';
COMMENT ON TABLE stock_document IS
  'DRAFT -> POSTED -> VOID. Immutable after DRAFT except the status move (trigger).';
