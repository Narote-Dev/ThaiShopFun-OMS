package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.catalog.PageResult;
import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.order.OrderLineRepository;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.stock.ReserveDemandPlanner;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
  private final ReserveDemandPlanner demandPlanner;
  private final ChannelAdapterRegistry adapters;

  public OrderQueryService(
      JdbcTemplate jdbc,
      OrderTransactions tx,
      SalesOrderRepository orders,
      OrderRecipientRepository recipients,
      OrderLineRepository lines,
      OrderStatusHistoryRepository history,
      ReserveDemandPlanner demandPlanner,
      ChannelAdapterRegistry adapters) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.orders = orders;
    this.recipients = recipients;
    this.lines = lines;
    this.history = history;
    this.demandPlanner = demandPlanner;
    this.adapters = adapters;
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
      Integer offset) {
    int pageLimit = PageResult.limit(limit);
    int pageOffset = PageResult.offset(offset);
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
    return tx.read(
        () -> {
          long total =
              jdbc.queryForObject(
                  "SELECT count(*) FROM sales_order o" + filter.joins() + filter.where(),
                  Long.class,
                  filter.params().toArray());
          List<Object> pageParams = new ArrayList<>(filter.params());
          pageParams.add(pageLimit);
          pageParams.add(pageOffset);
          List<OrderViews.ListItem> items =
              jdbc.query(
                  """
                  SELECT o.id, o.external_order_id, o.order_status, o.payment_status,
                         o.fulfillment_status, o.hold_reason, o.payment_method, o.grand_total,
                         o.ordered_at, o.ship_by, o.channel_account_id, ca.channel,
                         r.phone_last4
                  FROM sales_order o
                  JOIN channel_account ca ON ca.tenant_id = o.tenant_id AND ca.id = o.channel_account_id
                  """
                      + filter.joins()
                      + filter.where()
                      + " ORDER BY o.ordered_at DESC, o.id DESC LIMIT ? OFFSET ?",
                  this::mapListItem,
                  pageParams.toArray());
          return new OrderViews.Page<>(items, total, pageLimit, pageOffset);
        });
  }

  public OrderViews.DetailView detail(UUID id) {
    return tx.read(
        () -> {
          SalesOrder order = orders.findById(id).orElseThrow(OrderApiException::notFound);
          ChannelAccountRow account =
              channelAccount(order.channelAccountId()).orElseThrow(OrderApiException::notFound);
          boolean supportsCancel =
              adapters
                  .optional(Channel.valueOf(account.channel()))
                  .capabilities()
                  .supportsCancelRequest();
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
          return new OrderViews.DetailView(
              order.id(),
              order.externalOrderId(),
              order.orderStatus(),
              order.paymentStatus(),
              order.fulfillmentStatus(),
              order.holdReason(),
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
          List<HoldRow> rows =
              jdbc.query(
                  """
                  SELECT o.id, o.external_order_id, o.ordered_at, o.hold_reason
                  FROM sales_order o
                  WHERE o.hold_reason <> 'NONE'
                  ORDER BY o.ordered_at DESC
                  """,
                  (rs, rowNum) ->
                      new HoldRow(
                          rs.getObject("id", UUID.class),
                          rs.getString("external_order_id"),
                          rs.getObject("ordered_at", java.time.OffsetDateTime.class).toInstant(),
                          rs.getString("hold_reason")));
          Map<String, Long> counts = new LinkedHashMap<>();
          Map<String, List<OrderViews.HoldSample>> samples = new LinkedHashMap<>();
          for (HoldRow row : rows) {
            String key = groupKey(row);
            counts.merge(key, 1L, Long::sum);
            samples.computeIfAbsent(key, k -> new ArrayList<>());
            if (samples.get(key).size() < 5) {
              samples
                  .get(key)
                  .add(new OrderViews.HoldSample(row.id(), row.externalOrderId(), row.orderedAt()));
            }
          }
          List<OrderViews.HoldGroup> groups = new ArrayList<>();
          for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String key = entry.getKey();
            String holdReason = key;
            String holdDetail = null;
            if (key.endsWith(":BUNDLE_WITHOUT_COMPONENTS")) {
              holdReason = "OUT_OF_STOCK";
              holdDetail = "BUNDLE_WITHOUT_COMPONENTS";
            }
            groups.add(
                new OrderViews.HoldGroup(
                    holdReason, holdDetail, entry.getValue(), List.copyOf(samples.get(key))));
          }
          return new OrderViews.HoldsView(groups);
        });
  }

  private String groupKey(HoldRow row) {
    if ("OUT_OF_STOCK".equals(row.holdReason())) {
      if (isBundleWithoutComponents(row.id())) {
        return "OUT_OF_STOCK:BUNDLE_WITHOUT_COMPONENTS";
      }
      return "OUT_OF_STOCK";
    }
    return row.holdReason();
  }

  private boolean isBundleWithoutComponents(UUID orderId) {
    List<UUID> skuIds =
        lines.findByOrderId(orderId).stream()
            .map(OrderLineRepository.OrderLine::skuId)
            .filter(id -> id != null)
            .toList();
    if (skuIds.isEmpty()) {
      return false;
    }
    return !demandPlanner.componentlessBundleSkus(skuIds).isEmpty();
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
        SELECT sc.parent_sku_id, sc.component_sku_id, sc.qty, s.sku_code, s.name
        FROM sku_component sc
        JOIN sku s ON s.id = sc.component_sku_id
        WHERE sc.parent_sku_id IN ("""
            + placeholders
            + ")",
        (rs, rowNum) -> {
          UUID parent = rs.getObject("parent_sku_id", UUID.class);
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
    return recipients
        .find(orderId)
        .map(
            stored -> {
              if ("REDACTED".equals(stored.piiStatus())) {
                return new OrderViews.RecipientView(
                    "redacted", "redacted", stored.province(), stored.postcode(), "REDACTED");
              }
              return new OrderViews.RecipientView(
                  OrderPiiMask.maskedName(stored.name()),
                  OrderPiiMask.maskedPhone(stored.phoneLast4()),
                  stored.province(),
                  stored.postcode(),
                  stored.piiStatus());
            })
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

  private record HoldRow(UUID id, String externalOrderId, Instant orderedAt, String holdReason) {}

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
    if (digits.length() >= 9) {
      List<UUID> ids = recipients.findOrderIdsByPhone(query);
      if (!ids.isEmpty()) {
        return new PhoneSearch(ids);
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
        where.append(" AND (o.external_order_id = ? OR o.external_order_id LIKE ?)");
        params.add(external.query());
        params.add(external.query() + "%");
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
