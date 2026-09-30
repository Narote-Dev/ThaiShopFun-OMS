package com.thaishopfun.oms.catalog;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

/**
 * SKUs and bundle components. Bundle rules are checked here first for a friendly error; the V4
 * triggers stay the last line of defence. T07 reads {@code inventory} but never writes it.
 */
@Service
public class SkuService {

  private static final String SELECT =
      """
      SELECT s.id, s.product_id, p.name AS product_name, s.sku_code, s.name, s.barcode,
             s.weight_g, s.is_bundle, s.created_at, s.updated_at,
             st.on_hand, st.reserved,
             (SELECT count(*) FROM sku_bundle_component c WHERE c.bundle_sku_id = s.id)
               AS component_count
      FROM sku s
      JOIN product p ON p.id = s.product_id
      LEFT JOIN LATERAL (
        SELECT sum(i.on_hand) AS on_hand, sum(i.reserved) AS reserved
        FROM inventory i
        WHERE i.sku_id = s.id
      ) st ON true
      """;

  private final JdbcTemplate jdbc;
  private final CatalogTransactions tx;
  private final CatalogAccess access;
  private final CatalogAudit audit;
  private final ProductService products;

  public SkuService(
      JdbcTemplate jdbc,
      CatalogTransactions tx,
      CatalogAccess access,
      CatalogAudit audit,
      ProductService products) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.access = access;
    this.audit = audit;
    this.products = products;
  }

  private record Input(
      UUID productId,
      String productName,
      String code,
      String name,
      String barcode,
      Integer weight,
      boolean bundle) {}

  public SkuView create(SkuRequest request) {
    // Step 1: Validate the body before opening a transaction.
    CatalogAccess.Actor actor = access.requireWriter();
    Input input = validate(request, true);
    return tx.write(
        null,
        () -> {
          // Step 2: Resolve or create the product in the same transaction.
          UUID productId = input.productId();
          if (productId == null) {
            productId = products.insert(actor, input.productName(), "ACTIVE");
          } else {
            requireProduct(productId);
          }
          // Step 3: Insert. A duplicate sku_code fails the unique key and maps to 409.
          UUID id = UuidV7.generate();
          jdbc.update(
              """
              INSERT INTO sku (id, tenant_id, product_id, sku_code, name, barcode, weight_g, is_bundle)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?)
              """,
              id,
              TenantContext.requireTenantId(),
              productId,
              input.code(),
              input.name(),
              input.barcode(),
              input.weight(),
              input.bundle());
          SkuView created = detail(id);
          audit.write(actor, "SKU_CREATED", "sku", id, null, created.audit());
          return created;
        });
  }

  public SkuView get(UUID id) {
    return tx.read(() -> detail(id));
  }

  public PageResult<SkuView> list(String q, UUID productId, Integer limit, Integer offset) {
    int pageLimit = PageResult.limit(limit);
    int pageOffset = PageResult.offset(offset);
    String query = Fields.trim(q);
    // Step 1: q matches a sku_code prefix, an exact barcode, or a name substring, ignoring case.
    StringBuilder where = new StringBuilder(" WHERE true");
    List<Object> params = new ArrayList<>();
    if (query != null) {
      String escaped = Fields.likeEscape(query);
      where.append(
          " AND (lower(s.sku_code) LIKE lower(?) ESCAPE '\\' OR lower(s.barcode) = lower(?)"
              + " OR s.name ILIKE ? ESCAPE '\\')");
      params.add(escaped + "%");
      params.add(query);
      params.add("%" + escaped + "%");
    }
    if (productId != null) {
      where.append(" AND s.product_id = ?");
      params.add(productId);
    }
    return tx.read(
        () -> {
          Long total =
              jdbc.queryForObject(
                  "SELECT count(*) FROM sku s" + where, Long.class, params.toArray());
          List<Object> pageParams = new ArrayList<>(params);
          pageParams.add(pageLimit);
          pageParams.add(pageOffset);
          // Step 2: Stable order so paging never repeats or skips a row.
          List<SkuView> items =
              jdbc.query(
                  SELECT + where + " ORDER BY s.sku_code, s.id LIMIT ? OFFSET ?",
                  (rs, n) -> map(rs),
                  pageParams.toArray());
          return new PageResult<>(items, total == null ? 0 : total, pageLimit, pageOffset);
        });
  }

  public SkuView update(UUID id, SkuRequest request) {
    CatalogAccess.Actor actor = access.requireWriter();
    Input input = validate(request, false);
    return tx.write(
        null,
        () -> {
          // Step 1: Lock the SKU so is_bundle and its references cannot move under us.
          lockSkus(List.of(id));
          SkuView before = detail(id);
          requireProduct(input.productId());
          // Step 2: Friendly bundle checks. The is_bundle trigger re-checks under the same lock.
          if (input.bundle() != before.bundle()) {
            checkBundleFlip(id, input.bundle());
          }
          SkuView unchanged =
              new SkuView(
                  id,
                  input.productId(),
                  null,
                  input.code(),
                  input.name(),
                  input.barcode(),
                  input.weight(),
                  input.bundle(),
                  null,
                  null,
                  0,
                  null,
                  null,
                  null);
          if (unchanged.audit().equals(before.audit())) {
            return before;
          }
          // Step 3: Write and audit before/after.
          jdbc.update(
              """
              UPDATE sku
              SET product_id = ?, sku_code = ?, name = ?, barcode = ?, weight_g = ?, is_bundle = ?,
                  updated_at = now()
              WHERE id = ?
              """,
              input.productId(),
              input.code(),
              input.name(),
              input.barcode(),
              input.weight(),
              input.bundle(),
              id);
          SkuView after = detail(id);
          audit.write(actor, "SKU_UPDATED", "sku", id, before.audit(), after.audit());
          return after;
        });
  }

  public void delete(UUID id) {
    CatalogAccess.Actor actor = access.requireWriter();
    tx.write(
        "SKU_IN_USE",
        () -> {
          // Step 1: A SKU used by a bundle, stock, a listing, or a document is in use (409).
          lockSkus(List.of(id));
          SkuView before = detail(id);
          if (exists("SELECT 1 FROM sku_bundle_component WHERE component_sku_id = ?", id)) {
            throw CatalogApiException.conflict("SKU_IN_USE", "The SKU is a component of a bundle");
          }
          // Step 2: The bundle's own component list goes with it. Other references RESTRICT.
          List<SkuView.ComponentView> components = components(id);
          jdbc.update("DELETE FROM sku_bundle_component WHERE bundle_sku_id = ?", id);
          jdbc.update("DELETE FROM sku WHERE id = ?", id);
          Map<String, Object> values = before.audit();
          values.put("components", componentAudit(components));
          audit.write(actor, "SKU_DELETED", "sku", id, values, null);
          return null;
        });
  }

  public SkuView replaceComponents(UUID id, List<ComponentRequest> request) {
    // Step 1: Shape checks before any lock.
    CatalogAccess.Actor actor = access.requireWriter();
    if (request == null) {
      throw CatalogApiException.invalid("A JSON array of components is required");
    }
    if (request.size() > Fields.MAX_COMPONENTS) {
      throw CatalogApiException.invalid(
          "A bundle can have at most " + Fields.MAX_COMPONENTS + " components");
    }
    for (ComponentRequest item : request) {
      if (item == null) {
        throw CatalogApiException.invalid("components must not contain null");
      }
      boolean hasId = item.componentSkuId() != null;
      boolean hasCode = Fields.trim(item.componentSkuCode()) != null;
      if (hasId == hasCode) {
        throw CatalogApiException.invalid(
            "Each component needs exactly one of component_sku_id or component_sku_code");
      }
      Fields.require("qty", Fields.qtyError(item.qty()));
    }
    return tx.write(
        null,
        () -> {
          // Step 2: Resolve codes to ids, then lock the bundle and every component in id order.
          Map<String, UUID> byCode = idsByCode(codesOf(request));
          Map<UUID, Integer> wanted = new LinkedHashMap<>();
          for (ComponentRequest item : request) {
            UUID componentId = item.componentSkuId();
            if (componentId == null) {
              String code = Fields.trim(item.componentSkuCode());
              componentId = byCode.get(code);
              if (componentId == null) {
                throw CatalogApiException.invalid("Component SKU " + code + " not found");
              }
            }
            if (wanted.put(componentId, item.qty()) != null) {
              throw CatalogApiException.invalid("A component is listed twice");
            }
          }
          Set<UUID> ids = new HashSet<>(wanted.keySet());
          ids.add(id);
          Map<UUID, Boolean> locked = lockSkus(ids);
          if (!locked.containsKey(id)) {
            throw CatalogApiException.notFound("SKU");
          }
          for (UUID componentId : wanted.keySet()) {
            if (!locked.containsKey(componentId)) {
              throw CatalogApiException.invalid("Component SKU " + componentId + " not found");
            }
          }
          // Step 3: Bundle rules in Java first, in the same order as the V4 trigger.
          if (wanted.containsKey(id)) {
            throw new CatalogApiException(422, "NESTED_BUNDLE", "A bundle cannot contain itself");
          }
          for (UUID componentId : wanted.keySet()) {
            if (locked.get(componentId)) {
              throw new CatalogApiException(
                  422, "NESTED_BUNDLE", "A bundle cannot contain another bundle");
            }
          }
          if (!wanted.isEmpty() && !locked.get(id)) {
            throw new CatalogApiException(
                422, "BUNDLE_REQUIRED", "Set is_bundle on the SKU before adding components");
          }
          // Step 4: Replace. An identical list is a no-op and writes no audit row.
          List<SkuView.ComponentView> before = components(id);
          Map<UUID, Integer> current = new LinkedHashMap<>();
          for (SkuView.ComponentView component : before) {
            current.put(component.componentSkuId(), component.qty());
          }
          if (current.equals(wanted)) {
            return detail(id);
          }
          UUID tenantId = TenantContext.requireTenantId();
          jdbc.update("DELETE FROM sku_bundle_component WHERE bundle_sku_id = ?", id);
          List<Object[]> rows = new ArrayList<>();
          for (Map.Entry<UUID, Integer> entry : wanted.entrySet()) {
            rows.add(new Object[] {tenantId, id, entry.getKey(), entry.getValue()});
          }
          jdbc.batchUpdate(
              "INSERT INTO sku_bundle_component (tenant_id, bundle_sku_id, component_sku_id, qty) "
                  + "VALUES (?, ?, ?, ?)",
              rows);
          jdbc.update("UPDATE sku SET updated_at = now() WHERE id = ?", id);
          SkuView after = detail(id);
          audit.write(
              actor,
              "BUNDLE_COMPONENTS_REPLACED",
              "sku",
              id,
              Map.of("components", componentAudit(before)),
              Map.of("components", componentAudit(after.components())));
          return after;
        });
  }

  private void checkBundleFlip(UUID id, boolean toBundle) {
    if (toBundle) {
      if (exists("SELECT 1 FROM sku_bundle_component WHERE component_sku_id = ?", id)) {
        throw new CatalogApiException(
            422, "NESTED_BUNDLE", "The SKU is a component of a bundle and cannot become one");
      }
      if (exists("SELECT 1 FROM inventory WHERE sku_id = ?", id)
          || exists("SELECT 1 FROM stock_document_line WHERE sku_id = ?", id)) {
        throw new CatalogApiException(
            422, "BUNDLE_NOT_STOCKABLE", "The SKU has stock rows and cannot become a bundle");
      }
    } else if (exists("SELECT 1 FROM sku_bundle_component WHERE bundle_sku_id = ?", id)) {
      throw new CatalogApiException(
          422, "BUNDLE_REQUIRED", "Remove the components before clearing is_bundle");
    }
  }

  private Input validate(SkuRequest request, boolean create) {
    if (request == null) {
      throw CatalogApiException.invalid("Request body is required");
    }
    String code = Fields.trim(request.skuCode());
    String name = Fields.trim(request.name());
    String barcode = Fields.trim(request.barcode());
    String productName = Fields.trim(request.productName());
    Fields.require("sku_code", Fields.skuCodeError(code));
    Fields.require("name", Fields.nameError(name));
    Fields.require("barcode", Fields.barcodeError(barcode));
    Fields.require("weight_g", Fields.weightError(request.weightG()));
    // Change: a full update must state is_bundle; omitting it must not silently clear the flag.
    if (!create && request.bundle() == null) {
      throw CatalogApiException.invalid("is_bundle is required");
    }
    if (request.productId() == null) {
      if (!create) {
        throw CatalogApiException.invalid("product_id is required");
      }
      if (productName == null) {
        throw CatalogApiException.invalid("product_id or product_name is required");
      }
      Fields.require("product_name", Fields.nameError(productName));
    }
    return new Input(
        request.productId(),
        productName,
        code,
        name,
        barcode,
        request.weightG(),
        Boolean.TRUE.equals(request.bundle()));
  }

  private void requireProduct(UUID productId) {
    if (!exists("SELECT 1 FROM product WHERE id = ?", productId)) {
      throw CatalogApiException.invalid("product_id does not exist");
    }
  }

  /** Locks the visible SKUs in id order (FOR NO KEY UPDATE). Returns id to is_bundle. */
  Map<UUID, Boolean> lockSkus(Collection<UUID> ids) {
    Map<UUID, Boolean> locked = new HashMap<>();
    UUID[] array = ids.toArray(UUID[]::new);
    jdbc.query(
        "SELECT id, is_bundle FROM sku WHERE id = ANY (?) ORDER BY id FOR NO KEY UPDATE",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", array)),
        rs -> {
          locked.put(rs.getObject("id", UUID.class), rs.getBoolean("is_bundle"));
        });
    if (ids.size() == 1 && locked.isEmpty()) {
      throw CatalogApiException.notFound("SKU");
    }
    return locked;
  }

  private Map<String, UUID> idsByCode(List<String> codes) {
    Map<String, UUID> ids = new HashMap<>();
    if (codes.isEmpty()) {
      return ids;
    }
    String[] array = codes.toArray(String[]::new);
    jdbc.query(
        "SELECT id, sku_code FROM sku WHERE sku_code = ANY (?)",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", array)),
        rs -> {
          ids.put(rs.getString("sku_code"), rs.getObject("id", UUID.class));
        });
    return ids;
  }

  private static List<String> codesOf(List<ComponentRequest> request) {
    List<String> codes = new ArrayList<>();
    for (ComponentRequest item : request) {
      String code = Fields.trim(item.componentSkuCode());
      if (code != null) {
        codes.add(code);
      }
    }
    return codes;
  }

  SkuView detail(UUID id) {
    List<SkuView> rows = jdbc.query(SELECT + " WHERE s.id = ?", (rs, n) -> map(rs), id);
    if (rows.isEmpty()) {
      throw CatalogApiException.notFound("SKU");
    }
    return rows.get(0).withComponents(components(id));
  }

  List<SkuView.ComponentView> components(UUID bundleId) {
    return jdbc.query(
        """
        SELECT c.component_sku_id, s.sku_code, s.name, c.qty
        FROM sku_bundle_component c
        JOIN sku s ON s.id = c.component_sku_id
        WHERE c.bundle_sku_id = ?
        ORDER BY s.sku_code, s.id
        """,
        (rs, n) ->
            new SkuView.ComponentView(
                rs.getObject("component_sku_id", UUID.class),
                rs.getString("sku_code"),
                rs.getString("name"),
                rs.getInt("qty")),
        bundleId);
  }

  static List<Map<String, Object>> componentAudit(List<SkuView.ComponentView> components) {
    List<Map<String, Object>> list = new ArrayList<>();
    for (SkuView.ComponentView component : components) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("component_sku_id", component.componentSkuId().toString());
      item.put("sku_code", component.skuCode());
      item.put("qty", component.qty());
      list.add(item);
    }
    return list;
  }

  private boolean exists(String sql, Object param) {
    ResultSetExtractor<Boolean> found = ResultSet::next;
    return Boolean.TRUE.equals(jdbc.query(sql + " LIMIT 1", found, Objects.requireNonNull(param)));
  }

  static SkuView map(ResultSet rs) throws SQLException {
    boolean bundle = rs.getBoolean("is_bundle");
    long onHand = rs.getLong("on_hand");
    boolean hasStock = !rs.wasNull();
    long reserved = rs.getLong("reserved");
    int weight = rs.getInt("weight_g");
    Integer weightG = rs.wasNull() ? null : weight;
    return new SkuView(
        rs.getObject("id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_name"),
        rs.getString("sku_code"),
        rs.getString("name"),
        rs.getString("barcode"),
        weightG,
        bundle,
        bundle ? null : (hasStock ? (int) onHand : 0),
        bundle ? null : (hasStock ? (int) reserved : 0),
        rs.getInt("component_count"),
        ProductService.instant(rs, "created_at"),
        ProductService.instant(rs, "updated_at"),
        null);
  }
}
