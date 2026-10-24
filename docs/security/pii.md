# PII lifecycle (T27 / V16)

Recipient name, phone and address use AES-256-GCM with tenant/order/column AAD.
Phone lookup uses a separate, stable HMAC key. Every role sees a masked phone on
order screens; full pack-page access belongs to T18. Audit must omit personal
values or replace them with `[PII]`; metrics carry counts and bounded codes only.

## Logging

`logback-spring.xml` wraps the existing Spring Boot console pattern in `PiiLogConverter`,
including exception, cause and suppressed exception text. Existing levels and format
remain. Precompiled patterns mask Thai local phones (9–10 digits, optional spaces or
hyphens), +66/66 international forms, and emails. Known JSON keys are case insensitive:
name, phone, email, address, line1, line2, district, subdistrict, recipient,
recipient_name, recipient_phone, recipient_address, first_name, last_name, full_name,
street and note. Entire structured recipient/address values are masked, including
nested objects/arrays and escaped strings. Contract fixture name/address sentinels
are also masked if accidentally logged as free text. Arbitrary free-form names cannot
be reliably identified by regex: application code must never log payloads or PII.

The test-wide `PiiLogGate` greps actual stdout/stderr bytes for the T00
`PiiLogAssertions` sentinels and contract/fixture addresses. It applies automatically
to backend unit and mock-acceptance tests. Existing raw-event assertion helpers stay
in force. `PiiLogMaskTest` covers patterns, exceptions, and a warmed timing assertion
of <10 µs/normal line (including a JSON masking case).

## Schedules and transactions

`oms.pii.redaction.enabled` defaults true; `cron` defaults `0 0 3 * * *`, always
Asia/Bangkok. `batch-size` defaults 500, allowed 1–1000. Environment overrides are
`OMS_PII_REDACTION_ENABLED`, `OMS_PII_REDACTION_CRON`, `OMS_PII_REDACTION_BATCH_SIZE`.
The daily pass performs redaction and payload scrubbing. Tests disable scheduling
and drive `PiiLifecycleJob.runOnce()` directly. Each discovered tenant is drained
in bounded batches, one transaction per tenant batch, with transaction-scoped RLS.
Discovery includes every entitlement state; a stopped subscription never stops PDPA
retention. Row locks with SKIP LOCKED make concurrent passes safe. A failed batch
rolls back both changes and audit; scheduler logs failure, next pass retries.

`OrderPiiLifecycle.onOrderClosed(orderId, closedAt)` schedules close +90 days and
requires the transition's existing transaction. Cancellation calls it today with
the same clock as before. **Future COMPLETED transitions (T20/T22) must call this
hook exactly once in their transition transaction.** Repeated close events must
not extend retention. ACTIVE recipients due at or before the pass clock become
REDACTED: name_enc, phone_enc, address_enc, phone_hash and phone_last4 are NULL;
province/postcode remain. Phone search excludes them. One `PII_REDACTED` audit row
per nonempty recipient batch contains only `{"count": n}` with actor SYSTEM.
Rerunning does not write another audit row.

## Payload scrub fields and statuses

After seven days, remove `data.recipient.name`, `.phone`, `.address` (including the
entire nested address). Preserve province/postcode, order ID, lines, totals, event ID,
aggregate/version metadata and the **original** `payload_sha256`. Set
`payload_scrubbed_at` once. No plaintext payload is loaded into Java. Retained hashes
allow duplicate delivery of a scrubbed event to return 200 without running handlers.

- Inbox PROCESSED: age from processed_at. DEAD: processed_at if present, otherwise
  received_at (a conservative cap from receipt, since DEAD has no completion time).
- Outbox SENT: age from sent_at. DEAD: sent_at if present, otherwise created_at.
  An old row newly made DEAD can therefore scrub on the next daily pass.
- Inbox RECEIVED/FAILED and outbox PENDING/IN_FLIGHT remain intact so legitimate
  deferred/retryable work is processable. Monitoring/operational resolution of stuck
  work is required; tenant purge is a separate follow-up.
- A scrubbed DEAD outbox retry returns 409 `PAYLOAD_SCRUBBED` with a clear message.
  Admin retry locks the row and rechecks the marker atomically; it cannot resend a
  partial payload. Un-scrubbed DEAD retries retain their existing behavior.

Inbox tenant_id is NOT NULL. Unknown business shops receive 503 before insert;
active membership.changed provisions the tenant before insert. No tenantless
inbox payload can accumulate; no privileged payload-reading scrub path is needed.

## Key rotation runbook

1. Generate a new 32-byte encryption key in the secret manager, assign a new ID
   (e.g. k2), and add `k2:base64` to `OMS_PII_KEYS` while retaining k1 and all other
   keys needed by stored rows/backups. Never put key material in logs or tickets.
2. Deploy the expanded ring with the existing `OMS_PII_ACTIVE_KEY_ID=k1` first,
   so every instance can read k2 before any instance writes it.
3. Change `OMS_PII_ACTIVE_KEY_ID` to k2 and roll the app. New ciphertext headers
   identify k2; existing k1 ciphertext remains readable. Keep `OMS_PII_HASH_KEY`
   unchanged so phone_hash searches continue across the change.
4. Verify masked API reads of old/new orders, key-ID headers using restricted
   operational access, and phone search without printing plaintext. Roll back the
   active ID if necessary; keep the expanded ring for reading both generations.
5. Optional re-encryption requires a separately reviewed tenant-scoped batch job:
   decrypt with the header's key and re-encrypt with the active key using identical
   AAD, atomically per row. No ReencryptJob is included here. Do not retire k1 until
   all live ciphertext and restorable backups no longer require it. Rotating the
   HMAC key needs its own hash rebuild/migration and is not an AES rotation.

## Local demonstration and screenshots

With the local/e2e stack and demo orders seeded, POST
`http://127.0.0.1:8080/control/demo/pii-redact-cancelled`. The profile-gated control
only schedules the fixed shop_active / DEMO-CANCELLED closed order as due and drives
the real audited job. It accepts no arbitrary identifiers. Reload the order detail
on :5173: it shows the Thai PDPA info banner and province/postcode only.
`node scripts/screenshot-pages.mjs` (from frontend) captures
`order-detail-before-redaction.png` and `order-detail-redacted.png`, calling this
control between states. `OMS_BASE_URL` overrides the localhost backend URL. Use a
fresh demo dataset to capture the before state after a previous irreversible redaction.

## Label cache and follow-ups

T18 must purge label PDF cache **no later than seven days after SHIPPED**, including
GRACE/SUSPENDED/expired tenants. Do not retain label PDF permanently. No label cache
exists yet; this change builds none. T18 must add purge/expiry tests and enforce
full-PII pack-page authorization/audit while all other screens keep D9 masking.

Tenant-wide deletion 90 days after SUSPENDED requires a PO decision and a separate
implementation. Re-encryption is a separate follow-up. Backup/PITR retention and
restored-data handling belong to T26/T42; CSV export remains out of scope.

Local visual previews: [before/after screenshots](../ui/screenshots/t27/README.md).
They use the actual UI with fixture API data; database-backed e2e evidence must be
produced by CI/the local stack with Docker.
