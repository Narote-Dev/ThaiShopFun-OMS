--
-- PostgreSQL database dump
--

\restrict m7Zwlhr0g7ENX4EqLEGo362bkyShWQAQbmrrqD7bvI5BMZKVFgIQOE5VlLiSIqP

-- Dumped from database version 17.11 (Debian 17.11-0+deb13u1)
-- Dumped by pg_dump version 17.11 (Debian 17.11-0+deb13u1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: audit_log_reject_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.audit_log_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only';
END
$$;


--
-- Name: claim_inbox_batch(integer, interval); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.claim_inbox_batch(n integer, p_lease interval DEFAULT '00:05:00'::interval) RETURNS TABLE(id uuid, tenant_id uuid, next_attempt_at timestamp with time zone)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
BEGIN
  -- Step 1: Reject a limit or lease that would scan an unbounded batch or hide it too long.
  -- The 1 hour ceiling is InboxLimits.MAX_LEASE. Startup refuses a longer oms.inbox.lease.
  IF n IS NULL OR n < 1 OR n > 1000 THEN
    RAISE EXCEPTION 'claim_inbox_batch limit must be between 1 and 1000';
  END IF;
  IF p_lease IS NULL OR p_lease <= interval '0' OR p_lease > interval '1 hour' THEN
    RAISE EXCEPTION 'claim_inbox_batch lease must be greater than 0 and at most 1 hour';
  END IF;

  -- Step 2: Lease due RECEIVED/FAILED rows, oldest due first.
  -- COALESCE(next_attempt_at, received_at) treats a new row as due at received_at,
  -- so it does not sort ahead of a retry whose next_attempt_at is already past.
  -- SKIP LOCKED keeps parallel workers off the same row.
  RETURN QUERY
  WITH picked AS (
    SELECT i.id
    FROM public.inbox_event AS i
    WHERE i.status IN ('RECEIVED', 'FAILED')
      AND (i.next_attempt_at IS NULL OR i.next_attempt_at <= now())
    ORDER BY COALESCE(i.next_attempt_at, i.received_at), i.id
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
$$;


--
-- Name: FUNCTION claim_inbox_batch(n integer, p_lease interval); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.claim_inbox_batch(n integer, p_lease interval) IS 'Cross-tenant lease of inbox rows. FOR UPDATE SKIP LOCKED. Returns id, tenant_id, and the lease timestamp next_attempt_at.';


--
-- Name: claim_outbox_batch(integer, interval); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.claim_outbox_batch(n integer, p_lease interval DEFAULT '00:05:00'::interval) RETURNS TABLE(id uuid, tenant_id uuid)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
BEGIN
  -- Step 1: Reject a limit or lease that would scan an unbounded batch or hide it too long.
  IF n IS NULL OR n < 1 OR n > 1000 THEN
    RAISE EXCEPTION 'claim_outbox_batch limit must be between 1 and 1000';
  END IF;
  IF p_lease IS NULL OR p_lease <= interval '0' OR p_lease > interval '1 hour' THEN
    RAISE EXCEPTION 'claim_outbox_batch lease must be greater than 0 and at most 1 hour';
  END IF;

  -- Step 2: Claim due PENDING rows, and IN_FLIGHT rows whose lease expired.
  RETURN QUERY
  WITH picked AS (
    SELECT o.id
    FROM public.outbox_event AS o
    WHERE (
        o.status = 'PENDING'
        AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
      )
      OR (
        o.status = 'IN_FLIGHT'
        AND o.lease_until IS NOT NULL
        AND o.lease_until <= now()
      )
    ORDER BY o.next_attempt_at NULLS FIRST, o.created_at, o.id
    LIMIT n
    FOR UPDATE SKIP LOCKED
  )
  UPDATE public.outbox_event AS e
  SET status = 'IN_FLIGHT',
      attempts = e.attempts + 1,
      lease_until = pg_catalog.now() + p_lease
  FROM picked
  WHERE e.id = picked.id
  RETURNING e.id, e.tenant_id;
END
$$;


--
-- Name: FUNCTION claim_outbox_batch(n integer, p_lease interval); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.claim_outbox_batch(n integer, p_lease interval) IS 'Cross-tenant lease of outbox rows. Sets IN_FLIGHT. FOR UPDATE SKIP LOCKED. Returns id and tenant_id only.';


--
-- Name: inventory_ledger_reject_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.inventory_ledger_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
  RAISE EXCEPTION 'inventory_ledger is append-only';
END
$$;


--
-- Name: list_active_tenant_ids(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.list_active_tenant_ids() RETURNS TABLE(id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
  SELECT t.id
  FROM public.tenant AS t
  WHERE t.entitlement_status = 'ACTIVE'
$$;


--
-- Name: FUNCTION list_active_tenant_ids(); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.list_active_tenant_ids() IS 'Cross-tenant. Returns id only, for entitlement_status ACTIVE.';


--
-- Name: list_tenants_with_expired_reservations(timestamp with time zone, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.list_tenants_with_expired_reservations(p_now timestamp with time zone, p_limit integer) RETURNS TABLE(tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
  SELECT DISTINCT r.tenant_id
  FROM public.stock_reservation AS r
  WHERE r.status = 'ACTIVE'
    AND r.expires_at <= p_now
  LIMIT CASE
    WHEN p_limit BETWEEN 1 AND 1000 THEN p_limit
    ELSE (
      'list_tenants_with_expired_reservations limit must be between 1 and 1000, got '
        || coalesce(p_limit::text, 'null')
    )::integer
  END
$$;


--
-- Name: FUNCTION list_tenants_with_expired_reservations(p_now timestamp with time zone, p_limit integer); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.list_tenants_with_expired_reservations(p_now timestamp with time zone, p_limit integer) IS 'Cross-tenant. Returns tenant_id only, for tenants with ACTIVE reservations where expires_at <= p_now. Any entitlement status. Read-only. p_limit 1..1000.';


--
-- Name: list_tenants_for_order_backfill(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.list_tenants_for_order_backfill() RETURNS TABLE(id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
  SELECT t.id
  FROM tenant AS t
  WHERE (
      t.entitlement_status = 'ACTIVE'
      OR (
        t.entitlement_status = 'GRACE'
        AND (t.entitlement_expires_at IS NULL OR t.entitlement_expires_at > pg_catalog.now())
      )
    )
    AND NOT (
      t.entitlement_expires_at IS NOT NULL
      AND t.entitlement_expires_at <= pg_catalog.now()
      AND t.entitlement_status IN ('ACTIVE', 'GRACE')
    )
    AND EXISTS (
      SELECT 1
      FROM channel_account AS ca
      WHERE ca.tenant_id = t.id
        AND ca.channel = 'TSF'
        AND ca.status <> 'DISCONNECTED'
    );
$$;


--
-- Name: FUNCTION list_tenants_for_order_backfill(); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.list_tenants_for_order_backfill() IS 'Cross-tenant. Returns id only for ACTIVE/unexpired GRACE tenants with a connected TSF account.';


--
-- Name: lookup_login(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.lookup_login(p_tsf_shop_id text, p_tsf_user_id text) RETURNS TABLE(tenant_id uuid, user_id uuid, membership_id uuid, ent_ver bigint, membership_status text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
  SELECT t.id, u.id, m.id, t.ent_ver, m.status
  FROM public.tenant AS t
  LEFT JOIN public.app_user AS u
    ON u.tsf_user_id = p_tsf_user_id
  LEFT JOIN public.tenant_membership AS m
    ON m.tenant_id = t.id
   AND m.user_id = u.id
  WHERE t.tsf_shop_id = p_tsf_shop_id
$$;


--
-- Name: FUNCTION lookup_login(p_tsf_shop_id text, p_tsf_user_id text); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.lookup_login(p_tsf_shop_id text, p_tsf_user_id text) IS 'Read-only. Returns ids, ent_ver, and membership status. No other columns.';


--
-- Name: order_append_only_reject_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.order_append_only_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END
$$;


--
-- Name: order_line_return_qty_check(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.order_line_return_qty_check() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
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
$$;


--
-- Name: provision_membership(uuid, uuid, text, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.provision_membership(p_tenant_id uuid, p_user_id uuid, p_role text, p_ent_ver bigint) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
  v_id uuid;
  v_stored bigint;
BEGIN
  IF p_tenant_id IS NULL OR p_user_id IS NULL THEN
    RAISE EXCEPTION 'tenant_id and user_id are required';
  END IF;
  IF p_role IS NULL OR p_role NOT IN ('OWNER', 'ADMIN', 'STAFF') THEN
    RAISE EXCEPTION 'shop_role is invalid';
  END IF;
  IF p_ent_ver IS NULL OR p_ent_ver < 0 THEN
    RAISE EXCEPTION 'ent_ver is invalid';
  END IF;

  -- Same lock provision_tenant holds, so ent_ver cannot move between the check and the role write.
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_tenant_id::text), 4);

  SELECT t.ent_ver
    INTO v_stored
  FROM public.tenant AS t
  WHERE t.id = p_tenant_id;

  IF v_stored IS NULL THEN
    RAISE EXCEPTION 'provision_membership tenant is missing';
  END IF;

  IF v_stored <= p_ent_ver THEN
    INSERT INTO public.tenant_membership AS m (id, tenant_id, user_id, role, status)
    VALUES (pg_catalog.gen_random_uuid(), p_tenant_id, p_user_id, p_role, 'ACTIVE')
    ON CONFLICT (tenant_id, user_id) DO UPDATE
      SET role = EXCLUDED.role
      WHERE m.status <> 'REVOKED'
    RETURNING id INTO v_id;
  END IF;

  IF v_id IS NULL THEN
    SELECT existing.id
      INTO v_id
    FROM public.tenant_membership AS existing
    WHERE existing.tenant_id = p_tenant_id
      AND existing.user_id = p_user_id;
  END IF;

  IF v_id IS NULL THEN
    RAISE EXCEPTION 'provision_membership did not resolve an id';
  END IF;
  RETURN v_id;
END
$$;


--
-- Name: FUNCTION provision_membership(p_tenant_id uuid, p_user_id uuid, p_role text, p_ent_ver bigint); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.provision_membership(p_tenant_id uuid, p_user_id uuid, p_role text, p_ent_ver bigint) IS 'JIT membership upsert. Updates role only when tenant.ent_ver <= p_ent_ver. Does not reactivate REVOKED. Returns id only.';


--
-- Name: provision_tenant(text, text, text, text, timestamp with time zone, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.provision_tenant(p_tsf_shop_id text, p_name text, p_tier text, p_entitlement_status text, p_entitlement_expires_at timestamp with time zone, p_ent_ver bigint) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
  v_id uuid;
  v_account_tenant uuid;
BEGIN
  IF p_tsf_shop_id IS NULL OR btrim(p_tsf_shop_id) = '' THEN
    RAISE EXCEPTION 'tsf_shop_id is required';
  END IF;
  IF p_name IS NULL OR btrim(p_name) = '' THEN
    RAISE EXCEPTION 'tenant name is required';
  END IF;
  IF p_tier IS NULL OR btrim(p_tier) = '' THEN
    RAISE EXCEPTION 'membership tier is required';
  END IF;
  IF p_entitlement_status IS NULL
    OR p_entitlement_status NOT IN ('ACTIVE', 'GRACE', 'SUSPENDED') THEN
    RAISE EXCEPTION 'entitlement_status is invalid';
  END IF;
  IF p_ent_ver IS NULL OR p_ent_ver < 0 THEN
    RAISE EXCEPTION 'ent_ver is invalid';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_tsf_shop_id), 1);
  SELECT t.id
    INTO v_id
  FROM public.tenant AS t
  WHERE t.tsf_shop_id = p_tsf_shop_id;
  IF v_id IS NOT NULL THEN
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(v_id::text), 4);
  END IF;

  INSERT INTO public.tenant AS t (
    id,
    name,
    tsf_shop_id,
    membership_tier,
    entitlement_status,
    entitlement_expires_at,
    ent_ver
  )
  VALUES (
    pg_catalog.gen_random_uuid(),
    p_name,
    p_tsf_shop_id,
    p_tier,
    p_entitlement_status,
    p_entitlement_expires_at,
    p_ent_ver
  )
  ON CONFLICT (tsf_shop_id) DO UPDATE
    SET name = EXCLUDED.name,
        membership_tier = EXCLUDED.membership_tier,
        entitlement_status = EXCLUDED.entitlement_status,
        entitlement_expires_at = EXCLUDED.entitlement_expires_at,
        ent_ver = EXCLUDED.ent_ver
    WHERE t.ent_ver <= EXCLUDED.ent_ver
  RETURNING id INTO v_id;

  IF v_id IS NULL THEN
    SELECT existing.id
      INTO v_id
    FROM public.tenant AS existing
    WHERE existing.tsf_shop_id = p_tsf_shop_id;
  END IF;

  IF v_id IS NULL THEN
    RAISE EXCEPTION 'provision_tenant did not resolve an id';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(v_id::text), 4);

  INSERT INTO public.channel_account (
    id,
    tenant_id,
    channel,
    external_shop_id,
    mode,
    status
  )
  VALUES (
    pg_catalog.gen_random_uuid(),
    v_id,
    'TSF',
    p_tsf_shop_id,
    'OBSERVE',
    'CONNECTED'
  )
  ON CONFLICT (channel, external_shop_id) DO NOTHING;

  SELECT c.tenant_id
    INTO v_account_tenant
  FROM public.channel_account AS c
  WHERE c.channel = 'TSF'
    AND c.external_shop_id = p_tsf_shop_id;

  IF v_account_tenant IS DISTINCT FROM v_id THEN
    RAISE EXCEPTION 'tsf_shop_id % is already bound to another tenant', p_tsf_shop_id;
  END IF;

  RETURN v_id;
END
$$;


--
-- Name: FUNCTION provision_tenant(p_tsf_shop_id text, p_name text, p_tier text, p_entitlement_status text, p_entitlement_expires_at timestamp with time zone, p_ent_ver bigint); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.provision_tenant(p_tsf_shop_id text, p_name text, p_tier text, p_entitlement_status text, p_entitlement_expires_at timestamp with time zone, p_ent_ver bigint) IS 'JIT tenant upsert by tsf_shop_id. Ensures one TSF channel_account (OBSERVE, CONNECTED) without resetting existing rows. Returns id only.';


--
-- Name: resolve_reservation_tenant(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.resolve_reservation_tenant(p_reservation_group_id uuid) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
  SELECT tenant_id
  FROM public.stock_reservation
  WHERE reservation_group_id = p_reservation_group_id
  LIMIT 1
$$;


--
-- Name: FUNCTION resolve_reservation_tenant(p_reservation_group_id uuid); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.resolve_reservation_tenant(p_reservation_group_id uuid) IS 'Cross-tenant. Returns tenant_id only for one reservation_group_id, or NULL when unknown. Read-only.';


--
-- Name: resolve_tenant(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.resolve_tenant(channel text, external_shop_id text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
  SELECT COALESCE(
    (
      SELECT c.tenant_id
      FROM public.channel_account AS c
      WHERE c.channel = resolve_tenant.channel
        AND c.external_shop_id = resolve_tenant.external_shop_id
      LIMIT 1
    ),
    (
      SELECT t.id
      FROM public.tenant AS t
      WHERE resolve_tenant.channel = 'TSF'
        AND t.tsf_shop_id = resolve_tenant.external_shop_id
      LIMIT 1
    )
  )
$$;


--
-- Name: FUNCTION resolve_tenant(channel text, external_shop_id text); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.resolve_tenant(channel text, external_shop_id text) IS 'Cross-tenant. Resolves tenant id via channel_account; TSF falls back to tenant.tsf_shop_id when no account exists. Returns id only.';


--
-- Name: return_line_qty_check(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.return_line_qty_check() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
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
$$;


--
-- Name: return_request_rejected_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.return_request_rejected_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
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
$$;


--
-- Name: sku_bundle_component_check(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sku_bundle_component_check() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
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
$$;


--
-- Name: sku_is_bundle_change_check(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sku_is_bundle_change_check() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
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
$$;


--
-- Name: sku_require_stockable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sku_require_stockable() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
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
$$;


--
-- Name: stock_document_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.stock_document_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
BEGIN
  -- Step 0: Every document starts as DRAFT.
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'DRAFT' THEN
      RAISE EXCEPTION USING
        ERRCODE = 'check_violation',
        CONSTRAINT = 'stock_document_immutable',
        MESSAGE = 'STOCK_DOCUMENT_IMMUTABLE: a new document must be DRAFT (got '
          || NEW.status || ')';
    END IF;
    RETURN NEW;
  END IF;

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
$$;


--
-- Name: stock_document_line_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.stock_document_line_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    AS $$
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
$$;


--
-- Name: upsert_app_user(text, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.upsert_app_user(p_tsf_user_id text, p_email text, p_display_name text) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
  v_id uuid;
BEGIN
  IF p_tsf_user_id IS NULL OR btrim(p_tsf_user_id) = '' THEN
    RAISE EXCEPTION 'tsf_user_id is required';
  END IF;
  IF p_email IS NOT NULL AND btrim(p_email) = '' THEN
    p_email := NULL;
  END IF;
  IF p_display_name IS NOT NULL AND btrim(p_display_name) = '' THEN
    p_display_name := NULL;
  END IF;

  -- The second key keeps this lock off the tenant lock namespace.
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_tsf_user_id), 2);

  INSERT INTO public.app_user AS u (id, tsf_user_id, email, display_name, last_login_at)
  VALUES (
    pg_catalog.gen_random_uuid(),
    p_tsf_user_id,
    p_email,
    p_display_name,
    pg_catalog.now()
  )
  ON CONFLICT (tsf_user_id) DO UPDATE
    SET email = COALESCE(EXCLUDED.email, u.email),
        display_name = COALESCE(EXCLUDED.display_name, u.display_name),
        last_login_at = pg_catalog.now()
  RETURNING id INTO v_id;

  IF v_id IS NULL THEN
    RAISE EXCEPTION 'upsert_app_user did not resolve an id';
  END IF;
  RETURN v_id;
END
$$;


--
-- Name: FUNCTION upsert_app_user(p_tsf_user_id text, p_email text, p_display_name text); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.upsert_app_user(p_tsf_user_id text, p_email text, p_display_name text) IS 'JIT user upsert. Returns id only. Owned by oms_maint.';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: app_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user (
    id uuid NOT NULL,
    tsf_user_id text NOT NULL,
    email text,
    display_name text,
    last_login_at timestamp with time zone
);


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_type text NOT NULL,
    actor_id text,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id text,
    before jsonb,
    after jsonb,
    ip inet,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT audit_log_actor_type_check CHECK ((actor_type = ANY (ARRAY['USER'::text, 'SYSTEM'::text, 'TSF'::text, 'PLATFORM_ADMIN'::text])))
);

ALTER TABLE ONLY public.audit_log FORCE ROW LEVEL SECURITY;


--
-- Name: channel_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.channel_account (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    channel text NOT NULL,
    external_shop_id text NOT NULL,
    mode text DEFAULT 'OBSERVE'::text NOT NULL,
    stock_sync_paused boolean DEFAULT false NOT NULL,
    status text NOT NULL,
    credentials_ref text,
    token_expires_at timestamp with time zone,
    last_synced_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT channel_account_channel_check CHECK ((channel = ANY (ARRAY['TSF'::text, 'SHOPEE'::text, 'LAZADA'::text, 'TIKTOK'::text]))),
    CONSTRAINT channel_account_credentials_ref_check CHECK (((credentials_ref IS NULL) OR (btrim(credentials_ref) <> ''::text))),
    CONSTRAINT channel_account_external_shop_id_check CHECK ((btrim(external_shop_id) <> ''::text)),
    CONSTRAINT channel_account_mode_check CHECK ((mode = ANY (ARRAY['OBSERVE'::text, 'SHADOW'::text, 'CONTROL'::text, 'ACTIVE'::text]))),
    CONSTRAINT channel_account_status_check CHECK ((status = ANY (ARRAY['CONNECTED'::text, 'DISCONNECTED'::text])))
);

ALTER TABLE ONLY public.channel_account FORCE ROW LEVEL SECURITY;


--
-- Name: COLUMN channel_account.credentials_ref; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.channel_account.credentials_ref IS 'Name of a secret in the secret manager. Never a token or secret value.';


--
-- Name: channel_listing; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.channel_listing (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    channel_account_id uuid NOT NULL,
    sku_id uuid,
    external_item_id text,
    external_sku_id text NOT NULL,
    stock_control boolean DEFAULT false NOT NULL,
    safety_buffer integer DEFAULT 0 NOT NULL,
    last_exposed_qty integer,
    last_pushed_version bigint,
    last_seen_channel_qty integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    seller_sku text,
    name text,
    mapping_source text,
    mapped_at timestamp with time zone,
    removed_at timestamp with time zone,
    CONSTRAINT channel_listing_last_exposed_qty_check CHECK (((last_exposed_qty IS NULL) OR (last_exposed_qty >= 0))),
    CONSTRAINT channel_listing_last_pushed_version_check CHECK (((last_pushed_version IS NULL) OR (last_pushed_version >= 0))),
    CONSTRAINT channel_listing_mapping_source_check CHECK (((mapping_source IS NULL) OR (mapping_source = ANY (ARRAY['AUTO'::text, 'MANUAL'::text])))),
    CONSTRAINT channel_listing_mapping_source_sku_check CHECK ((((sku_id IS NULL) AND (mapping_source IS NULL) AND (mapped_at IS NULL)) OR ((sku_id IS NOT NULL) AND (mapping_source IS NOT NULL) AND (mapped_at IS NOT NULL)))),
    CONSTRAINT channel_listing_safety_buffer_check CHECK ((safety_buffer >= 0))
);

ALTER TABLE ONLY public.channel_listing FORCE ROW LEVEL SECURITY;


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: idempotency_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.idempotency_key (
    tenant_id uuid NOT NULL,
    scope text NOT NULL,
    key text NOT NULL,
    request_hash text NOT NULL,
    response_status integer,
    response_body jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.idempotency_key FORCE ROW LEVEL SECURITY;


--
-- Name: inbox_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inbox_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source text NOT NULL,
    event_id text NOT NULL,
    event_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload jsonb NOT NULL,
    status text NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    last_error text,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    processed_at timestamp with time zone,
    aggregate_version bigint DEFAULT 0 NOT NULL,
    payload_sha256 bytea DEFAULT '\x'::bytea NOT NULL,
    orphan_recorded_at timestamp with time zone,
    CONSTRAINT inbox_event_aggregate_version_check CHECK ((aggregate_version >= 0)),
    CONSTRAINT inbox_event_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT inbox_event_status_check CHECK ((status = ANY (ARRAY['RECEIVED'::text, 'PROCESSED'::text, 'FAILED'::text, 'DEAD'::text])))
);

ALTER TABLE ONLY public.inbox_event FORCE ROW LEVEL SECURITY;


--
-- Name: COLUMN inbox_event.aggregate_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.inbox_event.aggregate_version IS 'Envelope aggregate_version. 0 means the sender omitted it. A version at or below the latest PROCESSED version for the same aggregate is stored and not handled again.';


--
-- Name: COLUMN inbox_event.payload_sha256; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.inbox_event.payload_sha256 IS 'SHA-256 of the raw body stored on the first insert. A later delivery with the same event id and a different hash is still a duplicate.';


--
-- Name: inventory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    on_hand integer DEFAULT 0 NOT NULL,
    reserved integer DEFAULT 0 NOT NULL,
    stock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    ledger_seq bigint DEFAULT 0 NOT NULL,
    CONSTRAINT inventory_ledger_seq_check CHECK ((ledger_seq >= 0)),
    CONSTRAINT inventory_quantity_check CHECK (((on_hand >= 0) AND (reserved >= 0) AND (reserved <= on_hand))),
    CONSTRAINT inventory_stock_version_check CHECK ((stock_version >= 0))
);

ALTER TABLE ONLY public.inventory FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE inventory; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.inventory IS 'Non-bundle SKUs only (trigger inventory_sku_stockable).';


--
-- Name: COLUMN inventory.ledger_seq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.inventory.ledger_seq IS 'Count of ledger rows committed for this stock row. Incremented under row lock on write.';


--
-- Name: inventory_ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_ledger (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    delta_on_hand integer NOT NULL,
    delta_reserved integer NOT NULL,
    reason text NOT NULL,
    ref_type text,
    ref_id uuid,
    actor text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    ledger_seq bigint NOT NULL,
    CONSTRAINT inventory_ledger_reason_check CHECK ((reason = ANY (ARRAY['OPENING_BALANCE'::text, 'RECEIVE'::text, 'ADJUST_IN'::text, 'ADJUST_OUT'::text, 'COUNT_CORRECTION'::text, 'DAMAGE_WRITE_OFF'::text, 'RETURN_RESTOCK'::text, 'SHIP'::text, 'RESERVE'::text, 'RELEASE'::text, 'UNPACK'::text]))),
    CONSTRAINT inventory_ledger_ref_check CHECK (((ref_type IS NULL) = (ref_id IS NULL)))
);

ALTER TABLE ONLY public.inventory_ledger FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE inventory_ledger; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.inventory_ledger IS 'Append-only. UPDATE, DELETE, and TRUNCATE are rejected by trigger.';


--
-- Name: COLUMN inventory_ledger.ledger_seq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.inventory_ledger.ledger_seq IS 'Monotonic per (tenant, sku, warehouse); history and running totals order by this, not created_at.';


--
-- Name: order_hold_retry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_hold_retry (
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone NOT NULL,
    last_error text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.order_hold_retry FORCE ROW LEVEL SECURITY;


--
-- Name: order_line; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_line (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    sku_id uuid,
    external_line_id text,
    external_sku_id text,
    name text NOT NULL,
    qty integer NOT NULL,
    unit_price numeric(14,2) DEFAULT 0 NOT NULL,
    discount numeric(14,2) DEFAULT 0 NOT NULL,
    line_total numeric(14,2) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT order_line_amounts_check CHECK (((unit_price >= (0)::numeric) AND (discount >= (0)::numeric) AND (line_total >= (0)::numeric))),
    CONSTRAINT order_line_qty_check CHECK ((qty > 0))
);

ALTER TABLE ONLY public.order_line FORCE ROW LEVEL SECURITY;


--
-- Name: order_recipient; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_recipient (
    order_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name_enc bytea,
    phone_enc bytea,
    phone_hash bytea,
    phone_last4 text,
    address_enc bytea,
    province text,
    postcode text,
    pii_status text DEFAULT 'ACTIVE'::text NOT NULL,
    redact_after timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT order_recipient_active_check CHECK (((pii_status <> 'ACTIVE'::text) OR ((name_enc IS NOT NULL) AND (address_enc IS NOT NULL)))),
    CONSTRAINT order_recipient_phone_check CHECK (
CASE pii_status
    WHEN 'REDACTED'::text THEN ((phone_hash IS NULL) AND (phone_last4 IS NULL))
    ELSE ((phone_enc IS NULL) = (phone_hash IS NULL))
END),
    CONSTRAINT order_recipient_phone_hash_check CHECK (((phone_hash IS NULL) OR (octet_length(phone_hash) = 32))),
    CONSTRAINT order_recipient_phone_last4_check CHECK (((phone_last4 IS NULL) OR (phone_last4 ~ '^[0-9]{4}$'::text))),
    CONSTRAINT order_recipient_pii_status_check CHECK ((pii_status = ANY (ARRAY['ACTIVE'::text, 'REDACTED'::text]))),
    CONSTRAINT order_recipient_redacted_check CHECK (((pii_status <> 'REDACTED'::text) OR ((name_enc IS NULL) AND (phone_enc IS NULL) AND (address_enc IS NULL))))
);

ALTER TABLE ONLY public.order_recipient FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE order_recipient; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.order_recipient IS 'The only PII table. *_enc = app-level AES-256-GCM (version || kid_len || kid || nonce || ct+tag, AAD header||tenant||order||column). REDACTED keeps province and postcode only (enc columns, phone_hash, phone_last4 are NULL). ON DELETE CASCADE with its order.';


--
-- Name: order_status_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_status_history (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    dimension text NOT NULL,
    from_value text,
    to_value text NOT NULL,
    reason text,
    actor text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT order_status_history_dimension_check CHECK ((dimension = ANY (ARRAY['ORDER'::text, 'PAYMENT'::text, 'FULFILLMENT'::text, 'HOLD'::text]))),
    CONSTRAINT order_status_history_value_check CHECK (
CASE dimension
    WHEN 'ORDER'::text THEN ((to_value = ANY (ARRAY['ACTIVE'::text, 'CANCELLED'::text, 'COMPLETED'::text])) AND ((from_value IS NULL) OR (from_value = ANY (ARRAY['ACTIVE'::text, 'CANCELLED'::text, 'COMPLETED'::text]))))
    WHEN 'PAYMENT'::text THEN ((to_value = ANY (ARRAY['PENDING'::text, 'PAID'::text, 'COD_PENDING'::text, 'PARTIALLY_REFUNDED'::text, 'REFUNDED'::text])) AND ((from_value IS NULL) OR (from_value = ANY (ARRAY['PENDING'::text, 'PAID'::text, 'COD_PENDING'::text, 'PARTIALLY_REFUNDED'::text, 'REFUNDED'::text]))))
    WHEN 'FULFILLMENT'::text THEN ((to_value = ANY (ARRAY['UNFULFILLED'::text, 'READY_TO_PICK'::text, 'PICKING'::text, 'PACKED'::text, 'SHIPPED'::text, 'DELIVERED'::text])) AND ((from_value IS NULL) OR (from_value = ANY (ARRAY['UNFULFILLED'::text, 'READY_TO_PICK'::text, 'PICKING'::text, 'PACKED'::text, 'SHIPPED'::text, 'DELIVERED'::text]))))
    WHEN 'HOLD'::text THEN ((to_value = ANY (ARRAY['NONE'::text, 'SKU_NOT_MAPPED'::text, 'OUT_OF_STOCK'::text, 'ADDRESS_PROBLEM'::text, 'PAYMENT_MISMATCH'::text, 'CHANNEL_CANCEL_PENDING'::text, 'MANUAL'::text])) AND ((from_value IS NULL) OR (from_value = ANY (ARRAY['NONE'::text, 'SKU_NOT_MAPPED'::text, 'OUT_OF_STOCK'::text, 'ADDRESS_PROBLEM'::text, 'PAYMENT_MISMATCH'::text, 'CHANNEL_CANCEL_PENDING'::text, 'MANUAL'::text]))))
    ELSE false
END)
);

ALTER TABLE ONLY public.order_status_history FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE order_status_history; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.order_status_history IS 'Append-only. UPDATE, DELETE, and TRUNCATE are rejected by trigger.';


--
-- Name: outbox_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    status text NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    lease_until timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sent_at timestamp with time zone,
    CONSTRAINT outbox_event_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT outbox_event_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'IN_FLIGHT'::text, 'SENT'::text, 'DEAD'::text])))
);

ALTER TABLE ONLY public.outbox_event FORCE ROW LEVEL SECURITY;


--
-- Name: payment_status_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_status_snapshot (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    provider text NOT NULL,
    provider_ref text,
    status text NOT NULL,
    amount numeric(14,2) NOT NULL,
    refunded_amount numeric(14,2) DEFAULT 0 NOT NULL,
    currency text DEFAULT 'THB'::text NOT NULL,
    paid_at timestamp with time zone,
    observed_at timestamp with time zone NOT NULL,
    source_event_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT payment_status_snapshot_amounts_check CHECK (((amount >= (0)::numeric) AND (refunded_amount >= (0)::numeric))),
    CONSTRAINT payment_status_snapshot_currency_check CHECK ((currency = 'THB'::text)),
    CONSTRAINT payment_status_snapshot_provider_check CHECK ((provider = ANY (ARRAY['XENDIT'::text, 'OPN'::text]))),
    CONSTRAINT payment_status_snapshot_source_event_id_check CHECK ((btrim(source_event_id) <> ''::text)),
    CONSTRAINT payment_status_snapshot_status_check CHECK ((btrim(status) <> ''::text))
);

ALTER TABLE ONLY public.payment_status_snapshot FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE payment_status_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.payment_status_snapshot IS 'Append-only mirror of TSF Pay. No card or bank data. UNIQUE (tenant_id, source_event_id).';


--
-- Name: product; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    status text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT product_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'INACTIVE'::text])))
);

ALTER TABLE ONLY public.product FORCE ROW LEVEL SECURITY;


--
-- Name: reconciliation_issue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reconciliation_issue (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    rule text NOT NULL,
    order_id uuid,
    details jsonb,
    status text DEFAULT 'OPEN'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT reconciliation_issue_rule_check CHECK ((btrim(rule) <> ''::text)),
    CONSTRAINT reconciliation_issue_status_check CHECK ((status = ANY (ARRAY['OPEN'::text, 'ACK'::text, 'RESOLVED'::text])))
);

ALTER TABLE ONLY public.reconciliation_issue FORCE ROW LEVEL SECURITY;


--
-- Name: refund; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refund (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    return_id uuid,
    provider_ref text,
    amount numeric(14,2) NOT NULL,
    currency text DEFAULT 'THB'::text NOT NULL,
    status text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    source_event_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT refund_amount_check CHECK ((amount >= (0)::numeric)),
    CONSTRAINT refund_currency_check CHECK ((currency = 'THB'::text)),
    CONSTRAINT refund_source_event_id_check CHECK ((btrim(source_event_id) <> ''::text)),
    CONSTRAINT refund_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'SUCCEEDED'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY public.refund FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE refund; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.refund IS 'Read-only mirror of TSF Pay refunds. UNIQUE (tenant_id, source_event_id).';


--
-- Name: return_line; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.return_line (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    return_id uuid NOT NULL,
    order_line_id uuid NOT NULL,
    qty integer NOT NULL,
    condition text,
    restocked_qty integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT return_line_condition_check CHECK (((condition IS NULL) OR (condition = ANY (ARRAY['RESELLABLE'::text, 'DAMAGED'::text])))),
    CONSTRAINT return_line_qty_check CHECK ((qty > 0)),
    CONSTRAINT return_line_restocked_qty_check CHECK (((restocked_qty >= 0) AND (restocked_qty <= qty)))
);

ALTER TABLE ONLY public.return_line FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE return_line; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.return_line IS 'Same order as its request and order line (composite FKs). Qty per order line over never-rejected requests <= order_line.qty (triggers, READ COMMITTED only).';


--
-- Name: return_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.return_request (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    external_return_id text,
    type text NOT NULL,
    status text DEFAULT 'REQUESTED'::text NOT NULL,
    rejected boolean DEFAULT false NOT NULL,
    reason text,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    received_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT return_request_rejected_check CHECK ((((status <> 'REJECTED'::text) OR rejected) AND ((NOT rejected) OR (status = ANY (ARRAY['REJECTED'::text, 'CLOSED'::text]))))),
    CONSTRAINT return_request_status_check CHECK ((status = ANY (ARRAY['REQUESTED'::text, 'APPROVED'::text, 'REJECTED'::text, 'RECEIVED'::text, 'CLOSED'::text]))),
    CONSTRAINT return_request_type_check CHECK ((type = ANY (ARRAY['RETURN'::text, 'RTS'::text])))
);

ALTER TABLE ONLY public.return_request FORCE ROW LEVEL SECURITY;


--
-- Name: COLUMN return_request.rejected; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.return_request.rejected IS 'Sticky: set when status becomes REJECTED, never cleared. Rejected requests only move to CLOSED and never count toward the return qty limit.';


--
-- Name: sales_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sales_order (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    channel_account_id uuid NOT NULL,
    external_order_id text NOT NULL,
    order_status text DEFAULT 'ACTIVE'::text NOT NULL,
    payment_status text NOT NULL,
    fulfillment_status text DEFAULT 'UNFULFILLED'::text NOT NULL,
    hold_reason text DEFAULT 'NONE'::text NOT NULL,
    hold_note text,
    channel_status text,
    payment_method text NOT NULL,
    currency text DEFAULT 'THB'::text NOT NULL,
    subtotal numeric(14,2) DEFAULT 0 NOT NULL,
    shipping_fee numeric(14,2) DEFAULT 0 NOT NULL,
    discount numeric(14,2) DEFAULT 0 NOT NULL,
    grand_total numeric(14,2) DEFAULT 0 NOT NULL,
    ordered_at timestamp with time zone NOT NULL,
    paid_at timestamp with time zone,
    ship_by timestamp with time zone,
    completed_at timestamp with time zone,
    external_version bigint,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT sales_order_amounts_check CHECK (((subtotal >= (0)::numeric) AND (shipping_fee >= (0)::numeric) AND (discount >= (0)::numeric) AND (grand_total >= (0)::numeric))),
    CONSTRAINT sales_order_currency_check CHECK ((currency = 'THB'::text)),
    CONSTRAINT sales_order_external_order_id_check CHECK ((btrim(external_order_id) <> ''::text)),
    CONSTRAINT sales_order_external_version_check CHECK (((external_version IS NULL) OR (external_version >= 0))),
    CONSTRAINT sales_order_fulfillment_status_check CHECK ((fulfillment_status = ANY (ARRAY['UNFULFILLED'::text, 'READY_TO_PICK'::text, 'PICKING'::text, 'PACKED'::text, 'SHIPPED'::text, 'DELIVERED'::text]))),
    CONSTRAINT sales_order_hold_reason_check CHECK ((hold_reason = ANY (ARRAY['NONE'::text, 'SKU_NOT_MAPPED'::text, 'OUT_OF_STOCK'::text, 'ADDRESS_PROBLEM'::text, 'PAYMENT_MISMATCH'::text, 'CHANNEL_CANCEL_PENDING'::text, 'MANUAL'::text]))),
    CONSTRAINT sales_order_order_status_check CHECK ((order_status = ANY (ARRAY['ACTIVE'::text, 'CANCELLED'::text, 'COMPLETED'::text]))),
    CONSTRAINT sales_order_payment_method_check CHECK ((payment_method = ANY (ARRAY['PREPAID'::text, 'COD'::text]))),
    CONSTRAINT sales_order_payment_status_check CHECK ((payment_status = ANY (ARRAY['PENDING'::text, 'PAID'::text, 'COD_PENDING'::text, 'PARTIALLY_REFUNDED'::text, 'REFUNDED'::text]))),
    CONSTRAINT sales_order_version_check CHECK ((version >= 0))
);

ALTER TABLE ONLY public.sales_order FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE sales_order; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sales_order IS 'No PII. UNIQUE (channel_account_id, external_order_id). version is the optimistic lock.';


--
-- Name: shadow_diff; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.shadow_diff (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    channel_account_id uuid NOT NULL,
    kind text NOT NULL,
    ref text NOT NULL,
    oms_value jsonb,
    channel_value jsonb,
    observed_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT shadow_diff_kind_check CHECK ((kind = ANY (ARRAY['STOCK'::text, 'ORDER'::text, 'RESERVATION'::text]))),
    CONSTRAINT shadow_diff_ref_check CHECK ((btrim(ref) <> ''::text))
);

ALTER TABLE ONLY public.shadow_diff FORCE ROW LEVEL SECURITY;


--
-- Name: shipment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.shipment (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    carrier text,
    tracking_no text,
    external_shipment_id text,
    label_cached_until timestamp with time zone,
    status text DEFAULT 'PENDING'::text NOT NULL,
    shipped_at timestamp with time zone,
    delivered_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT shipment_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'LABEL_READY'::text, 'SHIPPED'::text, 'IN_TRANSIT'::text, 'DELIVERED'::text, 'FAILED'::text, 'RETURNED_TO_SENDER'::text])))
);

