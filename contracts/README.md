# contracts

JSON Schema and OpenAPI for the ThaiShopFun ↔ OMS boundary (plan sections 4.1–4.7).

This folder is the stand-in for the `tsf-oms-contracts` repo (T01C), which does not exist yet. It is laid out so it can be moved out as-is:

- `openapi/oms-checkout.yaml` — section 4.3 (OMS checkout reservation)
- `openapi/tsf-internal.yaml` — section 4.7 (TSF REST that OMS calls)
- `schemas/envelope.json` — section 4.4 envelope
- `schemas/events/*.json` — section 4.5 and 4.6 `data` objects, one file per `event_type`
- `schemas/rest/*.json` — REST bodies the mock sends or accepts
- `examples/` — one JSON document per schema (event files are full envelopes)

`mock-tsf` loads these files from the classpath (`contracts/` inside the jar) and rejects a payload that does not match. CI lints `openapi/*.yaml` with Spectral (`npm run lint`, which resolves `$ref`s) and runs `ContractExamplesTest`, which checks every file under `examples/` against the schema with the same name. An event example's filename must equal its `event_type`.

Event schemas describe `data` only. The envelope schema describes the wrapper. A document is valid when both pass and `event_type` matches the data schema filename.

## Resolved choices

- `EXPIRED` is not a membership status. The expired seed user is `ACTIVE` with `expires_at` in the past. OMS answers `403 ENTITLEMENT_INACTIVE`.
- `shop_name` is an optional user-token claim (`schemas/rest/user-claims.json`). OMS maps it to `tenant.name`, and uses `tsf_shop_id` when the claim is absent.
- HMAC secrets are directional. TSF → OMS uses the inbox secret. OMS → TSF uses the outbox secret (at least 32 bytes).
- Audiences are `oms` (API access token), `oms-internal` (TSF calling OMS), and `tsf-internal` (OMS calling TSF). Client ids are public `oms-web` (PKCE, no secret; this is the `id_token` `aud`), confidential `tsf`, and confidential `oms-service`. The plan names audiences, not these client ids.
- The access token is the section 4.1 JWT with header `typ=at+jwt` and `aud=oms`. The token endpoint also returns a minimal signed `id_token` (`typ=JWT`, `iss`, `sub`, `aud=oms-web`, `exp`, `iat`, and `nonce` when `/authorize` sent one) so standard OIDC clients can complete login. OMS rejects that `id_token` as a bearer. Client-credentials responses do not include `id_token`. `schemas/rest/user-claims.json` is the access-token claim set: `jti` is required and `email` is optional.
- Checkout returns OMS's status and body. Reservation is not implemented in OMS, so a 404 is surfaced.
- `/control` is not in this folder. Label download is raw PDF bytes (`%PDF`), not JSON. The authorize user picker is HTML.
- Section 4.5/4.6 `data` objects that have no full example use a minimal schema: `order.paid`, `order.cancelled`, `order.updated`, `shipment.status_changed`, `return.decided`, `payment.status_changed`, `listing.changed`, `order.status_changed`, `return.received`. `payment.status_changed` includes `UNPAID` for the COD seed order.
- `$id` is omitted. A bare filename is not a valid JSON Schema `$id`.
