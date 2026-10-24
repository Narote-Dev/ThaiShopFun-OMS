
## T27 / Flyway V16 — PII lifecycle

`inbox_event.payload_scrubbed_at` and `outbox_event.payload_scrubbed_at` are nullable
`timestamptz` markers written by `PiiLifecycleJob`. Partial due indexes cover terminal
statuses with NULL markers. Existing ENABLE/FORCE RLS policies remain unchanged.
`list_tenants_with_pii_due(timestamptz)` follows V6: SECURITY DEFINER owned by
`oms_maint`, pinned `pg_catalog, pg_temp`, qualified tables, PUBLIC revoked,
EXECUTE granted to `oms_app`. It returns **tenant UUIDs only**, across ACTIVE,
GRACE, SUSPENDED and expired shops. Each batch then runs in a separate tenant
transaction. See [PII policy and rotation runbook](../security/pii.md).