ALTER TABLE ONLY public.shipment FORCE ROW LEVEL SECURITY;


--
-- Name: sku; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sku (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    sku_code text NOT NULL,
    name text NOT NULL,
    barcode text,
    weight_g integer,
    is_bundle boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT sku_sku_code_check CHECK ((btrim(sku_code) <> ''::text)),
    CONSTRAINT sku_weight_g_check CHECK (((weight_g IS NULL) OR (weight_g >= 0)))
);

ALTER TABLE ONLY public.sku FORCE ROW LEVEL SECURITY;


--
-- Name: sku_bundle_component; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sku_bundle_component (
    tenant_id uuid NOT NULL,
    bundle_sku_id uuid NOT NULL,
    component_sku_id uuid NOT NULL,
    qty integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT sku_bundle_component_not_self_check CHECK ((bundle_sku_id <> component_sku_id)),
    CONSTRAINT sku_bundle_component_qty_check CHECK ((qty > 0))
);

ALTER TABLE ONLY public.sku_bundle_component FORCE ROW LEVEL SECURITY;


--
-- Name: stock_document; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_document (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    type text NOT NULL,
    status text NOT NULL,
    reference_no text,
    note text,
    count_started_at timestamp with time zone,
    posted_at timestamp with time zone,
    posted_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT stock_document_count_started_at_check CHECK (((count_started_at IS NULL) OR (type = 'COUNT'::text))),
    CONSTRAINT stock_document_posted_at_check CHECK (((status = 'DRAFT'::text) = (posted_at IS NULL))),
    CONSTRAINT stock_document_status_check CHECK ((status = ANY (ARRAY['DRAFT'::text, 'POSTED'::text, 'VOID'::text]))),
    CONSTRAINT stock_document_type_check CHECK ((type = ANY (ARRAY['OPENING'::text, 'RECEIVE'::text, 'ADJUSTMENT'::text, 'COUNT'::text, 'WRITE_OFF'::text])))
);

