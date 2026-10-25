# Channel listings API (T12B)

User JWT (`aud=oms`). Paths under `/api/v1`. JSON snake_case. `Cache-Control: no-store`.

## Access

- Reads: any member.
- Writes (mapping, sync): `OWNER` or `ADMIN`.

## Listings

`GET /channel-listings?channel_account_id=&mapped=&removed=&q=&limit=&offset=`

`channel_account_id` is **required**. Missing, blank, or invalid → `422 VALIDATION_FAILED` with `errors[].field = channel_account_id` and `trace_id`. Unknown account id for the caller’s tenant → empty page (`200`, `items: []`).

`removed=true` returns only listings with `removed_at` set (TSF removed).

Returns `{items, total, limit, offset}`. Each item includes mapping fields (`seller_sku`, `sku_id`, `mapping_source`, `mapped_at`), `removed_at` when the channel marked the listing deleted, `stock_control` (CONTROL allowlist), and `held_orders` (count of active orders on hold for that listing). Removed rows remain visible in list/count (UI shows a removed badge).

Intake stubs (`ensureStub` for unknown `external_sku_id`) insert with `stock_control=false` until explicitly allowlisted. `stock_control` affects checkout enforcement, CONTROL stock pushes and CONTROL oversell metrics; intake and hold resolution still reserve all mapped lines when the account mode enforces stock.

`GET /channel-listings/{id}` — single listing.

`PUT /channel-listings/{id}/mapping` body `{ "sku_id": "..." }` — manual map; audit `CHANNEL_LISTING_MAPPED`; triggers hold re-evaluation for related orders (cap 200 per change).

`DELETE /channel-listings/{id}/mapping` — unmap; audit `CHANNEL_LISTING_UNMAPPED`.

## Sync

`POST /channel-accounts/{id}/listing-syncs` — pulls listings from the channel adapter (outside DB transaction), upserts rows, auto-maps when `seller_sku` matches `sku_code` (counts both new stubs and existing unmapped rows that become mapped on this upsert), then re-evaluates holds for SKUs whose mapping changed.

Response fields: `fetched`, `created`, `updated`, `auto_mapped`, `revived`, `removed`, `removal_skipped`, `reevaluated_orders`, `deferred`.

`reevaluated_orders` is the sum of released, out-of-stock, and still-held outcomes across all re-evaluations in the sync. At most **200 orders** are re-evaluated per sync (shared cap). `deferred` is the sum of per-SKU `deferred` counts from re-evaluation plus the **held-order count** of SKUs skipped because the shared cap was exhausted (documented here).

Full sync may mark channel listings absent from the TSF feed as removed (`removed`, `removed_at` set, mapping kept) unless `removal_skipped` is true (guard: default max 50% of active listings when at least 10 active). Empty TSF feed removes nothing.

- `422 DISCONNECTED` when the account is disconnected.
- `422 CAPABILITY_UNSUPPORTED` when the adapter does not support listing pull.

## Inbox

`listing.changed` with `UPSERT` or `DELETE` updates `channel_listing` only (no stock engine).

## CONTROL allowlist (T40A)

`PUT /channel-listings/{id}/stock-control` with `{ "enabled": true }` changes one listing.
`POST /channel-listings/stock-control` with
`{ "channel_account_id": "uuid", "listing_ids": ["uuid"], "enabled": true }`
changes 1–500 listings atomically. Response: `{ "changed": 3 }`. Duplicate IDs
are counted once. Both endpoints require OWNER/ADMIN; STAFF gets 403 and GRACE
gets `403 ENTITLEMENT_GRACE`. Cross-tenant IDs and IDs outside the bulk account
return 404. Missing enabled/IDs, empty batches and batches over 500 return 422.

Enabling requires a mapped, non-removed listing, otherwise
`422 LISTING_NOT_CONTROLLABLE`. Disabling is allowed even after unmapping/removal.
Unmapping or removing a listing **does not clear stock_control**; the flag is
ineffective while unmapped or removed. Reviving/remapping restores its effect.
The UI edits this allowlist only in CONTROL; API writes may prepare it in other
modes without changing the account mode.

Every changed listing writes one `CHANNEL_LISTING_STOCK_CONTROL_CHANGED` audit
row with before/after `{ "stock_control": false|true }`, listing ID and actor ID;
no PII. No-ops write no audit row. Flags, audit rows and T15 dirty markers commit
in the same RLS-scoped transaction. A failed bulk request rolls everything back.
Checkout reads the flag immediately. The stock worker processes dirty markers
using its normal debounce and CONTROL eligibility checks.

**PO decision: disabling stops pushing that listing in CONTROL. No zero-out push
is sent.** Existing stock at the channel is left as-is. Already queued/in-flight
messages are not recalled. ACTIVE behavior continues to follow its mode rules.

`GET /channel-listings` additionally accepts `stock_control=true|false` and returns
`mode` and `allowlist_coverage_pct`. Coverage is 100 × allowlisted mapped active
listings / mapped active listings across the account, excluding removed rows,
independent of pagination/search/filters; no mapped rows gives 0. An unknown
account still returns an empty page, with null mode and zero coverage.

### Local/e2e demonstration fixture

Only with the `local` or `e2e` Spring profile, on loopback, after provisioning
`owner-active` and `POST http://127.0.0.1:8080/control/demo/order-catalog`:

```sh
curl -fsS -X POST http://127.0.0.1:8080/control/demo/stock-control-mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"CONTROL","reset_allowlist":true}'
```

This fixed demo-shop fixture returns `channel_account_id`, switches only that
account, and optionally resets `L-demo-*` flags for a repeatable demonstration.
The reset is fixture setup, not an audited production operation. It accepts only
CONTROL/ACTIVE, is absent in production/test profiles and rejects non-loopback
requests. Do not expose the local profile publicly.

Open Listings, choose all mappings, select `L-demo-ready` and `L-demo-cod`, then
“เปิดคุมสต็อก” and confirm: two audit rows and updated coverage. With fresh checkout
IDs, send each listing separately through the mock:

```sh
curl -fsS -X POST http://127.0.0.1:8090/control/checkout/reservations \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: t40a-ready-1' \
  -d '{"checkout_id":"t40a-ready-1","tsf_shop_id":"shop_active","items":[{"listing_sku_id":"L-demo-ready","qty":1}]}'
```

Repeat for `L-demo-cod` and `L-demo-oos`, using fresh matching idempotency/checkout
IDs: only ready/cod are enforced. Restore with the same mode fixture body
`{"mode":"ACTIVE"}`. Mode changes here are solely local fixture controls; general
mode switching, emergency controls and promotion automation remain T40/T41.

Capture the four allowlist views with
`node frontend/scripts/screenshot-pages.mjs --stock-control docs/ui/screenshots`.
The stack must be running locally; screenshots are not fabricated when it is absent.
