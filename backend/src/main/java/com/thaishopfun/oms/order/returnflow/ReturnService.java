package com.thaishopfun.oms.order.returnflow;

import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.web.OrderAccess;
import com.thaishopfun.oms.order.web.OrderApiException;
import com.thaishopfun.oms.order.web.OrderAudit;
import com.thaishopfun.oms.order.web.OrderTransactions;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.stock.StockMovements;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReturnService {

  private static final Set<String> CONDITIONS = Set.of("RESELLABLE", "DAMAGED");

  private final OrderAccess access;
  private final OrderTransactions tx;
  private final SalesOrderRepository orders;
  private final ReturnRepository returns;
  private final StockMovements stock;
  private final OutboxAppender outbox;
  private final OrderAudit audit;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public ReturnService(
      OrderAccess access,
      OrderTransactions tx,
      SalesOrderRepository orders,
      ReturnRepository returns,
      StockMovements stock,
      OutboxAppender outbox,
      OrderAudit audit,
      JdbcTemplate jdbc,
      Clock clock) {
    this.access = access;
    this.tx = tx;
    this.orders = orders;
    this.returns = returns;
    this.stock = stock;
    this.outbox = outbox;
    this.audit = audit;
    this.jdbc = jdbc;
    this.clock = clock;
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

  public ReturnViews.ReceiveResponseView receive(UUID returnId, ReturnViews.ReceiveBody body) {
    OrderAccess.Actor actor = access.requireOwnerOrAdmin();
    if (body == null || body.lines() == null || body.lines().isEmpty()) {
      throw OrderApiException.fieldValidationFailed("lines", "lines is required");
    }
    return tx.write(
        () -> {
          ReturnRepository.ReturnRequestRow request =
              returns.findById(returnId).orElseThrow(OrderApiException::notFound);
          SalesOrder order =
              orders.findById(request.orderId()).orElseThrow(OrderApiException::notFound);
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
            int remaining =
                line.qty() - line.restockedQty() - line.damagedQty();
            if (resellable + damaged > remaining) {
              throw OrderApiException.conflict(
                  "RETURN_RECEIVE_QTY_EXCEEDED",
                  "Receive qty exceeds remaining units on return line");
            }
            if (line.skuId() == null) {
              throw OrderApiException.conflict("SKU_NOT_MAPPED", "Return line has no mapped SKU");
            }
            String chunkKey = idempotencyKey(returnId, line.id(), resellable, damaged, line);
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
            returns.updateLineReceiveProgress(
                line.id(), nextRestocked, nextDamaged, lineCondition);
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
          List<Map<String, Object>> outboxLines = new ArrayList<>();
          for (ReturnRepository.ReturnLineRow line : byId.values()) {
            outboxLines.add(
                Map.of(
                    "line_id",
                    line.externalLineId(),
                    "qty",
                    line.qty(),
                    "restocked_qty",
                    line.restockedQty(),
                    "damaged_qty",
                    line.damagedQty(),
                    "condition",
                    line.condition()));
          }
          long version =
              orders.findById(order.id()).orElseThrow(OrderApiException::notFound).version();
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
        });
  }

  private static String idempotencyKey(
      UUID returnId,
      UUID returnLineId,
      int resellableQty,
      int damagedQty,
      ReturnRepository.ReturnLineRow before) {
    int priorRestocked = before.restockedQty();
    int priorDamaged = before.damagedQty();
    return "return-receive:"
        + returnId
        + ":"
        + returnLineId
        + ":"
        + priorRestocked
        + "+"
        + resellableQty
        + ":"
        + priorDamaged
        + "+"
        + damagedQty;
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
