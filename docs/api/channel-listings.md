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

Intake stubs (`ensureStub` for unknown `external_sku_id`) insert with `stock_control=false` until T40 explicitly allowlists the SKU. `stock_control` affects checkout enforcement and CONTROL oversell metrics only; intake and hold resolution still reserve all mapped lines when the account mode enforces stock.

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
