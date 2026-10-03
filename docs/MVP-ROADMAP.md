# ThaiShopFun OMS · MVP-to-Launch Roadmap

*ณ 3 ต.ค. 2026 · อิง `main` ที่ `02597ac` (PR #21) · เอกสารนี้สรุปและชี้ไปที่ [`docs/plan/`](plan/plan.md) ไม่ได้แทนที่*

> **วิธีอ่าน:** ตารางทุกตารางอ่านแยกกันได้ อะไรที่ยังไม่มีใน repo หรือยังไม่มีคนตัดสินใจ เขียนว่า **TBD**
> ขนาดงาน S/M/L เป็นค่าประมาณคร่าวๆ ของทีม (S ≈ PR เล็ก, M ≈ 1 PR เต็ม, L ≈ อาจต้องแตกหลาย PR) ไม่ใช่วันที่ส่งงาน

---

## 0. ศัพท์ที่ใช้บ่อย (อธิบายสั้นๆ)

| คำ | แปลว่า |
|---|---|
| **OMS** (Order Management System) | ระบบหลังบ้านที่รวมออเดอร์จากทุกช่องทางมาไว้ที่เดียว แล้วคุมสต๊อก แพ็ก ส่ง คืนของ |
| **Channel / ช่องทาง** | ที่ที่ร้านขายของ เช่น ThaiShopFun (TSF), Shopee, Lazada, TikTok Shop |
| **Tenant** | 1 ร้าน = 1 tenant ข้อมูลแต่ละร้านแยกกันเด็ดขาด |
| **Membership entitlement** | สิทธิ์ใช้ OMS มาจากสมาชิก TSF ที่จ่ายเงินแล้ว ไม่ได้ซื้อแยก |
| **Reservation (จองสต๊อก)** | กันของไว้ให้ลูกค้าที่กำลัง checkout หรือมีออเดอร์แล้ว คนอื่นซื้อชิ้นนั้นไม่ได้ |
| **Oversell (ขายเกิน)** | ขายได้มากกว่าของที่มีจริง ร้านต้องยกเลิกออเดอร์ = เสียลูกค้า |
| **Hold** | ออเดอร์ที่ติดปัญหา ยังแพ็กไม่ได้ เช่น `SKU_NOT_MAPPED` (ไม่รู้ว่าสินค้าในช่องทางคือ SKU ไหน), `OUT_OF_STOCK` |
| **SKU mapping** | จับคู่ "สินค้าในหน้าร้านช่องทาง" กับ "SKU ในคลังของ OMS" |
| **Pick / Pack / Ship** | หยิบของจากชั้น → แพ็กใส่กล่อง (ยิงบาร์โค้ดเช็ก) → พิมพ์ label แล้วส่งให้ขนส่ง |
| **RTS** (Return to Sender) | พัสดุตีกลับ ส่งไม่ถึงลูกค้า |
| **Reconciliation (กระทบยอด)** | เทียบข้อมูล OMS กับช่องทางทุกคืน หาจุดที่ไม่ตรงกัน |
| **Inbox / Outbox** | คิวรับ event เข้า / ส่ง event ออก แบบ "ส่งซ้ำได้ แต่ผลลัพธ์ไม่ซ้ำ" (at-least-once + idempotent) |
| **RLS** (Row-Level Security) | กฎใน PostgreSQL ที่บังคับว่าแต่ละ query เห็นแค่ข้อมูลร้านตัวเอง |
| **Connection mode** | ระดับที่ OMS คุมช่องทาง: OBSERVE (ดูอย่างเดียว) → SHADOW (คำนวณคู่ขนานแต่ไม่บังคับ) → CONTROL (คุมบาง SKU) → ACTIVE (คุมทั้งหมด) |
| **PITR** | Point-in-time recovery: กู้ฐานข้อมูลย้อนกลับไปเวลาใดก็ได้ในช่วงที่เก็บไว้ |
| **PDPA** | กฎหมายคุ้มครองข้อมูลส่วนบุคคลของไทย ชื่อ/เบอร์/ที่อยู่ผู้รับเป็นข้อมูลส่วนบุคคล |

---

## 1. สรุปภาพรวม + นิยาม MVP ที่ "พร้อมขาย"

### ภาพรวม
- ThaiShopFun OMS เป็น OMS แบบ multichannel (แนวเดียวกับ Oasys)
- **ช่องทางแรกคือ ThaiShopFun เอง** (first-party) ใช้ login เดียวกับ TSF (JWT/SSO) ไม่ต้องรอใครอนุมัติ
- สิทธิ์ใช้ OMS = **membership entitlement** ของสมาชิก TSF
- หลัง MVP ค่อยต่อ Shopee / Lazada / TikTok Shop (Phase 5) ลำดับใน plan คือ Shopee → TikTok Shop → Lazada ([02-mvp-scope](plan/02-mvp-scope.md))
- **เงินอยู่ที่ ThaiShopFun Pay** (Xendit xenPlatform / Opn) OMS **อ่านสถานะการจ่ายเงินอย่างเดียว** ไม่แตะเงิน
- B2B / D365 connector = **paid add-on ทีหลัง** ไม่อยู่ใน MVP
- เป้า MVP ในประโยคเดียว (จาก [02-mvp-scope](plan/02-mvp-scope.md)): ร้านสมาชิก TSF เข้า OMS ด้วยบัญชีเดิม แล้วจัดการออเดอร์ TSF ครบวงจร **โดยไม่มีออเดอร์ TSF ที่ขายเกิน** และเปิดใช้แบบค่อยเป็นค่อยไป

### Stack (ตรวจจาก repo แล้ว)
| ส่วน | ใช้อะไร |
|---|---|
| Frontend | React + Vite + TypeScript (Vitest, Playwright, ESLint) |
| Backend | Java 17, Spring Boot 4.1, Maven wrapper, Spotless |
| DB | PostgreSQL 16, **FORCE RLS**, runtime role `oms_app` (NOBYPASSRLS), Flyway V1–V11 (V5 จองไว้ ห้ามใช้) |
| Auth | JWT จาก TSF (OIDC PKCE ฝั่งเว็บ, client credentials ฝั่ง `/internal/**`) |
| Local TSF | `mock-tsf/` (IdP, REST 4.7, webhook, control API) |
| Contracts | `contracts/` ใน repo นี้ (OpenAPI 3.1 + JSON Schema + examples) แทน repo `tsf-oms-contracts` ที่ยังไม่ได้สร้าง |
| Hosting | **ยังไม่มี** ตอนนี้ localhost อย่างเดียวตามที่ owner ตัดสินใจ |

### นิยาม "พร้อมขาย" (Definition of Done สำหรับ launch)
เปิดให้สมาชิกทั่วไปได้เมื่อ **ครบทุกข้อ**:

| # | เงื่อนไข | มาจาก |
|---|---|---|
| 1 | งาน MVP Phase 0–4 ครบ (39 task ใน [05-task-list](plan/05-task-list.md)) | 05 |
| 2 | E2E เต็ม flow ผ่านใน CI: reserve → created → paid → pick → pack → label → ship → delivered → คืนบางชิ้น → refund + cancel ตอน PACKED (T19) | 05 |
| 3 | TSF ฝั่งจริงทำ TSF-01…09 เสร็จบน staging และ prod | 05 |
| 4 | มี prod env จริง (HTTPS, secret ไม่อยู่ใน git, PITR เปิด, alert ทำงาน) (T26) | 05, NFR |
| 5 | DR drill ผ่าน RPO ≤ 1 นาที, RTO ≤ 60 นาที (T42) | NFR |
| 6 | Load test ผ่านทุกตัวเลขใน NFR ที่ 2× peak (T43) | NFR |
| 7 | Security review ผ่าน: FORCE RLS ทุกตาราง, ไม่มี PII ใน log, dependency scan (T25) | 05 |
| 8 | Pilot ครบ 5 ขั้น และ "ครบ NFR 14 วัน" ที่ 5–10 ร้าน | 02 |
| 9 | Production readiness checklist (หัวข้อ 5) ติ๊กครบ รวม repo เป็น private | เอกสารนี้ |
| 10 | Terms / Privacy policy / คู่มือผู้ใช้ / ช่องทาง support พร้อม (หัวข้อ 6) | เอกสารนี้ |

---

## 2. สถานะปัจจุบัน

### ภาพรวมตัวเลข (MVP = Phase 0–4 = 39 task)
| สถานะ | จำนวน | task |
|---|---|---|
| ✅ เสร็จ + merge แล้ว | 18 | T01, T02, T03, T04, T05, T06, T07, T08, T08A, T10, T11, T12, T12A, T13, T14, T14B, T16, T17 |
| 🟡 ทำแล้วบางส่วน | 2 | T01C (contracts ใน repo มีแล้ว ยังขาด AsyncAPI, `oasdiff`, repo แยก), T27 (เข้ารหัส PII + `phone_hash` มากับ T10 แล้ว ส่วนอื่นยังไม่ทำ) |
| 🔨 กำลังทำ | 1 | T12B |
| ⬜ ยังไม่เริ่ม | 18 | T00, T09, T12C, T15, T18, T19, T20, T21, T22, T23, T24, T25, T26, T28, T40, T41, T42, T43 |

### งานที่เสร็จแล้ว (ตรวจกับ `git log` และ closed PRs: merge ทั้งหมด 20 PR, ไม่มี PR #2)
| Task | PR | สิ่งที่ได้ |
|---|---|---|
| T01 | [#1](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/1) | Monorepo skeleton (backend/frontend), docker-compose, CI, config staging Railway (ยังไม่เคย deploy จริง) |
| T02 | [#3](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/3) | Flyway V1: ตารางพื้นฐาน, FORCE RLS, roles `oms_migrator` / `oms_app` / `oms_maint` |
| T03 | [#4](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/4) | JWT auth, TenantContext, RLS ต่อ transaction, entitlement gate, JIT provisioning (V2) |
| T14 | [#5](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/5) | Outbox publisher at-least-once (lease, backoff, DEAD, หน้า admin retry) |
| T11 | [#6](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/6) | Inbox รับ event จาก TSF (HMAC, dedupe, advisory lock) + V3 |
| T05 | [#7](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/7) | Mock TSF (IdP, REST 4.7, ยิง event, control API) + `contracts/` ชุดแรก |
| T04 | [#8](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/8) | Frontend shell + SSO (OIDC PKCE, token ใน memory, paywall) |
| T06 | [#9](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/9) | Flyway V4: catalog, warehouse, stock (FORCE RLS) |
| T14B | [#10](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/10) | Chaos test inbox/outbox 1,000 event ต่อทิศ (ไม่หาย, ผลไม่ซ้ำ) |
| T08 | [#11](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/11) | Reservation engine (reserve, transfer, release, consume, expiry) + V6 |
| T07 | [#12](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/12) | Catalog + warehouse API, CSV import, UI ([docs/api/catalog.md](api/catalog.md)) |
| T10 | [#13](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/13) | Flyway V7 ฝั่งออเดอร์ + เข้ารหัส PII ผู้รับ (AES-256-GCM) |
| T08A | [#14](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/14) | เอกสารสต๊อก (opening/receive/adjust/count/write-off) + หน้าประวัติ + V8 ([docs/api/stock-documents.md](api/stock-documents.md)) |
| T12A | [#15](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/15) | Checkout Reserve API (idempotent, `enforced` ตาม mode) + V9 + Gatling perf job ใน CI |
| T16 | [#16](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/16), [#18](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/18) | `ChannelAdapter` + Resilience4j base + TSF adapter + V10 |
| T12 | [#17](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/17) | รับออเดอร์, `OrderStateMachine` 3 มิติ, โอน reservation CHECKOUT→ORDER |
| T13 | [#20](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/20) | State matrix test ครอบทุก transition + T12 follow-ups |
| T17 | [#19](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/19), [#21](https://github.com/Narote-Dev/ThaiShopFun-OMS/pull/21) | Orders UI (list, detail + timeline, hold queue, ขอยกเลิก) + V11 ([docs/api/orders.md](api/orders.md)) |

### งานที่กำลังทำ
| Task | สถานะ | หมายเหตุ |
|---|---|---|
| **T12B** SKU mapping + ปล่อย hold | 🔨 กำลังทำ (ยังไม่มี PR บน `main`) | sync listings, auto-map `seller_sku = sku_code`, หน้า map มือ, ปล่อย `SKU_NOT_MAPPED` แล้วจองใหม่ (late mapping) ของไม่พอ → `OUT_OF_STOCK` |

---

## 3. Roadmap ที่เหลือทีละ phase

> รายละเอียด AC เต็มอยู่ใน [05-task-list](plan/05-task-list.md) ตารางนี้ย่อแค่ "acceptance หลัก"
> ลำดับ = ลำดับที่แนะนำให้ทำ (เลขเดียวกัน = ทำคู่ขนานได้)

### Phase 0–1 ที่ค้าง (ทำแทรกได้ทุกเมื่อ)
| ลำดับ | Task | ผู้ทำ (ตาม plan) | Deps | ขนาด | Acceptance หลัก |
|---|---|---|---|---|---|
| แทรก | **T00** Invariants + NFR | Codex | — | M | มี `InvariantChecker` เรียกจากทุก test ได้ · ทำ invariant พังโดยตั้งใจแล้ว test fail · NFR ทุกบรรทัดมีค่าหรือ TBD |
| แทรก | **T01C** Contracts (ส่วนที่ขาด) | Codex | — | S–M | เพิ่ม AsyncAPI 3 · `oasdiff` breaking check (ลบ field required → CI fail) · ย้ายไป repo `tsf-oms-contracts` เมื่อ TSF พร้อม (หัวข้อ 4) |
| 4 | **T09** Concurrency + bundle stress | Codex | T08 ✅ | M | 7 เคสใน 05 ผ่าน เช่น {A,B} กับ {B,A} 1,000 คู่ = 0 deadlock · 10,000 op สุ่มแล้ว InvariantChecker ผ่าน (ต้องมี T00 ก่อนจะดีที่สุด) |

### Phase 2: TSF Order Integration (เหลือ)
| ลำดับ | Task | ผู้ทำ | Deps | ขนาด | Acceptance หลัก |
|---|---|---|---|---|---|
| 1 | **T12B** SKU mapping + hold resolution | Cursor | T12 ✅, T16 ✅ | M | map แล้วออเดอร์ `SKU_NOT_MAPPED` ถูกจองและปล่อยครบ · ของไม่พอ → `OUT_OF_STOCK` |
| 2 | **T12C** Backfill + gap fetch | Codex | T12 ✅, T16 ✅ | M | ปิด webhook ใน mock แล้วสร้าง 50 ออเดอร์ → เข้าครบใน 15 นาที ไม่ซ้ำ · cursor เดินต่อหลัง restart |
| 2 | **T15** Stock push | Cursor | T08 ✅, T14 ✅, T16 ✅ | M–L | จอง 5 ครั้งใน 1 วิ = ส่ง 1 event · bundle ถูก push ตาม component · pause แล้วไม่มี event ออก · p95 < 10 วิ |
| 3 | **T27** PII lifecycle (ส่วนที่เหลือ) | Codex | T10 ✅ | M | logback masking · redaction job (ปิด + 90 วัน) · scrub payload inbox/outbox 7 วัน · grep log ไม่เจอเบอร์/ที่อยู่ · หมุน key แล้วอ่านของเก่าได้ |
| 4 | **T09** (ดูด้านบน) | Codex | T08 ✅ | M | |

**Exit Phase 2:** ออเดอร์จาก mock TSF เข้าครบ ไม่ซ้ำ, reserve p95 ตาม NFR

### Phase 3: Fulfillment
| ลำดับ | Task | ผู้ทำ | Deps | ขนาด | Acceptance หลัก |
|---|---|---|---|---|---|
| 1 | **T18** Pick / Pack / Label / Ship | Cursor | T12 ✅, T16 ✅, **T27** | L | ยิงผิด/เกิน = ไม่ให้ PACKED · ขอ label ซ้ำไม่ออกใหม่ · SHIPPED แล้ว `on_hand` ลด + reservation CONSUMED · 100 label ใน PDF เดียว · ออเดอร์ hold ไม่อยู่ใน pick list · หน้าแพ็กเป็นที่เดียวที่เห็นที่อยู่/เบอร์เต็ม (PO decision) |
| 1 | **T20** Cancel + Returns (บางชิ้น) | Cursor | T12 ✅, T08A ✅ | L | คืน 1 ใน 2 ชิ้น → ออเดอร์ยัง ACTIVE/DELIVERED · RESELLABLE → `RETURN_RESTOCK`, DAMAGED → `DAMAGE_WRITE_OFF` · คืนเกิน → 422 · ยกเลิกตอน PACKED → unpack |
| 1 | **T21** Payment + refund snapshot | Codex | T12 ✅, T16 ✅ | M | event ซ้ำไม่สร้างแถวซ้ำ · refund 295/580 → `PARTIALLY_REFUNDED` · ไม่มี Xendit/Opn SDK ใน OMS |
| 2 | **T23** Stock drift (TSF) | Codex | **T15**, T16 ✅ | S–M | diff → issue `STOCK_DRIFT` + re-push · ไม่แตะ `on_hand` |
| 3 | **T22** Reconciliation | Cursor | T18, T20, T21 | L | 8 กฎใน 01 §1.5 จับได้ 8/8 · รันซ้ำไม่สร้าง issue ซ้ำ · invariant พัง → alert |
| 4 | **T19** E2E | Codex | T18, T20 | M | Playwright + mock TSF ทั้ง flow < 5 นาทีใน CI · InvariantChecker ผ่านท้ายทุก scenario |
| แทรก | **T28** Public product page + demo tenant | Cursor | T04 ✅ | S–M | เปิดได้ไม่ต้อง login (HTTPS) · demo tenant ไม่มี PII จริง · **ต้องมีก่อนยื่น marketplace** และต้องมี hosting สาธารณะ |

**Exit Phase 3:** E2E เต็ม flow ผ่านกับ mock TSF

### Phase 4: Pilot
| ลำดับ | Task | ผู้ทำ | Deps | ขนาด | Acceptance หลัก |
|---|---|---|---|---|---|
| 1 | **T26** Prod deploy + observability + PITR | Cursor | T22 | L | prod deploy ต้องกดเอง (Narote) · JSON log + `trace_id` · alert เมื่อ DEAD > 0, inbox lag > 5 นาที, reserve p95 เกิน NFR · PITR healthy · **รอ Narote เลือก hosting** |
| 1 | **T40** Connection modes + emergency controls | Cursor | T12A ✅, T15, T22 | L | ข้ามขั้น mode ไม่ได้ · PAUSE มีผลใน 5 วิ · DISCONNECT → `enforced=false` · RESYNC ครบ · ยืนยัน 2 ขั้น + audit |
| 2 | **T41** Shadow diff report | Codex | T40, T23 | M | diff % รายวัน · เกณฑ์ < 0.5% 7 วันคำนวณถูก |
| 2 | **T24** Dashboard + audit viewer | Cursor | T17 ✅, T40 | M | ตัวเลขการ์ดตรง query · audit ไม่แสดง PII |
| 2 | **T25** Security review | Codex | T27, T40 | M | เพิ่มตารางไม่มี FORCE RLS → CI fail · PII scan · dependency scan |
| 3 | **T42** DR drill | Codex | T26 | M | RPO ≤ 1 นาที, RTO ≤ 60 นาที · runbook ที่คนอื่นทำตามได้ |
| 3 | **T43** Load test | Codex | T26, T12A ✅ | M | ผ่าน NFR ที่ 2× peak (reserve, webhook ingest, orders/min, stock updates/s) |
| 4 | **Pilot 5 ขั้น** | ทีม + ร้าน | ทั้งหมดข้างบน | — | ตาม [02 Pilot progression](plan/02-mvp-scope.md): ร้านภายใน → 1–2 ร้าน SHADOW → CONTROL → 3–5 ร้าน → 5–10 ร้าน ACTIVE ครบ NFR 14 วัน |

**Exit Phase 4 = เปิดขายทั่วไป** (ดูนิยามในหัวข้อ 1)

### Phase 5: Multichannel (หลัง MVP, เริ่ม framework ได้ระหว่าง pilot)
| ลำดับ | Task | ผู้ทำ | Deps | ขนาด | Acceptance หลัก |
|---|---|---|---|---|---|
| 1 | **T50M** Flyway: `allocation_policy`, `channel_allocation` | Codex | T10 ✅ | S | ผลรวม allocation ≤ physical − buffer · ⚠️ ใน 05 เขียนว่า "V5" แต่ V5 ห้ามใช้ → ต้องใช้เลขว่างถัดไป (ตอนนี้ V12+) |
| 1 | **T50** Capability framework | Codex | T16 ✅ | M | ช่องทางไม่มี webhook → poll ได้ · `supportsCancelRequest=false` → ซ่อนปุ่ม + API 422 |
| 2 | **T51** Allocation strategy | Cursor | T50M, T15 | M–L | last-units protection · HARD ไม่เกินโควตา |
| 2 | **T30** Connect channel UI (OAuth) + token refresh | Cursor | T50 | M | token เข้ารหัส · refresh ก่อนหมด · เริ่ม OBSERVE เสมอ |
| 3 | **T31** Shopee adapter | Codex | T30, T51, **Shopee approval** | L | capabilities ตรง docs จริง · sandbox ผ่าน · ผ่าน shadow ก่อน control |
| 3 | **T32** TikTok Shop adapter | Codex | T30, T51, **TikTok approval** | L | เหมือนข้างบน |
| 3 | **T33** Lazada adapter | Codex | T30, T51, **Lazada approval** | L | เหมือนข้างบน |
| 4 | **T34** Cross-channel race | Codex | T31, T51 | M | สต๊อกไม่ติดลบ · HARD = 0 oversell · SHARED+protection < 0.1% |
| 4 | **T35** Multichannel drift + resync | Codex | T31 | M | drift ตั้งใจ → เจอใน 15 นาที + re-push |

**Future (paid add-on, ยังไม่มี task):** B2B / D365 F&O connector, WMS เบา, ใบกำกับภาษี → TBD

### Critical path (เส้นทางที่ช้าที่สุดถึง launch)
```
T12B ─┐
T15 ──┼─ T23 ─────────────┐
T27 ──┴─ T18 ─┐           │
T20 ──────────┼─ T22 ─ T40 ┼─ T41 ─┐
T21 ──────────┘     └ T26 ─┴─ T42, T43, T25, T24 ─ Pilot 5 ขั้น ─ Launch
                         ▲
              ต้องมี hosting + TSF-01…09 พร้อม
```

---

## 4. งานฝั่ง ThaiShopFun (TSF-xx) ที่ต้องทำคู่กัน

> OMS ใช้ `mock-tsf` แทนได้ถึง Phase 3 แต่ **Phase 4 (pilot) เริ่มไม่ได้** ถ้า TSF จริงยังไม่พร้อม ([02](plan/02-mvp-scope.md))
> สถานะฝั่ง TSF ไม่มีใน repo นี้ → ทุกแถวเป็น **TBD** จนกว่าจะยืนยันกับทีม TSF

| ID | ต้องพร้อมก่อน OMS task | งาน (สั้น) | สถานะ |
|---|---|---|---|
| TSF-01 | T04 (staging) | OIDC client `oms` + claims ตาม 4.1 + JWKS | TBD |
| TSF-02 | T12 | outbox + webhook ตาม 4.5 (รวม `reservation_id`, return lines, `refund.status_changed`) at-least-once | TBD |
| TSF-03 | T16 | internal REST ตาม 4.7 + client credentials + `Retry-After` | TBD |
| TSF-04 | T15 | รับ `stock.updated` / `shipment.updated` / `return.received` + dedupe `event_id` | TBD |
| TSF-05 | T18 | ออก label/tracking ผ่านขนส่งของ TSF (ถ้าไม่มี → ใบแพ็ก + กรอก tracking เอง, ตาม plan ข้อ 3) | TBD |
| TSF-06 | T03 | `membership.changed` + entitlement `oms` ต่อ tier | TBD |
| TSF-07 | T12A | checkout เรียก `POST /inventory/reservations` ก่อนสร้างออเดอร์, 409 → OUT_OF_STOCK, `DELETE` เมื่อทิ้ง checkout, ส่ง `reservation_id`, เคารพ `enforced` | TBD |
| TSF-08 | T01C | ใช้ contracts tag เดียวกับ OMS + contract test ใน CI ของ TSF | TBD |
| TSF-09 | T12A | timeout 800 ms + circuit breaker + fallback แบบ conditional fail-open + metric | TBD |

เพิ่มเติมที่ต้องมีเพื่อ launch แต่ยังไม่มี ID ใน 05 (เสนอ):
| เสนอ | งาน | เหตุผล |
|---|---|---|
| TSF-10 (เสนอ) | TSF Pay ส่ง `payment.status_changed` / `refund.status_changed` จาก Xendit/Opn ผ่าน TSF | T21 ต้องใช้ OMS อ่านอย่างเดียว |
| TSF-11 (เสนอ) | หน้า membership/billing ใน TSF ที่ขายแพ็กเกจที่มี OMS | หัวข้อ 6 |
| TSF-12 (เสนอ) | ลิงก์ "เข้า OMS" จากหลังบ้าน TSF + โดเมน/redirect URI prod | SSO prod |

### Contracts (T01C)
| เรื่อง | ตอนนี้ | ต้องทำ |
|---|---|---|
| ที่อยู่ spec | `contracts/` ใน repo นี้ (มากับ T05 PR #7) | สร้าง repo `tsf-oms-contracts` แล้วย้าย (ตาม [04 §4.9](plan/04-api-contract.md)) · เวลาที่จะย้าย = TBD |
| OpenAPI | `oms-checkout.yaml`, `tsf-internal.yaml` + Spectral lint ใน CI | ✅ |
| Event schema | JSON Schema 15 event + examples + `ContractExamplesTest` | ✅ |
| AsyncAPI 3 | ยังไม่มี | ⬜ |
| Breaking check (`oasdiff`) | ยังไม่มี | ⬜ |
| ฝั่ง TSF ใช้ spec เดียวกัน | TBD | TSF-08 |

กติกา: แก้ contract → แก้ spec ก่อนเสมอ แล้วค่อยแก้โค้ด (Definition of Done ใน 05)

---

## 5. Production readiness checklist

> ยังไม่มี env ไหนออนไลน์ ทุกข้อด้านล่างยังไม่ติ๊ก เว้นแต่เขียนว่า ✅

### 5.1 Security
| # | รายการ | สถานะ | อ้างอิง |
|---|---|---|---|
| S1 | **ทำ repo เป็น private ก่อนออนไลน์** (ตอนนี้ public) | ⬜ รอ Narote | context |
| S2 | Secrets อยู่ใน secret manager ของ host ไม่อยู่ใน git: `OMS_INBOX_HMAC_SECRETS`, `OMS_OUTBOX_WEBHOOK_SECRET`, `OMS_PII_KEYS`, `OMS_PII_ACTIVE_KEY_ID`, `OMS_PII_HASH_KEY`, DB passwords, client secret | ⬜ (dev ค่าอยู่ใน `application-local.yml` เท่านั้น) | README |
| S3 | สร้าง key prod ใหม่ทั้งหมด ห้ามใช้ค่า dev · มีแผนหมุน key (PII ring + HMAC หลายค่า) | ⬜ | README, NFR |
| S4 | FORCE RLS ทุกตาราง tenant + `oms_app` NOBYPASSRLS | ✅ ใน code/test · ⬜ CI guard (T25) | T02, T25 |
| S5 | **Flyway รันด้วย `oms_migrator`** ไม่ใช่ superuser ก่อน prod (ตอนนี้ dev ใช้ `oms` superuser) | ⬜ | README, backlog |
| S6 | Backend ไม่ยอม start ถ้า runtime role เป็น superuser/BYPASSRLS | ✅ | README |
| S7 | PDPA: PII เข้ารหัส ✅ (T10) · masking log + redaction + scrub (T27) ⬜ · data residency Singapore ต้องยืนยันกับที่ปรึกษา (TBD) · บทบาท controller/processor ระหว่าง TSF–ร้าน–OMS (TBD, ปรึกษากฎหมาย) | 🟡 | NFR, T27 |
| S8 | เบอร์ mask `***-***-1234` ทุก role, ที่อยู่/เบอร์เต็มเฉพาะหน้าแพ็ก T18 | ✅ API orders · ⬜ T18 | docs/api/orders.md |
| S9 | Audit log ทุก action สำคัญ (login, แก้ catalog, post เอกสาร, ขอยกเลิก) + หน้า audit viewer (T24) | 🟡 | T03, T07, T08A, T17, T24 |
| S10 | Dependency scan + PII scan ใน CI (T25) | ⬜ | T25 |
| S11 | ปิด demo/test profile ใน prod (`e2e`, mock control API, demo permit-all chain) | ⬜ ต้องตรวจตอน T26 | backlog T17 |
| S12 | Branch protection บน `main` (CI เขียว + review + Narote approve) | ตามกติกาใน 05 · การตั้งค่าใน GitHub จริง = TBD | 05 |

### 5.2 Infra / Deploy
| # | รายการ | ตัวเลือก / หมายเหตุ |
|---|---|---|
| I1 | **Hosting backend + Postgres** | Railway (plan แนะนำ: region Singapore, PITR, HA Postgres ก่อนร้านแรกเข้า CONTROL) หรือที่อื่นที่รัน Java container + managed Postgres ได้ → **รอ Narote ตัดสินใจ + งบ** |
| I2 | **Hosting frontend** | Vercel ได้ (static Vite build) หรือ Railway service `frontend` ที่มีอยู่ใน `.railway/railway.ts` · **Vercel host backend/DB/mock ไม่ได้** |
| I3 | Mock TSF | ใช้ใน local/CI/staging เท่านั้น ห้ามขึ้น prod |
| I4 | Environments | local (มีแล้ว) → staging (ต่อ TSF staging) → prod · แยก DB, secret, domain |
| I5 | Domain + TLS | TBD ชื่อโดเมน (เช่น subdomain ของ TSF) · TLS ทุกเส้น (NFR) · ต้องมี HTTPS สาธารณะก่อน T28 และก่อนยื่น marketplace |
| I6 | Staging IaC | `.railway/railway.ts` มีอยู่แต่ **ยังไม่เคย apply** และต้องแก้ก่อนใช้: ตอนนี้ map `DATABASE_USERNAME` เป็น `PGUSER` (superuser) ซึ่ง backend จะไม่ยอม start · ยังไม่มี env PII/HMAC |
| I7 | `railway.toml` | deprecated (README: hard stop 2026-12-01) ใช้ `railway.ts` แทน |
| I8 | Prod deploy ต้องกดเอง (Narote) ไม่ auto | T26 AC |
| I9 | ≥ 2 app instance สำหรับ Checkout Reserve API (99.9% ตอน CONTROL/ACTIVE) | NFR |

### 5.3 Backup / Restore
| # | รายการ | เป้า |
|---|---|---|
| B1 | เปิด PITR ตั้งแต่วันแรกที่มี staging/prod | RPO ≤ 1 นาที, ย้อนได้ ~28 วัน (NFR) |
| B2 | `pg_dump` รายสัปดาห์ เข้ารหัส เก็บนอก provider หลัก | เก็บ 35 วัน (NFR) |
| B3 | DR drill: restore ไป service ข้างๆ แล้วสลับ connection | RTO ≤ 60 นาที, ทุกเดือน + ก่อนขยาย pilot (T42) |

### 5.4 Monitoring / Alerting / Logging
| # | รายการ | อ้างอิง |
|---|---|---|
| M1 | JSON log + `trace_id` (error response มี `trace_id` แล้ว) · ไม่มี PII ใน log · เก็บ 30 วัน | T26, T27, NFR |
| M2 | Metrics: reserve latency, inbox lag, DEAD count, stock propagation lag, business oversell, hold count | T26 |
| M3 | Alert: DEAD > 0, inbox lag > 5 นาที, reserve p95 เกิน NFR, invariant พัง | T26, T22 |
| M4 | ช่องทางรับ alert (LINE/Slack/อีเมล/on-call) | TBD |
| M5 | Uptime check `/actuator/health` จากภายนอก | TBD เครื่องมือ |

### 5.5 Performance / Load
| # | รายการ | สถานะ |
|---|---|---|
| P1 | Gatling checkout reserve ใน CI (`Checkout reserve perf` job) | ✅ มีแล้ว (T12A) |
| P2 | Orders list 10,000 ออเดอร์ < 1 วิ (keyset index V11) | ✅ T17 |
| P3 | Load test ครบ NFR ที่ 2× peak บน env จริง | ⬜ T43 |
| P4 | ตัวเลข NFR ส่วนใหญ่ยังเป็น TBD + ค่าเริ่ม → ทบทวนหลัง pilot ขั้น 2 | [NFR.md](plan/NFR.md) |

### 5.6 CI/CD
| # | รายการ | สถานะ |
|---|---|---|
| C1 | CI: backend verify + mock acceptance, chaos, mock-tsf, perf, contracts, frontend, Playwright e2e | ✅ (`.github/workflows/ci.yml`) |
| C2 | CD ไป staging อัตโนมัติจาก `main` | ⬜ (T01 AC นี้ยังไม่ได้ทำ เพราะ localhost-only) |
| C3 | Promote ไป prod แบบกดเอง + rollback plan | ⬜ T26 |
| C4 | แก้ test flaky (`InboxApiTest`) ก่อนใช้ CI เป็นด่าน deploy | ⬜ backlog |
| C5 | Migration check: Flyway ต่อด้วย `oms_migrator` ใน CI | ⬜ |

### 5.7 Incident runbook (ต้องเขียน, ยังไม่มี)
| สถานการณ์ | ทำอะไรก่อน (ร่าง) |
|---|---|
| OMS ล่มตอน checkout | TSF ใช้ conditional fail-open (TSF-09) อยู่แล้ว · OMS กู้แล้วจองย้อนหลังตอน `order.created` · ดูออเดอร์ที่ ON_HOLD `OUT_OF_STOCK` |
| สต๊อกเพี้ยน / push ผิด | กด PAUSE STOCK SYNC หรือ global kill switch (T40) → หาเหตุ → FORCE FULL RESYNC |
| Event DEAD ค้าง | ดูหน้า `#/admin/outbox` แล้ว retry · ฝั่ง inbox ดู `last_error` |
| DB เสีย / ลบผิด | PITR restore ไป service ข้างๆ ตาม runbook T42 |
| ข้อมูลรั่ว (PII) | TBD: ขั้นตอนแจ้งตาม PDPA ต้องปรึกษากฎหมาย |
| ช่องทาง rate limit / ล่ม | circuit breaker ของ adapter (T16) ทำงานเอง · backfill (T12C) ตามเก็บ |

---

## 6. Go-to-market / ธุรกิจ

> ไม่มีตัวเลขตลาดหรือราคาใน repo ทุกอย่างในหัวข้อนี้เป็น **ข้อเสนอให้ตัดสินใจ**

### 6.1 แพ็กเกจ membership entitlement
- หลักที่ตกลงแล้ว: สิทธิ์ OMS มากับ membership TSF (TSF-06 ส่ง `membership.changed`)
- ข้อเสนอใน plan ข้อ 1: **ทุก tier ที่จ่ายเงินและ ACTIVE ได้ OMS (ช่องทาง TSF)**, ช่องทาง marketplace เฉพาะ tier บน (Phase 5)
- สมาชิกหมดอายุ (plan ข้อ 2, ทำใน T03 แล้ว): GRACE = อ่านได้ เขียนไม่ได้ → SUSPENDED · เก็บข้อมูล 90 วัน (NFR)

### 6.2 Pricing options (ข้อเสนอให้ตัดสินใจ)
| ตัวเลือก | หน้าตา | ข้อดี | ข้อควรคิด |
|---|---|---|---|
| A. รวมในทุก tier ที่จ่ายเงิน | ไม่มีค่าเพิ่ม | ดึงคนสมัคร TSF, ง่าย | ต้นทุน host ต่อร้านต้องอยู่ใน margin ของ membership |
| B. Add-on รายเดือน | จ่ายเพิ่มจาก membership | มีรายได้ตรงจาก OMS | ต้องทำ billing แยกใน TSF Pay |
| C. ตามขนาด | tier ตามจำนวนออเดอร์/ช่องทาง/ผู้ใช้ | ร้านใหญ่จ่ายตามการใช้จริง | ต้องนับ usage (ยังไม่มีใน OMS) |
| D. ผสม A + upsell | TSF ฟรีใน tier จ่ายเงิน · marketplace + B2B/D365 คิดเพิ่ม | ตรงกับ plan ข้อ 1 และ add-on ทีหลัง | ต้องกำหนดเส้นแบ่ง tier |
ราคาเป็นตัวเลข = **TBD**

### 6.3 Billing
- เก็บเงินผ่าน **TSF Pay** (Xendit xenPlatform / Opn) ทั้งหมด OMS ไม่เก็บเงิน ไม่เก็บข้อมูลบัตร
- OMS รู้แค่สถานะสิทธิ์จาก `membership.changed` (ACTIVE / GRACE / SUSPENDED + `expires_at`)
- งานที่ต้องมีฝั่ง TSF: TSF-11 (เสนอ)

### 6.4 Onboarding ร้านค้า (ร่าง)
| ขั้น | ทำอะไร | ระบบที่รองรับ |
|---|---|---|
| 1 | ร้านกด "เข้า OMS" จาก TSF → login ครั้งแรก = สร้าง tenant อัตโนมัติ (JIT) | T03 ✅ |
| 2 | Import สินค้า/SKU (CSV) + ตั้งคลัง | T07 ✅ |
| 3 | ตั้งยอดสต๊อกเริ่มต้น (Opening balance) | T08A ✅ |
| 4 | Sync listings + map SKU | T12B 🔨 |
| 5 | เริ่ม mode OBSERVE → SHADOW (ดูความต่าง) → CONTROL → ACTIVE | T40 ⬜ |
| 6 | สอนทีมแพ็ก: pick list, ยิงบาร์โค้ด, label | T18 ⬜ |
- ร้าน pilot ที่ plan แนะนำ (ข้อ 9): ขายแค่ TSF, 20–200 ออเดอร์/วัน, < 500 SKU, ติดต่อ owner ทาง LINE ได้

### 6.5 Support + เอกสาร
| รายการ | สถานะ |
|---|---|
| ช่องทาง support (LINE OA / อีเมล / ในแอป) + เวลาให้บริการ | TBD |
| เป้า pilot: support ticket < 1 ต่อร้านต่อสัปดาห์ (pilot ขั้น 4) | จาก 02 |
| คู่มือผู้ใช้ภาษาไทย (เริ่มต้นใช้งาน, import CSV, เอกสารสต๊อก, แพ็ก/ส่ง, คืนของ, hold แต่ละแบบแก้ยังไง) | ⬜ ยังไม่มี · ตอนนี้มีแค่ API docs ใน `docs/api/` |
| FAQ / วิดีโอสั้น | TBD |
| หน้า product สาธารณะ + demo tenant | T28 ⬜ |

### 6.6 Legal
| รายการ | สถานะ |
|---|---|
| Terms of Service ของ OMS (หรือเพิ่มใน terms ของ TSF) | TBD |
| Privacy Policy (ข้อมูลผู้รับของลูกค้าร้าน, ระยะเก็บตาม NFR) | TBD · ต้องปรึกษาที่ปรึกษากฎหมาย |
| Data processing terms ระหว่าง TSF กับร้าน | TBD |
| นิติบุคคลที่ยื่น partner marketplace (plan ข้อ 4: บริษัท ThaiShopFun) | ข้อเสนอใน plan · สถานะการยื่น = TBD |

---

## 7. Decision log + เรื่องที่รอ Narote

### 7.1 PO decisions ที่ตัดสินแล้ว
| # | เรื่อง | ตัดสินว่า |
|---|---|---|
| D1 | ช่องทางแรก | ThaiShopFun ก่อน แล้ว Shopee → TikTok Shop → Lazada (ยืนยันกับยอดขายร้านจริงอีกที) |
| D2 | B2B/D365 | paid add-on ทีหลัง ไม่อยู่ใน MVP |
| D3 | เงิน | อยู่ใน TSF Pay, OMS อ่านสถานะอย่างเดียว |
| D4 | Hosting ตอนนี้ | localhost อย่างเดียว ยังไม่ deploy Railway/Vercel |
| D5 | ออเดอร์จบแล้ว (terminal) | fulfillment/hold แช่แข็ง · payment เดินได้แค่ไปสถานะ refund |
| D6 | `READY_TO_PICK` | ต้อง `order_status=ACTIVE` |
| D7 | (2026-10-01) OBSERVE / DISCONNECTED / tenant ที่ OMS ไม่คุมสต๊อก | ไม่บังคับ ORDER reservation ตอน `READY_TO_PICK` ถ้า `hold_reason=NONE` |
| D8 | ขอยกเลิกออเดอร์ | OWNER/ADMIN เท่านั้น · OMS ขอไปที่ TSF ไม่ยกเลิกเอง |
| D9 | เบอร์โทร | mask `***-***-1234` ทุก role · ที่อยู่/เบอร์เต็มเฉพาะหน้าแพ็ก T18 |
| D10 | Inbox staleness | เทียบความเก่าของ event แยกตาม event type เท่านั้น |
| D11 | OMS ล่มตอน checkout | conditional fail-open (TSF ขายต่อได้ถ้าสต๊อกล่าสุด ≥ qty + 1 และไม่ใช่ SKU "จำกัด" แล้ว OMS จองย้อนหลัง) |
| D12 | Stack | React+Vite+TS / Spring Boot 4.1 Java 17 / PostgreSQL / Flyway / JWT |

### 7.2 ค่าแนะนำใน plan ที่ทีมใช้เดินงานไปก่อน (ยืนยันได้)
จาก [plan.md "ต้องให้ Narote ตัดสินใจ"](plan/plan.md): ข้อ 1 tier, 2 grace 7 วัน, 3 TSF ออก label, 4 นิติบุคคลยื่น marketplace, 5 Railway + PITR + HA, 7 allocation ค่าเริ่ม, 8 PII 90 วัน, 9 เกณฑ์ร้าน pilot, 10 ใช้ตัวเลข NFR ค่าเริ่ม → ถ้าไม่ตอบ ทีมใช้ค่าแนะนำ

### 7.3 เรื่องที่รอ Narote ตัดสินใจ
| # | เรื่อง | ตัวเลือก | ต้องรู้ก่อน |
|---|---|---|---|
| Q1 | **จะเริ่มออนไลน์ (staging) เมื่อไร** | หลัง Phase 2 / หลัง Phase 3 / ก่อน T28 | T26, T28, TSF-01 staging |
| Q2 | **Hosting + งบ** | Railway ทั้งชุด · Vercel (frontend) + Railway (backend/DB) · อื่นๆ | T26 |
| Q3 | HA Postgres ตอนไหน (มีค่าใช้จ่ายเพิ่ม) | plan แนะนำก่อนร้านแรกเข้า CONTROL | T40/pilot |
| Q4 | **ทำ repo private เมื่อไร** | ตอนนี้ / ก่อน staging online | S1 |
| Q5 | **Pricing / แพ็กเกจ** | A / B / C / D (หัวข้อ 6.2) | launch |
| Q6 | **Marketplace ที่ 2** | Shopee (plan) / เจ้าที่อนุมัติก่อน · ยื่น partner แล้วหรือยัง | Phase 5 |
| Q7 | โดเมน prod | subdomain TSF หรือโดเมนใหม่ | I5, T28 |
| Q8 | ย้าย contracts ไป repo `tsf-oms-contracts` เมื่อไร | ตอนนี้ / ตอน TSF เริ่ม TSF-08 | T01C |
| Q9 | ร้าน pilot ตัวจริง | ตามเกณฑ์ plan ข้อ 9 | Phase 4 |
| Q10 | ที่ปรึกษากฎหมาย PDPA (residency Singapore, privacy policy, terms) | — | ก่อน pilot ร้านจริง |
| Q11 | ช่องทาง alert + support | LINE / Slack / อีเมล | T26, launch |
| Q12 | ลำดับเก็บ tech debt | ทำแทรกทีละก้อน / sprint เก็บก่อน T26 | หัวข้อ 8 |

---

## 8. Tech debt / backlog

| # | เรื่อง | มาจาก | ความสำคัญ (เสนอ) | ควรทำก่อน |
|---|---|---|---|---|
| 1 | T00 invariants + NFR ยังไม่ทำ | 05 | สูง | T09, T19 |
| 2 | T28 public page + demo tenant | 05 | กลาง | ยื่น marketplace |
| 3 | Inbox/outbox: นับ attempt ต่อแถว (per-row attempt accounting) | backlog | กลาง | T26 |
| 4 | ล้าง idempotency key ของ T08 หลัง 24 ชม. | backlog | กลาง | T26 (ตารางโตเรื่อยๆ) |
| 5 | T07 / T04 P2 ที่ค้าง | review | กลาง | TBD รายการละเอียดอยู่ใน PR review |
| 6 | **Flyway ต้องรันด้วย `oms_migrator`** (ตอนนี้ใช้ superuser) | backlog | สูง | prod |
| 7 | **Repo ต้อง private ก่อนออนไลน์** | backlog | สูง | staging online |
| 8 | README ส่วน "Staging on Railway" เก่า (ยังไม่เคย deploy, `railway.ts` map superuser) และ README บอก "Flyway V1, V2, V3" ทั้งที่มีถึง V11 | backlog + ตรวจพบ | กลาง | T26 |
| 9 | `InboxApiTest` flaky | backlog | กลาง | ใช้ CI เป็นด่าน deploy |
| 10 | T16 leftovers | backlog | ต่ำ–กลาง | TBD รายการละเอียด |
| 11 | T12A P3s | review | ต่ำ | TBD รายการละเอียด |
| 12 | T13 P3s: durable orphan marker แทนการใช้ `last_error`, ใส่ SKU id field ใน `StockOperationException` ฯลฯ | review | ต่ำ | — |
| 13 | T17 P3: demo `shop_active` ค้าง ACTIVE | review | ต่ำ | — |
| 14 | T17 P3: test ใช้ state ร่วมกัน | review | ต่ำ | — |
| 15 | T17 P3: EXPLAIN SQL ถูก copy ไว้ใน test | review | ต่ำ | — |
| 16 | T17 P3: profile gate ของ mock-tsf (cosmetic) | review | ต่ำ | — |
| 17 | T17 P3: demo permit-all chain ภายใต้ test profile | review | กลาง (ต้องปิดใน prod) | T26 |
| 18 | T17 P3: demo stock ไม่มี ledger rows | review | ต่ำ | — |
| 19 | Orders list: snapshot commit-visibility window (ออเดอร์ที่ commit หลัง snapshot อาจโผล่หน้าถัดไป) — **บันทึกไว้แล้วใน [orders.md](api/orders.md)** ยอมรับได้ | T17 #21 | ต่ำ | — |
| 20 | `InboxWorker` TODO: handler ต้องใช้ snapshot เต็มใน `data` จนกว่าจะมี REST refetch | โค้ด | กลาง | T12C |
| 21 | 05 เขียน T50M เป็น "Flyway V5" แต่ V5 ห้ามใช้ → แก้ใน 05 ตอนเริ่ม T50M | ตรวจพบ | ต่ำ | T50M |
| 22 | T01 AC "deploy staging อัตโนมัติ + health UP บน staging" ยังไม่ผ่าน (localhost-only) | ตรวจพบ | ตาม Q1 | T26 |
| 23 | T01C: AsyncAPI + `oasdiff` ยังไม่มี | ตรวจพบ | กลาง | TSF-08 |

---

## 9. วิธีทำงาน (workflow) + รัน local demo

### 9.1 Workflow
| ขั้น | ใคร | ทำอะไร |
|---|---|---|
| 1 | Planner | เขียน/แก้ task + AC ใน [05-task-list](plan/05-task-list.md) |
| 2 | Cursor cloud agent (model composer-2.5) | ทำ task 1 task = 1 PR (branch `feat/Txx-*` หรือ `cursor/*`) · เกิน ~600 บรรทัด (ไม่นับ test) → แตก PR |
| 3 | Codex + OMS Builder | review รอบสอง · Codex ทำงานแยก (test, migration, contracts) ใน `codex/Txx-*` |
| 4 | CI | ต้องเขียวทุก job |
| 5 | **Narote** | approve ทุก merge เข้า `main` |

กติกาสำคัญ:
- Flyway: 1 migration ต่อ PR, ใช้เลขว่างถัดไปตามลำดับ merge, **ห้ามแก้ version ที่ merge แล้ว**, V5 ห้ามใช้ ([05 Flyway versions](plan/05-task-list.md))
- Definition of Done ต่อ task: CI เขียว, test ครอบ AC, ไม่มี PII ใน log, invariant ผ่าน, อัปเดต `docs/` ถ้า contract เปลี่ยน
- ห้าม commit secret, รหัสผ่าน prod, PII จริง

### 9.2 รัน local demo (จาก [README](../README.md))
ต้องมี Java 17, Node.js 22, Docker

```bash
docker compose up -d                                              # Postgres 16 + mock-tsf
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # API :8080
cd frontend && npm ci && npm run dev                              # UI :5173
```

| อะไร | URL / วิธี |
|---|---|
| UI | <http://localhost:5173> → "sign in with ThaiShopFun" แล้วเลือก user จาก mock picker |
| API health | <http://localhost:8080/actuator/health> |
| Mock TSF | <http://localhost:8090> (IdP + control API) |
| Login hints | `owner-active`, `owner-grace`, `owner-suspended`, `owner-expired` |
| ใส่ข้อมูลตัวอย่าง (ออเดอร์ demo) | `curl -X POST http://localhost:8090/control/demo/orders-seed` (เรียก order-catalog ให้ก่อน) |
| ทั้ง stack สำหรับ Playwright | `frontend/scripts/e2e-stack.sh` (เรียกผ่าน `npm run test:e2e`) |

ข้อควรรู้: restart `mock-tsf` = ได้ RSA key ใหม่ OMS cache JWKS ~5 นาที token ใหม่อาจ 401 จน restart backend (README)

---

## แหล่งอ้างอิง
- [plan.md](plan/plan.md) · [01-process-map](plan/01-process-map.md) · [02-mvp-scope](plan/02-mvp-scope.md) · [03-data-model](plan/03-data-model.md) · [04-api-contract](plan/04-api-contract.md) · [05-task-list](plan/05-task-list.md) · [NFR](plan/NFR.md) · [CHANGELOG-v2](plan/CHANGELOG-v2.md)
- [README](../README.md) · [`contracts/`](../contracts/) · [`docs/api/`](api/)
- Merged PRs #1, #3–#21 บน GitHub
