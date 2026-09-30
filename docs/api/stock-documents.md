# Stock documents and stock history API (T08A)

OMS user API for the frontend, same conventions as [catalog.md](catalog.md): user JWT (`aud=oms`), tenant from the token, paths under `/api/v1`, snake_case JSON, `Cache-Control: no-store`, section 4.8 error body. Not part of the TSF contract (`contracts/` is unchanged).

Every change to `on_hand` that is not a reservation comes from a stock document. Posting and voiding run in the stock engine layer (`com.thaishopfun.oms.stock.StockMovements`), the same layer as reservations, so the lock order, conditional UPDATEs, ledger writes, and `StockChanged` (including bundles that use a changed component) are shared.

## Access

| Action | Who |
|---|---|
| Read documents and history | any member |
| Create, edit, delete drafts; add, edit, delete lines; start a count | any member (including `STAFF`) |
| Post `RECEIVE` | any member |
| Post `OPENING`, `ADJUSTMENT`, `COUNT`, `WRITE_OFF` | `OWNER` or `ADMIN` (`403 FORBIDDEN`) |
| Void any document | `OWNER` or `ADMIN` (`403 FORBIDDEN`) |

The plan requires OWNER/ADMIN for adjustments. Opening balance, counts, and write-offs also change stock without a supplier trail, so they follow the same rule. A shop in GRACE is read-only: every write is `403 ENTITLEMENT_GRACE` from the entitlement gate. Another tenant's document or SKU is `404 NOT_FOUND`.

## Documents

| Method and path | Body | Result |
|---|---|---|
| `GET /stock-documents?type=&status=&from=&to=&limit=&offset=` | | `{items, total, limit, offset}`, newest first. `from`/`to` filter `created_at` |
| `POST /stock-documents` | `{type, reference_no?, note?}` | `201` DRAFT document |
| `GET /stock-documents/{id}` | | document with `lines` |
| `PUT /stock-documents/{id}` | `{reference_no?, note?}` | document (DRAFT only; `type` is fixed) |
| `DELETE /stock-documents/{id}` | | `204`, DRAFT only, lines included |
| `POST /stock-documents/{id}/lines` | `{sku_id \| sku_code, warehouse_id?, qty?, counted_qty?, reason_code?}` | `201` line |
| `PUT /stock-documents/{id}/lines/{line_id}` | same | line (full replace) |
| `DELETE /stock-documents/{id}/lines/{line_id}` | | `204` |
| `POST /stock-documents/{id}/start-count` | | document. COUNT only |
| `POST /stock-documents/{id}/post` | | `200` movement result |
| `POST /stock-documents/{id}/void` | | `200` movement result |

`type`: `OPENING`, `RECEIVE`, `ADJUSTMENT`, `COUNT`, `WRITE_OFF`. `status`: `DRAFT`, `POSTED`, `VOID`.

A document has `id, type, status, reference_no, note, count_started_at, posted_at, posted_by, line_count, warehouse_ids, created_at, updated_at` and, on single reads, `lines`. V4 keeps the warehouse on each line (`stock_document` has no warehouse column), so a line without `warehouse_id` uses the default warehouse, and `warehouse_ids` lists what the lines use. No migration was added.

A line has `id, sku_id, sku_code, sku_name, warehouse_id, warehouse_code, qty, system_qty_at_start, counted_qty, reason_code, on_hand, reserved, created_at`. `on_hand`/`reserved` are the current values of its inventory row (`null` before the row exists).

Drafts may be incomplete. Line writes check only the shape: `qty` within ±1,000,000, `counted_qty` only on COUNT (0–1,000,000), `reason_code` only on ADJUSTMENT/WRITE_OFF and one of `DAMAGED, LOST, FOUND, DATA_ENTRY, OTHER`, no bundle SKU (`422 BUNDLE_NOT_STOCKABLE`), a SKU at most once per warehouse on a COUNT (`422 DUPLICATE_LINE`), at most 500 lines. A line write locks the document `FOR SHARE`, the same lock the V4 line trigger takes, so it either completes before a post or sees `409 DOCUMENT_NOT_DRAFT`.

## Post rules per type

| Type | Line rule at post | Delta | Ledger reason |
|---|---|---|---|
| `OPENING` | `qty ≥ 0`; the inventory row must have no ledger entry yet (`422 OPENING_ALREADY_SET`) | `+qty` | `OPENING_BALANCE` (also written for 0, so the row counts as opened) |
| `RECEIVE` | `qty > 0` | `+qty` | `RECEIVE` |
| `ADJUSTMENT` | `qty ≠ 0`; `reason_code` required (`422 REASON_REQUIRED`); `OTHER` needs the document note (`422 NOTE_REQUIRED`) | `qty` | `ADJUST_IN` (qty > 0) / `ADJUST_OUT` (qty < 0) |
| `WRITE_OFF` | `qty > 0` | `−qty` | `DAMAGE_WRITE_OFF` |
| `COUNT` | count started (`422 COUNT_NOT_STARTED`); `counted_qty` set (`422 COUNTED_QTY_REQUIRED`) | `counted_qty − system_qty_at_start` | `COUNT_CORRECTION` (none for 0) |

