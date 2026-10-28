package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.fulfillment.FulfillmentViews.CompletePackResponse;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.PackLine;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.PackRecipient;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.PackSession;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.ScanBody;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.ScanResponse;
import com.thaishopfun.oms.order.OrderLineRepository;
import com.thaishopfun.oms.order.OrderLineRepository.OrderLine;
import com.thaishopfun.oms.order.OrderOptimisticLockException;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStateException;
import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.StoredRecipient;
import com.thaishopfun.oms.order.web.OrderApiException;
import com.thaishopfun.oms.order.web.OrderAudit;
import com.thaishopfun.oms.order.web.OrderTransactions;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PackService {

  private final JdbcTemplate jdbc;
  private final OrderTransactions tx;
  private final SalesOrderRepository orders;
  private final OrderLineRepository lines;
  private final OrderRecipientRepository recipients;
  private final OrderStateMachine stateMachine;
  private final OrderStatusHistoryRepository statusHistory;
  private final FulfillmentAccess access;
  private final OrderAudit audit;

  public PackService(
      JdbcTemplate jdbc,
      OrderTransactions tx,
      SalesOrderRepository orders,
      OrderLineRepository lines,
      OrderRecipientRepository recipients,
      OrderStateMachine stateMachine,
      OrderStatusHistoryRepository statusHistory,
      FulfillmentAccess access,
      OrderAudit audit) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.orders = orders;
    this.lines = lines;
    this.recipients = recipients;
    this.stateMachine = stateMachine;
    this.statusHistory = statusHistory;
    this.access = access;
    this.audit = audit;
  }

  public PackSession session(UUID orderId) {
    FulfillmentAccess.Actor actor = access.actor();
    return tx.write(
        () -> {
          SalesOrder order = orders.findById(orderId).orElseThrow(OrderApiException::notFound);
          validatePackSessionStatus(order.fulfillmentStatus());
          PackSession session = buildSession(order, loadLines(orderId));
          if ("PICKING".equals(order.fulfillmentStatus())
              && !packPiiViewAlreadyAudited(actor, orderId)) {
            audit.writeEntity(
                actor.tenantId(),
                actor.userId(),
                "ORDER_PACK_PII_VIEWED",
                "sales_order",
                orderId,
                Map.of(),
                Map.of("fulfillment_status", order.fulfillmentStatus()));
          }
          return session;
        });
  }

  public ScanResponse scan(UUID orderId, ScanBody body) {
    FulfillmentAccess.Actor actor = access.requireWriter();
    if (body == null || body.scan() == null || body.scan().isBlank()) {
      throw OrderApiException.fieldValidationFailed("scan", "scan is required");
    }
    String scan = body.scan().trim();

    return tx.write(
        () -> {
          // Step 1: Lock order and validate status.
          SalesOrder order =
              orders.findByIdForUpdate(orderId).orElseThrow(OrderApiException::notFound);
          validatePackableStatus(order.fulfillmentStatus());

          // Step 2: Resolve SKU and the next order line that still needs scans.
          SkuMatch sku = resolveSku(scan).orElseThrow(() -> scanUnknown(scan));
          OrderLine line = findScannableLine(orderId, sku).orElseThrow(() -> scanMismatch(scan));

          // Step 3: Load progress and enforce qty limits.
          int required = line.qty();
          int packed = progressQty(orderId, line.id());
          if (packed >= required) {
            throw OrderApiException.conflict(
                "PACK_SCAN_OVERFLOW", "Scan exceeds required quantity for " + sku.code());
          }

          // Step 4: Increment progress.
          upsertProgress(orderId, line.id(), packed + 1);
          int nextPacked = packed + 1;
          boolean complete = allLinesPacked(orderId);
          return new ScanResponse(line.id(), sku.code(), nextPacked, required, complete);
        });
  }

  public CompletePackResponse complete(UUID orderId) {
    FulfillmentAccess.Actor actor = access.requireWriter();
    return tx.write(
        () -> {
          SalesOrder order =
              orders.findByIdForUpdate(orderId).orElseThrow(OrderApiException::notFound);
          validatePackableStatus(order.fulfillmentStatus());
          if (!allLinesPacked(orderId)) {
            throw OrderApiException.conflict(
                "PACK_INCOMPLETE", "Not all lines are fully scanned yet");
          }
          OrderStateMachine.GuardContext guards =
              new OrderStateMachine.GuardContext(false, true, Instant.now());
          try {
            stateMachine.applyFulfillmentStatus(
                order, "PACKED", "pack scan complete", actor.userId().toString(), guards);
          } catch (OrderStateException ex) {
            throw OrderApiException.conflict("FULFILLMENT_STATE", ex.getMessage());
          } catch (OrderOptimisticLockException ex) {
            throw OrderApiException.conflict("ORDER_OPTIMISTIC_LOCK", ex.getMessage());
          }
          SalesOrder updated = orders.findById(orderId).orElseThrow(OrderApiException::notFound);
          releasePickListMembership(orderId);
          audit.writeEntity(
              actor.tenantId(),
              actor.userId(),
              "ORDER_PACKED",
              "sales_order",
              orderId,
              Map.of("fulfillment_status", order.fulfillmentStatus()),
              Map.of("fulfillment_status", updated.fulfillmentStatus()));
          return new CompletePackResponse(updated.fulfillmentStatus(), updated.version());
        });
  }

  private boolean packPiiViewAlreadyAudited(FulfillmentAccess.Actor actor, UUID orderId) {
    java.time.Instant pickingSince =
        statusHistory
            .transitionedAt(orderId, "FULFILLMENT", "PICKING")
            .orElse(java.time.Instant.EPOCH);
    List<Integer> found =
        jdbc.query(
            """
            SELECT 1 FROM audit_log
            WHERE tenant_id = ?
              AND actor_type = 'USER'
              AND actor_id = ?
              AND action = 'ORDER_PACK_PII_VIEWED'
              AND entity_type = 'sales_order'
              AND entity_id = ?
              AND created_at >= ?
            LIMIT 1
            """,
            (rs, rowNum) -> 1,
            actor.tenantId(),
            actor.userId().toString(),
            orderId.toString(),
            java.sql.Timestamp.from(pickingSince));
    return !found.isEmpty();
  }

  private PackSession buildSession(SalesOrder order, List<PackLine> packLines) {
    StoredRecipient stored = recipients.find(order.id()).orElseThrow(OrderApiException::notFound);
    if (stored.redacted()) {
      throw OrderApiException.conflict("PII_REDACTED", "Recipient PII is no longer available");
    }
    PackRecipient recipient =
        new PackRecipient(
            stored.name(),
            stored.phone(),
            stored.address(),
            stored.province(),
            stored.postcode(),
            stored.piiStatus());
    boolean complete = packLines.stream().allMatch(line -> line.packedQty() >= line.requiredQty());
    return new PackSession(
        order.id(),
        order.externalOrderId(),
        order.fulfillmentStatus(),
        order.version(),
        recipient,
        packLines,
        complete);
  }

  private List<PackLine> loadLines(UUID orderId) {
    List<OrderLine> orderLines = lines.findByOrderId(orderId);
    List<PackLine> result = new ArrayList<>();
    for (OrderLine line : orderLines) {
      String skuCode = null;
      String barcode = null;
      if (line.skuId() != null) {
        SkuMatch sku =
            jdbc
                .query(
                    """
                    SELECT id, sku_code, barcode FROM sku WHERE id = ?
                    """,
                    (rs, rowNum) ->
                        new SkuMatch(
                            rs.getObject("id", UUID.class),
                            rs.getString("sku_code"),
                            rs.getString("barcode")),
                    line.skuId())
                .stream()
                .findFirst()
                .orElse(null);
        if (sku != null) {
          skuCode = sku.code();
          barcode = sku.barcode();
        }
      }
      int packed = line.skuId() == null ? 0 : progressQty(orderId, line.id());
      result.add(
          new PackLine(
              line.id(),
              line.skuId(),
              skuCode,
              barcode,
              line.name(),
              line.qty(),
              packed,
              line.skuId() != null));
    }
    return result;
  }

  private int progressQty(UUID orderId, UUID lineId) {
    List<Integer> values =
        jdbc.query(
            """
            SELECT packed_qty FROM order_pack_progress
            WHERE order_id = ? AND order_line_id = ?
            """,
            (rs, rowNum) -> rs.getInt("packed_qty"),
            orderId,
            lineId);
    return values.isEmpty() ? 0 : values.get(0);
  }

  private void upsertProgress(UUID orderId, UUID lineId, int packedQty) {
    UUID tenantId = TenantContext.requireTenantId();
    int updated =
        jdbc.update(
            """
            UPDATE order_pack_progress
            SET packed_qty = ?
            WHERE tenant_id = ? AND order_id = ? AND order_line_id = ?
            """,
            packedQty,
            tenantId,
            orderId,
            lineId);
    if (updated == 0) {
      jdbc.update(
          """
          INSERT INTO order_pack_progress (tenant_id, order_id, order_line_id, packed_qty)
          VALUES (?, ?, ?, ?)
          """,
          tenantId,
          orderId,
          lineId,
          packedQty);
    }
  }

  private boolean allLinesPacked(UUID orderId) {
    for (PackLine line : loadLines(orderId)) {
      if (!line.mapped()) {
        return false;
      }
      if (line.packedQty() < line.requiredQty()) {
        return false;
      }
    }
    return true;
  }

  private java.util.Optional<OrderLine> findScannableLine(UUID orderId, SkuMatch sku) {
    List<OrderLine> matching =
        lines.findByOrderId(orderId).stream()
            .filter(row -> sku.id().equals(row.skuId()))
            .sorted(java.util.Comparator.comparing(OrderLine::id))
            .toList();
    if (matching.isEmpty()) {
      return java.util.Optional.empty();
    }
    for (OrderLine line : matching) {
      if (progressQty(orderId, line.id()) < line.qty()) {
        return java.util.Optional.of(line);
      }
    }
    throw OrderApiException.conflict(
        "PACK_SCAN_OVERFLOW", "Scan exceeds required quantity for " + sku.code());
  }

  private void releasePickListMembership(UUID orderId) {
    jdbc.update(
        """
        UPDATE pick_list_order
        SET released_at = now()
        WHERE tenant_id = ? AND order_id = ? AND released_at IS NULL
        """,
        TenantContext.requireTenantId(),
        orderId);
  }

  private java.util.Optional<SkuMatch> resolveSku(String scan) {
    List<SkuMatch> matches =
        jdbc.query(
            """
            SELECT id, sku_code, barcode FROM sku
            WHERE tenant_id = ?
              AND (lower(btrim(sku_code)) = lower(btrim(?))
                OR (barcode IS NOT NULL AND btrim(barcode) = btrim(?)))
            LIMIT 2
            """,
            (rs, rowNum) ->
                new SkuMatch(
                    rs.getObject("id", UUID.class),
                    rs.getString("sku_code"),
                    rs.getString("barcode")),
            TenantContext.requireTenantId(),
            scan,
            scan);
    if (matches.isEmpty()) {
      return java.util.Optional.empty();
    }
    if (matches.size() > 1) {
      throw OrderApiException.conflict("PACK_SCAN_AMBIGUOUS", "Scan matches more than one SKU");
    }
    return java.util.Optional.of(matches.get(0));
  }

  private static void validatePackSessionStatus(String fulfillment) {
    if (!"PICKING".equals(fulfillment) && !"PACKED".equals(fulfillment)) {
      throw OrderApiException.conflict(
          "ORDER_NOT_PACKING", "Order must be in PICKING to scan or complete pack");
    }
  }

  private static void validatePackableStatus(String fulfillment) {
    if (!"PICKING".equals(fulfillment)) {
      throw OrderApiException.conflict(
          "ORDER_NOT_PACKING", "Order must be in PICKING to scan or complete pack");
    }
  }

  private static OrderApiException scanUnknown(String scan) {
    return new OrderApiException(422, "PACK_SCAN_UNKNOWN", "Unknown barcode or SKU: " + scan);
  }

  private static OrderApiException scanMismatch(String scan) {
    return new OrderApiException(
        422, "PACK_SCAN_MISMATCH", "Scanned item is not on this order: " + scan);
  }

  private record SkuMatch(UUID id, String code, String barcode) {}
}
