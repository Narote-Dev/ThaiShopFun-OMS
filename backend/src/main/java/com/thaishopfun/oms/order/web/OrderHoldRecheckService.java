package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob.ReevalSummary;
import com.thaishopfun.oms.stock.IdempotencyConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OrderHoldRecheckService {

  static final int MAX_CLIENT_IDEMPOTENCY_KEY_LENGTH = 128;

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
      throw OrderApiException.fieldValidationFailed(
          "Idempotency-Key", "Idempotency-Key is required");
    }
    String clientKey = idempotencyKeyHeader.trim();
    if (clientKey.length() > MAX_CLIENT_IDEMPOTENCY_KEY_LENGTH) {
      throw new OrderApiException(
          422,
          "VALIDATION_FAILED",
          "Idempotency-Key must be at most 128 characters",
          List.of(
              Map.of(
                  "field",
                  "Idempotency-Key",
                  "message",
                  "Idempotency-Key must be at most 128 characters")));
    }
    String key = orderId + ":" + clientKey;
    String hash = sha256(orderId + "|" + clientKey);

    tx.read(() -> orders.findById(orderId).orElseThrow(OrderApiException::notFound));

    OrderHoldRecheckIdempotency.LookupResult existing =
        tx.read(() -> idempotency.lookup(actor.tenantId(), key, hash));
    OrderViews.HoldRecheckResponse replay = replayIfCompleted(existing);
    if (replay != null) {
      return replay;
    }
    if (existing.state() == OrderHoldRecheckIdempotency.LookupState.IN_PROGRESS) {
      throw OrderApiException.idempotencyInProgress();
    }

    SalesOrder before =
        tx.read(() -> orders.findById(orderId).orElseThrow(OrderApiException::notFound));
    if (!isRecheckable(before)) {
      throw OrderApiException.conflict("HOLD_NOT_RECHECKABLE", "Order hold is not recheckable");
    }

    boolean claimed = tx.write(() -> idempotency.tryClaim(actor.tenantId(), key, hash));
    if (!claimed) {
      OrderHoldRecheckIdempotency.LookupResult raced =
          tx.read(() -> idempotency.lookup(actor.tenantId(), key, hash));
      replay = replayIfCompleted(raced);
      if (replay != null) {
        return replay;
      }
      throw OrderApiException.idempotencyInProgress();
    }

    try {
      ReevalSummary summary = resolverJob.resolveOrderRecheck(orderId, clientKey);
      return tx.write(
          () -> {
            SalesOrder after = orders.findById(orderId).orElseThrow(OrderApiException::notFound);
            audit.write(
                actor,
                "ORDER_HOLD_RECHECKED",
                orderId,
                Map.of("hold_reason", before.holdReason()),
                Map.of("hold_reason", after.holdReason()));
            OrderViews.HoldRecheckResponse response =
                new OrderViews.HoldRecheckResponse(
                    after.id(),
                    after.holdReason(),
                    summary.released(),
                    summary.outOfStock(),
                    summary.stillHeld());
            ObjectNode body = json.valueToTree(response);
            idempotency.complete(actor.tenantId(), key, 200, body);
            return response;
          });
    } catch (IdempotencyConflictException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      tx.write(
          () -> {
            idempotency.abandon(actor.tenantId(), key);
            return null;
          });
      throw ex;
    }
  }

  private OrderViews.HoldRecheckResponse replayIfCompleted(
      OrderHoldRecheckIdempotency.LookupResult lookup) {
    if (lookup.state() != OrderHoldRecheckIdempotency.LookupState.COMPLETED) {
      return null;
    }
    return json.treeToValue(lookup.stored().body(), OrderViews.HoldRecheckResponse.class);
  }

  private static boolean isRecheckable(SalesOrder order) {
    return "ACTIVE".equals(order.orderStatus())
        && "UNFULFILLED".equals(order.fulfillmentStatus())
        && ("SKU_NOT_MAPPED".equals(order.holdReason())
            || "OUT_OF_STOCK".equals(order.holdReason()));
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
