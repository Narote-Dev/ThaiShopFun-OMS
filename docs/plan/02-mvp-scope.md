# 2. MVP Scope

## เป้าหมาย MVP (ประโยคเดียว)
ร้านสมาชิก TSF เข้า OMS ด้วยบัญชีเดิม (SSO) แล้วจัดการออเดอร์ TSF ครบวงจร: จองตอน checkout → รับออเดอร์ → แพ็ก → label → ส่ง → คืน → กระทบยอด **โดยไม่มีออเดอร์ TSF ที่ขายเกิน** และเปิดใช้แบบค่อยเป็นค่อยไป (shadow ก่อน control)

## ปรับทิศจาก research (ตัดสินใจแล้ว)
- Research เสนอ B2B/D365 เป็นแกน MVP → **เลื่อนเป็น paid add-on ทีหลัง** เพราะ OMS เป็นสิทธิ์สมาชิก TSF ฐานลูกค้ามีอยู่แล้ว
- Research เสนอ Next.js/Prisma → **React + Vite + TS / Spring Boot 4.1 (Java 17) / PostgreSQL / Flyway / JWT**
- Research เสนอเริ่ม Lazada → **เริ่ม TSF** first-party ไม่ต้องรอ approval และจองสต๊อกแบบ strong ได้

## In scope (MVP = Phase 0–4)
| หมวด | ของที่ทำ |
|---|---|
| Foundation | invariants + NFR, contracts (OpenAPI/AsyncAPI) + contract test ใน CI, RLS (FORCE), inbox/outbox at-least-once |
| Auth/Tenant | SSO ด้วย JWT จาก TSF, 1 TSF shop = 1 tenant, gate ตาม membership, role `OWNER/ADMIN/STAFF` |
| Catalog | product, SKU, bundle (ไม่ซ้อน), import CSV, map SKU ↔ TSF listing |
| Inventory | reservation engine (CHECKOUT/ORDER), stock operations ครบ (opening, รับเข้า, ปรับ, นับ, ตัดเสีย, restock), ledger + หน้าประวัติ |
| TSF integration | Checkout Reservation API, order intake, สถานะ 3 มิติ + hold, backfill, stock push, TSF adapter บน base adapter + capabilities |
| Fulfillment | pick list, แพ็กยิงบาร์โค้ด, label จาก TSF, ยืนยันส่ง, cancel, return บางชิ้น, refund (อ่าน), payment snapshot, reconciliation |
| Rollout | connection mode OBSERVE → SHADOW → CONTROL → ACTIVE, emergency controls, monitoring, DR test |
| Security/PII | แยกตาราง PII, เข้ารหัส phone/address, mask log/audit, ลบตามอายุ (ดู NFR.md) |

## Out of scope (MVP)
- Shopee / Lazada / TikTok adapter (Phase 5 หลังได้ approval)
- B2B / ขายส่ง / D365 F&O connector → **paid add-on อนาคต**
- WMS เต็ม (location, lot/FEFO), หลายคลังพร้อม allocation rule
- ขนส่งตรง (Flash/Kerry/J&T) ใช้ label จากช่องทางเท่านั้น
- ใบกำกับภาษี, บัญชี settlement/ค่าธรรมเนียม, dashboard กำไร
- แชท/ไลฟ์, POS, ระบบตัวแทน, mobile app
- Split shipment (1 ออเดอร์ = 1 shipment), nested bundle

## Channel Connection Mode (shadow ก่อนคุมจริง)
ทุก `channel_account` มี `mode` เปลี่ยนได้ทีละขั้น (ถอยได้ทุกขั้น)

| Mode | OMS อ่าน | OMS คำนวณ | OMS เขียนไปช่องทาง | Checkout reserve (TSF) |
|---|---|---|---|---|
| **OBSERVE** | ✅ orders, listings, สต๊อกช่องทาง | ❌ | ❌ | ไม่เรียก |
| **SHADOW** | ✅ | ✅ จอง/คำนวณ exposed เต็ม | ❌ เก็บเป็น "would push" เทียบกับของจริง | เรียก แต่ `enforced=false` TSF ใช้สต๊อกตัวเอง |
| **CONTROL** | ✅ | ✅ | ✅ เฉพาะ SKU ใน allowlist (`stock_control=true`) | `enforced=true` เฉพาะ SKU allowlist |
| **ACTIVE** | ✅ | ✅ | ✅ ทุก SKU + fulfillment ผ่าน OMS | `enforced=true` ทั้งหมด |

**PO decision 2026-10-01 (T12 intake):** โหมด OBSERVE, ช่องทาง DISCONNECTED และ tenant ที่ OMS ไม่คุมสต๊อก — ไม่บังคับ ORDER reservation ใน guard `READY_TO_PICK`; COD/PAID ไป `READY_TO_PICK` ได้เมื่อ `hold_reason=NONE` แม้ไม่มีแถว `stock_reservation`.

**เกณฑ์เลื่อนขั้น:** SHADOW → CONTROL เมื่อ diff (OMS vs ของจริง) < 0.5% ติดกัน 7 วัน และไม่มี DEAD event; CONTROL → ACTIVE เมื่อ allowlist ≥ 50% ของ SKU 7 วันไม่มี business oversell

