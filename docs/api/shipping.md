# Ship and labels (T18b)

User JWT under `/api/v1/ship`. Labels come from mock TSF (`ChannelAdapter` + `supportsLabel`) only; carrier and tracking on TSF labels are whatever TSF returns.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/ship/candidates` | `PACKED` orders ready to print label / confirm ship |
| POST | `/api/v1/ship/labels` | Body `{ "order_ids": [uuid…] }` → merged A6 PDF (max 100). Idempotent per order: repeats TSF `createShipment` with the same idempotency key and reuses cached PDF when present. |
| POST | `/api/v1/ship/orders/{orderId}/manual-tracking` | Body `{ "carrier", "tracking_no" }` when TSF returns `LABEL_NOT_AVAILABLE` (422). Stores shipment + packing-slip PDF in cache. |
| POST | `/api/v1/ship/orders/{orderId}/confirm` | `PACKED` → `SHIPPED`, `ReservationEngine.consume`, `shipment.updated` outbox, `label_cached_until = shipped_at + 7 days`. Idempotent if already shipped. |

## Storage

- `shipment` (V7): one row per order; `label_cached_until` drives purge.
- `shipment_label_cache` (V18): transient PDF bytes; FORCE RLS; purged by `LabelCachePurgeJob` after `label_cached_until`.

## Mock TSF

- Orders whose external id starts with `NO-LBL-` get a shipment without label (`GET …/label` → 404 `LABEL_NOT_AVAILABLE`).
