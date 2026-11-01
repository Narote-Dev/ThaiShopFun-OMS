package com.thaishopfun.oms.order.returnflow;

import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.web.OrderAccess;
import com.thaishopfun.oms.order.web.OrderApiException;
import com.thaishopfun.oms.order.web.OrderAudit;
import com.thaishopfun.oms.order.web.OrderTransactions;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.stock.RestockResult;
import com.thaishopfun.oms.stock.StockMovements;
import com.thaishopfun.oms.stock.WriteOffResult;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
          Instant receivedAt = clock.instant();
          List<Map<String, Object>> outboxLines = new ArrayList<>();
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
            ReturnRepository.ReturnLineRow line = byId.get(input.returnLineId());
            if (line == null) {
              throw OrderApiException.invalid("Unknown return line");
            }
            if (line.condition() != null) {
              continue;
            }
            if (line.skuId() == null) {
              throw OrderApiException.conflict("SKU_NOT_MAPPED", "Return line has no mapped SKU");
            }
            String idempotencyKey = "return-receive:" + returnId + ":" + line.id();
            if ("RESELLABLE".equals(condition)) {
              RestockResult restock =
                  stock.restockReturn(
                      line.id(), line.skuId(), null, line.qty(), idempotencyKey + ":restock");
              returns.updateLineCondition(line.id(), condition, restock.qty());
            } else {
              WriteOffResult writeOff =
                  stock.writeOffReturn(
                      line.id(), line.skuId(), null, line.qty(), idempotencyKey + ":writeoff");
              returns.updateLineCondition(line.id(), condition, 0);
              if (writeOff.qty() != line.qty()) {
                throw new IllegalStateException("write-off qty mismatch");
              }
            }
            outboxLines.add(
                Map.of(
                    "line_id", line.externalLineId(), "qty", line.qty(), "condition", condition));
          }
          returns.markReceived(returnId, receivedAt);
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
