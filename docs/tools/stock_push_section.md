
## T15 / Flyway V15 — stock push state

This supplement extends the V13 schema snapshot above with V15. See
[stock push API and operational rules](../api/stock-push.md). All new state is
ids, quantities, versions and timestamps; no PII. `stock_push_pending` has ENABLE
and FORCE RLS, with tenant USING/WITH CHECK, owner `oms_migrator`, and DML grants
only to `oms_app` / `oms_maint`. Its primary key is `(tenant_id, listing_id)`.
Composite tenant foreign keys point to the listing (ON DELETE CASCADE) and account.
The partial dirty index covers `(tenant_id, channel_account_id, dirty_since)`.

| Table.column | Type / nullability | Meaning and writer/reader |
| --- | --- | --- |
| `stock_push_pending.tenant_id` | uuid NOT NULL | Tenant boundary, FK to tenant; capture/trigger and builder |
| `stock_push_pending.channel_account_id` | uuid NOT NULL | TSF account to debounce and claim |
| `stock_push_pending.listing_id` | uuid NOT NULL | Listing whose durable version survives clean windows/remapping |
| `stock_push_pending.dirty_since` | timestamptz NULL | First capture timestamp in a pending window; NULL means clean; listener/trigger set, builder clears |
| `stock_push_pending.changed_at` | timestamptz NULL | Earliest source ledger transaction timestamp (mapping: trigger timestamp) for propagation lag; cleared with dirty marker |
| `stock_push_pending.mapping_revision` | bigint NOT NULL DEFAULT 0, >= 0 | Incremented by relevant listing-change trigger, included in source key |
| `stock_push_pending.source_key` | text NULL | Last observed ordered stock/mapping/recipe signature; builder writes and compares |
| `stock_push_pending.observed_qty` | integer NULL | Last observed absolute exposed quantity, including SHADOW; builder writes |
| `stock_push_pending.stock_version` | bigint NOT NULL DEFAULT 0, >= 0 | Retained per-listing monotonic snapshot version; builder writes under account lock |
| `outbox_event.stock_changed_at` | timestamptz NULL | Oldest source change represented by a stock batch; builder writes, publisher reads for successful propagation histogram; other event types leave NULL |

`channel_listing.last_exposed_qty` and `last_pushed_version` are now written by
`StockPushService`, atomically with outbox append. They describe the latest
**enqueued** absolute snapshot, not a TSF acknowledgement. `shadow_diff.kind` now
also accepts `STOCK_PUSH`; its `ref` is the external listing SKU id,
`oms_value={available,stock_version}`, and `channel_value=NULL`.

`mark_listing_stock_push()` is an invoker trigger function with a pinned search
path, capturing all mapping writers without modifying the stock engine.
`list_stock_push_accounts(boolean)` is owned by `oms_maint`, executable only by
`oms_app`, with `search_path=pg_catalog,pg_temp`. It returns tenant/account UUIDs
only; all business reads and writes then run under transaction-scoped tenant RLS.