ALTER TABLE ONLY public.stock_document FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE stock_document; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.stock_document IS 'DRAFT -> POSTED -> VOID. Immutable after DRAFT except the status move (trigger).';


--
-- Name: stock_document_line; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_document_line (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    qty integer NOT NULL,
    system_qty_at_start integer,
    counted_qty integer,
    reason_code text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT stock_document_line_counted_qty_check CHECK (((counted_qty IS NULL) OR (counted_qty >= 0))),
    CONSTRAINT stock_document_line_system_qty_check CHECK (((system_qty_at_start IS NULL) OR (system_qty_at_start >= 0)))
);

ALTER TABLE ONLY public.stock_document_line FORCE ROW LEVEL SECURITY;


--
-- Name: stock_reservation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_reservation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    owner_type text NOT NULL,
    owner_ref text NOT NULL,
    sku_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    qty integer NOT NULL,
    status text NOT NULL,
    expires_at timestamp with time zone,
    reservation_group_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT stock_reservation_owner_ref_check CHECK ((btrim(owner_ref) <> ''::text)),
    CONSTRAINT stock_reservation_owner_type_check CHECK ((owner_type = ANY (ARRAY['CHECKOUT'::text, 'ORDER'::text]))),
    CONSTRAINT stock_reservation_qty_check CHECK ((qty > 0)),
    CONSTRAINT stock_reservation_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'CONSUMED'::text, 'RELEASED'::text, 'EXPIRED'::text])))
);

