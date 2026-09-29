# CHANGELOG v2 (29 ก.ย. 2026)

v1 เก็บไว้ที่ `v1/` ทิศทางและ stack ไม่เปลี่ยน (Narote อนุมัติแล้ว)

## P0
**1. TSF Checkout Reservation (strong reservation)**
- เปลี่ยน: TSF เรียก `POST /internal/v1/inventory/reservations` ก่อนสร้างออเดอร์ จองแบบ all-or-nothing ได้ `reservation_id` + `expires_at` (TTL 15 นาที), ไม่พอ = `409 OUT_OF_STOCK`; เพิ่ม `DELETE` คืนตอนทิ้ง checkout; `order.created` มี `reservation_id` แล้ว OMS โอน owner CHECKOUT → ORDER; มี expiry job ทุก 1 นาที; เพิ่มฟิลด์ `enforced` ให้ใช้กับ shadow mode
- ที่: 01 §1.1, §1.2 (sequence diagram ใหม่) · 03 `stock_reservation` (owner_type, owner_ref, status, expires_at) · 04 §4.3 (ใหม่), §4.5 · 05 T08, T12A (ใหม่), T12, TSF-07 (ใหม่)

**2. แยกสถานะออเดอร์เป็น 3 มิติ + hold**
- เปลี่ยน: `order_status` (ACTIVE/CANCELLED/COMPLETED), `payment_status` (PENDING/PAID/COD_PENDING/PARTIALLY_REFUNDED/REFUNDED), `fulfillment_status` (UNFULFILLED → DELIVERED), `hold_reason` (NONE/SKU_NOT_MAPPED/OUT_OF_STOCK/ADDRESS_PROBLEM/…); COD = `COD_PENDING` + `READY_TO_PICK`; return กับ refund เป็น entity แยก คืนบางชิ้นได้ ไม่เปลี่ยนสถานะหลักของออเดอร์; state diagram เดียวของ v1 แยกเป็น 4 รูป (order, payment, fulfillment, return)
- ที่: 01 §1.3, §1.4, §1.5 (กฎ reconcile ใหม่), ท้ายไฟล์ "Order State" · 03 `sales_order`, `order_status_history.dimension`, `return_line`, `refund` (ใหม่) · 04 `return.requested` มี lines, `refund.status_changed` (ใหม่) · 05 T10, T12, T13, T20, T21

**3. Inbox/outbox แบบ at-least-once**
- เปลี่ยน: ประกาศชัดว่าเป็น at-least-once + consumer idempotent ไม่ใช่ exactly-once; outbox ใช้ lease (`IN_FLIGHT` + `lease_until`); ประมวลผลทีละ aggregate ด้วย advisory lock; เขียน AC ของ T14 ใหม่ทั้งหมด (หลาย instance ห้ามถือ event เดียวพร้อมกัน, ส่งซ้ำได้, dedupe ด้วย `event_id`, crash ก่อน/หลัง ack ไม่หาย); T11/T12 บังคับ business update + reservation + history + outbox + inbox PROCESSED ใน transaction เดียว; เพิ่ม chaos test
- ที่: 01 §1.1 · 03 `outbox_event`, `inbox_event` · 04 หลักการ, §4.4 · 05 T11, T12, T14, T14B (ใหม่)

**4. RLS context**
- เปลี่ยน: JWT filter แค่ resolve `TenantContext`; `set_config('app.tenant_id', ?, true)` รันใน `JpaTransactionManager.doBegin()` บน connection เดียวกับ query; FORCE RLS ทุกตาราง; `oms_app` ไม่มี BYPASSRLS; แยก role `oms_migrator` / `oms_maint` (maintenance เท่านั้น); งานข้าม tenant ใช้ function `SECURITY DEFINER` ที่คืนแค่ id แล้วรันต่อ tenant; เพิ่ม AC pool leak test / ThreadLocal leak test
- ที่: 03 "RLS + DB roles" (ใหม่) · 05 T02, T03, T11, T22, T25

**5. Stock operations**
- เปลี่ยน: เพิ่ม task T08A (opening balance, รับเข้า, ปรับมือ+เหตุผล, นับสต๊อก, ตัดเสีย, return restock, หน้าประวัติสต๊อก); ledger reason ชุดใหม่ `OPENING_BALANCE, RECEIVE, ADJUST_IN, ADJUST_OUT, COUNT_CORRECTION, DAMAGE_WRITE_OFF, RETURN_RESTOCK, SHIP, RESERVE, RELEASE, UNPACK`; เพิ่มตาราง `stock_document`, `stock_document_line`
- ที่: 01 §1.6 (ใหม่) · 03 Inventory · 05 T06, T08A (ใหม่), T20

**6. Bundle rules**
- เปลี่ยน: `bundle_available = min(floor(component_available / qty))`; component เปลี่ยน → คำนวณ bundle ที่ขึ้นกับมัน + `stock.updated`; ห้าม nested/circular (API + DB trigger); lock ตาม SKU id เรียงลำดับ; T09 ต้องครอบ 6 เคสที่กำหนด + deadlock ordering
- ที่: 01 §1.2 "Bundle rules" · 03 `sku_bundle_component` trigger + index · 04 `stock.updated` ตัวอย่าง bundle · 05 T06, T07, T08, T09, T15

