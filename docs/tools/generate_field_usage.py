#!/usr/bin/env python3
"""One-shot generator for field_meta/field_usage.py — run after column set changes."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys_path = ROOT / "docs/tools"
import sys

sys.path.insert(0, str(sys_path))
from field_meta.all_fields import FIELDS  # noqa: E402

# Per (table, column) — must be specific; no table-wide boilerplate.
OVERRIDES: dict[tuple[str, str], str] = {
    ("audit_log", "action"): "เขียน `TenantSessionService` (auth.login), `MembershipChangedHandler` (membership.changed), `CatalogAudit`/`ProductService`/`SkuService`/`CatalogImportService` (PRODUCT_*/SKU_*/CATALOG_IMPORTED), `ListingAudit` (CHANNEL_LISTING_MAPPED/UNMAPPED), `OrderAudit` (ORDER_CANCEL_REQUESTED, ORDER_HOLD_RECHECKED), `OutboxAdminService` (outbox.retry); อ่าน ops/SQL",
    ("audit_log", "created_at"): "INSERT ตอนเขียน audit; อ่านเรียงเวลา",
    ("audit_log", "id"): "INSERT audit writers; อ่าน ops",
    ("audit_log", "tenant_id"): "INSERT จาก `TenantContext`; RLS กรอง",
    ("audit_log", "actor_type"): "INSERT audit writers (USER/SYSTEM/TSF)",
    ("audit_log", "actor_id"): "INSERT audit writers (user id หรือ null)",
    ("audit_log", "entity_type"): "INSERT audit writers",
    ("audit_log", "entity_id"): "INSERT audit writers",
    ("audit_log", "before"): "INSERT audit writers (JSON snapshot)",
    ("audit_log", "after"): "INSERT audit writers (JSON snapshot)",
    ("audit_log", "ip"): "INSERT `TenantSessionService`, `CatalogAudit`, `OrderAudit` จาก request IP",
    ("sales_order", "completed_at"): "ยังไม่มี writer บน main — คอลัมน์สำรองเมื่อปิดออเดอร์; อ่าน `OrderQueryService`",
    ("channel_listing", "last_exposed_qty"): "ยังไม่มี writer บน main ([T15](../plan/05-task-list.md#L216)); อ่านอนาคต stock push",
    ("channel_listing", "last_pushed_version"): "ยังไม่มี writer บน main ([T15](../plan/05-task-list.md#L216)); อ่านอนาคต stock push",
    ("channel_listing", "last_seen_channel_qty"): "ยังไม่มี writer บน main ([T23](../plan/05-task-list.md#L245)); อ่านอนาคต drift job",
    ("channel_listing", "stock_control"): "เขียน `ChannelListingRepository` (map/sync), demo seed; อ่าน `OrderIntakeSupport`, `OrderHoldEffects`, `CheckoutRepository` กรอง CONTROL",
    ("channel_listing", "safety_buffer"): "เขียน `ChannelListingRepository`; อ่าน `CheckoutRepository`, `StockAvailability`, `StockRepository` หัก sellable",
    ("outbox_event", "status"): "INSERT `OutboxAppender` (PENDING); อัปเดต `OutboxPublisher`/`OutboxStore` (IN_FLIGHT→SENT/DEAD); admin retry → PENDING (`OutboxAdminService`)",
    ("outbox_event", "attempts"): "อัปเดต `OutboxPublisher` ตอน claim/fail; reset `OutboxAdminService` retry",
    ("outbox_event", "next_attempt_at"): "อัปเดต `OutboxPublisher` backoff; NULL เมื่อ SENT/DEAD/retry",
    ("outbox_event", "lease_until"): "ตั้ง `OutboxPublisher` ตอน claim IN_FLIGHT; ล้างเมื่อส่งหรือคืน PENDING",
    ("outbox_event", "sent_at"): "ตั้ง `OutboxPublisher` เมื่อส่งสำเร็จ",
    ("outbox_event", "payload"): "INSERT `OutboxAppender`; อ่าน `OutboxPublisher` HTTP POST",
    ("outbox_event", "event_type"): "INSERT `OutboxAppender` (เช่น order.status_changed)",
    ("order_hold_retry", "attempts"): "เขียน `OrderHoldRetryRepository.recordBackoff` (scheduled sweeper); ลบแถวเมื่อ RELEASED/OUT_OF_STOCK",
    ("order_hold_retry", "next_attempt_at"): "เขียน `recordBackoff` จาก `OrderHoldResolverJob.persistRetryState`",
    ("order_hold_retry", "last_error"): "เขียน `recordBackoff` — โค้ด STILL_HELD | DEFERRED | simple name ของ exception (ไม่ใช่ message)",
    ("order_hold_retry", "order_id"): "PK; เขียน/ลบ `OrderHoldResolverJob.persistRetryState`",
    ("order_hold_retry", "tenant_id"): "PK; RLS",
    ("order_hold_retry", "updated_at"): "อัปเดต `OrderHoldRetryRepository`",
    ("inventory", "stock_version"): "อัปเดต `StockRepository` ทุกการเปลี่ยนสต็อก (+1 monotonic); อ่าน payload `stock.updated` ([T15](../plan/05-task-list.md#L216)) — ไม่ใช่ optimistic lock (lock แถว FOR UPDATE)",
    ("inventory", "ledger_seq"): "อัปเดต `StockRepository` ตอน lock แถว inventory",
    ("stock_reservation", "expires_at"): "ตั้ง `CheckoutReserveService`/`ReservationEngine` (CHECKOUT TTL); ORDER unpaid PREPAID = payment_expires_at + grace (`OrderIntakeSupport.adoptForOrder`); ล้าง NULL เมื่อ paid (`StockRepository`)",
    ("stock_reservation", "status"): "อัปเดต `ReservationEngine`, `StockExpiryJob` (EXPIRED), cancel/release paths",
    ("inbox_event", "status"): "INSERT `InboxIngestService` (RECEIVED); อัปเดต `InboxWorker` (PROCESSED/FAILED/DEAD)",
    ("inbox_event", "next_attempt_at"): "อัปเดต `InboxWorker`/`claim_inbox_batch` สำหรับ lease/retry",
    ("sales_order", "currency"): "INSERT order intake; ค่า THB ตามช่องทาง — อ่าน API/UI",
}

# table -> default writer class hint for generic columns
TABLE_HINT: dict[str, str] = {
    "app_user": "`IdentityProvisioner`/`upsert_app_user`",
    "tenant": "`IdentityProvisioner`, `MembershipChangedHandler`",
    "tenant_membership": "`provision_membership`",
    "channel_account": "JIT provision, intake",
    "product": "`ProductService`",
    "sku": "`SkuService`",
    "sku_bundle_component": "`SkuService` components API",
    "channel_listing": "`ChannelListingRepository`, `ListingChangedHandler`, `ChannelListingSyncService`",
    "warehouse": "`WarehouseService`",
    "inventory": "`StockRepository`/`ReservationEngine`",
    "inventory_ledger": "`StockRepository`",
    "stock_reservation": "`CheckoutReserveService`, `ReservationEngine`, intake",
    "stock_document": "`StockDocumentService`",
    "stock_document_line": "`StockDocumentStore`",
    "sales_order": "order intake, `OrderStateMachine`",
    "order_line": "`OrderLineRepository`, intake",
    "order_recipient": "`OrderRecipientRepository`",
    "order_status_history": "`OrderStateMachine`",
    "inbox_event": "`InboxIngestService`, `InboxWorker`",
    "outbox_event": "`OutboxAppender`, `OutboxPublisher`",
    "idempotency_key": "Checkout/Stock idempotency stores",
    "shadow_diff": "`ShadowDiffRepository`, checkout",
    "reconciliation_issue": "`ReconciliationIssueRepository`",
    "audit_log": "audit writers (ดูคอลัมน์ action)",
    "payment_status_snapshot": "reserved T21 — ยังไม่มี writer",
    "refund": "reserved T21",
    "return_request": "reserved T20",
    "return_line": "reserved T20",
    "shipment": "reserved T18 — อ่าน `OrderQueryService`",
    "sync_cursor": "reserved [T12C](../plan/05-task-list.md#L212)",
}

READ_HINT: dict[str, str] = {
    "app_user": "join `tenant_membership`",
    "tenant": "`MeService`, `TenantSessionService`",
    "sales_order": "`OrderQueryService`, orders UI",
    "channel_listing": "listings UI, intake, hold resolver",
    "inventory": "stock UI, checkout availability",
    "outbox_event": "`OutboxPublisher`, `OutboxAdminService` list",
    "inbox_event": "`InboxWorker`",
    "shipment": "`OrderQueryService`",
    "sync_cursor": "reserved T12C job",
}


def generic_usage(table: str, col: str) -> str:
    key = (table, col)
    if key in OVERRIDES:
        return OVERRIDES[key]
    w = TABLE_HINT.get(table, f"`{table}` services")
    r = READ_HINT.get(table, "API/repository ที่ query ตาราง")
    if col == "tenant_id":
        return f"INSERT ใส่ tenant ปัจจุบัน; RLS กรองทุก SELECT"
    if col == "id":
        return f"PK; INSERT {w}; อ่าน {r}"
    if col == "created_at":
        return f"INSERT default now(); อ่าน {r}"
    if col == "updated_at":
        return f"อัปเดต {w}; อ่าน {r}"
    if col.endswith("_at") or col in ("ordered_at", "paid_at", "ship_by", "observed_at", "posted_at"):
        return f"เขียน {w}; อ่าน {r}"
    if col in ("version", "external_version", "ent_ver"):
        return f"เขียน {w}; อ่าน concurrency/dedup paths"
    return f"เขียน {w}; อ่าน {r}"


def main():
    usage = {}
    for key in FIELDS:
        usage[key] = generic_usage(key[0], key[1])
    # merge explicit usage from all_fields if present and not generic
    for key, meta in FIELDS.items():
        if meta.get("usage", "").strip() and key not in OVERRIDES:
            # keep only if more specific than generic (already in all_fields)
            if key[0] in ("shipment", "channel_listing", "inbox_event", "order_status_history", "sales_order", "stock_reservation", "tenant"):
                usage[key] = meta["usage"].strip()

    out = ROOT / "docs/tools/field_meta/field_usage.py"
    lines = [
        '# Auto-generated by docs/tools/generate_field_usage.py — edit OVERRIDES there and re-run.',
        "USAGE: dict[tuple[str, str], str] = {",
    ]
    for key in sorted(usage.keys()):
        val = usage[key].replace("\\", "\\\\").replace("'", "\\'")
        lines.append(f"    ({key[0]!r}, {key[1]!r}): '{val}',")
    lines.append("}")
    lines.append("")
    lines.append(f"assert len(USAGE) == {len(usage)}")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {len(usage)} entries to {out}")


if __name__ == "__main__":
    main()
