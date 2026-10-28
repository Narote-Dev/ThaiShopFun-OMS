# Pick lists and pack scan (T18a)

User JWT under `/api/v1/pick-lists` and `/api/v1/pack/orders`. Mock TSF only; label/ship is T18b.

## Access

- Reads: any member (including GRACE).
- Creates pick lists, pack scans, and pack complete: any member when entitlement is not GRACE (`403 ENTITLEMENT_GRACE`).

## Pick list candidates

`GET /pick-lists/candidates` — active orders with `fulfillment_status=READY_TO_PICK`, `hold_reason=NONE`, `payment_status` in `PAID`/`COD_PENDING`, not already on a pick list. Held orders never appear.

## Create pick list

`POST /pick-lists` body `{ "order_ids": ["…"] }` (unique, max 100).

- Validates every order (ready, no hold, active, paid/COD).
- Inserts `pick_list` at the tenant default warehouse and moves each order to `PICKING`.
- Audit: `PICK_LIST_CREATED` on entity `pick_list`.
- Response includes `pdf_url` for the printable pick list.

## Pick list detail and PDF

- `GET /pick-lists/{id}` — orders on the list and SKU lines aggregated for picking.
- `GET /pick-lists/{id}/pdf` — **A6** thermal PDF (`application/pdf`), IBM Plex Sans Thai, inline disposition.

## Pack session (full PII)

`GET /pack/orders/{orderId}` — only when `fulfillment_status=PICKING`.

- Returns full recipient name, phone, and address JSON (not masked).
- Audit: `ORDER_PACK_PII_VIEWED` once per user per order while the order stays in `PICKING` (repeat `GET` during the same pack session does not append another row). A new audit is written if the order enters `PICKING` again later.
- Order detail API remains masked for all roles.

## Pack scan

`POST /pack/orders/{orderId}/scans` body `{ "scan": "…" }`.

- Accepts **SKU code** or **barcode (EAN)** (trimmed, case-insensitive code match).
- Increments packed qty for the matching order line.
- `422 PACK_SCAN_UNKNOWN` — no SKU matches scan.
- `422 PACK_SCAN_MISMATCH` — SKU exists but not on this order.
- `409 PACK_SCAN_OVERFLOW` — scan would exceed line qty (order stays `PICKING`, not `PACKED`).
- `409 PACK_SCAN_AMBIGUOUS` — scan matches more than one SKU.

## Complete pack

`POST /pack/orders/{orderId}/complete` — when every mapped line has `packed_qty = required_qty`.

- Sets `fulfillment_status=PACKED`.
- Audit: `ORDER_PACKED`.
- Unmapped lines block completion (`409 PACK_INCOMPLETE`).

## Errors

Same envelope as orders API (`error`, `message`, `trace_id`).
