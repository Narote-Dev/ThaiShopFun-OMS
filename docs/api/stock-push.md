# Stock push (T15)

OMS sends the existing, unchanged `stock.updated` contract to TSF through the T14
outbox. Items contain `listing_sku_id` (the listing's external SKU id), nonempty
`seller_sku`, absolute nonnegative `available`, and `stock_version`. A bundle exposes
`max(0, min(floor(component_available / component_qty)) - safety_buffer)`.
No UI or contract changes are required.

## Capture, debounce and recovery

An AFTER_COMMIT `StockChanged` listener resolves both the changed SKUs and dependent
bundles and upserts `stock_push_pending` in a new tenant transaction. A V15 listing
trigger captures inserts, manual mapping, auto-map, remapping, unmapping, revival,
seller SKU changes and safety-buffer changes inside their transaction. It does not
fire when pushed markers are updated. Removed/unmapped listings are filtered later.
The dirty marker preserves the first change time in each fixed account window;
new arrivals do not extend it. At flush all dirty listings in the account are
coalesced, each once, with current availability. This is a fixed window, not a
trailing-edge debounce that could starve under continuous traffic.

Each builder takes the account row lock, checks the window again, then locks
listing rows in id order and their pending state. All of this runs in one
REQUIRES_NEW, READ COMMITTED transaction with the tenant context set before begin.
It takes no stock locks and does not change the engine lock order. Independent
processes and full scans use the same account lock; the second builder sees the
first's committed clean state and pushed versions. Outbox append, version state,
listing `last_exposed_qty` / `last_pushed_version`, and dirty-marker clearing commit
atomically. Counters increment only after commit. A rollback leaves work dirty.

Pending rows survive process restarts. If a process dies between the stock commit
and its AFTER_COMMIT capture (or capture fails), the full scan recovers the change
from inventory versions. Capture failure is logged with tenant id and exception
class only; a successful stock commit must not be reported as a failed reserve.

## Versions

Each listing retains its own bigint counter in `stock_push_pending`, even when
clean, removed or unmapped. The source key includes the mapped SKU, mapping
revision, ordered inventory `(sku_id, warehouse_id, stock_version)` tuples and
ordered bundle `(component_sku_id, qty)` tuples. The mapping trigger increments
its revision for relevant changes, including unmap/remap and remove/revive cycles.

A changed source key or exposed quantity assigns
`max(retained_version, last_pushed_version or 0) + 1`; an identical snapshot retains
its version. Overflow fails the transaction rather than wrapping. The account
lock serializes the calculation. Therefore every distinct emitted snapshot for a
listing has a strictly higher version than all of its previous emitted snapshots,
including component changes that leave a bundle's rounded quantity unchanged,
and remaps to a SKU with a smaller inventory version. Initial legacy pushed
markers are also a lower bound. Clean state is deleted only with the listing.

The signature is read before availability. A concurrent stock commit either
appears in this snapshot or its AFTER_COMMIT marker waits for the state lock and
schedules another window. Versions are per listing; consumers compare each item,
not the envelope's aggregate version (the maximum version in that batch).

## Build-time gating

| Account/listing state | Result |
| --- | --- |
| Paused or DISCONNECTED | No event or shadow row |
| OBSERVE | No event or shadow row |
| SHADOW | No event; one `STOCK_PUSH` shadow row per dirty eligible listing per window |
| CONTROL | Push only `stock_control=true` |
| ACTIVE | Push all mapped, non-removed listings |
| Unmapped or removed listing | Never push or record would-push |
| Non-TSF account | Never targeted |

SHADOW writes `{available, stock_version}` to `oms_value`, with `channel_value=NULL`,
and does not update the listing's pushed markers. A clean unchanged full scan
does not repeat the shadow row. Pausing consumes pending work without updating
pushed markers; the next full scan after unpause sends the current snapshot once.

Null or blank `channel_listing.seller_sku` falls back to mapped `sku.sku_code` and
increments `oms.stock.push.seller_sku_fallback`. The item is kept. Events split at
500 items by default. Only changed quantity/version pairs and never-pushed
listings are sent. The 15-minute full scan uses the same rules; there is no
unconditional resend of identical snapshots at any frequency. Delivery itself
remains at least once, with T14 HMAC, leases and the existing retry ladder.

## Configuration and metrics

| Property | Default | Meaning |
| --- | --- | --- |
| `oms.stock.push.enabled` | `true` | Enable scheduled debounce and full scan; capture stays durable |
| `oms.stock.push.debounce-ms` | `2000` | Fixed account window, validated 2000–5000 ms |
| `oms.stock.push.poll-delay-ms` | `500` | Delay between debounce job runs |
| `oms.stock.push.full-delay-ms` | `900000` | Full scan interval and initial delay |
| `oms.stock.push.batch-size` | `500` | Validated 1–500 items per event |

A dedicated single-thread scheduler prevents inbox work from delaying the stock
push job. Correctness across instances depends on the database lock, not the
scheduler. Each account uses its own tenant transaction; discovery is an ids-only
SECURITY DEFINER function with a pinned search path and no PUBLIC execution grant.

Counters: `oms.stock.push.events`, `items`, `skipped_paused`, `shadow_records`,
`seller_sku_fallback` (all under the same prefix). The
`oms.stock.push.propagation_lag` Timer publishes a histogram on successful outbox
acknowledgement, measured as `outbox_event.sent_at - stock_changed_at`. For stock
changes the origin is the source ledger transaction's `created_at` (the NFR proxy
for commit time), retaining the earliest origin in a coalesced window. Mapping
changes use their trigger timestamp. Full scans can recover the latest source
ledger timestamp when no dirty marker exists. This includes debounce, outbox wait,
HTTP and retries; it is not just build duration. For batched origins it measures
the oldest represented change, conservatively. A never-pushed historical listing
can therefore contribute a large lag. No payloads or PII are logged.

## Verification and local demo

`StockPushAcceptanceTest` uses `oms_app`, `@VerifyInvariants(STOCK_ONLY)`, real engine
reservations and the real mock-tsf HMAC/schema-validating receiver. It checks five
reserves within one second coalescing to one final absolute update, component sale
plus bundle, paused/unpause, all modes, disconnected/removed/unmapped filtering,
mapping lifecycle, retained pending state, two builders, full recovery, 1,000
random stock changes with strictly increasing plain/bundle versions, tenancy,
batch splitting, seller SKU fallback and its counter. Every inspected event is
validated against the unchanged contract. The 200-change lag test prints p95/max
and writes `backend/target/stock-push-lag.json`; it asserts p95 < 10 seconds using
commit-return timestamps and successful `sent_at`, plus the receiver timestamp.

No Docker is available in this implementation environment. Acceptance and V15
migration tests are compiled for CI, but their runtime results and measured lag
numbers must be supplied by a Docker-capable run; no p95 numbers are claimed here.

The existing `local` profile already wires the localhost receiver and matching
dev-only secret. Start mock-tsf on 8090 and run:

```sh
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Seed the demo catalog (ACTIVE account), post a RECEIVE document for
`DEMO-SKU-READY` (+5), then within about five seconds inspect:

```sh
curl -s localhost:8090/control/received-events \
  | jq '.[] | select(.event_type=="stock.updated") | .body | fromjson'
```

The new absolute quantity appears for `L-demo-ready`, with affected bundle
listings. Without the local profile, configure `TSF_OMS_EVENTS_URL` and
`OMS_OUTBOX_WEBHOOK_SECRET` as described in the README before expecting delivery.
