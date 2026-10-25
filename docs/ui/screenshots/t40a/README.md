# T40A allowlist screenshots

Real Chromium captures against the branch's local backend, PostgreSQL demo data,
Vite frontend and mock TSF on 25 October 2026. STAFF uses the mock's real
`staff-active` identity in `shop_active`. No API responses were mocked in the browser.

- [CONTROL toggles and coverage](listings-control.png)
- [Bulk confirmation for two listings](listings-control-bulk-dialog.png)
- [ACTIVE, read-only allowlist](listings-active-readonly.png)
- [STAFF, read-only allowlist](listings-staff.png)

Capture against the standard local stack:

```sh
node frontend/scripts/screenshot-pages.mjs --stock-control docs/ui/screenshots/t40a
```

Optional environment variables: `PLAYWRIGHT_BASE_URL`, `OMS_BASE_URL`, and
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` (otherwise Playwright's installed Chromium).
The script seeds demo listings, explicitly resets the demo allowlist, captures
CONTROL and ACTIVE, signs in separately as STAFF, and restores ACTIVE afterward.
The bulk dialog is cancelled; capturing screenshots makes no allowlist API writes.

Validation: 151 backend tests requiring no Docker; 119 Vitest tests; frontend lint,
TypeScript and production build; mock TSF tests and Spotless; OWNER bulk e2e against
local PostgreSQL. The localhost HTTP/database check confirmed two audit rows for
ready/cod and checkout enforcement true/true/false; the 3-changed + 1-unchanged
bulk check produced three audit rows, and repeating produced none. STAFF/GRACE
returned 403 (GRACE: ENTITLEMENT_GRACE), another tenant returned 404, and enabling
an unmapped listing returned LISTING_NOT_CONTROLLABLE.

New database integration tests cover transaction rollback, removed/unmapped
validation, auditing, coverage, and CONTROL push eligibility including no zero-out
on disable. They compile locally and require Testcontainers in CI. All pre-existing
tests and `.github/workflows` are unchanged. No migration is added.
