#!/usr/bin/env python3
"""Generate field_meta/field_usage.py from OVERRIDES + rules. Run: python docs/tools/generate_field_usage.py"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "backend/src/main/java"
sys.path.insert(0, str(ROOT / "docs/tools"))
from field_meta.all_fields import FIELDS  # noqa: E402

INTAKE_INSERT = "เขียน `SalesOrderRepository.insert` (caller order intake handlers)"
STATE_MACHINE = "เขียน `OrderStateMachine` ผ่าน `SalesOrderRepository.updateStatusFields`"
READ_REPO = "อ่าน `SalesOrderRepository` (`findById` / `findByExternalId`)"
READ_ORDERS_UI = "อ่าน `OrderQueryService` / orders UI"

# Per (table, column) — verified against main @ ee4e425 Java/SQL
OVERRIDES: dict[tuple[str, str], str] = {
    # --- app_user ---
    ("app_user", "id"): "INSERT `upsert_app_user` (V2) จาก `IdentityProvisioner`; อ่าน join `tenant_membership`",
    ("app_user", "tsf_user_id"): "INSERT/UPDATE `upsert_app_user` (V2) จาก `IdentityProvisioner`; ไม่มี reader บน main",
    ("app_user", "email"): "INSERT/UPDATE `upsert_app_user` (V2) จาก `IdentityProvisioner`; ไม่มี reader บน main",
    ("app_user", "display_name"): "INSERT/UPDATE `upsert_app_user` (V2) จาก `IdentityProvisioner`; ไม่มี reader บน main",
    ("app_user", "last_login_at"): "ตั้งโดย `upsert_app_user` (V2) ตอน JIT login; ไม่มี reader บน main",
    # --- tenant ---
    ("tenant", "id"): "INSERT `provision_tenant` (V2); อ่าน `MeService`, RLS",
    ("tenant", "name"): "INSERT/UPDATE `provision_tenant`, `MembershipChangedHandler`; อ่าน `MeService`",
    ("tenant", "tsf_shop_id"): "INSERT `provision_tenant`; อ่าน `MeService`, `CheckoutRepository`, `OutboxAppender`",
    ("tenant", "membership_tier"): "อัปเดต `MembershipChangedHandler`; อ่าน `MeService`",
    ("tenant", "entitlement_status"): "อัปเดต `MembershipChangedHandler`; อ่าน `MeService`, `TenantSessionService`, `InboxWorker`",
    ("tenant", "entitlement_expires_at"): "อัปเดต `MembershipChangedHandler`; อ่าน `MeService`, `CheckoutRepository`",
    ("tenant", "ent_ver"): "อัปเดต `MembershipChangedHandler`; อ่าน `TenantSessionService` (JWT gate)",
    # --- channel_account ---
    ("channel_account", "id"): "INSERT โดย `provision_tenant` (V10) ตอน JIT provision; อ่าน `ChannelAccountListingSyncController`, `CheckoutRepository`, `OrderQueryService`",
    ("channel_account", "tenant_id"): "INSERT โดย `provision_tenant` (V10); RLS กรอง",
    ("channel_account", "channel"): "INSERT โดย `provision_tenant` (V10); อ่าน `OrderQueryService`, `ChannelAccountLookup`",
    ("channel_account", "external_shop_id"): "INSERT โดย `provision_tenant` (V10); อ่าน `resolve_tenant` (V10)",
    ("channel_account", "mode"): "INSERT โดย `provision_tenant` (V10); อ่าน `CheckoutRepository`, `OrderHoldResolver`, `OrderQueryService`",
    ("channel_account", "status"): "INSERT โดย `provision_tenant` (V10); อ่าน `CheckoutRepository`, `OrderHoldResolver`, `OrderQueryService`",
    ("channel_account", "stock_sync_paused"): "ไม่มี writer บน main (default false); อ่าน `CheckoutRepository`",
    ("channel_account", "credentials_ref"): "reserved — ไม่มี writer/reader บน main",
    ("channel_account", "token_expires_at"): "reserved — ไม่มี writer/reader บน main",
    ("channel_account", "last_synced_at"): "เขียน `ChannelListingSyncService` (`UPDATE channel_account SET last_synced_at`); ไม่มี reader บน main",
    ("channel_account", "created_at"): "default ตอน INSERT `provision_tenant`; อ่าน `OrderDemoCatalogService` (ORDER BY created_at)",
    ("channel_account", "updated_at"): "trigger/default; ไม่มี writer แยกบน main",
    # --- channel_listing ---
    ("channel_listing", "id"): "INSERT `ChannelListingRepository` (upsert/ensureStub); อ่าน `ChannelListingRepository` list/detail",
    ("channel_listing", "tenant_id"): "INSERT `ChannelListingRepository`; RLS กรอง",
    ("channel_listing", "channel_account_id"): "INSERT/UPDATE `ChannelListingRepository`; อ่าน list filters, `OrderIntakeSupport` joins",
    ("channel_listing", "sku_id"): "เขียน `ChannelListingRepository.putManualMapping` / `clearMapping` / upsert auto-map; อ่าน list + mapping API",
    ("channel_listing", "external_item_id"): "reserved — ไม่ถูกอ้างอิงบน main",
    ("channel_listing", "external_sku_id"): "INSERT/UPDATE `ChannelListingRepository.upsertFromChannel`; อ่าน list, intake line match",
    ("channel_listing", "seller_sku"): "INSERT/UPDATE `ChannelListingRepository` (sync/inbox); อ่าน list UI, auto-map by `sku_code`",
    ("channel_listing", "name"): "INSERT/UPDATE `ChannelListingRepository` (sync/inbox); อ่าน list UI",
    ("channel_listing", "stock_control"): "INSERT `ChannelListingRepository` (stub/sync); อ่าน `OrderIntakeSupport`, `OrderHoldEffects`, `CheckoutRepository`",
    ("channel_listing", "safety_buffer"): "ไม่มี writer บน main (default 0); อ่าน `CheckoutRepository`, `StockRepository`, `StockAvailability`",
    ("channel_listing", "last_exposed_qty"): "reserved — ยังไม่มี writer บน main ([T15](../plan/05-task-list.md#L216))",
    ("channel_listing", "last_pushed_version"): "reserved — ยังไม่มี writer บน main ([T15](../plan/05-task-list.md#L216))",
    ("channel_listing", "last_seen_channel_qty"): "reserved — ยังไม่มี writer บน main ([T23](../plan/05-task-list.md#L245))",
    ("channel_listing", "mapping_source"): "เขียน `ChannelListingRepository.putManualMapping` / upsert AUTO; อ่าน list API",
    ("channel_listing", "mapped_at"): "เขียน `ChannelListingRepository.putManualMapping` / upsert; อ่าน list API",
    ("channel_listing", "removed_at"): "เขียน `ChannelListingRepository.markVanished` / `markRemoved`; inbox upsert ล้าง NULL; อ่าน list filter",
    ("channel_listing", "created_at"): "INSERT default; อ่าน list ordering",
    ("channel_listing", "updated_at"): "อัปเดต `ChannelListingRepository` (sync/map/removed); อ่าน sync guard (`updated_at < syncStartedAt`)",
    # --- sales_order ---
    ("sales_order", "id"): f"{INTAKE_INSERT}; อ่าน `OrderQueryService`, handlers",
    ("sales_order", "tenant_id"): f"{INTAKE_INSERT}; RLS กรอง",
    ("sales_order", "channel_account_id"): f"{INTAKE_INSERT}; อ่าน `OrderQueryService` list/filter",
    ("sales_order", "external_order_id"): f"{INTAKE_INSERT}; อ่าน `OrderQueryService`, `SalesOrderRepository.existsByExternalId`",
    ("sales_order", "order_status"): f"{STATE_MACHINE}; อ่าน `OrderQueryService`",
    ("sales_order", "payment_status"): f"{STATE_MACHINE}; อ่าน `OrderQueryService`",
    ("sales_order", "fulfillment_status"): f"{STATE_MACHINE}; อ่าน `OrderQueryService`",
    ("sales_order", "hold_reason"): f"{STATE_MACHINE} และ `OrderHoldEffects.applyHold`; อ่าน `OrderQueryService`",
    ("sales_order", "hold_note"): f"{STATE_MACHINE} / `OrderHoldEffects.applyHold`; อ่าน `OrderQueryService` detail",
    ("sales_order", "channel_status"): f"{INTAKE_INSERT}; {READ_REPO}",
    ("sales_order", "payment_method"): f"{INTAKE_INSERT}; อ่าน `OrderQueryService` list",
    ("sales_order", "currency"): f"{INTAKE_INSERT}; อ่าน `OrderQueryService` / detail",
    ("sales_order", "subtotal"): f"{INTAKE_INSERT}; อ่าน detail (`SalesOrder` จาก repository)",
    ("sales_order", "shipping_fee"): f"{INTAKE_INSERT}; อ่าน detail (`SalesOrder` จาก repository)",
    ("sales_order", "discount"): f"{INTAKE_INSERT}; อ่าน detail (`SalesOrder` จาก repository)",
    ("sales_order", "grand_total"): f"{INTAKE_INSERT}; อ่าน `OrderQueryService` list",
    ("sales_order", "ordered_at"): f"{INTAKE_INSERT}; อ่าน `OrderQueryService` list (sort/cursor)",
    ("sales_order", "paid_at"): f"{INTAKE_INSERT} และ {STATE_MACHINE}; อ่าน detail timeline",
    ("sales_order", "ship_by"): f"{INTAKE_INSERT}; อ่าน `OrderQueryService` list",
    ("sales_order", "completed_at"): "reserved — ไม่มี writer/reader บน main",
    ("sales_order", "external_version"): f"{INTAKE_INSERT}; {READ_REPO} (inbox aggregate version)",
    ("sales_order", "version"): f"{STATE_MACHINE}; optimistic lock ใน `SalesOrderRepository.updateStatusFields`",
    ("sales_order", "created_at"): "default ตอน insert; อ่าน ops",
    ("sales_order", "updated_at"): "อัปเดต `SalesOrderRepository.updateStatusFields`; อ่าน ops",
    # --- shipment ---
    ("shipment", "id"): "อ่าน `OrderQueryService.loadShipments`",
    ("shipment", "tenant_id"): "reserved [T18](../plan/05-task-list.md#L229) — ไม่มี reader บน main",
    ("shipment", "order_id"): "อ่าน `OrderQueryService` (filter `shipment.order_id` / join)",
    ("shipment", "warehouse_id"): "reserved [T18](../plan/05-task-list.md#L229) — ไม่มี reader บน main",
    ("shipment", "carrier"): "อ่าน `OrderQueryService.loadShipments`",
    ("shipment", "tracking_no"): "อ่าน `OrderQueryService.loadShipments` และค้นหา orders (`tracking_no = ?`)",
    ("shipment", "external_shipment_id"): "reserved [T18](../plan/05-task-list.md#L229) — ไม่มี reader บน main",
    ("shipment", "label_cached_until"): "reserved [T18](../plan/05-task-list.md#L229) — ไม่มี reader บน main",
    ("shipment", "status"): "อ่าน `OrderQueryService.loadShipments`",
    ("shipment", "shipped_at"): "อ่าน `OrderQueryService.loadShipments`",
    ("shipment", "delivered_at"): "reserved [T18](../plan/05-task-list.md#L229) — ไม่มี reader บน main",
    ("shipment", "created_at"): "reserved [T18](../plan/05-task-list.md#L229) — ไม่มี reader บน main",
    ("shipment", "updated_at"): "reserved [T18](../plan/05-task-list.md#L229) — ไม่มี reader บน main",
    # --- outbox / hold / inventory (round 3) ---
    ("outbox_event", "aggregate_type"): "INSERT `OutboxAppender`; อ่าน `OutboxAdminService.listDead`",
    ("outbox_event", "aggregate_id"): "INSERT `OutboxAppender`; อ่าน `OutboxAdminService.listDead`",
    ("outbox_event", "status"): "INSERT `OutboxAppender` (PENDING); อัปเดต `OutboxStore` (IN_FLIGHT→SENT/DEAD); admin retry → PENDING (`OutboxAdminService`)",
    ("outbox_event", "attempts"): "อัปเดต `OutboxStore` ตอน claim/fail; reset `OutboxAdminService.retry`",
    ("outbox_event", "next_attempt_at"): "อัปเดต `OutboxStore` backoff; NULL เมื่อ SENT/DEAD/retry",
    ("outbox_event", "lease_until"): "ตั้ง `OutboxStore` ตอน claim IN_FLIGHT; ล้างเมื่อส่งหรือคืน PENDING",
    ("outbox_event", "sent_at"): "อัปเดต `OutboxStore` เมื่อ SENT",
    ("outbox_event", "payload"): "INSERT `OutboxAppender`; อ่าน `OutboxStore` / `OutboxHttpSender`",
    ("outbox_event", "event_type"): "INSERT `OutboxAppender` (เช่น order.status_changed)",
    ("order_hold_retry", "attempts"): "เขียน `OrderHoldRetryRepository.recordBackoff` (scheduled sweeper); ลบแถวเมื่อ RELEASED/OUT_OF_STOCK",
    ("order_hold_retry", "next_attempt_at"): "เขียน `OrderHoldRetryRepository.recordBackoff` จาก scheduled hold-resolver job",
    ("order_hold_retry", "last_error"): "เขียน `recordBackoff` — โค้ด STILL_HELD | DEFERRED | simple name ของ exception (ไม่ใช่ message)",
    ("order_hold_retry", "order_id"): "PK; เขียน/ลบ `OrderHoldRetryRepository` จาก hold-resolver job",
    ("order_hold_retry", "tenant_id"): "PK; RLS",
    ("order_hold_retry", "updated_at"): "อัปเดต `OrderHoldRetryRepository`",
    ("inventory", "stock_version"): "อัปเดต `StockRepository` ทุกการเปลี่ยนสต็อก (+1 monotonic); อ่าน payload `stock.updated` ([T15](../plan/05-task-list.md#L216)) — ไม่ใช่ optimistic lock (lock แถว FOR UPDATE)",
    ("inventory", "ledger_seq"): "อัปเดต `StockRepository` ตอน lock แถว inventory",
    ("inventory", "on_hand"): "อัปเดต `StockRepository` / `ReservationEngine`",
    ("inventory", "reserved"): "อัปเดต `StockRepository` / `ReservationEngine`",
    ("inventory", "updated_at"): "อัปเดต `StockRepository`",
    ("stock_reservation", "id"): "INSERT `StockRepository`; อ่าน `OrderQueryService`",
    ("stock_reservation", "tenant_id"): "INSERT `StockRepository`; RLS",
    ("stock_reservation", "owner_type"): "INSERT/UPDATE `StockRepository`; อ่าน `StockRepository` (expiry queries)",
    ("stock_reservation", "owner_ref"): "INSERT/UPDATE `StockRepository`; อ่าน `OrderQueryService`",
    ("stock_reservation", "sku_id"): "INSERT `StockRepository` / `ReservationEngine`",
    ("stock_reservation", "warehouse_id"): "INSERT `StockRepository`",
    ("stock_reservation", "qty"): "INSERT `StockRepository` / `ReservationEngine`",
    ("stock_reservation", "reservation_group_id"): "INSERT `StockRepository`; อ่าน checkout response",
    ("stock_reservation", "created_at"): "INSERT default; อ่าน ops",
    ("stock_reservation", "updated_at"): "อัปเดต `StockRepository`",
    ("order_status_history", "id"): "INSERT `OrderStatusHistoryRepository`; อ่าน `OrderQueryService` timeline",
    ("order_status_history", "tenant_id"): "INSERT `OrderStatusHistoryRepository`; RLS",
    ("order_status_history", "order_id"): "INSERT `OrderStatusHistoryRepository` (caller order state machine)",
    ("order_status_history", "dimension"): "INSERT `OrderStatusHistoryRepository`; อ่าน `OrderQueryService`",
    ("order_status_history", "from_value"): "INSERT `OrderStatusHistoryRepository`",
    ("order_status_history", "to_value"): "INSERT `OrderStatusHistoryRepository`",
    ("order_status_history", "reason"): "INSERT `OrderStatusHistoryRepository`",
    ("order_status_history", "actor"): "INSERT `OrderStatusHistoryRepository`",
    ("order_status_history", "created_at"): "INSERT default; อ่าน timeline",
    ("stock_reservation", "expires_at"): "ตั้ง `CheckoutReserveService`/`ReservationEngine` (CHECKOUT TTL); ORDER unpaid PREPAID = payment_expires_at + grace (order intake adopt path); ล้าง NULL เมื่อ paid (`StockRepository`)",
    ("stock_reservation", "status"): "อัปเดต `ReservationEngine`, `StockExpiryJob` (EXPIRED), cancel/release paths",
    ("inbox_event", "id"): "INSERT `InboxIngestService`; อ่าน `InboxWorker`",
    ("inbox_event", "tenant_id"): "INSERT `InboxIngestService`; RLS กรอง",
    ("inbox_event", "source"): "INSERT `InboxIngestService`",
    ("inbox_event", "event_id"): "INSERT `InboxIngestService` (dedup); อ่าน `InboxWorker`",
    ("inbox_event", "event_type"): "INSERT `InboxIngestService`; อ่าน `InboxWorker` router",
    ("inbox_event", "aggregate_id"): "INSERT `InboxIngestService`; อ่าน `InboxWorker`",
    ("inbox_event", "payload"): "INSERT `InboxIngestService`; อ่าน `InboxWorker` handler",
    ("inbox_event", "status"): "INSERT `InboxIngestService` (RECEIVED); อัปเดต `InboxWorker` (PROCESSED/FAILED/DEAD)",
    ("inbox_event", "attempts"): "อัปเดต `InboxWorker` (claim/retry); อ่าน `InboxWorker`",
    ("inbox_event", "next_attempt_at"): "อัปเดต `InboxWorker`/`claim_inbox_batch` สำหรับ lease/retry",
    ("inbox_event", "last_error"): "อัปเดต `InboxWorker`; อ่าน `InboxWorker`",
    ("inbox_event", "received_at"): "INSERT default (DB); อ่าน `InboxWorker`",
    ("inbox_event", "processed_at"): "อัปเดต `InboxWorker` เมื่อ PROCESSED",
    ("inbox_event", "aggregate_version"): "INSERT `InboxIngestService`; อ่าน `InboxWorker` dedup",
    ("inbox_event", "payload_sha256"): "INSERT `InboxIngestService`; อ่าน `InboxIngestService` duplicate check",
    ("inbox_event", "orphan_recorded_at"): "อัปเดต `InboxWorker` เมื่อเกิน max-defer",
    # --- order_line ---
    ("order_line", "id"): "INSERT `OrderLineRepository` จาก `OrderIntakeSupport`; อ่าน `OrderQueryService`",
    ("order_line", "tenant_id"): "INSERT `OrderLineRepository`; RLS",
    ("order_line", "order_id"): "INSERT `OrderLineRepository`; อ่าน `OrderQueryService`",
    ("order_line", "sku_id"): "INSERT `OrderLineRepository`; อ่าน `OrderQueryService`",
    ("order_line", "external_line_id"): "INSERT `OrderIntakeSupport` / `OrderLineRepository`",
    ("order_line", "external_sku_id"): "INSERT `OrderLineRepository`; อ่าน hold resolver joins",
    ("order_line", "name"): "INSERT `OrderLineRepository`; อ่าน `OrderQueryService`",
    ("order_line", "qty"): "INSERT `OrderLineRepository`; อ่าน `OrderQueryService`",
    ("order_line", "unit_price"): "INSERT `OrderIntakeSupport` / `OrderLineRepository`; อ่าน `OrderQueryService`",
    ("order_line", "discount"): "INSERT `OrderLineRepository`; อ่าน `OrderQueryService`",
    ("order_line", "line_total"): "INSERT `OrderLineRepository`; อ่าน `OrderLineRepository`",
    ("order_line", "created_at"): "INSERT default; อ่าน `OrderQueryService`",
    ("order_line", "updated_at"): "default/trigger; ไม่มี writer แยกบน main",
    # --- idempotency_key ---
    ("idempotency_key", "tenant_id"): "INSERT `CheckoutIdempotency` / `StockIdempotency`; RLS",
    ("idempotency_key", "scope"): "INSERT idempotency helpers; อ่าน replay lookup",
    ("idempotency_key", "key"): "INSERT `CheckoutIdempotency` / `StockIdempotency`",
    ("idempotency_key", "request_hash"): "INSERT/SELECT `CheckoutIdempotency`, `StockIdempotency`",
    ("idempotency_key", "response_status"): "UPDATE `CheckoutIdempotency`, `StockIdempotency` complete",
    ("idempotency_key", "response_body"): "UPDATE `CheckoutIdempotency`, `StockIdempotency` complete",
    ("idempotency_key", "created_at"): "INSERT default; อ่าน ops",
    ("audit_log", "action"): "เขียน `TenantSessionService` (auth.login), `MembershipChangedHandler` (membership.changed), `CatalogAudit`, `ListingAudit`, `OrderAudit` (PRODUCT/SKU/CATALOG/CHANNEL_LISTING/ORDER_* constants), `OutboxAdminService` (outbox.retry); อ่าน ops/SQL",
}

TABLE_HINT: dict[str, str] = {
    "app_user": "`IdentityProvisioner` / `upsert_app_user` (V2)",
    "tenant": "`IdentityProvisioner`, `MembershipChangedHandler`",
    "tenant_membership": "`provision_membership` (V2)",
    "product": "`ProductService`",
    "sku": "`SkuService`",
    "sku_bundle_component": "`SkuService` components API",
    "warehouse": "`WarehouseService`",
    "inventory": "`StockRepository` / `ReservationEngine`",
    "inventory_ledger": "`StockRepository`",
    "stock_reservation": "`CheckoutReserveService`, `ReservationEngine`",
    "stock_document": "`StockDocumentService`",
    "stock_document_line": "`StockDocumentStore`",
    "order_line": "`OrderLineRepository`, `OrderIntakeSupport`",
    "order_recipient": "`OrderRecipientRepository`",
    "order_status_history": "`OrderStateMachine`",
    "inbox_event": "`InboxIngestService`, `InboxWorker`",
    "outbox_event": "`OutboxAppender`, `OutboxPublisher`",
    "idempotency_key": "`CheckoutIdempotency`, `StockIdempotency`, `OrderHoldRecheckIdempotency`",
    "shadow_diff": "`ShadowDiffRepository`, `CheckoutRepository`",
    "reconciliation_issue": "`ReconciliationIssueRepository`",
    "audit_log": "audit writers (ดูคอลัมน์ action)",
    "payment_status_snapshot": "reserved [T21](../plan/05-task-list.md#L237)",
    "refund": "reserved [T21](../plan/05-task-list.md#L237)",
    "return_request": "reserved [T20](../plan/05-task-list.md#L233)",
    "return_line": "reserved [T20](../plan/05-task-list.md#L233)",
    "sync_cursor": "reserved [T12C](../plan/05-task-list.md#L212) — ไม่มี writer/reader บน main",
}

READ_HINT: dict[str, str] = {
    "app_user": "join `tenant_membership` / `MeService`",
    "tenant": "`MeService`, `TenantSessionService`",
    "order_line": "`OrderQueryService`",
    "inbox_event": "`InboxWorker`",
    "outbox_event": "`OutboxAdminService`",
    "sync_cursor": "ไม่มี reader บน main ([T12C](../plan/05-task-list.md#L212))",
}


def generic_usage(table: str, col: str) -> str:
    key = (table, col)
    if key in OVERRIDES:
        return OVERRIDES[key]
    if table in ("payment_status_snapshot", "refund"):
        link = "[T21](../plan/05-task-list.md#L237)"
        return f"reserved — ไม่มี writer/reader บน main ({link})"
    if table in ("return_request", "return_line"):
        link = "[T20](../plan/05-task-list.md#L233)"
        return f"reserved — ไม่มี writer/reader บน main ({link})"
    if table == "sync_cursor":
        link = "[T12C](../plan/05-task-list.md#L212)"
        return f"reserved — ไม่มี writer/reader บน main ({link})"
    w = TABLE_HINT.get(table, f"service ของ `{table}`")
    r = READ_HINT.get(table, "repository/API ที่ query ตาราง")
    if col == "tenant_id":
        return "INSERT ใส่ tenant ปัจจุบัน; RLS กรองทุก SELECT"
    if col == "id":
        return f"PK; เขียน {w}; อ่าน {r}"
    if col == "created_at":
        return f"INSERT default now(); อ่าน {r}"
    if col == "updated_at":
        return f"อัปเดต {w}; อ่าน {r}"
    if col.endswith("_at") or col in ("ordered_at", "paid_at", "ship_by", "observed_at", "posted_at"):
        return f"เขียน {w}; อ่าน {r}"
    if col in ("version", "external_version", "ent_ver"):
        return f"เขียน {w}; อ่าน concurrency/dedup paths"
    return f"เขียน {w}; อ่าน {r}"


def _camel(snake: str) -> str:
    parts = snake.split("_")
    return parts[0] + "".join(p.title() for p in parts[1:])


def _usage_role_classes(text: str) -> list[tuple[str, str]]:
    """(class_name, 'write'|'read') pairs parsed from Thai usage line."""
    pairs: list[tuple[str, str]] = []
    if "อ่าน" in text:
        write_part, read_part = text.split("อ่าน", 1)
    else:
        write_part, read_part = text, ""
    for cls in re.findall(r"`([A-Z][A-Za-z0-9]+)`", write_part):
        if any(k in write_part for k in ("เขียน", "INSERT", "อัปเดต", "ตั้ง", "default")):
            pairs.append((cls, "write"))
    for cls in re.findall(r"`([A-Z][A-Za-z0-9]+)`", read_part):
        pairs.append((cls, "read"))
    return pairs


def verify_usage_against_java(usage: dict[tuple[str, str], str]) -> list[tuple[str, str, str, str]]:
    """Return list of (table, column, class, reason) for class/column mismatches."""
    skip_usage = ("reserved", "ไม่มี writer", "ไม่มี reader", "provision_tenant", "upsert_app_user", "claim_inbox_batch", "ops/SQL")
    java_classes = {p.stem for p in JAVA.rglob("*.java")}
    mismatches: list[tuple[str, str, str, str]] = []
    extra_needles: dict[str, set[str]] = {
        "external_version": {"externalVersion"},
        "channel_account_id": {"channelAccountId"},
        "external_order_id": {"externalOrderId"},
        "hold_reason": {"holdReason"},
        "hold_note": {"holdNote"},
        "payment_method": {"paymentMethod"},
        "grand_total": {"grandTotal"},
        "ordered_at": {"orderedAt"},
        "ship_by": {"shipBy"},
        "line_total": {"lineTotal"},
        "unit_price": {"unitPrice"},
        "tracking_no": {"trackingNo"},
        "stock_version": {"stockVersion"},
    }
    for (table, col), text in usage.items():
        if any(s in text for s in skip_usage):
            continue
        for cls, _role in _usage_role_classes(text):
            if cls not in java_classes:
                continue
            paths = list(JAVA.rglob(f"{cls}.java"))
            if not paths:
                continue
            body = paths[0].read_text(encoding="utf-8")
            needles = {col, _camel(col)}
            needles |= extra_needles.get(col, set())
            if not any(n in body for n in needles):
                mismatches.append((table, col, cls, text[:100]))
    return mismatches


def main():
    usage = {key: generic_usage(key[0], key[1]) for key in FIELDS}
    out = ROOT / "docs/tools/field_meta/field_usage.py"
    lines = [
        "# Auto-generated by docs/tools/generate_field_usage.py — edit OVERRIDES there and re-run.",
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

    mismatches = verify_usage_against_java(usage)
    print(f"usage/class-column mismatches: {len(mismatches)}")
    for m in mismatches[:15]:
        print(" ", m)
    if mismatches:
        sys.exit(1)


if __name__ == "__main__":
    main()
