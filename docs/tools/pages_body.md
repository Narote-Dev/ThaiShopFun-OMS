# Frontend pages

อ้างอิง main @ ee4e425 (Flyway V13)

Hash routing (`#/...`). สี/label: [`frontend/src/ui/status.ts`](../../frontend/src/ui/status.ts). ภาพรวม UI: [DESIGN-SYSTEM.md](../ui/DESIGN-SYSTEM.md).

## Auth (mock TSF → OMS)

1. `LoginPage` → OIDC `http://localhost:8090/tsf-idp` (`client_id=oms-web`, PKCE)
2. Token in memory → `GET /api/v1/me`
3. `AppLayout` หรือ `Paywall` / `SettingUp`

| login_hint / picker | Shop | ทดสอบ |
|---|---|---|
| `owner-active` / Active | `shop_active` | `ACTIVE` ครบ |
| `owner-grace` / Grace | `shop_grace` | GRACE read-only POST |
| `owner-bump` / **Bump** | `shop_bump` | **ACTIVE แยกต่างหาก** — ใช้ทดสอบ `membership.changed` โดยไม่กระทบ `shop_active` (`SeedData` mock-tsf) |
| `owner-suspended` / Suspended | `shop_suspended` | SUSPENDED paywall |
| `owner-expired` / Expired | `shop_expired` | ACTIVE แต่ `expires_at` อดีต → inactive |

## Shared UI (`frontend/src/ui`)

| Component | หน้าที่ |
|---|---|
| `AppShell`, `PageHeader`, `PageContent` | layout — DESIGN-SYSTEM |
| `Button`, `Input`, `Select`, `Checkbox`, `Textarea`, `Label` | forms |
| `Card`, `Dialog`, `Sheet`, `Tabs`, `FilterBar` | structure |
| `DataTable`, `TablePager` | tables |
| `StatusBadge`, `OrderDisplayBadge`, `HoldReasonBadge` | `status.ts` |
| `Alert`, `Toast` | errors |
| `Timeline` | order history |
| `format.ts` | เงิน/เวลาไทย |

---

## `#/` — Dashboard (`DashboardPage`)

- **ผู้ใช้:** ทุก role
- **ส่วน:** ชื่อร้าน, การ์ดสรุป (ตาม implementation ปัจจุบัน)
- **API:** ไม่มีเพิ่มจาก `/me`
- **Empty/error:** ใช้ข้อมูลจาก `me` ที่โหลดแล้ว

---

## `#/orders` — Orders list (`OrdersListPage`)

- **ส่วน:** แท็บ, filter bar, ตาราง, bulk select (export placeholder)
- **Hash params:** `fulfillment_status`, `order_status`, `payment_status`, `hold_reason`, `channel`, `q`, `ordered_from`, `ordered_to`, `cursor`
- **แท็บ:** `all` | `ready` | `hold` | `cancelled` (preset filters ใน `orderViews.ts`)
- **API:** `GET /api/v1/orders` (+ tab count queries ใน `listOrders.ts`)
- **Status แสดง:** `resolveOrderDisplayStatus` — CANCELLED (muted+strike), ON HOLD (danger), fulfillment badge
- **Hold reason ไทย:** `holdReasonLabelTh` / `HOLD_REASON_TH`
- **Empty:** ตารางว่าง + pager; **error:** `AlertBanner` + `ordersMessage`

| ค่า | label / สี (`status.ts`) |
|---|---|
| fulfillment `READY_TO_PICK` | success |
| `PICKING`/`PACKED` | info |
| `SHIPPED` | neutral |
| `DELIVERED` | success |
| `UNFULFILLED` | muted |
| payment `PENDING` | warning |
| `PAID` | success |
| `COD_PENDING` | violet |
| `PARTIALLY_REFUNDED` | warning |
| `REFUNDED` | muted |

---

## `#/orders/holds` — Hold queue (`HoldQueuePage`)

