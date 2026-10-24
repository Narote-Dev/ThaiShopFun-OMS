# T27 implementation and acceptance coverage

Scopes A–F are implemented in one local change: console masking and suite log gate;
recipient retention/close hook; terminal payload scrub; rotation proof and runbook;
Thai redacted recipient card/demo control/screenshots; label-cache requirement only.

All new database acceptance tests are in `backend/src/mock-acceptance` and carry
`@VerifyInvariants`; application DB work uses `oms_app` with tenant FORCE RLS.

| AC | Test mapping |
| --- | --- |
| AC1 patterns | `PiiLogMaskTest#masksEachPhoneAndEmailPattern`, `#masksJsonKeysAndEscapedValues`, `#masksWholeNestedRecipientAndAddressWithoutLeakingUnknownKeys` |
| AC1 deliberate log / exceptions / gate | `PiiLogMaskTest#existingLayoutMasksMessageAndNestedExceptionText`, `#suiteGateDetectsFixturePiiEvenAcrossUtf8WriteBoundaries`; automatic `PiiLogGate#afterEach` / `#afterAll` across the suite |
| AC1 timing | `PiiLogMaskTest#normalLineOverheadIsUnderTenMicroseconds` |
| AC2 due cancelled order, future untouched, phone search, API detail, rerun and audit | `PiiLifecycleAcceptanceTest#dueCancelledRecipientRedactsOnceWithCountOnlyAuditPhoneSearchAndApiDetail` |
| AC2 suspended/expired tenant | `PiiLifecycleAcceptanceTest#suspendedExpiredTenantStillRedacts` |
| AC2 one audit per batch | `PiiLifecycleAcceptanceTest#eachRecipientBatchHasOneCountOnlyAuditRow` |
| AC3 rotation and hash search | `PiiLifecycleAcceptanceTest#rotationKeepsOldRowsReadableNewRowsUseK2AndPhoneHashSearchSurvives` |
| AC4 created/updated scrub, retained data/hash, duplicate 200/no handler, six-day/retryable rows untouched, SENT and DEAD, retry refusal | `PiiLifecycleAcceptanceTest#terminalPayloadScrubPreservesHashAndDuplicatePostDoesNotRunHandler` (parameterized for both event types) |
| V16 discovery/grants/FORCE RLS | `FlywayV16PiiLifecycleTest#discoveryReturnsOnlyTenantIdsAndAppWithoutContextSeesNoRows` |
| AC5 card | `frontend/src/orders/redactedRecipient.test.tsx` — `shows the PDPA banner and province/postcode without masked or empty fields` |
| AC5 full-stack state/control | `frontend/e2e/pii-lifecycle.e2e.ts` — `cancelled demo recipient becomes a PDPA card through the local control`; `scripts/screenshot-pages.mjs` captures before/after |
| AC6 regression | Existing tests retained; only latest `containsExactly` migration lists append `16`. Local checks below; full CI pending. |

V16 adds two nullable scrub timestamps, two partial terminal-work indexes, and
`list_tenants_with_pii_due(timestamptz)` returning tenant UUIDs only. The function
uses the V6 maintenance-owner/grant/search-path pattern; it includes every
entitlement state. No RLS policy or existing migration changes.

Local checks passed: Spotless apply/check; 151 Docker-free backend unit cases;
`-Pmock-acceptance test-compile`; 79 Vitest cases; frontend lint and build (includes
typecheck); Playwright test discovery; dictionary generation/verification with
331 snapshot fields, 0 blank meanings, 0 usage/class mismatches, 0 rendered mismatches.
The V15 supplement continues to describe its ten fields separately (341 total).

Not verified locally: Testcontainers acceptance/migration tests, delivery chaos,
full-stack e2e and the seven GitHub CI jobs (Docker unavailable). A preliminary
attempt to run `PiiStartupGuardTest` confirmed that it also requires Testcontainers;
it is excluded from the local Docker-free command, not disabled in source/CI.
Mermaid rendering attempted by the generator failed in this restricted environment;
the dictionary's structural checks and eight diagram blocks validate successfully.
[Before/after screenshots](../ui/screenshots/t27/README.md) are explicitly fixture
previews using the real UI; they do not claim local database verification.

One existing-test compatibility issue remains for approval: the unchanged
`FlywayV15StockPushTest#emptyDatabaseMigratesToLatestWithForcedRlsAndIdsOnlyDiscovery`
asserts latest version `15` at line 43. V16 necessarily makes this fail. The user's
instruction permits only `containsExactly` version-list edits, so that assertion
has not been changed. A version-only exception was requested; no test was disabled
or weakened to hide this issue. This branch cannot claim 7/7 CI until it is resolved.
