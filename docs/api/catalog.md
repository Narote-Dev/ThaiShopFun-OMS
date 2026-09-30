# Catalog and warehouse API (T07)

OMS user API for the frontend. User JWT (`aud=oms`), tenant from the token. Not part of the TSF contract (`contracts/` is unchanged). All paths are under `/api/v1`. JSON is snake_case. Every response is `Cache-Control: no-store`.

## Access

- Reads (`GET`): any member (`OWNER`, `ADMIN`, `STAFF`).
- Writes: `OWNER` or `ADMIN`. `STAFF` gets `403 FORBIDDEN`.
- A shop in GRACE is read-only: every write gets `403 ENTITLEMENT_GRACE` from the existing entitlement gate, before the controller.
- Another tenant's id is `404 NOT_FOUND` (row-level security hides it), never `403`.

## Errors

Section 4.8 body: `{"error": "...", "message": "...", "trace_id": "..."}`, plus `X-Trace-Id`. The import adds `errors`.

| Status | `error` | When |
|---|---|---|
| 404 | `NOT_FOUND` | Unknown id, another tenant's id, or a malformed id in the path |
| 409 | `SKU_CODE_EXISTS` | `sku_code` already used in this shop (unique key `sku_tenant_sku_code_key`, also under a concurrent create) |
| 409 | `WAREHOUSE_CODE_EXISTS` | Warehouse `code` already used in this shop |
| 409 | `SKU_IN_USE` | Delete of a SKU that is a bundle component, or has stock, listing, or document rows |
| 409 | `PRODUCT_IN_USE` | Delete of a product that has SKUs (archive it instead) |
| 409 | `WAREHOUSE_IN_USE` | Delete of a warehouse with stock or document rows |
| 409 | `WAREHOUSE_IS_DEFAULT` | Delete of the default warehouse |
| 422 | `NESTED_BUNDLE` | A bundle as a component (including itself), or `is_bundle` on a SKU that is a component |
| 422 | `BUNDLE_REQUIRED` | Components on a non-bundle, or clearing `is_bundle` while components exist |
| 422 | `BUNDLE_NOT_STOCKABLE` | `is_bundle` on a SKU that has inventory or stock document rows |
| 422 | `VALIDATION_FAILED` | Bad or missing field, unreadable JSON |
| 422 | `IMPORT_INVALID` | CSV import rejected; see below |

The bundle rules are checked in Java first for a clear message. The V4 triggers are the last line of defence and are mapped by SQLSTATE `23514` and constraint name (`sku_bundle_nesting`, `sku_bundle_parent`, `sku_bundle_stock`), never by message text. `READ_COMMITTED_REQUIRED` (`sku_bundle_isolation`) would be a server bug and is a `500`.

## Products

| Method and path | Body | Result |
|---|---|---|
| `GET /products?q=&status=&limit=&offset=` | | `{items, total, limit, offset}`. `q` is a case-insensitive name substring. Sorted by name, id |
| `POST /products` | `{name, status?}` (`ACTIVE` default) | `201` product |
| `GET /products/{id}` | | product (`id, name, status, sku_count, created_at, updated_at`) |
| `PUT /products/{id}` | `{name, status?}` | product |
| `POST /products/{id}/archive` | | product with `status = INACTIVE` |
| `DELETE /products/{id}` | | `204`, only without SKUs (`409 PRODUCT_IN_USE`) |

## SKUs

| Method and path | Body | Result |
|---|---|---|
| `GET /skus?q=&product_id=&limit=&offset=` | | Page of SKUs. `q` matches a `sku_code` prefix, an exact `barcode`, or a `name` substring, all case-insensitive. Sorted by `sku_code`, id. `limit` default 50, max 200 |
| `POST /skus` | `{product_id \| product_name, sku_code, name, barcode?, weight_g?, is_bundle?}` | `201` SKU. `product_name` creates the product in the same transaction |
| `GET /skus/{id}` | | SKU with `components` |
| `PUT /skus/{id}` | `{product_id, sku_code, name, barcode?, weight_g?, is_bundle}` | SKU (full replace) |
| `DELETE /skus/{id}` | | `204`. A bundle's own component list is removed with it; any other reference is `409 SKU_IN_USE` |
| `PUT /skus/{id}/components` | `[{component_sku_id \| component_sku_code, qty}]` | SKU. Replaces the whole list in one transaction. `[]` clears it |

A SKU has `id, product_id, product_name, sku_code, name, barcode, weight_g, is_bundle, on_hand, reserved, component_count, created_at, updated_at`. `on_hand` and `reserved` are read-only sums over all warehouses from `inventory` (`null` for a bundle). T07 never writes `inventory`, `stock_reservation`, or `inventory_ledger`.

`sku_code`: 1–64 characters, no spaces, no `:` or `|` (they delimit import components). Names: at most 200 characters. `weight_g`: 0–1,000,000. Component `qty`: 1–10,000, at most 50 components.

## Warehouses