- **API:** `GET /api/v1/orders/holds`
- **แสดง:** hold reason badge (ไทย), order display status
- **Empty/error:** เหมือน orders list

---

## `#/orders/:id` — Order detail (`OrderDetailPage`)

- **API:** `GET /api/v1/orders/{id}`, `POST .../cancel-requests`, `POST .../hold-rechecks`
- **ส่วน:** หัวสถานะ, บรรทัด, reservations, shipments (ถ้ามี), timeline (`Timeline`)
- **Status:** order/payment/fulfillment/hold badges แยกมิติ
- **Error:** alert + retry; cancel/hold-recheck แสดง API error

---

## `#/channel/listings` — Listings (`ListingsPage`)

- **Hash params:** `channel_account_id`, `mapped` (`true`/`false`/`all`, default **unmapped**), `removed=true`, `q`, `offset`
- **API:** `GET /api/v1/channel-accounts`, `GET /api/v1/channel-listings`, `PUT .../mapping`, `DELETE .../mapping`, `POST .../listing-syncs`
- **ส่วน:** filter บัญชี, select mapped/unmapped/all, **chip removed** (`aria-pressed` toggle — เปิดแล้วบังคับ `mapped=all`), ค้นหา seller sku/name, ตาราง, ปุ่ม sync, dialog แมป SKU
- **Sync result (ไทยใน UI):** แสดงจำนวน inserted/updated/removed + `ReevalSummary` (released/outOfStock/stillHeld/deferred)
- **Warning:** `removal_skipped` เมื่อ sync guard บล็อกการ mark removed มากเกิน (`max-removal-ratio`)
- **Removed:** chip toggle กรอง `removed=true`; แถวที่ `removed_at` มี chip “removed” และ **ซ่อน**ปุ่ม Map/Unmap (`!row.removed_at`)
- **Empty:** ไม่มี listing ตาม filter; **error:** `listingsMessage` + `AlertBanner`

---

## `#/catalog/products` — Products (`ProductsPage`)

- **API:** `/api/v1/products`
- **เขียน:** OWNER/ADMIN, ไม่ใช่ GRACE
- **Status:** `product` ACTIVE/INACTIVE (`status.ts`)

---

## `#/catalog/skus` — SKU list (`SkuListPage`)

- **API:** `GET /api/v1/skus`
- **ลิงก์:** form, history

---

## `#/catalog/skus/new` | `#/catalog/skus/:id` — SKU form (`SkuFormPage`)

- **API:** CRUD SKU, `PUT .../components` สำหรับ bundle
- **Empty/error:** validation จาก API

---

## `#/catalog/skus/:id/history` — Stock history (`StockHistoryPage`)

- **API:** `GET /api/v1/skus/{id}/stock-history`
- **แสดง:** ledger reasons, running balance
- **Empty:** ไม่มี movement

---

## `#/catalog/import` — Import (`ImportPage`)

- **API:** `POST /api/v1/catalog/import` (multipart CSV)
- **Error:** รายงานแถวผิดจาก API

---

## `#/warehouses` — Warehouses (`WarehousesPage`)

- **API:** `/api/v1/warehouses`
- **เขียน:** OWNER/ADMIN

---

## `#/stock/documents` — Stock documents list (`StockDocumentsPage`)

- **API:** `GET /api/v1/stock-documents`
- **Status:** `DRAFT` warning, `POSTED` success, `VOID` muted
- **สิทธิ:** `stockAccess(me)`

---

## `#/stock/documents/:id` — Document detail (`StockDocumentPage`)

- **API:** lines, post, void, start-count
- **Error:** stock document error handler codes

---

## `#/admin/outbox` — Dead outbox (`OutboxAdminPage`)

- **ผู้ใช้:** OWNER/ADMIN
- **API:** `GET /api/v1/outbox`, `POST /api/v1/outbox/{id}/retry`
- **Status:** `DEAD` danger, `PENDING` warning, `SENT` success
- **GRACE:** read-only (ไม่ retry)