- `start-count` sets `count_started_at` and snapshots `system_qty_at_start` = current `on_hand` (0 without a row) on every line; lines added later snapshot at insert. The correction is applied to the **current** `on_hand`, so units shipped after the start (T08 `consume`) are not taken twice: final `on_hand` = counted − shipped since the start. Reserved-but-not-shipped units are still on the shelf and counted, so `reserved` is untouched. The post writes the applied correction into each COUNT line's `qty`.
- Several lines for the same SKU and warehouse are summed into one delta for that inventory row; the ledger keeps one entry per line (`ref_type = stock_document_line`, `ref_id` = line id).
- A row whose delta would take `on_hand` below `reserved` (or 0) is `422 BELOW_RESERVED`. The check runs under the row lock and the UPDATE is conditional (`on_hand + delta >= reserved`); the `CHECK` constraint is the last line of defence.
- All lines post or none do. Every refused line is listed in `errors`.
- Missing `(sku, warehouse)` inventory rows are created first in their own short transaction (`on_hand = reserved = 0`).

The post is `DRAFT → POSTED` with `posted_at` (app clock) and `posted_by` (user id), in the same transaction as every inventory update (`stock_version` bumped), ledger row, and the audit row. `StockChanged` is published after commit.

## Void

`POSTED → VOID` writes one reversing ledger entry per entry the post wrote (same reason, negated delta, same `ref_type`/`ref_id`) and reverses `on_hand`. It is refused with `422 BELOW_RESERVED` when a reversal would take a row below `reserved` or 0. The plan calls for "VOID + a reversing document"; V4 does not allow a document to be created past DRAFT, so the reversal lives on the voided document's own lines instead of a second document. Voiding a DRAFT or VOID document is `409 DOCUMENT_NOT_POSTED`.

## Idempotency

Post and void use `idempotency_key` with scope `stock.document.post` / `stock.document.void` and key = document id. A second call (double click, concurrent, or later) waits on the key, then returns the stored result with `200` and the same body, and writes nothing. Posting a VOID document is `409 DOCUMENT_NOT_DRAFT`. A refused post (any `422`) rolls back, key included, so the draft can be fixed and posted again.

Movement result: `{document_id, type, status, posted_at, posted_by, voided_at, movements: [{ledger_id, line_id, sku_id, warehouse_id, reason, delta_on_hand}]}`.

## Errors

| Status | `error` | When |
|---|---|---|
| 403 | `FORBIDDEN` | Post of a non-RECEIVE type or any void without OWNER/ADMIN |
| 404 | `NOT_FOUND` | Unknown or other-tenant document, line, or history SKU; malformed id in the path |
| 409 | `DOCUMENT_NOT_DRAFT` | Edit, delete, line change, or start-count of a POSTED/VOID document; post of a VOID document |
| 409 | `DOCUMENT_NOT_POSTED` | Void of a DRAFT or VOID document |
| 409 | `COUNT_ALREADY_STARTED` | Second `start-count` |
| 422 | `BELOW_RESERVED` | A row would drop below `reserved` or 0; `errors[]` has `sku_id, warehouse_id, on_hand, reserved, delta` and `line_id` when one line caused it |
| 422 | `REASON_REQUIRED`, `NOTE_REQUIRED`, `INVALID_LINE`, `COUNT_NOT_STARTED`, `COUNTED_QTY_REQUIRED`, `DUPLICATE_LINE`, `OPENING_ALREADY_SET`, `DOCUMENT_EMPTY` | Post rules above; `errors[]` per line |
| 422 | `UNKNOWN_SKU`, `UNKNOWN_WAREHOUSE`, `BUNDLE_NOT_STOCKABLE`, `VALIDATION_FAILED` | Draft input |
| 503 | `STOCK_BUSY` | Lock timeout or retries exhausted; `Retry-After: 1` |

## Stock history

`GET /skus/{id}/stock-history?warehouse_id=&reason=&from=&to=&cursor=&limit=`

`{sku: {id, sku_code, name}, items, next_cursor}`. Ledger rows newest first, keyset-paged on `(created_at, id)`; pass `next_cursor` back as `cursor` (`null` on the last page). `limit` default 50, max 200. `from`/`to` accept an ISO instant or a date (`YYYY-MM-DD`, a Bangkok calendar day; a `to` date includes that whole day). A bundle SKU is `422 BUNDLE_NOT_STOCKABLE`: bundles have no stock of their own, open a component's history instead.

An entry has `id, created_at, warehouse_id, warehouse_code, reason, delta_on_hand, delta_reserved, on_hand_after, reserved_after, actor, ref_type, ref_id, link`. `on_hand_after`/`reserved_after` are running totals of that (sku, warehouse) row right after the entry, computed by a window over the SKU's whole ledger, so they stay right under any filter. That reads every ledger row of the SKU per request, which is fine at shop volumes.

`link` by `ref_type`:

| `ref_type` | `link` |
|---|---|
| `stock_document_line` | `{kind: "stock_document", document_id, document_type, document_status, reference_no}` |
| `stock_reservation` | `{kind: "reservation", reservation_group_id, owner_type, order_ref}` (`order_ref` only for an ORDER owner) |
| `return_line` | `{kind: "return_line", return_line_id}` (id only until T13) |

## Return restock hook (T13)

`StockMovements.restockReturn(returnLineId, skuId, warehouseId, qty, idempotencyKey)` (Java, no HTTP): `on_hand += qty`, ledger `RETURN_RESTOCK`, `ref_type = return_line`, `ref_id = returnLineId` (opaque, no foreign key). Standalone it owns its transaction; inside T13's READ COMMITTED transaction it joins and is that transaction's one engine write (a second engine write is refused). A missing inventory row is created in the same transaction: it is one row, so it cannot invert the id order.
