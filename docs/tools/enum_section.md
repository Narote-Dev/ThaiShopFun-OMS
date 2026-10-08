
### `tenant.entitlement_status`
- `ACTIVE` — ร้านใช้งานปกติ (เขียน API ได้ถ้า role ผ่าน)
- `GRACE` — อ่านได้; POST ธุรกิจถูก `403 ENTITLEMENT_GRACE`; inbox ยังรับ event
- `SUSPENDED` — paywall; inbox defer (ยกเว้น `membership.changed`)

(ค่าที่ JWT รับได้จริง: `ACTIVE`, `GRACE`, `SUSPENDED` เท่านั้น — `UserClaims.STATUSES` + CHECK)

### `tenant_membership.role` / `status`
- `OWNER`, `ADMIN`, `STAFF` — สิทธิ์ UI/API
- `ACTIVE`, `REVOKED` — membership ยังใช้หรือถูก revoke

### `channel_account.channel`
`TSF`, `SHOPEE`, `LAZADA`, `TIKTOK`

### `channel_account.mode`
- `OBSERVE` — ไม่บังคับสต็อก
- `SHADOW` — จำลอง + `shadow_diff`
- `CONTROL` — บังคับเฉพาะ listing ที่ `stock_control=true`
- `ACTIVE` — บังคับทุก listing ที่แมปแล้ว

### `channel_account.status`
`CONNECTED`, `DISCONNECTED`

### `product.status`
`ACTIVE`, `INACTIVE`

### `sales_order` — `OrderStateMachine` transitions
- **ORDER:** `ACTIVE`→`CANCELLED`|`COMPLETED`; `CANCELLED` terminal
- **PAYMENT:** `PENDING`→`PAID`; `COD_PENDING`→`PAID`; `PAID`→`PARTIALLY_REFUNDED`→`REFUNDED`
- **FULFILLMENT:** `UNFULFILLED`→`READY_TO_PICK`→`PICKING`→`PACKED`→`SHIPPED`→`DELIVERED` (`PACKED`→`READY_TO_PICK` ได้)
- **HOLD:** ทุกค่าในเซตเปลี่ยนได้ยกเว้น guard (hold ≠ `NONE` บล็อก fulfillment)

**Guards (สรุป):**
- `READY_TO_PICK`: `order_status=ACTIVE`, `payment_status` เป็น `PAID` หรือ `COD_PENDING`, `hold_reason=NONE`, และเมื่อ stock-enforced ต้องมี ORDER reservation ครบ mapped lines
- `COMPLETED`: `fulfillment=DELIVERED`, `paid_at` ไม่ null, ไม่มี open return, และ ≥7 วันหลัง `DELIVERED` (จาก history)
- ออเดอร์ `CANCELLED`/`COMPLETED`: fulfillment/hold แก้ไม่ได้; payment เปลี่ยนได้เฉพาะ refund states

### `sales_order.payment_method`
- `PREPAID` — ชำระก่อนส่ง
- `COD` — เก็บเงินปลายทาง (`COD_PENDING` ใน payment)

### `stock_reservation`
- `owner_type`: `CHECKOUT`, `ORDER`
- `status`: `ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`
- Lifecycle: CHECKOUT+TTL → adopt เป็น ORDER (`expires_at` NULL) ผ่าน `ReservationEngine`; `StockExpiryJob` ปล่อย CHECKOUT หมดอายุ (query `owner_type` ผ่าน `StockRepository`)

### `inventory_ledger.reason`
`OPENING_BALANCE`, `RECEIVE`, `ADJUST_IN`, `ADJUST_OUT`, `COUNT_CORRECTION`, `DAMAGE_WRITE_OFF`, `RETURN_RESTOCK`, `SHIP`, `RESERVE`, `RELEASE`, `UNPACK`

### `stock_document.type` / `status`
- type: `OPENING`, `RECEIVE`, `ADJUSTMENT`, `COUNT`, `WRITE_OFF`
- status: `DRAFT`, `POSTED`, `VOID`

### `inbox_event.status`
`RECEIVED`, `PROCESSED`, `FAILED`, `DEAD`

### `outbox_event.status`
`PENDING`, `IN_FLIGHT`, `SENT`, `DEAD`

### `audit_log.actor_type`
- `USER` — ผู้ใช้ OMS
- `SYSTEM` — job ภายใน
- `TSF` — ระบบ TSF
- `PLATFORM_ADMIN` — admin แพลตฟอร์ม

### `refund.status` (T21)
`PENDING`, `SUCCEEDED`, `FAILED`

### `return_request.type` / `status` (T20)
- type: `RETURN`, `RTS`
- status: `REQUESTED`, `APPROVED`, `REJECTED`, `RECEIVED`, `CLOSED`

### `shipment.status` (T18)
`PENDING`, `LABEL_READY`, `SHIPPED`, `IN_TRANSIT`, `DELIVERED`, `FAILED`, `RETURNED_TO_SENDER`

### `channel_listing.mapping_source`
`AUTO`, `MANUAL`, หรือ NULL ก่อนแมป

### `shadow_diff.kind`
`STOCK`, `ORDER`, `RESERVATION`

### `reconciliation_issue.status`
`OPEN`, `ACK`, `RESOLVED`

### `order_recipient.pii_status`
`ACTIVE`, `REDACTED` — ดู [PII lifecycle](../plan/03-data-model.md#pii-lifecycle)
