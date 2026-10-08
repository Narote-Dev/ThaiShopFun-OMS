# Frontend pages

อ้างอิง main @ ee4e425 (Flyway V13)

แอป React (Vite) ใช้ **hash routing** (`#/...`). สถานะสีและ label มาจาก [`frontend/src/ui/status.ts`](../../frontend/src/ui/status.ts); ภาพรวม UI ดู [DESIGN-SYSTEM.md](../ui/DESIGN-SYSTEM.md).

## Auth flow (mock TSF → OMS)

1. ผู้ใช้เปิด UI (`localhost:5173`) → `LoginPage` → Sign in
2. `AuthProvider` เรียก OIDC ที่ `http://localhost:8090/tsf-idp` (`client_id=oms-web`, PKCE)
3. Mock IdP แสดงตัวเลือกร้าน/ผู้ใช้ (หรือ `login_hint`) แล้ว redirect กลับพร้อม code
4. แลก token → เรียก `GET /api/v1/me` → แสดง `AppLayout`

**ตัวเลือก mock ที่ใช้ทดสอบ entitlement** (ดู root `README.md`):

| login hint | ทดสอบอะไร |
|---|---|
| `owner-active` | `ACTIVE` — ใช้งานครบ |
| `owner-grace` | `GRACE` — อ่านได้, POST ถูก `403 ENTITLEMENT_GRACE` |
| `owner-suspended` | `SUSPENDED` → Paywall |
| `owner-expired` | `ACTIVE` แต่ `expires_at` ผ่านแล้ว → `403 ENTITLEMENT_INACTIVE` |

(ชื่อใน picker อาจเป็น Active / Grace / Bump / Suspended / Expired — map ไปสถานะเดียวกัน)

## Routes

| Hash route | หน้า | ผู้ใช้ | API หลัก | หมายเหตุ UI |
|---|---|---|---|---|
| `#/` | `DashboardPage` | ทุก role | — | ภาพรวมชื่อร้าน |
| `#/orders` | `OrdersListPage` | ทุก role | `GET /api/v1/orders` | แท็บ all/ready/hold/cancelled; filter ใน query hash |
| `#/orders/holds` | `HoldQueuePage` | ทุก role | `GET /api/v1/orders/holds` | คิว hold |
| `#/orders/:id` | `OrderDetailPage` | ทุก role | `GET /api/v1/orders/:id`, cancel/hold-recheck | timeline, reservations |
| `#/channel/listings` | `ListingsPage` | ทุก role | listings API ใน `channel/listings/api.ts` | แมป SKU, sync |
| `#/catalog/products` | `ProductsPage` | ทุก role (เขียน OWNER/ADMIN) | `/api/v1/products` | GRACE = read-only |
| `#/catalog/skus` | `SkuListPage` | เหมือนกัน | `/api/v1/skus` | |
| `#/catalog/skus/new` / `#/catalog/skus/:id` | `SkuFormPage` | เหมือนกัน | SKU CRUD + components | |
| `#/catalog/skus/:id/history` | `StockHistoryPage` | ทุก role | `GET /api/v1/skus/:id/stock-history` | |
| `#/catalog/import` | `ImportPage` | OWNER/ADMIN | `POST /api/v1/catalog/import` | CSV |
| `#/warehouses` | `WarehousesPage` | OWNER/ADMIN เขียน | `/api/v1/warehouses` | |
| `#/stock/documents` | `StockDocumentsPage` | ตาม `stockAccess` | `/api/v1/stock/documents` | |
| `#/stock/documents/:id` | `StockDocumentPage` | ตาม `stockAccess` | document lines, post/void | |
| `#/admin/outbox` | `OutboxAdminPage` | OWNER/ADMIN | `GET/POST /api/v1/outbox` | Dead outbox retry |

### `#/orders` — params และแท็บ

Query ใน hash: `fulfillment_status`, `order_status`, `payment_status`, `hold_reason`, `channel`, `q`, `ordered_from`, `ordered_to`, `cursor`.

| Tab | filter |
|---|---|
| `all` | ไม่บังคับ hold |
| `ready` | fulfillment พร้อมหยิบ |
| `hold` | `hold_reason` ≠ `NONE` |
| `cancelled` | `order_status=CANCELLED` |

**Status แสดงผล:** `resolveOrderDisplayStatus` — CANCELLED / ON HOLD / fulfillment badge (`status.ts`). Hold reason ภาษาไทย: `holdReasonLabelTh` (`HOLD_REASON_TH`).

**Empty / error:** `AlertBanner` +ข้อความจาก `ordersMessage`; โหลดครั้งแรกแสดง `role="status"`.

### Listings / catalog / stock

- Listings: filter ตาม `ListingsPage` (ดู `listingsPages.test.tsx`); สิทธิ์ `channel/listings/access.ts`
- Catalog write: `canWriteCatalog(me)` และไม่ใช่ GRACE
- Stock post: `stockAccess(me)` ใน `stock/access.ts`

## Shared UI (`frontend/src/ui`)

| Component | บรรทัดเดียว |
|---|---|
| `AppShell` / `PageHeader` / `PageContent` | layout หลัก — ดู DESIGN-SYSTEM |
| `Button`, `Input`, `Select`, `Checkbox`, `Textarea`, `Label` | form controls |
| `Card`, `Dialog`, `Sheet`, `Tabs` | โครงหน้า |
| `DataTable`, `TablePager`, `FilterBar` | ตารางและ filter |
| `StatusBadge`, `OrderDisplayBadge`, `HoldReasonBadge` | สถานะจาก `status.ts` |
| `Badge`, `Alert`, `Toast` | feedback |
| `Timeline` | order history |
| `format.ts` | เงิน/เวลาไทย |
| `ComingSoon` | ปุ่ม placeholder |

## Shell

| Component | หน้าที่ |
|---|---|
| `LoginPage` | ปุ่ม sign-in |
| `Paywall` | entitlement ไม่ผ่าน |
| `SettingUp` | JIT รอ provision |
| `GraceBanner` / read-only notice | GRACE |
| `RequireSession` | gate login |
