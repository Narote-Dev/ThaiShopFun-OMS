package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob.ReevalSummary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OrderHoldRecheckService {

  private final OrderAccess access;
  private final OrderTransactions tx;
  private final SalesOrderRepository orders;
  private final OrderHoldResolverJob resolverJob;
  private final OrderHoldRecheckIdempotency idempotency;
  private final OrderAudit audit;
  private final JsonMapper json;

  public OrderHoldRecheckService(
      OrderAccess access,
      OrderTransactions tx,
      SalesOrderRepository orders,
      OrderHoldResolverJob resolverJob,
      OrderHoldRecheckIdempotency idempotency,
      OrderAudit audit,
      JsonMapper json) {
    this.access = access;
    this.tx = tx;
    this.orders = orders;
    this.resolverJob = resolverJob;
    this.idempotency = idempotency;
    this.audit = audit;
    this.json = json;
  }

  public OrderViews.HoldRecheckResponse recheck(UUID orderId, String idempotencyKeyHeader) {
    OrderAccess.Actor actor = access.requireOwnerOrAdmin();
    if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
      throw OrderApiException.fieldValidationFailed("Idempotency-Key", "Idempotency-Key is required");
    }
    String key = "hold-recheck:" + orderId;
    String hash = sha256(orderId + "|" + idempotencyKeyHeader);
    OrderHoldRecheckIdempotency.Stored stored =
        idempotency.claim(actor.tenantId(), key, hash);
    if (stored != null) {
      return json.treeToValue(stored.body(), OrderViews.HoldRecheckResponse.class);
    }
    SalesOrder before =
        tx.read(() -> orders.findById(orderId).orElseThrow(OrderApiException::notFound));
    if (!"SKU_NOT_MAPPED".equals(before.holdReason())
        && !"OUT_OF_STOCK".equals(before.holdReason())) {
      throw OrderApiException.conflict("HOLD_NOT_RECHECKABLE", "Order hold is not recheckable");
    }
    ReevalSummary summary =
        resolverJob.resolveOrderRecheck(orderId, idempotencyKeyHeader.trim());
    SalesOrder after =
        tx.write(
            () -> {
              SalesOrder fresh = orders.findById(orderId).orElseThrow(OrderApiException::notFound);
              audit.write(
                  actor,
                  "ORDER_HOLD_RECHECKED",
                  orderId,
                  Map.of("hold_reason", before.holdReason()),
                  Map.of("hold_reason", fresh.holdReason()));
              return fresh;
            });
    OrderViews.HoldRecheckResponse response =
        new OrderViews.HoldRecheckResponse(
            after.id(),
            after.holdReason(),
            summary.released(),
            summary.outOfStock(),
            summary.stillHeld());
    ObjectNode body = json.valueToTree(response);
    tx.write(
        () -> {
          idempotency.complete(actor.tenantId(), key, 200, body);
          return null;
        });
    return response;
  }

  private static String sha256(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
