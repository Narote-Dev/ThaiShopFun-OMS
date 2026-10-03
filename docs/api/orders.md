# Orders API (T17)

OMS user API for the Orders UI. User JWT (`aud=oms`), tenant from the token. Not part of the TSF contract (`contracts/` is unchanged). Paths are under `/api/v1/orders`. JSON is snake_case. Responses use `Cache-Control: no-store`.

## Access

- Reads (`GET`): any member (`OWNER`, `ADMIN`, `STAFF`), including shops in GRACE.
- `POST /orders/{id}/cancel-requests`: `OWNER` or `ADMIN` only. `STAFF` → `403 FORBIDDEN`. GRACE → `403 ENTITLEMENT_GRACE` (entitlement gate, before the controller).
- Another tenant's order id → `404 NOT_FOUND` (RLS), never `403`.

## PII

Recipient phone is always `***-***-` + `phone_last4` for every role. Name is first character + `***`. Province and postcode are clear. Address and full phone are never returned. `pii_status=REDACTED` → masked fields show `"redacted"`.

## List

`GET /orders` with cursor pagination: `limit` (default 50, max 200) and opaque `cursor` (omit on the first page). Stable sort: `ordered_at DESC`, `id DESC`.

| Query | Meaning |
|---|---|
| `order_status`, `payment_status`, `fulfillment_status` | Exact match on the dimension |
| `hold_reason` | Exact hold, `NONE`, or `ANY` (any hold) |
| `channel` | `channel_account.channel` (e.g. `TSF`) |
| `channel_account_id` | UUID |
| `ordered_from`, `ordered_to` | Bangkok calendar date (`YYYY-MM-DD`) or ISO instant; `from` inclusive, `to` date is inclusive whole day, `to` instant is exclusive |
| `q` | Phone (normalized hash), exact `tracking_no`, or `external_order_id` exact/prefix |

Response: `{items, total, limit, next_cursor}`. The opaque `cursor` carries a snapshot instant (database time) taken on the first page; only rows with `created_at` at or before that instant are included in `total` and in every page. Sort order remains `ordered_at DESC`, `id DESC`, so a late-ingested order with an older `ordered_at` does not appear on later pages or change `total`. `next_cursor` is `null` on the last page.

List items may include `phone_masked`.

## Detail

`GET /orders/{id}` — header (three statuses, hold, totals, dates, channel account, `version`, `supports_cancel_request`), lines (mapping state, bundle components), ORDER `stock_reservation` rows, shipments, masked recipient, and `timeline` from `order_status_history` (oldest first).

## Hold queue

`GET /orders/holds` — groups with `hold_reason`, optional `hold_detail` (`BUNDLE_WITHOUT_COMPONENTS` for componentless bundles on `OUT_OF_STOCK`), `count`, and up to five sample orders per group.

## Request cancel

`POST /orders/{id}/cancel-requests` body `{ "reason": "..." }`.

- `reason` is required: non-blank after trim, at most 500 characters. Missing, blank, or overlong → `400 VALIDATION_FAILED` with field `reason` (no channel call).
- Active order, fulfillment not `SHIPPED`/`DELIVERED`, channel supports cancel → `202` with TSF `cancel_request_id`; sets `hold_reason=CHANNEL_CANCEL_PENDING` via the state machine, one history row, one `ORDER_CANCEL_REQUESTED` audit row.
- Repeat while already `CHANNEL_CANCEL_PENDING` → `202`, idempotent (no extra history/audit).
- `422 CAPABILITY_UNSUPPORTED` when the adapter does not support cancel requests.
- `409 ORDER_NOT_CANCELLABLE` when shipped, cancelled, or not active.
- `409 CANCEL_REQUEST_CONFLICT` when the channel reports an idempotency conflict for the same cancel key with a different reason (order unchanged).
- Other channel errors → `502`/`503`/`429` per channel exception mapping; order unchanged.

## Errors

Same envelope as catalog (section 4.8): `error`, `message`, `trace_id`, plus `X-Trace-Id`.