**Emergency controls** (OWNER ของร้าน + platform admin, ลง audit ทุกครั้ง)
| ปุ่ม | ผล |
|---|---|
| **PAUSE STOCK SYNC** | หยุด push สต๊อกไปช่องทางนั้น (reservation ยังทำงาน) + ช่องทางแสดงค่าล่าสุด → เตือนบน dashboard ทุกนาทีที่ pause |
| **DISCONNECT CHANNEL** | หยุดทุก sync เข้า-ออก, checkout reserve ตอบ `enforced=false`, ข้อมูลเดิมยังอยู่ |
| **FORCE FULL RESYNC** | ดึงออเดอร์ย้อนหลังตามช่วงที่เลือก + คำนวณและ push `channel_exposed` ทุก listing ใหม่ |
| Global kill switch (platform admin) | PAUSE STOCK SYNC ทุก tenant พร้อมกัน |

## Pilot progression (Phase 4)
| ขั้น | ใคร | Mode | ระยะ | ผ่านเมื่อ |
|---|---|---|---|---|
| 1 | ร้านทดสอบภายใน (TSF staging → prod) | OBSERVE → SHADOW → CONTROL → ACTIVE เร็ว | 1 สัปดาห์ | E2E ผ่านบน prod, DR drill ผ่าน |
| 2 | 1–2 ร้านที่คุ้นเคย | OBSERVE → **SHADOW** | 2 สัปดาห์ | diff < 0.5% 7 วัน |
| 3 | ร้านเดิม | **CONTROL** (allowlist 20% → 50%) | 2 สัปดาห์ | ไม่มี business oversell, lag ตาม NFR |
| 4 | ขยายเป็น 3–5 ร้าน | SHADOW 1 สัปดาห์ → CONTROL/ACTIVE | 2 สัปดาห์ | support ticket < 1/ร้าน/สัปดาห์ |
| 5 | ขยายเป็น 5–10 ร้าน | ACTIVE | 2 สัปดาห์+ | ครบ NFR 14 วัน → เปิดให้สมาชิกทั่วไป |

## ลำดับเพิ่ม marketplace (ตัดสินใจแล้ว)
**ยื่นขอ partner ทั้ง 3 เจ้าสัปดาห์แรก** แล้ว build ตามลำดับ **Shopee → TikTok Shop → Lazada** เจ้าไหนอนุมัติก่อนทำก่อนได้ (ยืนยันกับยอดขายร้านสมาชิก TSF จริงอีกที)

| Channel | ต้องมีอะไรก่อน (จาก research) | ระยะรอ |
|---|---|---|
| **ThaiShopFun** | first-party ใช้ internal API + event | 0 |
| **Shopee** | Third-party Partner, นิติบุคคล/license, product URL HTTPS (TLS1.2), trial account ให้ตรวจ | review ~10 วันทำการ |
| **TikTok Shop** | Partner Center แบบ ISV, public app ต้องผ่าน app review | ไม่ระบุ เผื่อ 2–4 สัปดาห์ |
| **Lazada** | ขอ app category ใน App Console แนบเหตุผล/เอกสาร | ไม่ระบุ เผื่อ 2–4 สัปดาห์ |

ทุกเจ้าต้องมี **หน้า product ของ OMS ที่เปิดสาธารณะ (HTTPS)** + บัญชี demo ให้ reviewer → เตรียมใน Phase 3

## Phases + Timeline
| Phase | สัปดาห์ | ผลลัพธ์ | Exit criteria |
|---|---|---|---|
| **0 Foundation** | 1–3 | invariants, NFR, repo/CI, contracts, PG + FORCE RLS, auth/tenant, inbox/outbox, mock TSF | login ผ่าน mock TSF, RLS leak test ผ่าน, chaos test inbox/outbox ผ่าน |
| **1 Catalog + Inventory** | 4–6 | product/SKU/bundle, warehouse, ledger, stock operations, reservation engine (CHECKOUT/ORDER), stress test | concurrency + bundle stress ผ่านทุกเคส, ledger = inventory |
| **2 TSF Order Integration** | 7–10 | Checkout Reserve API, order intake, สถานะ 3 มิติ, SKU mapping, backfill, stock push, base adapter + TSF adapter, orders UI, PII | ออเดอร์จาก mock TSF ครบ ไม่ซ้ำ, reserve p95 ตาม NFR |
| **3 Fulfillment** | 11–13 | pick/pack/label/ship, cancel, return บางชิ้น, refund, payment snapshot, reconciliation | E2E เต็ม flow ผ่านกับ mock TSF |
| **4 Pilot** | 14–22 | modes + emergency controls, monitoring, load test, DR test, 1 → 2 → 5 → 10 ร้าน | ตาราง pilot ข้างบนครบ |
| **5 Multichannel** | เริ่มได้ ~14 (framework) / adapter หลัง approval | capability framework, allocation strategy, Shopee → TikTok → Lazada, cross-channel race/drift test | adapter ละ ~3–4 สัปดาห์, business oversell < 0.1% |
| **Future (paid)** | — | B2B/D365 F&O connector, WMS เบา, ใบกำกับภาษี | — |

**ขึ้นกับฝั่ง TSF:** Phase 2 ต้องมี TSF-01..04 + TSF-07 บน staging (ดู 05-task-list) ไม่พร้อมก็ใช้ mock TSF ต่อได้ แต่ Phase 4 เริ่มไม่ได้
