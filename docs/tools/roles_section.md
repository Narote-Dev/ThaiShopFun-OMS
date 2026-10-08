
| Role | ใช้เมื่อ | สิทธิ์ |
|---|---|---|
| `oms_migrator` | Flyway | DDL; owner ตาราง |
| `oms_app` | runtime + jobs | DML, **NOBYPASSRLS** |
| `oms_maint` | break-glass | BYPASSRLS; owner ฟังก์ชัน SECURITY DEFINER |

**RLS mechanics:** ทุก transaction ตั้ง `set_config('app.tenant_id', …, true)` ผ่าน `TenantAwareDataSourceTransactionManager` (`tenant/TenantAwareDataSourceTransactionManager.java`) ทันทีที่ `doBegin` — ไม่ใช่ JPA generic manager. JWT filter ตั้ง `TenantContext` (ThreadLocal) เท่านั้น; query นอก `@Transactional` = ไม่มี context = 0 แถว (fail-closed).

**SECURITY DEFINER (ข้าม tenant, คืนเฉพาะ id หรือ batch):** `claim_inbox_batch`, `claim_outbox_batch`, `list_active_tenant_ids`, `list_tenants_with_expired_reservations`, `resolve_tenant`, `resolve_reservation_tenant` (V9), `upsert_app_user`, `provision_tenant`, `provision_membership`, `lookup_login` — ดู `V1__foundation_rls.sql`, `V2__jit_provision.sql`, `V6__stock_expiry_tenant_claim.sql`, `V9__reservation_tenant_lookup.sql`.

### Flyway V1–V13 (ไม่มี V5)

| Version | ไฟล์ | สรุป |
|---|---|---|
| V1 | `V1__foundation_rls.sql` | roles, tenant core, inbox/outbox, RLS |
| V2 | `V2__jit_provision.sql` | JIT SECURITY DEFINER |
| V3 | `V3__inbox_tenant_dedup.sql` | inbox dedup ต่อ tenant + `inbox_event_due_idx` |
| V4 | `V4__catalog_inventory.sql` | catalog + stock + triggers |
| _V5_ | _ไม่มี_ | จอง T07 แต่ ship โดยไม่มี migration — **ห้ามเพิ่ม V5** |
| V6 | `V6__stock_expiry_tenant_claim.sql` | `list_tenants_with_expired_reservations` |
| V7 | `V7__orders.sql` | orders + payment/return schema |
| V8 | `V8__ledger_seq.sql` | `ledger_seq` |
| V9 | `V9__reservation_tenant_lookup.sql` | **`resolve_reservation_tenant`** |
| V10 | `V10__tsf_channel_account_provision.sql` | channel provision |
| V11 | `V11__sales_order_list_cursor_index.sql` | list orders index |
| V12 | `V12__listing_mapping_and_orphan_marker.sql` | listing mapping + inbox orphan marker |
| V13 | `V13__order_hold_retry.sql` | `order_hold_retry` |
