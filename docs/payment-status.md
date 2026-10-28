# Payment and refund snapshots (T21)

OMS only mirrors TSF's payment state. It makes no payment/refund commands and has no payment-provider SDK dependency.

`payment.status_changed` appends a snapshot keyed by the tenant and event ID. `refund.status_changed` inserts or updates one refund per order/provider reference, retaining the first source event ID. All writes serialize on the existing order advisory lock and row lock; snapshot/refund changes, payment projection, status history and a PII-free audit entry commit in one transaction. Existing FORCE RLS policies apply.

Snapshots are selected by `occurred_at` (stored as `observed_at`), with deterministic status precedence for equal timestamps. Refund updates older than the stored observation are ignored; success is final. The inbox lets these handlers apply their own ordering because payment/refund aggregate versions are independent of order REST versions. A delayed `order.paid` cannot overwrite a refund state.

The order projection compares the order total against the greater of the snapshot's refunded amount and the sum of successful refund rows, avoiding double counting. Refunding 295 of 580 produces `PARTIALLY_REFUNDED`; 580 produces `REFUNDED`. Pending/failed refunds do not count. Successful payments and refunds do not regress when a delayed unpaid observation arrives.

TSF may supply optional `provider` (`XENDIT`/`OPN`) and `provider_ref` metadata. OMS takes the provider only from the explicit `provider` field and never infers it from `provider_ref`. If `provider` is absent or unknown, OMS skips inserting a snapshot but still projects the observation and successful refunds and writes an audit in the same transaction. No migration is needed: the provider NOT NULL and XENDIT/OPN check remain unchanged. Snapshot `amount` uses the order total because the contract has no captured amount. An optional return reference links to an existing return of the same order; a missing return does not block financial status ingestion.

Reconciliation can call `PaymentSnapshotService.reconcile(ChannelAccountRef, externalOrderId)` with tenant context set. It calls the channel adapter's GET `/orders/{id}/payment-status`, merges refunds and appends a snapshot only when status/refunded amount changed. Repeated unchanged responses with a known provider make no writes or audit entries. Observations with an unknown provider are audited even when no status changes; they never create snapshot rows. T22 can use this entry point without duplicating payment logic.

Order detail adds `payment_snapshot` (nullable) and `refunds` (array). These views expose only statuses, amounts, currency, observation/payment times and internal refund IDs; no card, account or customer details. The UI uses shared components and Thai labels.

Mock TSF's existing POST `/control/events/send`, `/control/events/repeat` and `/control/events/shuffle` accept the contract example envelopes under an `event` field (or the endpoint's batch shape). Payment/refund events also update mock payment-status GET state; refund IDs are deduplicated. Payment event and REST examples demonstrate the optional provider metadata documented in `contracts/README.md`.

Validation: Docker-free backend tests and mock TSF tests run with Maven; frontend checks use `npx tsc -b`, `npm run lint`, and `npm test`. `PaymentSnapshotIntegrationTest` exercises signed inbox ingest/worker processing, source-event replay, partial/full refunds, stale events, fallback deduplication, read views and transaction rollback against PostgreSQL. It explicitly enables the inbox worker and hold resolver. Docker/Testcontainers is required to execute that class.
