package com.thaishopfun.oms.order.returnflow;

import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.web.OrderAccess;
import com.thaishopfun.oms.order.web.OrderApiException;
import com.thaishopfun.oms.order.web.OrderAudit;
import com.thaishopfun.oms.order.web.OrderTransactions;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.stock.IdempotencyConflictException;
import com.thaishopfun.oms.stock.StockMovements;
import com.thaishopfun.oms.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ReturnService {

  private static final Set<String> CONDITIONS = Set.of("RESELLABLE", "DAMAGED");
  static final int MAX_CLIENT_IDEMPOTENCY_KEY_LENGTH = 128;

  private final OrderAccess access;
  private final OrderTransactions tx;
  private final SalesOrderRepository orders;
  private final ReturnRepository returns;
  private final StockMovements stock;
  private final OutboxAppender outbox;
  private final OrderAudit audit;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final ReturnReceiveIdempotency idempotency;
  private final JsonMapper json;

  public ReturnService(
      OrderAccess access,
      OrderTransactions tx,
      SalesOrderRepository orders,
      ReturnRepository returns,
      StockMovements stock,
      OutboxAppender outbox,
      OrderAudit audit,
      JdbcTemplate jdbc,
      Clock clock,
      ReturnReceiveIdempotency idempotency,
      JsonMapper json) {
    this.access = access;
    this.tx = tx;
    this.orders = orders;
    this.returns = returns;
    this.stock = stock;
    this.outbox = outbox;
    this.audit = audit;
    this.jdbc = jdbc;
    this.clock = clock;
    this.idempotency = idempotency;
    this.json = json;
  }

  public ReturnViews.ReturnDetailView detail(UUID returnId) {
    return tx.read(
        () -> {
          access.actor();
          ReturnRepository.ReturnRequestRow request =
              returns.findById(returnId).orElseThrow(OrderApiException::notFound);
          SalesOrder order =
              orders.findById(request.orderId()).orElseThrow(OrderApiException::notFound);
          return toDetail(request, order);
        });
  }

  public ReturnViews.ReceiveResponseView receive(
      UUID returnId, String idempotencyKeyHeader, ReturnViews.ReceiveBody body) {
    OrderAccess.Actor actor = access.requireOwnerOrAdmin();
    if (body == null || body.lines() == null || body.lines().isEmpty()) {
      throw OrderApiException.fieldValidationFailed("lines", "lines is required");
    }
    if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
      throw OrderApiException.fieldValidationFailed(
          "Idempotency-Key", "Idempotency-Key is required");
    }
    String clientKey = idempotencyKeyHeader.trim();
    if (clientKey.length() > MAX_CLIENT_IDEMPOTENCY_KEY_LENGTH) {
      throw OrderApiException.fieldValidationFailed(
          "Idempotency-Key", "Idempotency-Key must be at most 128 characters");
    }
    String idempotencyKey = returnId + ":" + clientKey;
    String requestHash = sha256(returnId + "|" + clientKey + "|" + json.writeValueAsString(body));
    String stockScope = returnId + ":" + clientKey;

    ReturnReceiveIdempotency.LookupResult existing =
        tx.read(() -> idempotency.lookup(actor.tenantId(), idempotencyKey, requestHash));
    ReturnViews.ReceiveResponseView replay = replayIfCompleted(existing);
    if (replay != null) {
      return replay;
    }
    if (existing.state() == ReturnReceiveIdempotency.LookupState.IN_PROGRESS) {
      throw OrderApiException.idempotencyInProgress();
    }

    boolean claimed =
        tx.write(() -> idempotency.tryClaim(actor.tenantId(), idempotencyKey, requestHash));
    if (!claimed) {
      ReturnReceiveIdempotency.LookupResult raced =
          tx.read(() -> idempotency.lookup(actor.tenantId(), idempotencyKey, requestHash));
      replay = replayIfCompleted(raced);
      if (replay != null) {
        return replay;
      }
      throw OrderApiException.idempotencyInProgress();
    }

    try {
      ReturnViews.ReceiveResponseView response =
          tx.write(() -> doReceive(actor, returnId, body, stockScope));
      ObjectNode stored = json.valueToTree(response);
      tx.write(
          () -> {
            idempotency.complete(actor.tenantId(), idempotencyKey, 200, stored);
            return null;
          });
      return response;
    } catch (IdempotencyConflictException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      tx.write(
          () -> {
            idempotency.abandon(actor.tenantId(), idempotencyKey);
            return null;
          });
      throw ex;
    }
  }

  private ReturnViews.ReceiveResponseView doReceive(
      OrderAccess.Actor actor, UUID returnId, ReturnViews.ReceiveBody body, String stockScope) {
    ReturnRepository.ReturnRequestRow request =
        returns.findById(returnId).orElseThrow(OrderApiException::notFound);
    SalesOrder order = orders.findById(request.orderId()).orElseThrow(OrderApiException::notFound);
    if ("RECEIVED".equals(request.status())) {
      return new ReturnViews.ReceiveResponseView(
          request.id(), request.status(), request.receivedAt());
    }
    if (!"APPROVED".equals(request.status())) {
      throw OrderApiException.conflict(
          "RETURN_NOT_RECEIVABLE", "Return is not approved for receiving");
    }
    Map<UUID, ReturnRepository.ReturnLineRow> byId = new LinkedHashMap<>();
    for (ReturnRepository.ReturnLineRow line : returns.linesForReturn(returnId)) {
      byId.put(line.id(), line);
    }
    Map<UUID, List<ReturnViews.ReceiveLineBody>> grouped = new HashMap<>();
    for (ReturnViews.ReceiveLineBody input : body.lines()) {
      if (input.returnLineId() == null) {
        throw OrderApiException.fieldValidationFailed(
            "return_line_id", "return_line_id is required");
      }
      String condition = input.condition() == null ? "" : input.condition().trim();
      if (!CONDITIONS.contains(condition)) {
        throw OrderApiException.fieldValidationFailed(
            "condition", "condition must be RESELLABLE or DAMAGED");
      }
      if (input.qty() < 1) {
        throw OrderApiException.fieldValidationFailed("qty", "qty must be at least 1");
      }
      ReturnRepository.ReturnLineRow line = byId.get(input.returnLineId());
      if (line == null) {
        throw OrderApiException.invalid("Unknown return line");
      }
      grouped.computeIfAbsent(input.returnLineId(), ignored -> new ArrayList<>()).add(input);
    }

    for (Map.Entry<UUID, List<ReturnViews.ReceiveLineBody>> entry : grouped.entrySet()) {
      ReturnRepository.ReturnLineRow line = byId.get(entry.getKey());
      int resellable = 0;
      int damaged = 0;
      for (ReturnViews.ReceiveLineBody piece : entry.getValue()) {
        if ("RESELLABLE".equals(piece.condition())) {
          resellable += piece.qty();
        } else {
          damaged += piece.qty();
        }
      }
      int remaining = line.qty() - line.restockedQty() - line.damagedQty();
      if (resellable + damaged > remaining) {
        throw OrderApiException.conflict(
            "RETURN_RECEIVE_QTY_EXCEEDED", "Receive qty exceeds remaining units on return line");
      }
      if (line.skuId() == null) {
        throw OrderApiException.conflict("SKU_NOT_MAPPED", "Return line has no mapped SKU");
      }
      String chunkKey = stockIdempotencyKey(stockScope, line.id(), resellable, damaged);
      stock.receiveReturnUnits(
          line.id(),
          line.skuId(),
          null,
          resellable,
          damaged,
          chunkKey + ":restock",
          chunkKey + ":writeoff");
      int nextRestocked = line.restockedQty() + resellable;
      int nextDamaged = line.damagedQty() + damaged;
      String lineCondition = null;
      if (nextRestocked + nextDamaged == line.qty()) {
        lineCondition = summarizeCondition(nextRestocked, nextDamaged);
      }
      returns.updateLineReceiveProgress(line.id(), nextRestocked, nextDamaged, lineCondition);
      byId.put(
          line.id(),
          new ReturnRepository.ReturnLineRow(
              line.id(),
              line.orderId(),
              line.returnId(),
              line.orderLineId(),
              line.externalLineId(),
              line.qty(),
              lineCondition,
              nextRestocked,
              nextDamaged,
              line.skuId()));
    }

    boolean allComplete =
        byId.values().stream()
            .allMatch(line -> line.restockedQty() + line.damagedQty() == line.qty());
    if (!allComplete) {
      return new ReturnViews.ReceiveResponseView(returnId, "APPROVED", null);
    }

    Instant receivedAt = clock.instant();
    returns.markReceived(returnId, receivedAt);
    List<Map<String, Object>> outboxLines = contractReceivedLines(byId.values());
    long version = orders.findById(order.id()).orElseThrow(OrderApiException::notFound).version();
    outbox.append(
        OutboxDraft.of(
            "return",
            request.externalReturnId(),
            "return.received",
            Map.of(
                "order_id",
                order.externalOrderId(),
                "return_id",
                request.externalReturnId(),
                "lines",
                outboxLines),
            version));
    audit.writeEntity(
        actor.tenantId(),
        actor.userId(),
        "RETURN_RECEIVED",
        "return_request",
        returnId,
        Map.of("status", request.status()),
        Map.of("status", "RECEIVED"));
    return new ReturnViews.ReceiveResponseView(returnId, "RECEIVED", receivedAt);
  }

  static List<Map<String, Object>> contractReceivedLines(
      Iterable<ReturnRepository.ReturnLineRow> lines) {
    List<Map<String, Object>> outboxLines = new ArrayList<>();
    for (ReturnRepository.ReturnLineRow line : lines) {
      if (line.restockedQty() > 0) {
        outboxLines.add(
            Map.of(
                "line_id",
                line.externalLineId(),
                "qty",
                line.restockedQty(),
                "condition",
                "RESELLABLE"));
      }
      if (line.damagedQty() > 0) {
        outboxLines.add(
            Map.of(
                "line_id",
                line.externalLineId(),
                "qty",
                line.damagedQty(),
                "condition",
                "DAMAGED"));
      }
    }
    return outboxLines;
  }

  private ReturnViews.ReceiveResponseView replayIfCompleted(
      ReturnReceiveIdempotency.LookupResult lookup) {
    if (lookup.state() != ReturnReceiveIdempotency.LookupState.COMPLETED) {
      return null;
    }
    return json.treeToValue(lookup.stored().body(), ReturnViews.ReceiveResponseView.class);
  }

  private static String stockIdempotencyKey(
      String stockScope, UUID returnLineId, int resellableQty, int damagedQty) {
    return "return-receive:"
        + stockScope
        + ":"
        + returnLineId
        + ":r"
        + resellableQty
        + ":d"
        + damagedQty;
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

  private static String summarizeCondition(int restocked, int damaged) {
    if (restocked > 0 && damaged > 0) {
      return "MIXED";
    }
    if (damaged > 0) {
      return "DAMAGED";
    }
    return "RESELLABLE";
  }

  private ReturnViews.ReturnDetailView toDetail(
      ReturnRepository.ReturnRequestRow request, SalesOrder order) {
    List<ReturnViews.ReturnLineView> lines =
        returns.linesForReturn(request.id()).stream()
            .map(
                line ->
                    new ReturnViews.ReturnLineView(
                        line.id(),
                        line.orderLineId(),
                        line.externalLineId(),
                        lineName(line.orderLineId()),
                        line.qty(),
                        line.condition(),
                        line.restockedQty(),
                        line.damagedQty(),
                        line.skuId()))
            .toList();
    boolean canReceive = "APPROVED".equals(request.status());
    return new ReturnViews.ReturnDetailView(
        request.id(),
        order.id(),
        order.externalOrderId(),
        request.externalReturnId(),
        request.type(),
        request.status(),
        request.reason(),
        request.requestedAt(),
        request.receivedAt(),
        lines,
        canReceive);
  }

  private String lineName(UUID orderLineId) {
    return jdbc
        .query(
            """
            SELECT name FROM order_line WHERE tenant_id = ? AND id = ?
            """,
            (rs, rowNum) -> rs.getString("name"),
            TenantContext.requireTenantId(),
            orderLineId)
        .stream()
        .findFirst()
        .orElse("—");
  }
}