ALTER TABLE ONLY public.stock_reservation FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE stock_reservation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.stock_reservation IS 'One row per component SKU. At most one ACTIVE row per (tenant, owner_type, owner_ref, sku). Requires an inventory row (FK), so bundles cannot be reserved.';


--
-- Name: sync_cursor; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sync_cursor (
    tenant_id uuid NOT NULL,
    channel_account_id uuid NOT NULL,
    resource text NOT NULL,
    cursor text,
    last_success_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT sync_cursor_resource_check CHECK ((resource = ANY (ARRAY['ORDERS'::text, 'LISTINGS'::text])))
);

ALTER TABLE ONLY public.sync_cursor FORCE ROW LEVEL SECURITY;


--
-- Name: tenant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant (
    id uuid NOT NULL,
    name text NOT NULL,
    tsf_shop_id text NOT NULL,
    membership_tier text NOT NULL,
    entitlement_status text NOT NULL,
    entitlement_expires_at timestamp with time zone,
    ent_ver bigint NOT NULL,
    CONSTRAINT tenant_entitlement_status_check CHECK ((entitlement_status = ANY (ARRAY['ACTIVE'::text, 'GRACE'::text, 'SUSPENDED'::text])))
);

ALTER TABLE ONLY public.tenant FORCE ROW LEVEL SECURITY;