| Method and path | Body | Result |
|---|---|---|
| `GET /warehouses` | | `{items}`, default first. The first call by a writer (OWNER/ADMIN, not GRACE) creates `MAIN` as the default. For STAFF or a GRACE shop it only reads and may be empty |
| `POST /warehouses` | `{code, name, address?}` (`address` is a JSON object) | `201` warehouse, not default |
| `GET /warehouses/{id}` | | warehouse (`id, code, name, address, is_default, created_at, updated_at`) |
| `PUT /warehouses/{id}` | `{code, name, address?}` | warehouse |
| `POST /warehouses/{id}/default` | | warehouse. Unsets the old default and sets this one in one transaction |
| `DELETE /warehouses/{id}` | | `204`. Not the default (`409 WAREHOUSE_IS_DEFAULT`), not referenced (`409 WAREHOUSE_IN_USE`) |

The default warehouse is created idempotently (insert `ON CONFLICT DO NOTHING` against `warehouse_one_default_per_tenant_idx`), so concurrent first calls create exactly one. It is created from the warehouse list (writers only), warehouse create, and a successful CSV import, not from JIT provisioning. A GET by STAFF or in GRACE never writes a warehouse or an audit row.

## CSV import

`POST /catalog/import`, `multipart/form-data`, field `file`. UTF-8 (a BOM is fine), RFC 4180 quoting, a header row, at most 20,000 rows and 5 MB.

Columns (any order; the first three are required): `product_name, sku_code, sku_name, barcode, weight_g, is_bundle, components`. `is_bundle` is `true/false`, `1/0`, `yes/no`, or empty (false). `components` is `CODE:qty|CODE:qty` and may name SKUs earlier or later in the same file, or existing SKUs.

Absent column versus empty cell, for the optional columns (`barcode`, `weight_g`, `is_bundle`, `components`):

- **Column absent from the header:** an existing SKU keeps its stored value (barcode, weight, bundle flag, component list). A file with only `product_name, sku_code, sku_name` changes only SKU names and product assignment. A new SKU gets the defaults: no barcode, no weight, not a bundle, no components.
- **Column present, cell empty:** the value is cleared (no barcode, no weight, `is_bundle` false, no components).
- Without an `is_bundle` column, `components` uses the stored flag (false for a new SKU), so components on a non-bundle are still `BUNDLE_REQUIRED`. Clearing `is_bundle` while the `components` column is absent is rejected, because the stored list would be kept; add an empty `components` column to clear it.

Products are matched by `product_name`, exactly as written:

- An existing SKU stays on its product while the file's `product_name` equals that product's name.
- A different `product_name` moves the SKU to the product with that name (the oldest if several share it), creating the product when none exists. The old product is not renamed or removed.
- Renaming a product through the import is not supported yet. Rename it with `PUT /products/{id}` (or the Products page), then import with the new name.

- Every row is validated before anything is written: first each row on its own, then against the database under row locks. If any row fails, the answer is `422 IMPORT_INVALID` with `errors: [{row, column, error}]` for every bad cell, and nothing is written (not even the default warehouse or an audit row). `row` is the 1-based file line where the record starts (the header is line 1). A duplicate `sku_code` in the file is an error on the later row.
- Otherwise everything is written in one READ COMMITTED transaction with JDBC batches. Rows are upserted by `sku_code`: a new code is inserted, an existing one is updated, an unchanged one is not touched. A product is matched by `product_name` as described above. A bundle's component list is replaced only when the `components` column is present and the list differs.
- Re-importing the same file is a no-op apart from one `CATALOG_IMPORTED` audit row.
- Answer: `{rows, products_created, skus_created, skus_updated, skus_unchanged, bundles_replaced, elapsed_ms}`. The time is also logged. 1,000 rows take well under a second locally; CI asserts under 10 seconds.

## Transactions, locking, audit

- Catalog writes run at READ COMMITTED explicitly (the V4 `is_bundle` trigger refuses a flip at snapshot levels). A transaction that touches several SKUs locks them in id order (`FOR NO KEY UPDATE`). A deadlock (`40P01`) or serialization failure retries the whole transaction, at most 3 attempts with a short jittered backoff.
- Every mutation writes one `audit_log` row in the same transaction: `actor_type = USER`, `actor_id` = the OMS user id, `entity_type`/`entity_id`, `before`/`after`. Actions: `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_ARCHIVED`, `PRODUCT_DELETED`, `SKU_CREATED`, `SKU_UPDATED`, `SKU_DELETED`, `BUNDLE_COMPONENTS_REPLACED`, `WAREHOUSE_CREATED`, `WAREHOUSE_UPDATED`, `WAREHOUSE_DEFAULT_CHANGED`, `WAREHOUSE_DELETED`, and for an import one `CATALOG_IMPORTED` summary (with the file's SHA-256) plus the per-entity rows. A rejected mutation leaves no row. Warehouse addresses are not copied into the audit log, only whether one is set.

## Frontend

`#/catalog/skus` (search and paging), `#/catalog/skus/new` and `#/catalog/skus/{id}` (SKU form and bundle component editor), `#/catalog/products`, `#/catalog/import` (shows every row error), `#/warehouses` (including set default). Write controls are hidden or disabled for `STAFF` and while the shop is in GRACE.
