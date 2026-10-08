# Invariants (T00)

`InvariantChecker` (`com.thaishopfun.oms.invariant`) is the single entry point for stock/order consistency checks. Integration and acceptance tests run `checkSchema()` plus `checkTenant` for each tenant registered during the test (`InvariantTestTenants`, via `StockFixture` / `OrderFixture`) after each case via `@VerifyInvariants` — equivalent to `checkAll()` without scanning unrelated tenants on the shared Testcontainers database. The nightly job (`InvariantJob`, cron `oms.invariant.cron`, default **02:30 Asia/Bangkok**) runs `checkSchema()` once, then `checkTenant(tenantId)` for every id from `list_active_tenant_ids()`.

## Nightly scope (MVP)

Same tenant list as the hold resolver and stock expiry **claim** path: **`list_active_tenant_ids()` returns `entitlement_status = 'ACTIVE'` only.** Shops in **GRACE** or **SUSPENDED** are skipped (documented gap; stock expiry uses a separate SECURITY DEFINER claim for expired holds on non-ACTIVE shops). This matches the PO decision for MVP.

Violations emit metric `oms.invariant.violations{code}` and an **ERROR** log with `code`, `tenant_id`, and `entity_ids` only (no PII). Alert routing is T26.

Local profile: `POST /control/demo/invariants-check` runs one job pass (after `POST /control/demo/order-catalog` or `orders-seed`).

## Stock

| Code | Rule | Why | Enforced by |
|------|------|-----|-------------|
| `stock.reserved_bounds` | `0 ≤ reserved ≤ on_hand` per inventory row | Prevents oversell and negative holds | DB CHECK + checker |
| `stock.active_reservation_mismatch` | Sum of `ACTIVE` reservation `qty` per `(sku_id, warehouse_id)` = `inventory.reserved` | Reservation engine and inventory stay aligned | Checker |
| `stock.ledger_mismatch` | Sum of `inventory_ledger` deltas = `on_hand` / `reserved` | Ledger is source of truth for audit | Checker |
| `stock.reservation_owner_split` | One `(owner_type, owner_ref)` per `reservation_group_id` | One logical hold, one owner | Checker |

### SQL (tenant-scoped)

```sql
-- reserved_bounds
SELECT id FROM inventory WHERE reserved < 0 OR reserved > on_hand;

-- active_reservation_mismatch
SELECT i.id
FROM inventory i
LEFT JOIN (
  SELECT sku_id, warehouse_id, sum(qty) AS qty
  FROM stock_reservation WHERE status = 'ACTIVE'
  GROUP BY sku_id, warehouse_id
) r USING (sku_id, warehouse_id)
WHERE coalesce(r.qty, 0) <> i.reserved;

-- ledger_mismatch
SELECT i.id
FROM inventory i
LEFT JOIN (
  SELECT sku_id, warehouse_id, sum(delta_on_hand) AS on_hand, sum(delta_reserved) AS reserved
  FROM inventory_ledger GROUP BY sku_id, warehouse_id
) l USING (sku_id, warehouse_id)
WHERE coalesce(l.on_hand, 0) <> i.on_hand OR coalesce(l.reserved, 0) <> i.reserved;
```

## Orders

| Code | Rule | Why | Enforced by |
|------|------|-----|-------------|
| `order.reservation_orphan` | Every `ACTIVE` `ORDER` reservation references an existing `sales_order` | No ghost holds | Checker |
| `order.cancelled_active_reservation` | `CANCELLED` orders have no `ACTIVE` `ORDER` reservations | Cancel releases stock | Checker + engine |
| `order.terminal_active_reservation` | `COMPLETED` orders have no `ACTIVE` `ORDER` reservations | Terminal orders release holds | Checker + engine |
| `order.ready_to_pick_hold` | `READY_TO_PICK` and `order_status = ACTIVE` ⇒ `hold_reason` in (`NONE`, `CHANNEL_CANCEL_PENDING`) | PO picking gate (cancelled orders may keep stale fulfillment) | Checker |
| `order.ready_to_pick_coverage` | `READY_TO_PICK` on `channel_account.mode = ACTIVE` with mapped lines has ORDER reservation coverage | Stock-enforced pick path | Checker (uses `OrderReservationCoverage`) |
| `order.status_history_missing` | Status transitions should append `ORDER` dimension history | Audit trail (best-effort) | State machine (checker deferred — intake can start `ACTIVE` without an `ORDER` row) |

ACTIVE `ORDER` reservations on non-terminal orders are allowed; terminal statuses must not retain ACTIVE reservations.

## Schema & platform

| Code | Rule | Why | Enforced by |
|------|------|-----|-------------|
| `schema.force_rls` | Every `public` table with `tenant_id` has RLS + **FORCE** RLS + ≥1 policy | Tenant isolation | Flyway + checker |
| `schema.oms_app_role` | `oms_app` is not superuser and not `BYPASSRLS` | Runtime must not bypass RLS | `RuntimeRoleGuard` + checker |

## Handlers & logs

- **Idempotency:** `InboxHandlerIdempotencyTest` delivers each registered TSF handler’s contract example twice (distinct `event_id`, same `aggregate_version`). Chaos handlers are not registered in production.
- **No PII in logs:** `PiiLogAssertions` (sentinel phone/email/name) in intake/API tests; T27 extends to suite-wide gate.

## Test integration

- `@VerifyInvariants` on `StockTestBase`, `OrderIntegrationTest` (all `OrderApi*Test`, `OrderHoldRecheckApiTest`, `ChannelListingApiTest`), checkout/order mock-acceptance classes (`OrderCancelApiTest` inherits via `OrderIntegrationTest`), `StockDocumentApiTest`, `StockTenantLeakTest`.
- Tenants are registered in `StockFixture.shop()`, `CatalogHttp.shop()`, and `OrderFixture.shopFor()`; `InvariantTestTenants` is a global set (safe under sequential Surefire) so `@Timeout(threadMode = SEPARATE_THREAD)` tests still get checked. `VerifyInvariantsExtension` clears before each test and drains after each test (including when the checker bean is absent or checks are skipped). See `InvariantTestTenantsSeparateThreadTest`.
- `@SkipInvariantCheck("reason")` for deliberate corruption (`InvariantCheckerBreakTest`, `InvariantJobTest`) and perf fixtures (`OrderApiPerfTest`).
- `StockFixture.assertInvariants(shop)` delegates to `InvariantChecker` when injected.

## Deliberate-break tests

`InvariantCheckerBreakTest` corrupts data under tenant RLS (`fixture.inTenant`) or via superuser (drop/re-add `inventory_quantity_check` for `stock.reserved_bounds`, probe table for `schema.force_rls`). **`schema.oms_app_role`** is not broken in tests (would require mutating the production role); it remains covered by `RuntimeRoleGuard` and Flyway.

Nightly job: per-tenant failures are logged with `tenant_id`, emit `oms.invariant.violations{code=check_failed}`, and do not stop other tenants (`InvariantJobIsolationTest`).