--
-- Name: tenant_membership; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_membership (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role text NOT NULL,
    status text NOT NULL,
    CONSTRAINT tenant_membership_role_check CHECK ((role = ANY (ARRAY['OWNER'::text, 'ADMIN'::text, 'STAFF'::text]))),
    CONSTRAINT tenant_membership_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'REVOKED'::text])))
);

ALTER TABLE ONLY public.tenant_membership FORCE ROW LEVEL SECURITY;


--
-- Name: warehouse; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.warehouse (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    address jsonb,
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT warehouse_code_check CHECK ((btrim(code) <> ''::text))
);

ALTER TABLE ONLY public.warehouse FORCE ROW LEVEL SECURITY;


--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: app_user app_user_tsf_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_tsf_user_id_key UNIQUE (tsf_user_id);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);


--
-- Name: channel_account channel_account_channel_external_shop_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_account
    ADD CONSTRAINT channel_account_channel_external_shop_key UNIQUE (channel, external_shop_id);


--
-- Name: CONSTRAINT channel_account_channel_external_shop_key ON channel_account; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT channel_account_channel_external_shop_key ON public.channel_account IS 'Global on purpose: one external shop maps to exactly one tenant.';


--
-- Name: channel_account channel_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_account
    ADD CONSTRAINT channel_account_pkey PRIMARY KEY (id);


--
-- Name: channel_account channel_account_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_account
    ADD CONSTRAINT channel_account_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: channel_listing channel_listing_account_external_sku_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_listing
    ADD CONSTRAINT channel_listing_account_external_sku_key UNIQUE (channel_account_id, external_sku_id);


--
-- Name: channel_listing channel_listing_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_listing
    ADD CONSTRAINT channel_listing_pkey PRIMARY KEY (id);


--
-- Name: channel_listing channel_listing_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_listing
    ADD CONSTRAINT channel_listing_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: idempotency_key idempotency_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_key
    ADD CONSTRAINT idempotency_key_pkey PRIMARY KEY (tenant_id, scope, key);


--
-- Name: inbox_event inbox_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inbox_event
    ADD CONSTRAINT inbox_event_pkey PRIMARY KEY (id);


--
-- Name: inbox_event inbox_event_tenant_source_event_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inbox_event
    ADD CONSTRAINT inbox_event_tenant_source_event_key UNIQUE (tenant_id, source, event_id);


--
-- Name: CONSTRAINT inbox_event_tenant_source_event_key ON inbox_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT inbox_event_tenant_source_event_key ON public.inbox_event IS 'At-least-once dedup per tenant. Replaces V1 UNIQUE (source, event_id).';


--
-- Name: inventory_ledger inventory_ledger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_ledger
    ADD CONSTRAINT inventory_ledger_pkey PRIMARY KEY (id);


--
-- Name: inventory_ledger inventory_ledger_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_ledger
    ADD CONSTRAINT inventory_ledger_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: inventory inventory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory
    ADD CONSTRAINT inventory_pkey PRIMARY KEY (id);


--
-- Name: inventory inventory_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory
    ADD CONSTRAINT inventory_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: inventory inventory_tenant_sku_warehouse_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory
    ADD CONSTRAINT inventory_tenant_sku_warehouse_key UNIQUE (tenant_id, sku_id, warehouse_id);


--
-- Name: order_hold_retry order_hold_retry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_hold_retry
    ADD CONSTRAINT order_hold_retry_pkey PRIMARY KEY (tenant_id, order_id);


--
-- Name: order_line order_line_order_external_line_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT order_line_order_external_line_key UNIQUE (order_id, external_line_id);


--
-- Name: order_line order_line_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT order_line_pkey PRIMARY KEY (id);


--
-- Name: order_line order_line_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT order_line_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: order_line order_line_tenant_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT order_line_tenant_order_id_key UNIQUE (tenant_id, order_id, id);


--
-- Name: order_recipient order_recipient_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_recipient
    ADD CONSTRAINT order_recipient_pkey PRIMARY KEY (order_id);


--
-- Name: order_status_history order_status_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_status_history
    ADD CONSTRAINT order_status_history_pkey PRIMARY KEY (id);


--
-- Name: order_status_history order_status_history_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_status_history
    ADD CONSTRAINT order_status_history_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: outbox_event outbox_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);


--
-- Name: payment_status_snapshot payment_status_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_status_snapshot
    ADD CONSTRAINT payment_status_snapshot_pkey PRIMARY KEY (id);


--
-- Name: payment_status_snapshot payment_status_snapshot_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_status_snapshot
    ADD CONSTRAINT payment_status_snapshot_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: payment_status_snapshot payment_status_snapshot_tenant_source_event_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_status_snapshot
    ADD CONSTRAINT payment_status_snapshot_tenant_source_event_key UNIQUE (tenant_id, source_event_id);


--
-- Name: product product_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_pkey PRIMARY KEY (id);


--
-- Name: product product_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: reconciliation_issue reconciliation_issue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_issue
    ADD CONSTRAINT reconciliation_issue_pkey PRIMARY KEY (id);


--
-- Name: reconciliation_issue reconciliation_issue_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_issue
    ADD CONSTRAINT reconciliation_issue_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: refund refund_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refund
    ADD CONSTRAINT refund_pkey PRIMARY KEY (id);


--
-- Name: refund refund_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refund
    ADD CONSTRAINT refund_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: refund refund_tenant_source_event_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refund
    ADD CONSTRAINT refund_tenant_source_event_key UNIQUE (tenant_id, source_event_id);


--
-- Name: return_line return_line_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_line
    ADD CONSTRAINT return_line_pkey PRIMARY KEY (id);


--
-- Name: return_line return_line_return_order_line_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_line
    ADD CONSTRAINT return_line_return_order_line_key UNIQUE (return_id, order_line_id);


--
-- Name: return_line return_line_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_line
    ADD CONSTRAINT return_line_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: return_request return_request_order_external_return_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_request
    ADD CONSTRAINT return_request_order_external_return_key UNIQUE (order_id, external_return_id);


--
-- Name: return_request return_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_request
    ADD CONSTRAINT return_request_pkey PRIMARY KEY (id);


--
-- Name: return_request return_request_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_request
    ADD CONSTRAINT return_request_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: return_request return_request_tenant_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_request
    ADD CONSTRAINT return_request_tenant_order_id_key UNIQUE (tenant_id, order_id, id);


--
-- Name: sales_order sales_order_channel_account_external_order_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_order
    ADD CONSTRAINT sales_order_channel_account_external_order_key UNIQUE (channel_account_id, external_order_id);


--
-- Name: sales_order sales_order_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_order
    ADD CONSTRAINT sales_order_pkey PRIMARY KEY (id);


--
-- Name: sales_order sales_order_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_order
    ADD CONSTRAINT sales_order_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: shadow_diff shadow_diff_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shadow_diff
    ADD CONSTRAINT shadow_diff_pkey PRIMARY KEY (id);


--
-- Name: shadow_diff shadow_diff_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shadow_diff
    ADD CONSTRAINT shadow_diff_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: shipment shipment_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shipment
    ADD CONSTRAINT shipment_order_id_key UNIQUE (order_id);


--
-- Name: shipment shipment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shipment
    ADD CONSTRAINT shipment_pkey PRIMARY KEY (id);


--
-- Name: shipment shipment_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shipment
    ADD CONSTRAINT shipment_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: sku_bundle_component sku_bundle_component_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku_bundle_component
    ADD CONSTRAINT sku_bundle_component_pkey PRIMARY KEY (bundle_sku_id, component_sku_id);


--
-- Name: sku sku_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku
    ADD CONSTRAINT sku_pkey PRIMARY KEY (id);


--
-- Name: sku sku_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku
    ADD CONSTRAINT sku_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: sku sku_tenant_sku_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku
    ADD CONSTRAINT sku_tenant_sku_code_key UNIQUE (tenant_id, sku_code);


--
-- Name: stock_document_line stock_document_line_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document_line
    ADD CONSTRAINT stock_document_line_pkey PRIMARY KEY (id);


--
-- Name: stock_document_line stock_document_line_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document_line
    ADD CONSTRAINT stock_document_line_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: stock_document stock_document_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document
    ADD CONSTRAINT stock_document_pkey PRIMARY KEY (id);


--
-- Name: stock_document stock_document_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document
    ADD CONSTRAINT stock_document_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: stock_reservation stock_reservation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_reservation
    ADD CONSTRAINT stock_reservation_pkey PRIMARY KEY (id);


--
-- Name: stock_reservation stock_reservation_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_reservation
    ADD CONSTRAINT stock_reservation_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: sync_cursor sync_cursor_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sync_cursor
    ADD CONSTRAINT sync_cursor_pkey PRIMARY KEY (channel_account_id, resource);


--
-- Name: tenant_membership tenant_membership_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_membership
    ADD CONSTRAINT tenant_membership_pkey PRIMARY KEY (id);


--
-- Name: tenant_membership tenant_membership_tenant_user_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_membership
    ADD CONSTRAINT tenant_membership_tenant_user_key UNIQUE (tenant_id, user_id);


--
-- Name: tenant tenant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (id);


--
-- Name: tenant tenant_tsf_shop_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_tsf_shop_id_key UNIQUE (tsf_shop_id);


--
-- Name: warehouse warehouse_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.warehouse
    ADD CONSTRAINT warehouse_pkey PRIMARY KEY (id);


--
-- Name: warehouse warehouse_tenant_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.warehouse
    ADD CONSTRAINT warehouse_tenant_code_key UNIQUE (tenant_id, code);


--
-- Name: warehouse warehouse_tenant_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.warehouse
    ADD CONSTRAINT warehouse_tenant_id_id_key UNIQUE (tenant_id, id);


--
-- Name: channel_listing_tenant_sku_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX channel_listing_tenant_sku_idx ON public.channel_listing USING btree (tenant_id, sku_id) WHERE (sku_id IS NOT NULL);


