package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.CandidateItem;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.CandidatesView;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.CreatePickListBody;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.CreatePickListResponse;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.PickLine;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.PickListDetail;
import com.thaishopfun.oms.fulfillment.FulfillmentViews.PickListOrderRef;
import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.web.OrderApiException;
import com.thaishopfun.oms.order.web.OrderAudit;
import com.thaishopfun.oms.order.web.OrderTransactions;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PickListService {

  private static final int MAX_ORDERS = 100;

  private final JdbcTemplate jdbc;
  private final OrderTransactions tx;
  private final SalesOrderRepository orders;
  private final OrderStateMachine stateMachine;
  private final FulfillmentAccess access;
  private final OrderAudit audit;
  private final PickListPdfRenderer pdfRenderer;

  public PickListService(
      JdbcTemplate jdbc,
      OrderTransactions tx,
      SalesOrderRepository orders,
      OrderStateMachine stateMachine,
      FulfillmentAccess access,
      OrderAudit audit,
      PickListPdfRenderer pdfRenderer) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.orders = orders;
    this.stateMachine = stateMachine;
    this.access = access;
    this.audit = audit;
    this.pdfRenderer = pdfRenderer;
  }

  public CandidatesView candidates() {
    return tx.read(
        () ->
            new CandidatesView(
                jdbc.query(
                    """
                    SELECT o.id, o.external_order_id, o.ordered_at, o.ship_by,
                      (SELECT count(*)::int FROM order_line ol WHERE ol.order_id = o.id) AS line_count
                    FROM sales_order o
                    WHERE o.fulfillment_status = 'READY_TO_PICK'
                      AND o.hold_reason = 'NONE'
                      AND o.order_status = 'ACTIVE'
                      AND o.payment_status IN ('PAID', 'COD_PENDING')
                      AND NOT EXISTS (
                        SELECT 1 FROM pick_list_order plo
                        WHERE plo.tenant_id = o.tenant_id AND plo.order_id = o.id
                          AND plo.released_at IS NULL
                      )
                    ORDER BY o.ship_by NULLS LAST, o.ordered_at, o.id
                    LIMIT 500
                    """,
                    (rs, rowNum) ->
                        new CandidateItem(
                            rs.getObject("id", UUID.class),
                            rs.getString("external_order_id"),
                            SalesOrderRepository.instant(rs.getTimestamp("ordered_at")),
                            SalesOrderRepository.instant(rs.getTimestamp("ship_by")),
                            rs.getInt("line_count")))));
  }

  public CreatePickListResponse create(CreatePickListBody body) {
    FulfillmentAccess.Actor actor = access.requireWriter();
    if (body == null || body.orderIds() == null || body.orderIds().isEmpty()) {
      throw OrderApiException.invalid("order_ids is required");
    }
    if (body.orderIds().size() > MAX_ORDERS) {
      throw OrderApiException.invalid("At most " + MAX_ORDERS + " orders per pick list");
    }
    List<UUID> requested = body.orderIds().stream().distinct().toList();
    if (requested.size() != body.orderIds().size()) {
      throw OrderApiException.invalid("order_ids must be unique");
    }

    return tx.write(
        () -> {
          // Step 1: Resolve default warehouse for the tenant.
          UUID warehouseId = requireDefaultWarehouse();

          // Step 2: Lock and validate every order.
          List<SalesOrder> locked = new ArrayList<>();
          for (UUID orderId : requested) {
            SalesOrder order =
                orders.findByIdForUpdate(orderId).orElseThrow(() -> OrderApiException.notFound());
            validatePickCandidate(order);
            locked.add(order);
          }

          // Step 3: Persist pick list and membership rows.
          UUID pickListId = UuidV7.generate();
          jdbc.update(
              """
              INSERT INTO pick_list (id, tenant_id, warehouse_id, created_by)
              VALUES (?, ?, ?, ?)
              """,
              pickListId,
              actor.tenantId(),
              warehouseId,
              actor.userId());
          int sequence = 0;
          for (SalesOrder order : locked) {
            jdbc.update(
                """
                INSERT INTO pick_list_order (tenant_id, pick_list_id, order_id, sequence)
                VALUES (?, ?, ?, ?)
                """,
                actor.tenantId(),
                pickListId,
                order.id(),
                sequence++);
          }

          // Step 4: Move fulfillment to PICKING for each order.
          OrderStateMachine.GuardContext guards =
              new OrderStateMachine.GuardContext(false, true, Instant.now());
          for (SalesOrder order : locked) {
            SalesOrder fresh = orders.findById(order.id()).orElseThrow(OrderApiException::notFound);
            stateMachine.applyFulfillmentStatus(
                fresh, "PICKING", "pick list " + pickListId, actor.userId().toString(), guards);
          }

          audit.writeEntity(
              actor.tenantId(),
              actor.userId(),
              "PICK_LIST_CREATED",
              "pick_list",
              pickListId,
              Map.of(),
              Map.of("order_count", locked.size(), "warehouse_id", warehouseId.toString()));

          return new CreatePickListResponse(
              pickListId, locked.size(), "/api/v1/pick-lists/" + pickListId + "/pdf");
        });
  }

  public PickListDetail detail(UUID pickListId) {
    return tx.read(
        () -> {
          List<PickListDetail> rows =
              jdbc.query(
                  """
                  SELECT pl.id, pl.warehouse_id, w.code AS warehouse_code, pl.created_at
                  FROM pick_list pl
                  JOIN warehouse w ON w.tenant_id = pl.tenant_id AND w.id = pl.warehouse_id
                  WHERE pl.id = ?
                  """,
                  (rs, rowNum) ->
                      new PickListDetail(
                          rs.getObject("id", UUID.class),
                          rs.getObject("warehouse_id", UUID.class),
                          rs.getString("warehouse_code"),
                          SalesOrderRepository.instant(rs.getTimestamp("created_at")),
                          List.of(),
                          List.of()),
                  pickListId);
          if (rows.isEmpty()) {
            throw OrderApiException.notFound();
          }
          PickListDetail header = rows.get(0);
          List<PickListOrderRef> orderRefs =
              jdbc.query(
                  """
                  SELECT o.id, o.external_order_id, plo.sequence
                  FROM pick_list_order plo
                  JOIN sales_order o ON o.tenant_id = plo.tenant_id AND o.id = plo.order_id
                  WHERE plo.pick_list_id = ?
                  ORDER BY plo.sequence
                  """,
                  (rs, rowNum) ->
                      new PickListOrderRef(
                          rs.getObject("id", UUID.class),
                          rs.getString("external_order_id"),
                          rs.getInt("sequence")),
                  pickListId);
          List<PickLine> lines = aggregatePickLines(pickListId);
          return new PickListDetail(
              header.id(),
              header.warehouseId(),
              header.warehouseCode(),
              header.createdAt(),
              orderRefs,
              lines);
        });
  }

  public byte[] pdf(UUID pickListId) {
    PickListDetail detail = detail(pickListId);
    return pdfRenderer.renderA6(detail);
  }

  private List<PickLine> aggregatePickLines(UUID pickListId) {
    return jdbc.query(
        """
        SELECT ol.sku_id, s.sku_code, s.barcode, s.name,
          coalesce(w.code, wh.code) AS warehouse_code, sum(ol.qty)::int AS qty
        FROM pick_list_order plo
        JOIN order_line ol ON ol.tenant_id = plo.tenant_id AND ol.order_id = plo.order_id
        JOIN sku s ON s.tenant_id = ol.tenant_id AND s.id = ol.sku_id
        JOIN pick_list pl ON pl.tenant_id = plo.tenant_id AND pl.id = plo.pick_list_id
        JOIN warehouse wh ON wh.tenant_id = pl.tenant_id AND wh.id = pl.warehouse_id
        LEFT JOIN LATERAL (
          SELECT w2.code
          FROM stock_reservation sr
          JOIN warehouse w2 ON w2.id = sr.warehouse_id AND w2.tenant_id = sr.tenant_id
          WHERE sr.tenant_id = ol.tenant_id
            AND sr.owner_type = 'ORDER'
            AND sr.owner_ref = ol.order_id::text
            AND sr.sku_id = ol.sku_id
            AND sr.status = 'ACTIVE'
          ORDER BY sr.id
          LIMIT 1
        ) w ON true
        WHERE plo.pick_list_id = ? AND ol.sku_id IS NOT NULL
        GROUP BY ol.sku_id, s.sku_code, s.barcode, s.name, coalesce(w.code, wh.code)
        ORDER BY s.sku_code
        """,
        (rs, rowNum) ->
            new PickLine(
                rs.getObject("sku_id", UUID.class),
                rs.getString("sku_code"),
                rs.getString("barcode"),
                rs.getString("name"),
                rs.getString("warehouse_code"),
                rs.getInt("qty")),
        pickListId);
  }

  private UUID requireDefaultWarehouse() {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT id FROM warehouse
            WHERE tenant_id = ? AND is_default = true
            ORDER BY created_at
            LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            TenantContext.requireTenantId());
    if (ids.isEmpty()) {
      throw new OrderApiException(
          422, "WAREHOUSE_MISSING", "Default warehouse is required before creating a pick list");
    }
    return ids.get(0);
  }

  private static void validatePickCandidate(SalesOrder order) {
    if (!"READY_TO_PICK".equals(order.fulfillmentStatus())) {
      throw OrderApiException.conflict(
          "ORDER_NOT_READY", "Order is not ready to pick: " + order.externalOrderId());
    }
    if (!"NONE".equals(order.holdReason())) {
      throw OrderApiException.conflict(
          "ORDER_ON_HOLD", "Held orders cannot join a pick list: " + order.externalOrderId());
    }
    if (!"ACTIVE".equals(order.orderStatus())) {
      throw OrderApiException.conflict(
          "ORDER_NOT_ACTIVE", "Order is not active: " + order.externalOrderId());
    }
    if (!Set.of("PAID", "COD_PENDING").contains(order.paymentStatus())) {
      throw OrderApiException.conflict(
          "ORDER_NOT_PAID", "Order payment is not ready: " + order.externalOrderId());
    }
  }
}
