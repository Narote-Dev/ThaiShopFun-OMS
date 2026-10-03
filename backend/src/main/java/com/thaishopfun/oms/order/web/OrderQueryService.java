package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.catalog.Fields;
import com.thaishopfun.oms.catalog.PageResult;
import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAdapter;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.order.OrderLineRepository;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderQueryService {

  private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Bangkok");

  private final JdbcTemplate jdbc;
  private final OrderTransactions tx;
  private final SalesOrderRepository orders;
  private final OrderRecipientRepository recipients;
  private final OrderLineRepository lines;
  private final OrderStatusHistoryRepository history;
  private final ChannelAdapterRegistry adapters;
  private final OrderRecipientMaskedReader maskedRecipients;

  private static final String BASE_FROM =
      """
      FROM sales_order o
      JOIN channel_account ca ON ca.tenant_id = o.tenant_id AND ca.id = o.channel_account_id
      """;

  public OrderQueryService(
      JdbcTemplate jdbc,
      OrderTransactions tx,
      SalesOrderRepository orders,
      OrderRecipientRepository recipients,
      OrderLineRepository lines,
      OrderStatusHistoryRepository history,
      ChannelAdapterRegistry adapters,
      OrderRecipientMaskedReader maskedRecipients) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.orders = orders;
    this.recipients = recipients;
    this.lines = lines;
    this.history = history;
    this.adapters = adapters;
    this.maskedRecipients = maskedRecipients;
  }

  public OrderViews.Page<OrderViews.ListItem> list(
      String orderStatus,
      String paymentStatus,
      String fulfillmentStatus,
      String holdReason,
      String channel,
      UUID channelAccountId,
      String orderedFrom,
      String orderedTo,
      String q,
      Integer limit,
      String cursorRaw) {
    int pageLimit = PageResult.limit(limit);
    return tx.read(
        () -> {
          SearchClause search = resolveSearch(q);
          Filter filter =
              Filter.build(
                  orderStatus,
                  paymentStatus,
                  fulfillmentStatus,
                  holdReason,
                  channel,
                  channelAccountId,
                  orderedFrom,
                  orderedTo,
                  search);
          OrderListCursor cursor =
              cursorRaw == null || cursorRaw.isBlank()
                  ? OrderListCursor.firstPage(Instant.now())
                  : OrderListCursor.decode(cursorRaw);
          List<Object> countParams = new ArrayList<>(filter.params());
          StringBuilder countWhere = new StringBuilder(filter.where());
          countWhere.append(" AND o.created_at <= ?");
          countParams.add(java.sql.Timestamp.from(cursor.snapshotBefore()));
          long total =
              jdbc.queryForObject(
                  "SELECT count(*) " + BASE_FROM + filter.joins() + countWhere,
                  Long.class,
                  countParams.toArray());
          List<Object> whereParams = new ArrayList<>(filter.params());
          StringBuilder where = new StringBuilder(filter.where());
          where.append(" AND o.created_at <= ?");
          whereParams.add(java.sql.Timestamp.from(cursor.snapshotBefore()));
          if (cursor.hasKeyset()) {
            where.append(" AND (o.ordered_at < ? OR (o.ordered_at = ? AND o.id < ?))");
            whereParams.add(java.sql.Timestamp.from(cursor.orderedAt()));
            whereParams.add(java.sql.Timestamp.from(cursor.orderedAt()));
            whereParams.add(cursor.id());
          }
          List<Object> pageParams = new ArrayList<>(whereParams);
          pageParams.add(pageLimit);
          List<OrderViews.ListItem> items =
              jdbc.query(
                  """
                  SELECT o.id, o.external_order_id, o.order_status, o.payment_status,
                         o.fulfillment_status, o.hold_reason, o.payment_method, o.grand_total,
                         o.ordered_at, o.ship_by, o.channel_account_id, ca.channel,
                         r.phone_last4
                  """
                      + BASE_FROM
                      + filter.joins()
                      + where
                      + " ORDER BY o.ordered_at DESC, o.id DESC LIMIT ?",
                  this::mapListItem,
                  pageParams.toArray());
          String next = null;
          if (!items.isEmpty() && items.size() == pageLimit) {
            OrderViews.ListItem last = items.get(items.size() - 1);
            next = cursor.encode(last.orderedAt(), last.id());
          }
          return new OrderViews.Page<>(items, total, pageLimit, next);
        });
  }

  public OrderViews.DetailView detail(UUID id) {
    return tx.read(
        () -> {
          SalesOrder order = orders.findById(id).orElseThrow(OrderApiException::notFound);
          ChannelAccountRow account =
              channelAccount(order.channelAccountId()).orElseThrow(OrderApiException::notFound);
          ChannelAdapter cancelAdapter = adapters.optional(Channel.valueOf(account.channel()));
          boolean supportsCancel =
              cancelAdapter != null && cancelAdapter.capabilities().supportsCancelRequest();
          List<OrderViews.LineView> lineViews = loadLines(order.id());
          List<OrderViews.ReservationView> reservations = loadReservations(order.id());
          List<OrderViews.ShipmentView> shipments = loadShipments(order.id());
          OrderViews.RecipientView recipient = loadRecipient(order.id());
          List<OrderViews.TimelineEntry> timeline =
              history.findByOrderId(order.id()).stream()
                  .map(
                      row ->
                          new OrderViews.TimelineEntry(
                              row.dimension(),
                              row.fromValue(),
                              row.toValue(),
                              row.reason(),
                              row.actor(),
                              row.createdAt()))
                  .toList();
          String holdDetail = resolveHoldDetail(order.id(), order.holdReason());
          return new OrderViews.DetailView(
              order.id(),
              order.externalOrderId(),
              order.orderStatus(),
              order.paymentStatus(),
              order.fulfillmentStatus(),
              order.holdReason(),
              holdDetail,
              order.holdNote(),
              order.paymentMethod(),
              order.currency(),
              order.subtotal(),
              order.shippingFee(),
              order.discount(),
              order.grandTotal(),
              order.orderedAt(),
              order.paidAt(),
              order.shipBy(),
              order.version(),
              new OrderViews.ChannelAccountView(
                  account.id(), account.channel(), account.mode(), account.status()),
              supportsCancel,
              lineViews,
              reservations,
              shipments,
              recipient,
              timeline);
        });
  }

  public OrderViews.HoldsView holds() {
    return tx.read(
        () -> {
          List<HoldGroupRow> groups =
              jdbc.query(
                  """
                  WITH held AS (
                    SELECT
                      o.id,
                      o.external_order_id,
                      o.ordered_at,
                      o.hold_reason,
                      (
                        o.hold_reason = 'OUT_OF_STOCK'
                        AND EXISTS (
                          SELECT 1 FROM order_line ol
                          JOIN sku s ON s.id = ol.sku_id AND s.is_bundle = true
                          WHERE ol.order_id = o.id
                          AND NOT EXISTS (
                            SELECT 1 FROM sku_bundle_component bc
                            WHERE bc.tenant_id = ol.tenant_id AND bc.bundle_sku_id = ol.sku_id
                          )
                        )
                      ) AS bundle_without_components
                    FROM sales_order o
                    WHERE o.hold_reason <> 'NONE'
                  ),
                  classified AS (
                    SELECT
                      id,
                      external_order_id,
                      ordered_at,
                      CASE
                        WHEN bundle_without_components THEN 'OUT_OF_STOCK'
                        ELSE hold_reason
                      END AS group_reason,
                      CASE
                        WHEN bundle_without_components THEN 'BUNDLE_WITHOUT_COMPONENTS'
                        ELSE NULL
                      END AS hold_detail
                    FROM held
                  )
                  SELECT group_reason, hold_detail, COUNT(*) AS cnt
                  FROM classified
                  GROUP BY group_reason, hold_detail
                  ORDER BY group_reason, hold_detail NULLS FIRST
                  """,
                  (rs, rowNum) ->
                      new HoldGroupRow(
                          rs.getString("group_reason"),
                          rs.getString("hold_detail"),
                          rs.getLong("cnt")));
          List<OrderViews.HoldGroup> result = new ArrayList<>();
          for (HoldGroupRow group : groups) {
            List<OrderViews.HoldSample> samples =
                jdbc.query(
                    """
                    WITH held AS (
                      SELECT
                        o.id,
                        o.external_order_id,
                        o.ordered_at,
                        o.hold_reason,
                        (
                          o.hold_reason = 'OUT_OF_STOCK'
                          AND EXISTS (
                            SELECT 1 FROM order_line ol
                            JOIN sku s ON s.id = ol.sku_id AND s.is_bundle = true
                            WHERE ol.order_id = o.id
                            AND NOT EXISTS (
                              SELECT 1 FROM sku_bundle_component bc
                              WHERE bc.tenant_id = ol.tenant_id AND bc.bundle_sku_id = ol.sku_id
                            )
                          )
                        ) AS bundle_without_components
                      FROM sales_order o
                      WHERE o.hold_reason <> 'NONE'
                    ),
                    classified AS (
                      SELECT
                        id,
                        external_order_id,
                        ordered_at,
                        CASE
                          WHEN bundle_without_components THEN 'OUT_OF_STOCK'
                          ELSE hold_reason
                        END AS group_reason,
                        CASE
                          WHEN bundle_without_components THEN 'BUNDLE_WITHOUT_COMPONENTS'
                          ELSE NULL
                        END AS hold_detail
                      FROM held
                    )
                    SELECT id, external_order_id, ordered_at
                    FROM (
                      SELECT
                        c.id,
                        c.external_order_id,
                        c.ordered_at,
                        ROW_NUMBER() OVER (ORDER BY c.ordered_at DESC) AS rn
                      FROM classified c
                      WHERE c.group_reason = ? AND c.hold_detail IS NOT DISTINCT FROM ?
                    ) ranked
                    WHERE rn <= 5
                    ORDER BY ordered_at DESC
                    """,
                    (rs, rowNum) ->
                        new OrderViews.HoldSample(
                            rs.getObject("id", UUID.class),
                            rs.getString("external_order_id"),
                            rs.getObject("ordered_at", java.time.OffsetDateTime.class).toInstant()),
                    group.holdReason(),
                    group.holdDetail());
            result.add(
                new OrderViews.HoldGroup(
                    group.holdReason(), group.holdDetail(), group.count(), samples));
          }
          return new OrderViews.HoldsView(result);
        });
  }

  private String resolveHoldDetail(UUID orderId, String holdReason) {
    if (!"OUT_OF_STOCK".equals(holdReason)) {
      return null;
    }
    Boolean bundle =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM order_line ol
              JOIN sku s ON s.id = ol.sku_id AND s.is_bundle = true
              WHERE ol.order_id = ?
              AND NOT EXISTS (
                SELECT 1 FROM sku_bundle_component bc
                WHERE bc.tenant_id = ol.tenant_id AND bc.bundle_sku_id = ol.sku_id
              )
            )
            """,
            Boolean.class,
            orderId);
    return Boolean.TRUE.equals(bundle) ? "BUNDLE_WITHOUT_COMPONENTS" : null;
  }

  private OrderViews.ListItem mapListItem(ResultSet rs, int rowNum) throws SQLException {
    String last4 = rs.getString("phone_last4");
    return new OrderViews.ListItem(
        rs.getObject("id", UUID.class),
        rs.getString("external_order_id"),
        rs.getString("order_status"),
        rs.getString("payment_status"),
        rs.getString("fulfillment_status"),
        rs.getString("hold_reason"),
        rs.getString("payment_method"),
        rs.getBigDecimal("grand_total"),
        rs.getObject("ordered_at", java.time.OffsetDateTime.class).toInstant(),
        instant(rs.getTimestamp("ship_by")),
        rs.getObject("channel_account_id", UUID.class),
        rs.getString("channel"),
        OrderPiiMask.maskedPhone(last4));
  }

  private static Instant instant(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private List<OrderViews.LineView> loadLines(UUID orderId) {
    List<OrderLineRepository.OrderLine> orderLines = lines.findByOrderId(orderId);
    Map<UUID, SkuRow> skus = loadSkus(orderLines);
    Map<UUID, List<OrderViews.ComponentView>> components = loadComponents(orderLines, skus);
    List<OrderViews.LineView> result = new ArrayList<>();
    for (OrderLineRepository.OrderLine line : orderLines) {
      SkuRow sku = line.skuId() == null ? null : skus.get(line.skuId());
      boolean mapped = line.skuId() != null;
      boolean bundle = sku != null && sku.isBundle();
      result.add(
          new OrderViews.LineView(
              line.id(),
              line.externalLineId(),
              line.externalSkuId(),
              line.skuId(),
              sku == null ? null : sku.code(),
              line.name(),
              line.qty(),
              line.unitPrice(),
              mapped,
              bundle,
              bundle ? components.getOrDefault(line.skuId(), List.of()) : List.of()));
    }
    return result;
  }

  private Map<UUID, SkuRow> loadSkus(List<OrderLineRepository.OrderLine> orderLines) {
    Set<UUID> ids =
        orderLines.stream()
            .map(OrderLineRepository.OrderLine::skuId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    List<Object> params = new ArrayList<>(ids);
    List<SkuRow> rows =
        jdbc.query(
            "SELECT id, sku_code, name, is_bundle FROM sku WHERE id IN (" + placeholders + ")",
            (rs, rowNum) ->
                new SkuRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("sku_code"),
                    rs.getString("name"),
                    rs.getBoolean("is_bundle")),
            params.toArray());
    Map<UUID, SkuRow> map = new HashMap<>();
    for (SkuRow row : rows) {
      map.put(row.id(), row);
    }
    return map;
  }

  private Map<UUID, List<OrderViews.ComponentView>> loadComponents(
      List<OrderLineRepository.OrderLine> orderLines, Map<UUID, SkuRow> skus) {
    Set<UUID> bundleIds =
        orderLines.stream()
            .map(OrderLineRepository.OrderLine::skuId)
            .filter(id -> id != null && skus.get(id) != null && skus.get(id).isBundle())
            .collect(Collectors.toSet());
    if (bundleIds.isEmpty()) {
      return Map.of();
    }
    String placeholders = bundleIds.stream().map(id -> "?").collect(Collectors.joining(","));
    List<Object> params = new ArrayList<>(bundleIds);
    Map<UUID, List<OrderViews.ComponentView>> map = new HashMap<>();
    jdbc.query(
        """
        SELECT bc.bundle_sku_id, bc.component_sku_id, bc.qty, s.sku_code, s.name
        FROM sku_bundle_component bc
        JOIN sku s ON s.id = bc.component_sku_id
        WHERE bc.bundle_sku_id IN ("""
            + placeholders
            + ")",
        (rs, rowNum) -> {
          UUID parent = rs.getObject("bundle_sku_id", UUID.class);
          map.computeIfAbsent(parent, k -> new ArrayList<>())
              .add(
                  new OrderViews.ComponentView(
                      rs.getObject("component_sku_id", UUID.class),
                      rs.getString("sku_code"),
                      rs.getString("name"),
                      rs.getInt("qty")));
          return null;
        },
        params.toArray());
    return map;
  }

  private List<OrderViews.ReservationView> loadReservations(UUID orderId) {
    return jdbc.query(
        """
        SELECT sr.id, sr.sku_id, s.sku_code, sr.warehouse_id, w.code AS warehouse_code,
               sr.qty, sr.status, sr.expires_at
        FROM stock_reservation sr
        JOIN sku s ON s.id = sr.sku_id
        JOIN warehouse w ON w.id = sr.warehouse_id
        WHERE sr.owner_type = 'ORDER' AND sr.owner_ref = ?
        ORDER BY s.sku_code
        """,
        (rs, rowNum) ->
            new OrderViews.ReservationView(
                rs.getObject("id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getString("sku_code"),
                rs.getObject("warehouse_id", UUID.class),
                rs.getString("warehouse_code"),
                rs.getInt("qty"),
                rs.getString("status"),
                instant(rs.getTimestamp("expires_at"))),
        orderId.toString());
  }

  private List<OrderViews.ShipmentView> loadShipments(UUID orderId) {
    return jdbc.query(
        """
        SELECT id, tracking_no, carrier, status, shipped_at
        FROM shipment WHERE order_id = ?
        """,
        (rs, rowNum) ->
            new OrderViews.ShipmentView(
                rs.getObject("id", UUID.class),
                rs.getString("tracking_no"),
                rs.getString("carrier"),
                rs.getString("status"),
                instant(rs.getTimestamp("shipped_at"))),
        orderId);
  }

  private OrderViews.RecipientView loadRecipient(UUID orderId) {
    return maskedRecipients
        .find(orderId)
        .map(
            row ->
                new OrderViews.RecipientView(
                    row.nameMasked(),
                    row.phoneMasked(),
                    row.province(),
                    row.postcode(),
                    row.piiStatus()))
        .orElse(new OrderViews.RecipientView(null, null, null, null, "MISSING"));
  }

  private java.util.Optional<ChannelAccountRow> channelAccount(UUID id) {
    List<ChannelAccountRow> rows =
        jdbc.query(
            "SELECT id, channel, mode, status FROM channel_account WHERE id = ?",
            (rs, rowNum) ->
                new ChannelAccountRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("channel"),
                    rs.getString("mode"),
                    rs.getString("status")),
            id);
    return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
  }

  private record SkuRow(UUID id, String code, String name, boolean isBundle) {}

  private record ChannelAccountRow(UUID id, String channel, String mode, String status) {}

  private record HoldGroupRow(String holdReason, String holdDetail, long count) {}

  sealed interface SearchClause permits PhoneSearch, TrackingSearch, ExternalSearch, NoSearch {}

  record NoSearch() implements SearchClause {}

  record PhoneSearch(List<UUID> orderIds) implements SearchClause {}

  record TrackingSearch(String trackingNo) implements SearchClause {}

  record ExternalSearch(String query) implements SearchClause {}

  SearchClause resolveSearch(String q) {
    if (q == null || q.isBlank()) {
      return new NoSearch();
    }
    String query = q.trim();
    String digits = query.replaceAll("\\D", "");
    if (digits.length() >= 9 && digits.length() <= 15) {
      try {
        List<UUID> ids = recipients.findOrderIdsByPhone(query);
        if (!ids.isEmpty()) {
          return new PhoneSearch(ids);
        }
      } catch (IllegalArgumentException ignored) {
        // Normalization can reject digit strings that look like phones; fall through to
        // tracking/id.
      }
    }
    Boolean tracking =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipment WHERE tracking_no = ?)", Boolean.class, query);
    if (Boolean.TRUE.equals(tracking)) {
      return new TrackingSearch(query);
    }
    return new ExternalSearch(query);
  }

  static final class Filter {
    private final String joins;
    private final String where;
    private final List<Object> params;

    private Filter(String joins, String where, List<Object> params) {
      this.joins = joins;
      this.where = where;
      this.params = params;
    }

    String joins() {
      return joins;
    }

    String where() {
      return where;
    }

    List<Object> params() {
      return params;
    }

    static Filter build(
        String orderStatus,
        String paymentStatus,
        String fulfillmentStatus,
        String holdReason,
        String channel,
        UUID channelAccountId,
        String orderedFrom,
        String orderedTo,
        SearchClause search) {
      StringBuilder join = new StringBuilder();
      join.append(" LEFT JOIN order_recipient r ON r.order_id = o.id");
      StringBuilder where = new StringBuilder(" WHERE true");
      List<Object> params = new ArrayList<>();
      if (orderStatus != null && !orderStatus.isBlank()) {
        where.append(" AND o.order_status = ?");
        params.add(orderStatus.trim());
      }
      if (paymentStatus != null && !paymentStatus.isBlank()) {
        where.append(" AND o.payment_status = ?");
        params.add(paymentStatus.trim());
      }
      if (fulfillmentStatus != null && !fulfillmentStatus.isBlank()) {
        where.append(" AND o.fulfillment_status = ?");
        params.add(fulfillmentStatus.trim());
      }
      if (holdReason != null && !holdReason.isBlank()) {
        if ("ANY".equalsIgnoreCase(holdReason.trim())) {
          where.append(" AND o.hold_reason <> 'NONE'");
        } else {
          where.append(" AND o.hold_reason = ?");
          params.add(holdReason.trim());
        }
      }
      if (channelAccountId != null) {
        where.append(" AND o.channel_account_id = ?");
        params.add(channelAccountId);
      } else if (channel != null && !channel.isBlank()) {
        where.append(" AND ca.channel = ?");
        params.add(channel.trim());
      }
      Instant from = parseInstant("ordered_from", orderedFrom, false);
      Instant to = parseInstant("ordered_to", orderedTo, true);
      if (from != null) {
        where.append(" AND o.ordered_at >= ?");
        params.add(java.sql.Timestamp.from(from));
      }
      if (to != null) {
        where.append(" AND o.ordered_at < ?");
        params.add(java.sql.Timestamp.from(to));
      }
      if (search instanceof PhoneSearch phone) {
        if (phone.orderIds().isEmpty()) {
          where.append(" AND false");
        } else {
          where.append(" AND o.id IN (").append(placeholders(phone.orderIds().size())).append(")");
          params.addAll(phone.orderIds());
        }
      } else if (search instanceof TrackingSearch tracking) {
        join.append(" JOIN shipment sh ON sh.order_id = o.id");
        where.append(" AND sh.tracking_no = ?");
        params.add(tracking.trackingNo());
      } else if (search instanceof ExternalSearch external) {
        String escaped = Fields.likeEscape(external.query());
        where.append(" AND (o.external_order_id = ? OR o.external_order_id LIKE ? ESCAPE '\\')");
        params.add(external.query());
        params.add(escaped + "%");
      }
      return new Filter(join.toString(), where.toString(), params);
    }

    private static String placeholders(int count) {
      return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Instant parseInstant(String field, String value, boolean end) {
      if (value == null || value.isBlank()) {
        return null;
      }
      String text = value.strip();
      try {
        if (text.length() == 10) {
          LocalDate day = LocalDate.parse(text);
          return (end ? day.plusDays(1) : day).atStartOfDay(SHOP_ZONE).toInstant();
        }
        return Instant.parse(text);
      } catch (DateTimeParseException ex) {
        throw CatalogApiException.invalid(field + " must be a date (YYYY-MM-DD) or an ISO instant");
      }
    }
  }
}