--
-- Name: channel_listing_unmapped_account_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX channel_listing_unmapped_account_idx ON public.channel_listing USING btree (tenant_id, channel_account_id) WHERE (sku_id IS NULL);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: inbox_event_aggregate_processed_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inbox_event_aggregate_processed_idx ON public.inbox_event USING btree (tenant_id, source, aggregate_id, aggregate_version DESC) WHERE (status = 'PROCESSED'::text);


--
-- Name: inbox_event_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inbox_event_due_idx ON public.inbox_event USING btree (COALESCE(next_attempt_at, received_at), id) WHERE (status = ANY (ARRAY['RECEIVED'::text, 'FAILED'::text]));


--
-- Name: inbox_event_status_next_attempt_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inbox_event_status_next_attempt_at_idx ON public.inbox_event USING btree (status, next_attempt_at);


--
-- Name: inventory_ledger_tenant_ref_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inventory_ledger_tenant_ref_idx ON public.inventory_ledger USING btree (tenant_id, ref_type, ref_id) WHERE (ref_id IS NOT NULL);


--
-- Name: inventory_ledger_tenant_sku_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inventory_ledger_tenant_sku_created_idx ON public.inventory_ledger USING btree (tenant_id, sku_id, warehouse_id, created_at DESC);


--
-- Name: inventory_ledger_tenant_sku_seq_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inventory_ledger_tenant_sku_seq_idx ON public.inventory_ledger USING btree (tenant_id, sku_id, warehouse_id, ledger_seq DESC);


--
-- Name: inventory_ledger_tenant_sku_wh_seq_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX inventory_ledger_tenant_sku_wh_seq_key ON public.inventory_ledger USING btree (tenant_id, sku_id, warehouse_id, ledger_seq);


--
-- Name: inventory_tenant_warehouse_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inventory_tenant_warehouse_idx ON public.inventory USING btree (tenant_id, warehouse_id);


--
-- Name: order_hold_retry_tenant_next_attempt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_hold_retry_tenant_next_attempt_idx ON public.order_hold_retry USING btree (tenant_id, next_attempt_at);


--
-- Name: order_line_tenant_sku_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_line_tenant_sku_idx ON public.order_line USING btree (tenant_id, sku_id) WHERE (sku_id IS NOT NULL);


--
-- Name: order_recipient_pii_status_redact_after_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_recipient_pii_status_redact_after_idx ON public.order_recipient USING btree (pii_status, redact_after);


--
-- Name: order_recipient_tenant_phone_hash_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_recipient_tenant_phone_hash_idx ON public.order_recipient USING btree (tenant_id, phone_hash);


--
-- Name: order_status_history_tenant_order_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_status_history_tenant_order_created_idx ON public.order_status_history USING btree (tenant_id, order_id, created_at);


--
-- Name: outbox_event_status_next_attempt_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX outbox_event_status_next_attempt_at_idx ON public.outbox_event USING btree (status, next_attempt_at);


--
-- Name: payment_status_snapshot_tenant_order_observed_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX payment_status_snapshot_tenant_order_observed_idx ON public.payment_status_snapshot USING btree (tenant_id, order_id, observed_at DESC);


--
-- Name: reconciliation_issue_open_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX reconciliation_issue_open_key ON public.reconciliation_issue USING btree (tenant_id, rule, order_id) NULLS NOT DISTINCT WHERE (status <> 'RESOLVED'::text);


--
-- Name: INDEX reconciliation_issue_open_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.reconciliation_issue_open_key IS 'One open issue per (tenant, rule, order). NULLS NOT DISTINCT: one open shop-level issue per rule.';


--
-- Name: reconciliation_issue_tenant_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX reconciliation_issue_tenant_order_idx ON public.reconciliation_issue USING btree (tenant_id, order_id) WHERE (order_id IS NOT NULL);


--
-- Name: reconciliation_issue_tenant_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX reconciliation_issue_tenant_status_idx ON public.reconciliation_issue USING btree (tenant_id, status, created_at DESC);


--
-- Name: refund_tenant_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX refund_tenant_order_idx ON public.refund USING btree (tenant_id, order_id);


--
-- Name: refund_tenant_order_return_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX refund_tenant_order_return_idx ON public.refund USING btree (tenant_id, order_id, return_id) WHERE (return_id IS NOT NULL);


--
-- Name: return_line_order_line_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX return_line_order_line_idx ON public.return_line USING btree (order_line_id);


--
-- Name: return_line_tenant_order_return_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX return_line_tenant_order_return_idx ON public.return_line USING btree (tenant_id, order_id, return_id);


--
-- Name: sales_order_tenant_fulfillment_ordered_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sales_order_tenant_fulfillment_ordered_idx ON public.sales_order USING btree (tenant_id, fulfillment_status, ordered_at DESC);


--
-- Name: sales_order_tenant_hold_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sales_order_tenant_hold_idx ON public.sales_order USING btree (tenant_id, hold_reason) WHERE (hold_reason <> 'NONE'::text);


--
-- Name: sales_order_tenant_ordered_id_desc_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sales_order_tenant_ordered_id_desc_idx ON public.sales_order USING btree (tenant_id, ordered_at DESC, id DESC);


--
-- Name: sales_order_tenant_ship_by_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sales_order_tenant_ship_by_idx ON public.sales_order USING btree (tenant_id, ship_by) WHERE (fulfillment_status = ANY (ARRAY['READY_TO_PICK'::text, 'PICKING'::text, 'PACKED'::text]));


--
-- Name: shadow_diff_tenant_account_kind_observed_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX shadow_diff_tenant_account_kind_observed_idx ON public.shadow_diff USING btree (tenant_id, channel_account_id, kind, observed_at DESC);


--
-- Name: shipment_tenant_tracking_no_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX shipment_tenant_tracking_no_idx ON public.shipment USING btree (tenant_id, tracking_no) WHERE (tracking_no IS NOT NULL);


--
-- Name: shipment_tenant_warehouse_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX shipment_tenant_warehouse_idx ON public.shipment USING btree (tenant_id, warehouse_id);


--
-- Name: sku_bundle_component_component_sku_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sku_bundle_component_component_sku_idx ON public.sku_bundle_component USING btree (component_sku_id);


--
-- Name: sku_tenant_product_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sku_tenant_product_idx ON public.sku USING btree (tenant_id, product_id);


--
-- Name: stock_document_line_document_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_document_line_document_idx ON public.stock_document_line USING btree (tenant_id, document_id);


--
-- Name: stock_document_line_sku_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_document_line_sku_idx ON public.stock_document_line USING btree (tenant_id, sku_id);


--
-- Name: stock_document_tenant_status_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_document_tenant_status_created_idx ON public.stock_document USING btree (tenant_id, status, created_at DESC);


--
-- Name: stock_reservation_active_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_reservation_active_expiry_idx ON public.stock_reservation USING btree (status, expires_at) WHERE (status = 'ACTIVE'::text);


--
-- Name: stock_reservation_active_owner_sku_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX stock_reservation_active_owner_sku_key ON public.stock_reservation USING btree (tenant_id, owner_type, owner_ref, sku_id) WHERE (status = 'ACTIVE'::text);


--
-- Name: stock_reservation_group_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_reservation_group_id_idx ON public.stock_reservation USING btree (reservation_group_id);


--
-- Name: stock_reservation_group_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_reservation_group_idx ON public.stock_reservation USING btree (tenant_id, reservation_group_id);


--
-- Name: stock_reservation_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_reservation_owner_idx ON public.stock_reservation USING btree (tenant_id, owner_type, owner_ref);


--
-- Name: stock_reservation_tenant_sku_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_reservation_tenant_sku_idx ON public.stock_reservation USING btree (tenant_id, sku_id, warehouse_id);


--
-- Name: sync_cursor_tenant_channel_account_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sync_cursor_tenant_channel_account_idx ON public.sync_cursor USING btree (tenant_id, channel_account_id);


--
-- Name: warehouse_one_default_per_tenant_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX warehouse_one_default_per_tenant_idx ON public.warehouse USING btree (tenant_id) WHERE is_default;


--
-- Name: audit_log audit_log_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER audit_log_append_only BEFORE DELETE OR UPDATE ON public.audit_log FOR EACH ROW EXECUTE FUNCTION public.audit_log_reject_mutation();


--
-- Name: audit_log audit_log_append_only_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER audit_log_append_only_truncate BEFORE TRUNCATE ON public.audit_log FOR EACH STATEMENT EXECUTE FUNCTION public.audit_log_reject_mutation();


--
-- Name: inventory_ledger inventory_ledger_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER inventory_ledger_append_only BEFORE DELETE OR UPDATE ON public.inventory_ledger FOR EACH ROW EXECUTE FUNCTION public.inventory_ledger_reject_mutation();


--
-- Name: inventory_ledger inventory_ledger_append_only_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER inventory_ledger_append_only_truncate BEFORE TRUNCATE ON public.inventory_ledger FOR EACH STATEMENT EXECUTE FUNCTION public.inventory_ledger_reject_mutation();


--
-- Name: inventory inventory_sku_stockable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER inventory_sku_stockable BEFORE INSERT OR UPDATE OF sku_id ON public.inventory FOR EACH ROW EXECUTE FUNCTION public.sku_require_stockable();


--
-- Name: order_line order_line_return_qty_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER order_line_return_qty_check BEFORE UPDATE OF qty ON public.order_line FOR EACH ROW WHEN ((new.qty < old.qty)) EXECUTE FUNCTION public.order_line_return_qty_check();


--
-- Name: order_status_history order_status_history_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER order_status_history_append_only BEFORE DELETE OR UPDATE ON public.order_status_history FOR EACH ROW EXECUTE FUNCTION public.order_append_only_reject_mutation();


--
-- Name: order_status_history order_status_history_append_only_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER order_status_history_append_only_truncate BEFORE TRUNCATE ON public.order_status_history FOR EACH STATEMENT EXECUTE FUNCTION public.order_append_only_reject_mutation();


--
-- Name: payment_status_snapshot payment_status_snapshot_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER payment_status_snapshot_append_only BEFORE DELETE OR UPDATE ON public.payment_status_snapshot FOR EACH ROW EXECUTE FUNCTION public.order_append_only_reject_mutation();


--
-- Name: payment_status_snapshot payment_status_snapshot_append_only_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER payment_status_snapshot_append_only_truncate BEFORE TRUNCATE ON public.payment_status_snapshot FOR EACH STATEMENT EXECUTE FUNCTION public.order_append_only_reject_mutation();


--
-- Name: return_line return_line_qty_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER return_line_qty_check BEFORE INSERT OR UPDATE OF qty, order_line_id, return_id, order_id ON public.return_line FOR EACH ROW EXECUTE FUNCTION public.return_line_qty_check();


--
-- Name: return_request return_request_rejected_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER return_request_rejected_guard BEFORE INSERT OR UPDATE OF status, rejected ON public.return_request FOR EACH ROW EXECUTE FUNCTION public.return_request_rejected_guard();