## P1
**7. Cross-channel oversell**
- เปลี่ยน: ตัด `available × alloc_pct` ออก ใช้คำ `physical_available`, `channel_allocated`, `channel_exposed`, `safety_buffer`; นิยาม Shared Exposure กับ Hard Allocation; ค่าเริ่ม = Shared + last-units protection; นิยาม "business oversell" = ออเดอร์ที่ ON_HOLD `OUT_OF_STOCK`; T34 เขียนใหม่ให้วัด oversell rate ต่อ strategy
- ที่: 01 §1.2 · 03 `allocation_policy`, `channel_allocation` (Phase 5), `channel_listing` · 05 T50M, T51, T34

**8. Channel Capability Matrix + base adapter**
- เปลี่ยน: `ChannelCapabilities` 8 ตัว; `BaseChannelAdapter` มี rate limiter, backoff + jitter, `Retry-After`, circuit breaker, concurrency limit ต่อ channel (Resilience4j core); T16 กลายเป็น foundation ของ Phase 2; Phase 5 "capability framework" = T50 ต่อยอด (polling fallback, gating UI)
- ที่: 05 T16, T50 (ใหม่) · 04 §4.7 (`Retry-After`)

**9. Shadow mode + emergency controls + pilot progression**
- เปลี่ยน: `channel_account.mode` OBSERVE → SHADOW → CONTROL → ACTIVE พร้อมเกณฑ์เลื่อนขั้น; CONTROL ใช้ SKU allowlist (`stock_control`); ปุ่ม PAUSE STOCK SYNC / DISCONNECT CHANNEL / FORCE FULL RESYNC + global kill switch; ตาราง pilot 5 ขั้น (ร้านภายใน → 1–2 ร้าน shadow → control → 3–5 → 5–10); ตาราง `shadow_diff`
- ที่: 02 "Channel Connection Mode", "Pilot progression" (ใหม่) · 03 `channel_account`, `channel_listing`, `shadow_diff` · 04 `enforced`, §4.6 · 05 T15, T40, T41 (ใหม่)

**10. NFR.md**
- เปลี่ยน: ไฟล์ใหม่ครบทุกหัวข้อที่ขอ (tenants, ออเดอร์, peak, SKU, stock updates/s, latency p95 3 ตัว, availability, RPO/RTO, retention) พร้อมค่าเริ่ม/TBD; แทน daily backup ด้วย **Railway PITR** (ตรวจแล้วรองรับ: WAL archive ผ่าน pgBackRest, ย้อนได้ ~4 สัปดาห์, restore เป็น service ใหม่) + offsite `pg_dump` รายสัปดาห์; เพิ่ม T00 (invariants + NFR), T42 (DR drill), T43 (load test)
- ที่: `NFR.md` (ใหม่) · 05 T00, T26, T42, T43

**11. Contract governance**
- เปลี่ยน: repo แยก `tsf-oms-contracts` (OpenAPI 3.1 + AsyncAPI 3 / JSON Schema + examples), CI lint + breaking-change check, contract test ใน CI ของ OMS และ TSF, mock TSF validate กับ spec
- ที่: 04 §4.9 (ใหม่) · 05 T01C (ใหม่), T05, T12A, T16, TSF-08 (ใหม่)

**12. PII lifecycle**
- เปลี่ยน: ย้าย PII ออกจาก `sales_order` ไป `order_recipient` (เข้ารหัส AES-GCM, `phone_hash`, `phone_last4`, redact หลังปิดออเดอร์ 90 วัน); ไม่เก็บ label PDF ถาวร (cache ≤ 7 วัน); scrub payload inbox/outbox; mask log/audit/UI; retention ใน NFR
- ที่: 03 `order_recipient`, "PII lifecycle" (ใหม่) · NFR "PII retention" · 05 T27 (ใหม่), T17, T25

## โครงสร้าง Phase และ task list
- จัด Phase ใหม่ตาม layout ที่กำหนด (0 Foundation → 5 Multichannel); inbox/outbox ย้ายไป Phase 0; stock push/adapter อยู่ Phase 2; modes/DR อยู่ Phase 4
- Task: v1 มี 31 (T01–T26 + T30–T34, ไม่นับ TSF-side; สรุป v1 ที่รายงานว่า 34 นับผิด) → v2 มี **48** (Cursor 22, Codex 26), MVP (Phase 0–4) = 39; ID เดิมคงไว้เพื่อ trace ได้ ของใหม่: T00, T01C, T08A, T12A, T12B, T12C, T14B, T27, T28, T40–T43, T50, T50M, T51, T35
- งาน TSF เพิ่ม TSF-07 (เรียก reserve ตอน checkout), TSF-08 (contract test), TSF-09 (timeout/fallback)
- Timeline: สร้างเสร็จ 13 สัปดาห์ (v1 = 10) + pilot 9 สัปดาห์ → ครบ 10 ร้าน ~สัปดาห์ 22 (v1 = 12)
- "ต้องให้ Narote ตัดสินใจ" เพิ่มข้อ 6–10 (fallback checkout, allocation ค่าเริ่ม, อายุ PII, ร้าน pilot, ตัวเลข NFR), ข้อ 5 เพิ่ม HA + PITR
