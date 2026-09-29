# NFR: Non-Functional Requirements (v0.1, ทำใน Phase 0 / T00)

> ตัวเลขที่ยังไม่รู้จริงใส่ **TBD** + ค่าเริ่มที่เสนอ ใช้ออกแบบและ load test ไปก่อน ทบทวนหลัง pilot ขั้น 2
> "platform" = ทุก tenant รวมกัน

## Volume
| รายการ | ค่าเริ่ม (เสนอ) | สถานะ | หมายเหตุ |
|---|---|---|---|
| Tenants (ร้าน) | pilot 10 · 12 เดือน 500 | TBD | ขึ้นกับจำนวนสมาชิก TSF ที่จ่ายเงิน |
| Channel accounts ต่อ tenant | MVP 1 (TSF) · Phase 5 ≤ 5 (ปกติ 2–4) | ตั้งแล้ว | |
| Orders/วัน ต่อ tenant | median 50 · p95 tenant 1,000 | TBD | |
| Orders/วัน platform | 12 เดือน 50,000 | TBD | |
| Peak orders/นาที | platform 500 · tenant 100 | TBD | ช่วง 11.11 / flash sale ≈ 10× ปกติ |
| Checkout reserve req/วินาที | peak 200 req/s | TBD | รวมคนที่ทิ้ง checkout (≈ 3× ออเดอร์) |
| SKUs ต่อร้าน | ปกติ 500 · สูงสุด 20,000 | TBD | |
| Stock updates/วินาที (ขาออกหลัง debounce) | ปกติ 50/s · peak 300/s | TBD | |

## Latency / Lag
| รายการ | เป้า | วัดจาก |
|---|---|---|
| Checkout reserve API | p95 < 150 ms · p99 < 300 ms | server-side, T43 |
| Webhook ingestion (รับ → ตอบ 202) | p95 < 100 ms · p99 < 300 ms | T11 metric `oms.inbox.ack` |
| Order processing lag (รับ event → ออเดอร์ + reservation commit) | p95 < 5 วิ · p99 < 30 วิ | `processed_at − received_at` |
| Stock propagation lag (inventory เปลี่ยน → TSF รับ `stock.updated`) | TSF p95 < 10 วิ · marketplace p95 < 60 วิ | outbox `sent_at − ledger.created_at` |
| หน้า orders list (10,000 ออเดอร์) | < 1 วิ | T17 |

## Availability / DR
| รายการ | เป้า | หมายเหตุ |
|---|---|---|
| Availability UI/API | 99.5% ต่อเดือน (pilot) | |
| Availability Checkout Reserve API | **99.9%** เมื่อร้านอยู่ mode CONTROL/ACTIVE | อยู่ใน checkout path → ต้อง HA Postgres + ≥ 2 app instance + TSF fallback (TSF-09) |
| RPO | ≤ 1 นาที | Railway PITR ส่ง WAL ทุกครั้งที่ commit, `archive_timeout=60s` |
| RTO | ≤ 60 นาที (pilot) → 30 นาที (หลัง pilot) | PITR restore เป็น sibling service แล้วสลับ connection string (T42) |
| DR drill | ทุกเดือน + ก่อนขยาย pilot แต่ละขั้น | |

## Backup (แทน "daily backup" ของ v1)
- **ตรวจแล้ว: Railway Postgres รองรับ PITR** (docs.railway.com/volumes/point-in-time-recovery)
  - archive WAL ทุก segment ไป Railway bucket ด้วย pgBackRest, base backup full ทุกสัปดาห์ + incremental ทุกวัน
  - เก็บ full 4 ชุดล่าสุด → restore ได้ย้อนหลัง **~4 สัปดาห์** ถึงระดับวินาที
  - restore สร้าง **service ใหม่ข้างๆ** ของเดิมไม่ถูกแตะ; รองรับทั้ง single-node และ HA cluster
  - window เริ่มนับหลังเปิด → **เปิด PITR ตั้งแต่วันแรกที่มี staging/prod** (T26)
- เสริม: `pg_dump` รายสัปดาห์ เข้ารหัส เก็บนอก Railway (คนละ provider/region) 5 สัปดาห์ กันกรณี account/region มีปัญหา
- Backup retention: PITR ~28 วัน · offsite dump 35 วัน

## PII retention
| ข้อมูล | อายุ | หลังจากนั้น |
|---|---|---|
| ชื่อ/เบอร์/ที่อยู่ผู้รับ | ปิดออเดอร์ + 90 วัน | REDACTED (เหลือ province/postcode) |
| Label PDF cache | 7 วันหลัง SHIPPED | ลบ |
| Event payload ที่มี PII | 7 วันหลัง PROCESSED/SENT | ล้าง field PII |
| Audit log | 2 ปี (ไม่มี PII อยู่แล้ว) | ลบ |
| App log | 30 วัน (ห้ามมี PII) | ลบ |
| Backup | ตาม backup retention | ข้อมูลที่ลบแล้วหายจาก backup ครบภายใน ~35 วันหลัง redaction |
| Tenant ที่ SUSPENDED | 90 วัน | ลบข้อมูลทั้ง tenant |

## อื่นๆ
- Security: TLS ทุกเส้น, secret ใน Railway variables/secret manager, FORCE RLS ทุกตาราง tenant, หมุน HMAC/encryption key ได้โดยไม่ downtime
- Data residency: Railway region **Southeast Asia (Singapore)** · TBD ยืนยันเรื่อง PDPA cross-border กับที่ปรึกษา
- Observability: metric ทุกตัวในไฟล์นี้ต้องมี dashboard + alert ก่อนเข้า Phase 4