--
-- Name: sku_bundle_component sku_bundle_component_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER sku_bundle_component_check BEFORE INSERT OR UPDATE ON public.sku_bundle_component FOR EACH ROW EXECUTE FUNCTION public.sku_bundle_component_check();


--
-- Name: sku sku_is_bundle_change_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER sku_is_bundle_change_check BEFORE UPDATE OF is_bundle ON public.sku FOR EACH ROW WHEN ((old.is_bundle IS DISTINCT FROM new.is_bundle)) EXECUTE FUNCTION public.sku_is_bundle_change_check();


--
-- Name: stock_document stock_document_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER stock_document_guard BEFORE INSERT OR DELETE OR UPDATE ON public.stock_document FOR EACH ROW EXECUTE FUNCTION public.stock_document_guard();


--
-- Name: stock_document_line stock_document_line_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER stock_document_line_guard BEFORE INSERT OR DELETE OR UPDATE ON public.stock_document_line FOR EACH ROW EXECUTE FUNCTION public.stock_document_line_guard();


--
-- Name: stock_document_line stock_document_line_sku_stockable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER stock_document_line_sku_stockable BEFORE INSERT OR UPDATE OF sku_id ON public.stock_document_line FOR EACH ROW EXECUTE FUNCTION public.sku_require_stockable();


