# T23: Stock drift (TSF)

The detector compares each connected TSF account's paginated `GET /shops/{shopId}/listings`
with `channel_listing.last_exposed_qty`. It records the returned quantity in
`last_seen_channel_qty`. It never writes inventory quantities or the inventory ledger.

## Existing schema decision

No migration is required or added. V7's `reconciliation_issue_open_key` uses
`NULLS NOT DISTINCT` on `(tenant_id, rule, order_id)` for non-resolved issues. Therefore
there is **one unresolved `STOCK_DRIFT` issue per tenant**, with `order_id = NULL`.
Its details aggregate the listings:

```json
{
  "run_id": "019a29e5-7000-7000-8000-000000000002",
  "listings": [{
    "listing_id": "019a29e5-7000-7000-8000-000000000001",
    "channel_account_id": "019a29e5-7000-7000-8000-000000000003",
    "external_listing_id": "tsf_sku_7781",
    "sku_ids": ["019a29e5-7000-7000-8000-000000000004"],
    "expected": 10,
    "seen": 3,
    "first_seen_at": "2026-10-28T08:00:00Z",
    "last_seen_at": "2026-10-28T08:05:00Z",
    "repush_enqueued_at": "2026-10-28T08:05:00Z"
  }]
}
```

`sku_ids` includes the mapped SKU and any bundle component SKUs. Details and audit contain
identifiers, quantities and timestamps only; no recipient, seller name or other PII.

The read API enriches each listing with `sku_codes`, resolving `sku_ids` against the
current tenant's `sku.code` values in the same order (mapped SKU, then components).
This also works for existing issue rows without rewriting details or migrating data.
Unavailable SKU references are omitted from `sku_codes`; the UI shows an em dash when
no code can be resolved and never falls back to internal UUIDs.

Each complete scan recomputes the aggregate, preserving `first_seen_at` for continuing
drift. `OPEN` and `ACK` issues retain their status while drift remains. No duplicate
unresolved row is created. An empty aggregate resolves the issue. A later recurrence
creates a new OPEN issue; resolved history remains available (its listings array is empty).

A future migration could add a nullable `listing_id` foreign key and split the unique
index into order/shop and per-listing rules. That would permit independent ACK/resolution
and indexed per-listing history. This implementation deliberately retains the V16 schema.

## Control and push safety

Only mapped, non-removed listings in unpaused connected accounts are eligible:
`ACTIVE` controls all mapped listings; `CONTROL` requires `stock_control = true`.
`OBSERVE` and `SHADOW` never initiate drift repair. Disconnected accounts are not fetched.

A dirty T15 marker or a matching `stock.updated` outbox item in `PENDING`/`IN_FLIGHT`
skips comparison. Events created or sent after the scan began also skip comparison,
preventing a response fetched before delivery from becoming a false positive. Outbox
checks are per listing, so an unrelated pending listing does not block an account.
An unobserved listing (missing from the channel response), or a pending repair, preserves
its previous issue entry until a fresh observation verifies it. It cannot falsely resolve
an existing issue. Listings that cease to be controlled leave the aggregate.

Drifting listings are handed once per run to `StockPushService.repush`. This uses the
existing T15 availability calculation, version floor/increment, batching, and outbox
appender. Resetting the retained source signature forces a new version even when the
OMS quantity is unchanged. Pending delivery prevents repeated enqueues on subsequent
runs. After delivery, a still-drifting listing may enqueue once again on the next run.
The manual trigger builds the outbox repair immediately, even if the scheduled T15 job
is disabled; delivery still uses the existing outbox worker.

All channel network calls occur outside database transactions. A failed page or repeated
cursor aborts the scan before any observations, issues or repairs are written. Tenant
context is set before transactions and restored afterward. The existing narrow
`list_stock_push_accounts(true)` function discovers tenant IDs for scheduling;
normal queries use the `oms_app` role and FORCE RLS.

The write transaction takes a tenant advisory lock (including when no issue exists),
locks the unresolved issue, then accounts and listings in T15 lock order. Overlapping
scans whose observations predate the current issue update are discarded as superseded.
Observation changes, aggregate updates, repair outbox events and audit commit or roll
back together. A dedicated scheduler keeps channel fetches off the T15 push scheduler.

## Configuration and API

- `oms.stock.drift.enabled` / `OMS_STOCK_DRIFT_ENABLED`: default `true`;
  `application-test.yml` sets it to `false`. Manual triggers remain available.
- `oms.stock.drift.interval-ms` / `OMS_STOCK_DRIFT_INTERVAL_MS`: default `300000`;
  used for initial delay and delay between scans.
- `GET /api/v1/stock-drift?status=OPEN|ACK|RESOLVED`: authenticated tenant members;
  omit status for all statuses. Returns `{ "items": [...] }`, newest first, up to
  100 aggregate issues, including status, details and creation/update timestamps.
- `POST /api/v1/stock-drift/runs`: OWNER/ADMIN with write entitlement. Returns
  `{ "run_id": "...", "drifting": 2, "repush_events": 1, "superseded": false }`.
  `drifting` counts aggregate listing entries, including retained pending repairs;
  `repush_events` counts T15 batch events, not individual listings. Superseded scans
  return zero counts and make no mutations.
- `POST /control/demo/stock-drift/runs`: local/e2e profiles only, loopback only;
  scans the `shop_active` demo tenant without a user session.

The frontend route `#/channel/stock-drift` shows one row per listing from the aggregate
and translates OPEN/ACK/RESOLVED through `ui/labels.ts`. It uses the existing AppShell
(full-height sidebar), IBM Plex Sans Thai and coral theme. Readers can filter issues;
only writers see the run action. This task does not add ACK editing controls.

REST response schemas and examples are in `contracts/schemas/rest/stock-drift-*.json`
and `contracts/examples/rest/stock-drift-*.json`.

## Mock simulation

`POST /control/listings/{listingSkuId}/quantity` accepts `{ "available": 3 }`, returns
the listing, and changes its TSF-visible quantity without changing its stock version.
Unknown IDs return 404; malformed/negative/out-of-range quantities fail validation.
Request and response schemas/examples live under `contracts/`. This is a harness endpoint,
not a production TSF API. Listings in the mock catalog are shared by the demo shops,
consistent with its existing listing endpoint.

For example, establish a successful initial stock push, force a different TSF quantity,
then invoke the demo scan. The outbox contains the versioned repair and the issue appears
on the page. After HMAC/schema validation, the mock receiver records OMS events and
applies each new `stock.updated` item's `available` quantity to the catalog when its
`stock_version` is strictly newer than the listing's current version. Equal/older
versions and duplicate event IDs leave quantity unchanged; unknown listing IDs are
recorded but do not create listings. Ordering is per listing, including hidden listings,
and uses the existing shared demo catalog. Let repair delivery finish and scan again:
the TSF-visible quantity now matches OMS and the issue resolves automatically.

## Verification

`StockDriftAcceptanceTest` uses real Postgres/FORCE RLS and the actual T15 builder to
cover multiple listing aggregation, repair enqueue, unchanged on_hand/ledger, deduplication,
ACK preservation, resolution, allowlist/mode/pause gating, pending/sent races, pagination
failure, tenant isolation, overlapping runs and transaction rollback. It requires Docker
through the existing `StockTestBase`; no existing tests or migrations are changed.
Unit tests exercise pagination guards, transaction/context boundaries, the admin gate,
disabled scheduling, and HTTP authorization/error contracts. Mock tests validate the quantity control and schema examples;
frontend tests cover rows, Thai badges, filters, admin runs, error and retry behavior.
