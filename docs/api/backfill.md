# Order backfill and gap refetch

OMS pulls TSF orders on a schedule and refetches full snapshots when inbox `aggregate_version` gaps are detected.

## Scheduled backfill

- Config prefix: `oms.order.backfill`
- Default interval: **15 minutes** (`PT15M`)
- Disabled in tests via `oms.order.backfill.enabled=false` (call `OrderBackfillJob.runOnce()` directly)
- Tenants: `list_tenants_for_order_backfill()` (ACTIVE + unexpired GRACE with a connected TSF account)
- Per tenant: each non-`DISCONNECTED` TSF `channel_account`, one watermark per account in `sync_cursor` (`resource=ORDERS`)

### Cursor JSON (`sync_cursor.cursor`)

```json
{"updated_since":"2026-10-08T12:00:00Z","page_cursor":"Mg=="}
```

- `updated_since`: watermark for `GET /shops/{shopId}/orders?updated_since=` (minus a 2-minute overlap for clock skew)
- `page_cursor`: paging token while a run is in progress
- `last_success_at`: committed watermark time after a full successful run

HTTP list/get runs **outside** DB transactions. Each applied order runs in a new tenant transaction with `InboxAggregateLock.lockOrder`.

## Gap refetch

When `aggregate_version` skips ahead of the last PROCESSED version, `InboxWorker` does not apply the webhook delta. It refetches `GET /orders/{id}` (+ payment status) outside the inbox transaction, then applies the REST snapshot through the same intake handlers as webhooks.

## Cancellation via REST

`order.json` includes optional `status` (`ACTIVE` | `CANCELLED`). When present, backfill/gap apply issues `order.cancelled` through intake when OMS is not already cancelled. Missing `status` is treated as unknown (no cancel inference).

## Metrics

- `oms.order.backfill.fetched|applied|skipped|failed`
- `oms.order.backfill.lag_seconds` (gauge: watermark lag after a successful run)
- `oms.order.gap_refetch`

Logs include order ids only (no recipient PII).

## Local trigger

```bash
# mock-tsf: disable webhooks, seed orders, then invoke backfill (tests call OrderBackfillJob.runOnce())
curl -s -X POST localhost:8090/control/webhooks -H 'content-type: application/json' -d '{"enabled":false}'
curl -s -X POST localhost:8090/control/orders/bulk -H 'content-type: application/json' -d '{"count":5,"payment":"COD"}'
```