--
-- Name: audit_log audit_log_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: channel_account channel_account_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_account
    ADD CONSTRAINT channel_account_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: channel_listing channel_listing_channel_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_listing
    ADD CONSTRAINT channel_listing_channel_account_fkey FOREIGN KEY (tenant_id, channel_account_id) REFERENCES public.channel_account(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: channel_listing channel_listing_sku_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_listing
    ADD CONSTRAINT channel_listing_sku_fkey FOREIGN KEY (tenant_id, sku_id) REFERENCES public.sku(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: channel_listing channel_listing_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channel_listing
    ADD CONSTRAINT channel_listing_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: idempotency_key idempotency_key_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_key
    ADD CONSTRAINT idempotency_key_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: inbox_event inbox_event_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inbox_event
    ADD CONSTRAINT inbox_event_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: inventory_ledger inventory_ledger_inventory_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_ledger
    ADD CONSTRAINT inventory_ledger_inventory_fkey FOREIGN KEY (tenant_id, sku_id, warehouse_id) REFERENCES public.inventory(tenant_id, sku_id, warehouse_id) ON DELETE RESTRICT;


--
-- Name: inventory_ledger inventory_ledger_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_ledger
    ADD CONSTRAINT inventory_ledger_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: inventory inventory_sku_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory
    ADD CONSTRAINT inventory_sku_fkey FOREIGN KEY (tenant_id, sku_id) REFERENCES public.sku(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: inventory inventory_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory
    ADD CONSTRAINT inventory_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: inventory inventory_warehouse_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory
    ADD CONSTRAINT inventory_warehouse_fkey FOREIGN KEY (tenant_id, warehouse_id) REFERENCES public.warehouse(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: order_hold_retry order_hold_retry_sales_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_hold_retry
    ADD CONSTRAINT order_hold_retry_sales_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE CASCADE;


--
-- Name: order_hold_retry order_hold_retry_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_hold_retry
    ADD CONSTRAINT order_hold_retry_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: order_line order_line_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT order_line_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: order_line order_line_sku_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT order_line_sku_fkey FOREIGN KEY (tenant_id, sku_id) REFERENCES public.sku(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: order_line order_line_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT order_line_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: order_recipient order_recipient_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_recipient
    ADD CONSTRAINT order_recipient_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE CASCADE;


--
-- Name: order_recipient order_recipient_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_recipient
    ADD CONSTRAINT order_recipient_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: order_status_history order_status_history_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_status_history
    ADD CONSTRAINT order_status_history_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: order_status_history order_status_history_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_status_history
    ADD CONSTRAINT order_status_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: outbox_event outbox_event_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_event
    ADD CONSTRAINT outbox_event_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: payment_status_snapshot payment_status_snapshot_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_status_snapshot
    ADD CONSTRAINT payment_status_snapshot_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: payment_status_snapshot payment_status_snapshot_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_status_snapshot
    ADD CONSTRAINT payment_status_snapshot_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: product product_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: reconciliation_issue reconciliation_issue_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_issue
    ADD CONSTRAINT reconciliation_issue_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: reconciliation_issue reconciliation_issue_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_issue
    ADD CONSTRAINT reconciliation_issue_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: refund refund_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refund
    ADD CONSTRAINT refund_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: refund refund_return_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refund
    ADD CONSTRAINT refund_return_fkey FOREIGN KEY (tenant_id, order_id, return_id) REFERENCES public.return_request(tenant_id, order_id, id) ON DELETE RESTRICT;


--
-- Name: refund refund_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refund
    ADD CONSTRAINT refund_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: return_line return_line_order_line_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_line
    ADD CONSTRAINT return_line_order_line_fkey FOREIGN KEY (tenant_id, order_id, order_line_id) REFERENCES public.order_line(tenant_id, order_id, id) ON DELETE RESTRICT;


--
-- Name: return_line return_line_return_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_line
    ADD CONSTRAINT return_line_return_fkey FOREIGN KEY (tenant_id, order_id, return_id) REFERENCES public.return_request(tenant_id, order_id, id) ON DELETE RESTRICT;


--
-- Name: return_line return_line_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_line
    ADD CONSTRAINT return_line_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: return_request return_request_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_request
    ADD CONSTRAINT return_request_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: return_request return_request_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_request
    ADD CONSTRAINT return_request_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: sales_order sales_order_channel_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_order
    ADD CONSTRAINT sales_order_channel_account_fkey FOREIGN KEY (tenant_id, channel_account_id) REFERENCES public.channel_account(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: sales_order sales_order_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_order
    ADD CONSTRAINT sales_order_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: shadow_diff shadow_diff_channel_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shadow_diff
    ADD CONSTRAINT shadow_diff_channel_account_fkey FOREIGN KEY (tenant_id, channel_account_id) REFERENCES public.channel_account(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: shadow_diff shadow_diff_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shadow_diff
    ADD CONSTRAINT shadow_diff_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: shipment shipment_order_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shipment
    ADD CONSTRAINT shipment_order_fkey FOREIGN KEY (tenant_id, order_id) REFERENCES public.sales_order(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: shipment shipment_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shipment
    ADD CONSTRAINT shipment_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: shipment shipment_warehouse_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shipment
    ADD CONSTRAINT shipment_warehouse_fkey FOREIGN KEY (tenant_id, warehouse_id) REFERENCES public.warehouse(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: sku_bundle_component sku_bundle_component_bundle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku_bundle_component
    ADD CONSTRAINT sku_bundle_component_bundle_fkey FOREIGN KEY (tenant_id, bundle_sku_id) REFERENCES public.sku(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: sku_bundle_component sku_bundle_component_component_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku_bundle_component
    ADD CONSTRAINT sku_bundle_component_component_fkey FOREIGN KEY (tenant_id, component_sku_id) REFERENCES public.sku(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: sku_bundle_component sku_bundle_component_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku_bundle_component
    ADD CONSTRAINT sku_bundle_component_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: sku sku_product_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku
    ADD CONSTRAINT sku_product_fkey FOREIGN KEY (tenant_id, product_id) REFERENCES public.product(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: sku sku_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sku
    ADD CONSTRAINT sku_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: stock_document_line stock_document_line_document_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document_line
    ADD CONSTRAINT stock_document_line_document_fkey FOREIGN KEY (tenant_id, document_id) REFERENCES public.stock_document(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: stock_document_line stock_document_line_sku_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document_line
    ADD CONSTRAINT stock_document_line_sku_fkey FOREIGN KEY (tenant_id, sku_id) REFERENCES public.sku(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: stock_document_line stock_document_line_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document_line
    ADD CONSTRAINT stock_document_line_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: stock_document_line stock_document_line_warehouse_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document_line
    ADD CONSTRAINT stock_document_line_warehouse_fkey FOREIGN KEY (tenant_id, warehouse_id) REFERENCES public.warehouse(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: stock_document stock_document_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_document
    ADD CONSTRAINT stock_document_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: stock_reservation stock_reservation_inventory_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_reservation
    ADD CONSTRAINT stock_reservation_inventory_fkey FOREIGN KEY (tenant_id, sku_id, warehouse_id) REFERENCES public.inventory(tenant_id, sku_id, warehouse_id) ON DELETE RESTRICT;


--
-- Name: stock_reservation stock_reservation_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_reservation
    ADD CONSTRAINT stock_reservation_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: sync_cursor sync_cursor_channel_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sync_cursor
    ADD CONSTRAINT sync_cursor_channel_account_fkey FOREIGN KEY (tenant_id, channel_account_id) REFERENCES public.channel_account(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: sync_cursor sync_cursor_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sync_cursor
    ADD CONSTRAINT sync_cursor_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: tenant_membership tenant_membership_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_membership
    ADD CONSTRAINT tenant_membership_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: tenant_membership tenant_membership_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_membership
    ADD CONSTRAINT tenant_membership_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(id);


--
-- Name: warehouse warehouse_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.warehouse
    ADD CONSTRAINT warehouse_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(id);


--
-- Name: audit_log; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.audit_log ENABLE ROW LEVEL SECURITY;

--
-- Name: channel_account; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.channel_account ENABLE ROW LEVEL SECURITY;

--
-- Name: channel_listing; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.channel_listing ENABLE ROW LEVEL SECURITY;

--
-- Name: idempotency_key; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.idempotency_key ENABLE ROW LEVEL SECURITY;

--
-- Name: inbox_event; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.inbox_event ENABLE ROW LEVEL SECURITY;

--
-- Name: inventory; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.inventory ENABLE ROW LEVEL SECURITY;

--
-- Name: inventory_ledger; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.inventory_ledger ENABLE ROW LEVEL SECURITY;

--
-- Name: order_hold_retry; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.order_hold_retry ENABLE ROW LEVEL SECURITY;

--
-- Name: order_line; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.order_line ENABLE ROW LEVEL SECURITY;

--
-- Name: order_recipient; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.order_recipient ENABLE ROW LEVEL SECURITY;

--
-- Name: order_status_history; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.order_status_history ENABLE ROW LEVEL SECURITY;

--
-- Name: outbox_event; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.outbox_event ENABLE ROW LEVEL SECURITY;

--
-- Name: payment_status_snapshot; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.payment_status_snapshot ENABLE ROW LEVEL SECURITY;

--
-- Name: product; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.product ENABLE ROW LEVEL SECURITY;

--
-- Name: reconciliation_issue; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.reconciliation_issue ENABLE ROW LEVEL SECURITY;

--
-- Name: refund; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.refund ENABLE ROW LEVEL SECURITY;

--
-- Name: return_line; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.return_line ENABLE ROW LEVEL SECURITY;

--
-- Name: return_request; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.return_request ENABLE ROW LEVEL SECURITY;

--
-- Name: sales_order; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sales_order ENABLE ROW LEVEL SECURITY;

--
-- Name: shadow_diff; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.shadow_diff ENABLE ROW LEVEL SECURITY;

--
-- Name: shipment; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.shipment ENABLE ROW LEVEL SECURITY;

--
-- Name: sku; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sku ENABLE ROW LEVEL SECURITY;

--
-- Name: sku_bundle_component; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sku_bundle_component ENABLE ROW LEVEL SECURITY;

--
-- Name: stock_document; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.stock_document ENABLE ROW LEVEL SECURITY;

--
-- Name: stock_document_line; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.stock_document_line ENABLE ROW LEVEL SECURITY;

--
-- Name: stock_reservation; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.stock_reservation ENABLE ROW LEVEL SECURITY;

--
-- Name: sync_cursor; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sync_cursor ENABLE ROW LEVEL SECURITY;

--
-- Name: tenant; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.tenant ENABLE ROW LEVEL SECURITY;

--
-- Name: audit_log tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.audit_log USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: channel_account tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.channel_account USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: channel_listing tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.channel_listing USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: idempotency_key tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.idempotency_key USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: inbox_event tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.inbox_event USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: inventory tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.inventory USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: inventory_ledger tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.inventory_ledger USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: order_hold_retry tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.order_hold_retry USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: order_line tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.order_line USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: order_recipient tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.order_recipient USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: order_status_history tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.order_status_history USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: outbox_event tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.outbox_event USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: payment_status_snapshot tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.payment_status_snapshot USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: product tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.product USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: reconciliation_issue tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.reconciliation_issue USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: refund tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.refund USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: return_line tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.return_line USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: return_request tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.return_request USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: sales_order tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.sales_order USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: shadow_diff tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.shadow_diff USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: shipment tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.shipment USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: sku tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.sku USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: sku_bundle_component tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.sku_bundle_component USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: stock_document tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.stock_document USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: stock_document_line tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.stock_document_line USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: stock_reservation tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.stock_reservation USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: sync_cursor tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.sync_cursor USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: tenant tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.tenant USING ((id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: tenant_membership tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.tenant_membership USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: warehouse tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation ON public.warehouse USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: tenant_membership; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.tenant_membership ENABLE ROW LEVEL SECURITY;

--
-- Name: warehouse; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.warehouse ENABLE ROW LEVEL SECURITY;

--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA public TO oms_app;
GRANT ALL ON SCHEMA public TO oms_migrator;
GRANT USAGE ON SCHEMA public TO oms_maint;


--
-- Name: FUNCTION audit_log_reject_mutation(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.audit_log_reject_mutation() FROM PUBLIC;


--
-- Name: FUNCTION claim_inbox_batch(n integer, p_lease interval); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.claim_inbox_batch(n integer, p_lease interval) FROM PUBLIC;
GRANT ALL ON FUNCTION public.claim_inbox_batch(n integer, p_lease interval) TO oms_app;


--
-- Name: FUNCTION claim_outbox_batch(n integer, p_lease interval); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.claim_outbox_batch(n integer, p_lease interval) FROM PUBLIC;
GRANT ALL ON FUNCTION public.claim_outbox_batch(n integer, p_lease interval) TO oms_app;


--
-- Name: FUNCTION inventory_ledger_reject_mutation(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.inventory_ledger_reject_mutation() FROM PUBLIC;


--
-- Name: FUNCTION list_active_tenant_ids(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.list_active_tenant_ids() FROM PUBLIC;
GRANT ALL ON FUNCTION public.list_active_tenant_ids() TO oms_app;


--
-- Name: FUNCTION list_tenants_for_order_backfill(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.list_tenants_for_order_backfill() FROM PUBLIC;
GRANT ALL ON FUNCTION public.list_tenants_for_order_backfill() TO oms_app;


--
-- Name: FUNCTION list_tenants_with_expired_reservations(p_now timestamp with time zone, p_limit integer); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.list_tenants_with_expired_reservations(p_now timestamp with time zone, p_limit integer) FROM PUBLIC;
GRANT ALL ON FUNCTION public.list_tenants_with_expired_reservations(p_now timestamp with time zone, p_limit integer) TO oms_app;


--
-- Name: FUNCTION lookup_login(p_tsf_shop_id text, p_tsf_user_id text); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.lookup_login(p_tsf_shop_id text, p_tsf_user_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION public.lookup_login(p_tsf_shop_id text, p_tsf_user_id text) TO oms_app;


--
-- Name: FUNCTION order_append_only_reject_mutation(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.order_append_only_reject_mutation() FROM PUBLIC;


--
-- Name: FUNCTION order_line_return_qty_check(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.order_line_return_qty_check() FROM PUBLIC;


--
-- Name: FUNCTION provision_membership(p_tenant_id uuid, p_user_id uuid, p_role text, p_ent_ver bigint); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.provision_membership(p_tenant_id uuid, p_user_id uuid, p_role text, p_ent_ver bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION public.provision_membership(p_tenant_id uuid, p_user_id uuid, p_role text, p_ent_ver bigint) TO oms_app;


--
-- Name: FUNCTION provision_tenant(p_tsf_shop_id text, p_name text, p_tier text, p_entitlement_status text, p_entitlement_expires_at timestamp with time zone, p_ent_ver bigint); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.provision_tenant(p_tsf_shop_id text, p_name text, p_tier text, p_entitlement_status text, p_entitlement_expires_at timestamp with time zone, p_ent_ver bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION public.provision_tenant(p_tsf_shop_id text, p_name text, p_tier text, p_entitlement_status text, p_entitlement_expires_at timestamp with time zone, p_ent_ver bigint) TO oms_app;


--
-- Name: FUNCTION resolve_reservation_tenant(p_reservation_group_id uuid); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.resolve_reservation_tenant(p_reservation_group_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION public.resolve_reservation_tenant(p_reservation_group_id uuid) TO oms_app;


--
-- Name: FUNCTION resolve_tenant(channel text, external_shop_id text); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.resolve_tenant(channel text, external_shop_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION public.resolve_tenant(channel text, external_shop_id text) TO oms_app;


--
-- Name: FUNCTION return_line_qty_check(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.return_line_qty_check() FROM PUBLIC;


--
-- Name: FUNCTION return_request_rejected_guard(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.return_request_rejected_guard() FROM PUBLIC;


--
-- Name: FUNCTION sku_bundle_component_check(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.sku_bundle_component_check() FROM PUBLIC;


--
-- Name: FUNCTION sku_is_bundle_change_check(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.sku_is_bundle_change_check() FROM PUBLIC;


--
-- Name: FUNCTION sku_require_stockable(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.sku_require_stockable() FROM PUBLIC;


--
-- Name: FUNCTION stock_document_guard(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.stock_document_guard() FROM PUBLIC;


--
-- Name: FUNCTION stock_document_line_guard(); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.stock_document_line_guard() FROM PUBLIC;


--
-- Name: FUNCTION upsert_app_user(p_tsf_user_id text, p_email text, p_display_name text); Type: ACL; Schema: public; Owner: -
--

REVOKE ALL ON FUNCTION public.upsert_app_user(p_tsf_user_id text, p_email text, p_display_name text) FROM PUBLIC;
GRANT ALL ON FUNCTION public.upsert_app_user(p_tsf_user_id text, p_email text, p_display_name text) TO oms_app;


--
-- Name: TABLE app_user; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.app_user TO oms_maint;


--
-- Name: TABLE audit_log; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT ON TABLE public.audit_log TO oms_app;
GRANT SELECT,INSERT ON TABLE public.audit_log TO oms_maint;


--
-- Name: TABLE channel_account; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.channel_account TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.channel_account TO oms_maint;


--
-- Name: TABLE channel_listing; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.channel_listing TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.channel_listing TO oms_maint;


--
-- Name: TABLE idempotency_key; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.idempotency_key TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.idempotency_key TO oms_maint;


--
-- Name: TABLE inbox_event; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.inbox_event TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.inbox_event TO oms_maint;


--
-- Name: TABLE inventory; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.inventory TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.inventory TO oms_maint;


--
-- Name: TABLE inventory_ledger; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT ON TABLE public.inventory_ledger TO oms_app;
GRANT SELECT,INSERT ON TABLE public.inventory_ledger TO oms_maint;


--
-- Name: TABLE order_hold_retry; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.order_hold_retry TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.order_hold_retry TO oms_maint;


--
-- Name: TABLE order_line; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.order_line TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.order_line TO oms_maint;


--
-- Name: TABLE order_recipient; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.order_recipient TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.order_recipient TO oms_maint;


--
-- Name: TABLE order_status_history; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT ON TABLE public.order_status_history TO oms_app;
GRANT SELECT,INSERT ON TABLE public.order_status_history TO oms_maint;


--
-- Name: TABLE outbox_event; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.outbox_event TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.outbox_event TO oms_maint;


--
-- Name: TABLE payment_status_snapshot; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT ON TABLE public.payment_status_snapshot TO oms_app;
GRANT SELECT,INSERT ON TABLE public.payment_status_snapshot TO oms_maint;


--
-- Name: TABLE product; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.product TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.product TO oms_maint;


--
-- Name: TABLE reconciliation_issue; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.reconciliation_issue TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.reconciliation_issue TO oms_maint;


--
-- Name: TABLE refund; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.refund TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.refund TO oms_maint;


--
-- Name: TABLE return_line; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.return_line TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.return_line TO oms_maint;


--
-- Name: TABLE return_request; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.return_request TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.return_request TO oms_maint;


--
-- Name: TABLE sales_order; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.sales_order TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.sales_order TO oms_maint;


--
-- Name: TABLE shadow_diff; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.shadow_diff TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.shadow_diff TO oms_maint;


--
-- Name: TABLE shipment; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.shipment TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.shipment TO oms_maint;


--
-- Name: TABLE sku; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.sku TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.sku TO oms_maint;


--
-- Name: TABLE sku_bundle_component; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.sku_bundle_component TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.sku_bundle_component TO oms_maint;


--
-- Name: TABLE stock_document; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.stock_document TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.stock_document TO oms_maint;


--
-- Name: TABLE stock_document_line; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.stock_document_line TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.stock_document_line TO oms_maint;


--
-- Name: TABLE stock_reservation; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.stock_reservation TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.stock_reservation TO oms_maint;


--
-- Name: TABLE sync_cursor; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.sync_cursor TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.sync_cursor TO oms_maint;


--
-- Name: TABLE tenant; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.tenant TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.tenant TO oms_maint;


--
-- Name: TABLE tenant_membership; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.tenant_membership TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.tenant_membership TO oms_maint;


--
-- Name: TABLE warehouse; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.warehouse TO oms_app;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.warehouse TO oms_maint;


--
-- PostgreSQL database dump complete
--

\unrestrict m7Zwlhr0g7ENX4EqLEGo362bkyShWQAQbmrrqD7bvI5BMZKVFgIQOE5VlLiSIqP

